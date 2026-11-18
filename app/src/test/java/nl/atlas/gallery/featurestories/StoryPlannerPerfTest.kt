package nl.atlas.gallery.featurestories

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frozen pre-PERF-16 baseline from StoryPlanner.kt blob 605b0ecb01757158f4316c241f5f390e52ce3479.
 * Function/constant names are changed; original algorithm is not optimized.
 */
private const val BASELINE_MAX_SLIDES = 24
private const val BASELINE_MAX_PER_DAY = 3
private const val BASELINE_MAX_MANUAL_STORIES = 24
private const val BASELINE_MAX_MANUAL_URIS = 64

private fun baselinePlanStories(
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
        if ((item.isHidden || item.isTrashed) && baselineSafeContentUri(item.uri)) {
            blockedUris.add(item.uri)
        }
    }
    val seen = HashSet<String>()
    val manualUris = manualStories.take(BASELINE_MAX_MANUAL_STORIES).flatMap {
        it.orderedUris.take(BASELINE_MAX_MANUAL_URIS)
    }.filter(::baselineSafeContentUri).toSet()
    val availableManual = HashMap<String, StoryMedia>()

    catalog.media.forEachIndexed { index, item ->
        if ((index and 1023) == 0) cancellationCheckpoint?.invoke()
        if (!baselineSafeContentUri(item.uri) || item.uri in blockedUris || !seen.add(item.uri)) {
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
                baselineAddBounded(matchingDay, date, item)
            }
            if (YearMonth.from(date) == lastMonth) baselineAddBounded(lastMonthDays, date, item)
            if (item.isFavorite && !date.isBefore(recentStart) && !date.isAfter(today)) {
                baselineAddBounded(favoriteDays, date, item)
            }
        }
    }
    cancellationCheckpoint?.invoke()

    val cards = mutableListOf<StoryCard>()
    val thisDay = baselineBalanced(matchingDay, 18)
    if (thisDay.size >= 3) cards += StoryCard(
        key = "suggested:on-this-day", title = "On this day",
        caption = "Moments from this date in past years", kind = StoryKind.ON_THIS_DAY,
        items = thisDay
    )
    val monthItems = baselineBalanced(lastMonthDays, BASELINE_MAX_SLIDES)
    if (monthItems.size >= 4) cards += StoryCard(
        key = "suggested:last-month", title = lastMonth.month.name.lowercase(Locale.ROOT)
            .replaceFirstChar { it.titlecase(Locale.ROOT) } + " recap",
        caption = "A look back at last month", kind = StoryKind.LAST_MONTH,
        items = monthItems
    )
    val favorites = baselineBalanced(favoriteDays, BASELINE_MAX_SLIDES)
    if (favorites.size >= 4) cards += StoryCard(
        key = "suggested:favorites", title = "Favorite moments",
        caption = "Favorites captured in the last 120 days", kind = StoryKind.FAVORITES,
        items = favorites
    )
    val manualIds = HashSet<String>()
    for (definition in manualStories.take(BASELINE_MAX_MANUAL_STORIES)) {
        val id = definition.id.trim()
        if (id.isBlank() || id.length > 80 || !manualIds.add(id)) continue
        val cleanTitle = definition.title.trim().replace(Regex("\\s+"), " ").take(80)
        if (cleanTitle.isBlank()) continue
        val requested = definition.orderedUris.take(BASELINE_MAX_MANUAL_URIS).distinct()
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

private fun baselineSafeContentUri(value: String): Boolean = value.startsWith("content://") &&
    value.length in 11..2048 && value.none { it == '\n' || it == '\r' || it == '\u0000' }

/** Keep only three candidates per calendar day, irrespective of a 150k-item catalogue. */
private fun baselineAddBounded(
    buckets: MutableMap<LocalDate, MutableList<StoryMedia>>, date: LocalDate, item: StoryMedia
) {
    val slot = buckets.getOrPut(date) { mutableListOf() }
    slot += item
    slot.sortWith(compareByDescending<StoryMedia> { it.isFavorite }
        .thenByDescending { it.takenAtMillis }.thenBy { it.uri })
    if (slot.size > BASELINE_MAX_PER_DAY) slot.removeAt(slot.lastIndex)
}

/** Round-robin calendar days avoids one camera burst monopolizing the recap. */
private fun baselineBalanced(buckets: Map<LocalDate, List<StoryMedia>>, limit: Int): List<StoryMedia> {
    val dates = buckets.keys.sorted()
    val out = ArrayList<StoryMedia>(limit)
    for (round in 0 until BASELINE_MAX_PER_DAY) {
        for (date in dates) {
            buckets.getValue(date).getOrNull(round)?.let(out::add)
            if (out.size == limit) return out.sortedWith(
                compareBy<StoryMedia> { it.takenAtMillis }.thenBy { it.uri })
        }
    }
    return out.sortedWith(compareBy<StoryMedia> { it.takenAtMillis }.thenBy { it.uri })
}


class StoryPlannerPerfTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun time(d: LocalDate, zone: ZoneId = this.zone) =
        d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun photo(i: Int, d: LocalDate, fav: Boolean = false) =
        StoryMedia("content://atlas/perf/$i", time(d), isFavorite = fav)

    private fun compare(name: String, media: List<StoryMedia>, now: Long,
        zone: ZoneId = this.zone, manual: List<ManualStory> = emptyList(),
        policy: StorySuggestionPolicy = StorySuggestionPolicy(),
        grant: String? = "g", epoch: String = "g", revision: String = "r") {
        val catalog = StoryCatalog(epoch, revision, media)
        val before = baselinePlanStories(catalog, grant, now, zone, manual, suggestionPolicy = policy)
        val after = planStories(catalog, grant, now, zone, manual, suggestionPolicy = policy)
        assertEquals(name + " entire deck/order/media/keys", before, after)
        assertEquals(name + " covers", before.cards.map { it.cover }, after.cards.map { it.cover })
        assertTrue(name + " slide cap", after.cards.filter { it.kind != StoryKind.MANUAL }
            .all { it.items.size <= 24 })
        assertTrue(name + " per-day cap", after.cards.filter { it.kind != StoryKind.MANUAL }
            .all { card -> card.items.groupingBy {
                Instant.ofEpochMilli(it.takenAtMillis).atZone(zone).toLocalDate()
            }.eachCount().values.all { it <= 3 } })
    }

    @Test fun adversarialFixturesMatchFrozenPlanner() {
        val today = LocalDate.of(2026, 11, 3)
        val now = time(today)
        val dense = (0 until 600).map {
            photo(it, LocalDate.of(2026, 10, it % 31 + 1), it % 7 == 0)
                .copy(isVideo = it % 11 == 0)
        }
        val messy = dense + listOf(
            dense[0].copy(isHidden = true), dense[1].copy(isTrashed = true),
            dense[3].copy(takenAtMillis = 0L), dense[4].copy(takenAtMillis = now + 1L),
            StoryMedia("content://atlas/unknown", 0L),
            StoryMedia("content://atlas/future", now + 5_000L),
            StoryMedia("file:///private", now),
            StoryMedia("https://external.invalid", now),
            StoryMedia("content://bad\nuri", now),
            StoryMedia("content://" + "x".repeat(2050), now))
        val manual = listOf(
            ManualStory("  trip  ", "  October   memories  ",
                listOf(dense[0].uri, dense[1].uri, dense[5].uri, dense[5].uri,
                    "content://atlas/missing", "content://atlas/future",
                    "content://atlas/unknown")),
            ManualStory("trip", "duplicate", listOf(dense[6].uri)),
            ManualStory("blank", "   ", listOf(dense[7].uri)))
        compare("dense-veto", messy, now, manual = manual)
        compare("reverse-veto", messy.reversed(), now, manual = manual)
        compare("manual-only", messy, now, manual = manual,
            policy = StorySuggestionPolicy(enabled = false))
        compare("excluded", messy, now, manual = manual,
            policy = StorySuggestionPolicy(excludedDates = setOf(LocalDate.of(2026, 10, 2)),
                excludedUris = setOf(dense[5].uri)))
        compare("grant-revoked", messy, now, manual = manual, grant = "revoked")
        compare("grant-blank", messy, now, manual = manual, epoch = "")
        compare("revision-blank", messy, now, manual = manual, revision = "")
        compare("clock-invalid", messy, -1L, manual = manual)
        val leap = listOf(photo(701, LocalDate.of(2024, 2, 29)),
            photo(702, LocalDate.of(2027, 2, 28)),
            photo(703, LocalDate.of(2027, 3, 1)),
            photo(704, LocalDate.of(2028, 2, 29)))
        compare("leap-day", leap, time(LocalDate.of(2028, 2, 29)))
        val dst = listOf("2026-03-29T00:30:00Z", "2026-03-29T01:30:00Z",
            "2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z",
            "2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z",
            "2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z")
            .mapIndexed { i, instant -> StoryMedia("content://atlas/dst/$i",
                Instant.parse(instant).toEpochMilli(), isFavorite = true) }
        for (z in listOf(zone, ZoneId.of("America/New_York")))
            compare("DST-" + z.id, dst, time(today, z), zone = z)
        compare("max-epoch", listOf(StoryMedia("content://atlas/extreme", Long.MAX_VALUE)),
            Long.MAX_VALUE)
    }

    @Test fun cancellationCheckpointCountsAndPublicationMatchReference() {
        val catalog = StoryCatalog("g", "r", (0 until 4096).map {
            photo(it, LocalDate.of(2026, 10, it % 31 + 1))
        })
        for (stop in 1..12) {
            var originalCalls = 0
            var newCalls = 0
            val before = try {
                baselinePlanStories(catalog, "g", time(LocalDate.of(2026, 11, 3)), zone,
                    cancellationCheckpoint = {
                        if (++originalCalls == stop) throw InterruptedException("cancel")
                    })
            } catch (_: InterruptedException) { null }
            val after = try {
                planStories(catalog, "g", time(LocalDate.of(2026, 11, 3)), zone,
                    cancellationCheckpoint = {
                        if (++newCalls == stop) throw InterruptedException("cancel")
                    })
            } catch (_: InterruptedException) { null }
            assertEquals("checkpoint $stop", originalCalls, newCalls)
            assertEquals("partial deck at $stop", before, after)
        }
    }

    private data class Measure(val deck: StoryDeck, val wall: Long,
        val cpu: Long?, val alloc: Long?, val heapDelta: Long)

    private fun measure(run: () -> StoryDeck): Measure {
        // Android's boot classpath excludes java.lang.management and com.sun.management.
        // Reflectively probe optional desktop-JVM counters instead of importing them.
        val mx: Any? = runCatching {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            factory.getMethod("getThreadMXBean").invoke(null)
        }.getOrNull()
        fun metric(method: String, vararg args: Any): Any? = runCatching {
            val bean = mx ?: return@runCatching null
            val types = args.map { when (it) {
                is Long -> java.lang.Long.TYPE
                else -> error("Unsupported management argument")
            } }.toTypedArray()
            bean.javaClass.getMethod(method, *types).invoke(bean, *args)
        }.getOrNull()
        fun cpu(): Long? {
            if (metric("isCurrentThreadCpuTimeSupported") != true ||
                metric("isThreadCpuTimeEnabled") != true) return null
            return (metric("getCurrentThreadCpuTime") as? Long)?.takeIf { it >= 0L }
        }
        fun allocated(): Long? {
            if (metric("isThreadAllocatedMemorySupported") != true ||
                metric("isThreadAllocatedMemoryEnabled") != true) return null
            return (metric("getThreadAllocatedBytes", Thread.currentThread().id) as? Long)
                ?.takeIf { it >= 0L }
        }
        fun heap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val c0 = cpu(); val a0 = allocated(); val h0 = heap()
        val t0 = System.nanoTime()
        val result = run()
        val wall = System.nanoTime() - t0
        val c1 = cpu(); val a1 = allocated()
        return Measure(result, wall, if (c0 != null && c1 != null) c1 - c0 else null,
            if (a0 != null && a1 != null) a1 - a0 else null, heap() - h0)
    }

    private fun summary(readings: List<Measure>): String {
        fun stats(values: List<Long>, unit: String): String {
            if (values.size != readings.size) return "unavailable"
            val sorted = values.sorted()
            return "p50=" + sorted[(sorted.size - 1) / 2] + " " + unit +
                " p95=" + sorted[(sorted.size * 95 + 99) / 100 - 1] + " " + unit
        }
        return "n=" + readings.size +
            " wall(" + stats(readings.map { it.wall }, "ns") + ")" +
            " cpu(" + stats(readings.mapNotNull { it.cpu }, "ns") + ")" +
            " allocated(" + stats(readings.mapNotNull { it.alloc }, "bytes") + ")" +
            " heapDelta(" + stats(readings.map { it.heapDelta }, "bytes") + ")"
    }

    @Test fun pairedDenseAndSparse250kJvmMeasurements() {
        val today = LocalDate.of(2026, 11, 3)
        val now = time(today)
        for ((name, days) in listOf("dense" to 31, "sparse" to (365 * 20))) {
            val timestamps = (0 until days).map { i -> time(
                if (name == "dense") LocalDate.of(2026, 10, 1).plusDays(i.toLong())
                else today.minusDays(i.toLong())) }
            val items = List(250_000) { i ->
                StoryMedia("content://atlas/perf/" + (if (i == 249_999) 200 else i),
                    timestamps[i % days], isFavorite = i % 17 == 0,
                    isTrashed = i == 249_999)
            }
            val catalog = StoryCatalog("g", "r", items)
            repeat(2) {
                assertEquals(baselinePlanStories(catalog, "g", now, zone),
                    planStories(catalog, "g", now, zone))
            }
            val before = mutableListOf<Measure>()
            val after = mutableListOf<Measure>()
            repeat(9) { iteration ->
                val pair = if (iteration % 2 == 0)
                    measure { baselinePlanStories(catalog, "g", now, zone) } to
                        measure { planStories(catalog, "g", now, zone) }
                else {
                    val optimized = measure { planStories(catalog, "g", now, zone) }
                    measure { baselinePlanStories(catalog, "g", now, zone) } to optimized
                }
                assertEquals(name + " iteration=" + iteration, pair.first.deck, pair.second.deck)
                before += pair.first; after += pair.second
            }
            println("PERF16 " + name + " rows=250000 paired=9 warmup=2 same-JVM=true")
            println("PERF16 BEFORE " + summary(before))
            println("PERF16 AFTER " + summary(after))
        }
    }
}
