package nl.atlas.gallery.featurestories

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** A minimal, authorized, read-only projection. Never persist this as a second media catalogue. */
data class StoryMedia(
    val uri: String,
    val takenAtMillis: Long,
    val isVideo: Boolean = false,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isTrashed: Boolean = false
)

/** Both strings must change when MediaStore or the current selected-media grant changes. */
data class StoryCatalog(
    val grantEpoch: String,
    val catalogRevision: String,
    val media: List<StoryMedia>
)

/** Optional preference projection: applies to automatic suggestions, not deliberate manual stories. */
data class StorySuggestionPolicy(
    val enabled: Boolean = true,
    val excludedDates: Set<LocalDate> = emptySet(),
    val excludedUris: Set<String> = emptySet()
)

data class ManualStory(
    val id: String,
    val title: String,
    val orderedUris: List<String>
)

enum class StoryKind { ON_THIS_DAY, LAST_MONTH, FAVORITES, MANUAL }

data class StoryCard(
    val key: String,
    val title: String,
    val caption: String,
    val kind: StoryKind,
    val items: List<StoryMedia>,
    val missingCount: Int = 0
) {
    val cover: StoryMedia get() = items.firstOrNull { it.isFavorite } ?: items.first()
}

/** A computed view, NOT an authorization token or a durable story database. */
data class StoryDeck(
    val grantEpoch: String,
    val catalogRevision: String,
    val cards: List<StoryCard>
)

/** A UI holding a stale projection must render nothing, never stale thumbnails. */
fun currentStoryCardsOrEmpty(
    deck: StoryDeck?, currentGrantEpoch: String?, currentCatalogRevision: String?
): List<StoryCard> = if (
    deck != null && !currentGrantEpoch.isNullOrBlank() &&
    !currentCatalogRevision.isNullOrBlank() &&
    deck.grantEpoch == currentGrantEpoch && deck.catalogRevision == currentCatalogRevision
) deck.cards else emptyList()

private const val MAX_SLIDES = 24
private const val MAX_PER_DAY = 3
private const val MAX_MANUAL_STORIES = 24
private const val MAX_MANUAL_URIS = 64

/** Original, bounded offline recap selection; never implies machine-rated "best" memories. */
fun planStories(
    catalog: StoryCatalog,
    currentGrantEpoch: String?,
    nowMillis: Long,
    zone: ZoneId,
    manualStories: List<ManualStory> = emptyList(),
    cancellationCheckpoint: (() -> Unit)? = null,
    suggestionPolicy: StorySuggestionPolicy = StorySuggestionPolicy()
): StoryDeck {
    if (catalog.grantEpoch.isBlank() || catalog.catalogRevision.isBlank() ||
        catalog.grantEpoch != currentGrantEpoch || nowMillis <= 0L
    ) return StoryDeck(catalog.grantEpoch, catalog.catalogRevision, emptyList())

    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val lastMonth = YearMonth.from(today).minusMonths(1)
    val recentStart = today.minusDays(119)
    val matchingDay = mutableMapOf<LocalDate, MutableList<StoryMedia>>()
    val lastMonthDays = mutableMapOf<LocalDate, MutableList<StoryMedia>>()
    val favoriteDays = mutableMapOf<LocalDate, MutableList<StoryMedia>>()
    // Duplicate catalogue rows can disagree on visibility after a provider refresh.
    // A hidden/trashed row for a URI vetoes *every* copy, regardless of input order.
    val blockedUris = HashSet<String>()
    catalog.media.forEachIndexed { index, item ->
        if ((index and 1023) == 0) cancellationCheckpoint?.invoke()
        if ((item.isHidden || item.isTrashed) && safeContentUri(item.uri)) {
            blockedUris.add(item.uri)
        }
    }
    val seen = HashSet<String>()
    val manualUris = manualStories.take(MAX_MANUAL_STORIES).flatMap {
        it.orderedUris.take(MAX_MANUAL_URIS)
    }.filter(::safeContentUri).toSet()
    val availableManual = HashMap<String, StoryMedia>()

    catalog.media.forEachIndexed { index, item ->
        if ((index and 1023) == 0) cancellationCheckpoint?.invoke()
        if (!safeContentUri(item.uri) || item.uri in blockedUris || !seen.add(item.uri)) {
            return@forEachIndexed
        }
        // A future or unknown timestamp never becomes a suggested memory.
        if (item.takenAtMillis <= 0L || item.takenAtMillis > nowMillis) {
            if (item.uri in manualUris) availableManual[item.uri] = item
            return@forEachIndexed
        }
        val date = runCatching {
            Instant.ofEpochMilli(item.takenAtMillis).atZone(zone).toLocalDate()
        }.getOrNull() ?: return@forEachIndexed
        if (item.uri in manualUris) availableManual[item.uri] = item
        if (suggestionPolicy.enabled && date !in suggestionPolicy.excludedDates &&
            item.uri !in suggestionPolicy.excludedUris) {
            if (date.isBefore(today) && date.month == today.month && date.dayOfMonth == today.dayOfMonth) {
                addBounded(matchingDay, date, item)
            }
            if (YearMonth.from(date) == lastMonth) addBounded(lastMonthDays, date, item)
            if (item.isFavorite && !date.isBefore(recentStart) && !date.isAfter(today)) {
                addBounded(favoriteDays, date, item)
            }
        }
    }
    cancellationCheckpoint?.invoke()

    val cards = mutableListOf<StoryCard>()
    val thisDay = balanced(matchingDay, 18)
    if (thisDay.size >= 3) cards += StoryCard(
        key = "suggested:on-this-day", title = "On this day",
        caption = "Moments from this date in past years", kind = StoryKind.ON_THIS_DAY,
        items = thisDay
    )
    val monthItems = balanced(lastMonthDays, MAX_SLIDES)
    if (monthItems.size >= 4) cards += StoryCard(
        key = "suggested:last-month", title = lastMonth.month.name.lowercase(Locale.ROOT)
            .replaceFirstChar { it.titlecase(Locale.ROOT) } + " recap",
        caption = "A look back at last month", kind = StoryKind.LAST_MONTH,
        items = monthItems
    )
    val favorites = balanced(favoriteDays, MAX_SLIDES)
    if (favorites.size >= 4) cards += StoryCard(
        key = "suggested:favorites", title = "Favorite moments",
        caption = "Favorites captured in the last 120 days", kind = StoryKind.FAVORITES,
        items = favorites
    )
    val manualIds = HashSet<String>()
    for (definition in manualStories.take(MAX_MANUAL_STORIES)) {
        val id = definition.id.trim()
        if (id.isBlank() || id.length > 80 || !manualIds.add(id)) continue
        val cleanTitle = definition.title.trim().replace(Regex("\\s+"), " ").take(80)
        if (cleanTitle.isBlank()) continue
        val requested = definition.orderedUris.take(MAX_MANUAL_URIS).distinct()
        val resolved = requested.mapNotNull(availableManual::get)
        if (resolved.isEmpty()) continue
        cards += StoryCard(
            key = "manual:" + id, title = cleanTitle,
            caption = "Your collection", kind = StoryKind.MANUAL,
            items = resolved, missingCount = requested.size - resolved.size
        )
    }
    cancellationCheckpoint?.invoke()
    return StoryDeck(catalog.grantEpoch, catalog.catalogRevision, cards)
}

private fun safeContentUri(value: String): Boolean = value.startsWith("content://") &&
    value.length in 11..2048 && value.none { it == '\n' || it == '\r' || it == '\u0000' }

/** Keep only three candidates per calendar day, irrespective of a 150k-item catalogue. */
private fun addBounded(
    buckets: MutableMap<LocalDate, MutableList<StoryMedia>>, date: LocalDate, item: StoryMedia
) {
    val slot = buckets.getOrPut(date) { mutableListOf() }
    slot += item
    slot.sortWith(compareByDescending<StoryMedia> { it.isFavorite }
        .thenByDescending { it.takenAtMillis }.thenBy { it.uri })
    if (slot.size > MAX_PER_DAY) slot.removeAt(slot.lastIndex)
}

/** Round-robin calendar days avoids one camera burst monopolizing the recap. */
private fun balanced(buckets: Map<LocalDate, List<StoryMedia>>, limit: Int): List<StoryMedia> {
    val dates = buckets.keys.sorted()
    val out = ArrayList<StoryMedia>(limit)
    for (round in 0 until MAX_PER_DAY) {
        for (date in dates) {
            buckets.getValue(date).getOrNull(round)?.let(out::add)
            if (out.size == limit) return out.sortedWith(
                compareBy<StoryMedia> { it.takenAtMillis }.thenBy { it.uri })
        }
    }
    return out.sortedWith(compareBy<StoryMedia> { it.takenAtMillis }.thenBy { it.uri })
}
