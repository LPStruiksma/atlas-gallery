package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.DateTimeException
import java.util.Locale
import kotlin.math.roundToInt

/** Chronology is a capability of the current sort, not a property of every album. */
enum class TimelineSort { NEWEST, OLDEST, LARGEST, SMALLEST, RANDOM }
enum class TimelineBucket { DAY, MONTH, YEAR }

data class TimelineMedia(val key: String, val takenAtMillis: Long)

sealed interface TimelineSlot {
    val stableKey: String
    data class Header(
        override val stableKey: String,
        val label: String,
        val firstMediaIndex: Int,
        val mediaCount: Int
    ) : TimelineSlot
    data class Media(override val stableKey: String, val mediaIndex: Int) : TimelineSlot
}

data class TimelineHeaderPosition(val entryIndex: Int, val header: TimelineSlot.Header)

/** Immutable full-span header and media slots built for one catalog generation. */
class GridTimelineIndex(
    val slots: List<TimelineSlot>,
    val mediaEntryPositions: IntArray,
    val headers: List<TimelineHeaderPosition>,
    val grouped: Boolean
) {
    val mediaCount: Int get() = mediaEntryPositions.size

    fun entryForMedia(index: Int): Int = mediaEntryPositions[index]

    /** Scrubbing targets *media count*, not raw grid slots containing headers. */
    fun entryAtFraction(fraction: Float): Int? {
        if (mediaCount == 0 || !fraction.isFinite()) return null
        val mediaIndex = ((mediaCount - 1).toDouble() * fraction.coerceIn(0f, 1f)).roundToInt()
        return mediaEntryPositions[mediaIndex]
    }

    /** Date bubble for a visible media entry. O(log number of headers). */
    fun bubbleForEntry(entryIndex: Int): String? {
        if (!grouped || entryIndex !in slots.indices || headers.isEmpty()) return null
        var lo = 0
        var hi = headers.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (headers[mid].entryIndex <= entryIndex) lo = mid else hi = mid - 1
        }
        return headers[lo].header.label
    }
}

/** No dependence on Samsung database types, Android, or unavailable proprietary date tables. */
fun buildGridTimelineIndex(
    items: List<TimelineMedia>,
    sort: TimelineSort,
    density: GridDensity,
    zone: ZoneId,
    locale: Locale = Locale.getDefault(),
    /**
     * #642 (spec GRID:T1-T2 "sparse single days merge"): when > 0, consecutive
     * days in the same month that each hold fewer photos than this (a part
     * row) share one header, "3–9 Nov" (current year), so their photos fill whole rows.
     * 0 keeps one header per day. Day headers only; months never merge.
     */
    mergeSparseDaysBelow: Int = 0,
    /** Sample once per index, in the viewing device's time zone. Injectable for tests. */
    clock: Clock = Clock.system(zone),
    cancellationCheckpoint: (() -> Unit)? = null
): GridTimelineIndex {
    val positions = IntArray(items.size)
    val chronological = sort == TimelineSort.NEWEST || sort == TimelineSort.OLDEST
    // Fail closed on stale/asynchronously wrong sort: headers are incorrect unless
    // timestamps really are monotone in the *same* catalog publication.
    var ordered = chronological
    if (chronological) for (i in 1 until items.size) {
        if ((i and 1023) == 0) cancellationCheckpoint?.invoke()
        val prev = items[i - 1].takenAtMillis
        val next = items[i].takenAtMillis
        if ((sort == TimelineSort.NEWEST && next > prev) ||
            (sort == TimelineSort.OLDEST && next < prev)) { ordered = false; break }
    }
    val slots = ArrayList<TimelineSlot>(items.size + if (ordered) items.size / 5 else 0)
    val headers = ArrayList<TimelineHeaderPosition>()
    // Historical timezone offsets can move local midnight BACKWARD even while
    // UTC timestamps remain correctly sorted (e.g. St John's, 25 Oct 1987).
    // A calendar bucket may therefore occur in multiple disjoint runs. Compose
    // requires a distinct stable key for each *header occurrence*.
    val usedHeaderKeys = HashSet<String>()
    if (!ordered) {
        items.forEachIndexed { index, media ->
            if ((index and 1023) == 0) cancellationCheckpoint?.invoke()
            positions[index] = index
            slots.add(TimelineSlot.Media("media:" + media.key, index))
        }
        cancellationCheckpoint?.invoke()
        return GridTimelineIndex(slots, positions, headers, grouped = false)
    }
    val bucket = when (density) {
        GridDensity.LARGE, GridDensity.MEDIUM -> TimelineBucket.DAY
        GridDensity.SMALL, GridDensity.MONTH -> TimelineBucket.MONTH
        GridDensity.YEAR -> TimelineBucket.YEAR
    }
    val currentYear = LocalDate.now(clock.withZone(zone)).year
    val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    val shortDayFormat = DateTimeFormatter.ofPattern("d MMM", locale)
    val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val shortMonthFormat = DateTimeFormatter.ofPattern("LLLL", locale)
    fun groupFor(timestamp: Long): Pair<String, String> {
        if (timestamp == 0L) return "unknown" to "Unknown date"
        val local = try { Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() }
        catch (_: DateTimeException) { return "unknown" to "Unknown date" }
        return when (bucket) {
            TimelineBucket.DAY -> local.toString() to
                local.format(if (local.year == currentYear) shortDayFormat else dayFormat)
            TimelineBucket.MONTH -> {
                // Extended ISO years can exceed four digits; do not substring(0, 7).
                local.withDayOfMonth(1).toString().dropLast(3) to
                    local.format(if (local.year == currentYear) shortMonthFormat else monthFormat)
            }
            TimelineBucket.YEAR -> local.year.toString() to local.year.toString()
        }
    }
    // Only headers need localized strings. A 150k-photo day may have a single
    // header: formatting every photo generated 150k short-lived Strings/Pairs.
    // The primitive bucket ordinal also avoids textual equality and makes
    // month/day comparisons allocation-free after the LocalDate calculation.
    val unknownBucket = Long.MIN_VALUE // outside LocalDate/YearMonth valid ranges
    fun bucketOrdinal(timestamp: Long): Long {
        if (timestamp == 0L) return unknownBucket
        val date = try { Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() }
        catch (_: DateTimeException) { return unknownBucket }
        return when (bucket) {
            TimelineBucket.DAY -> date.toEpochDay()
            TimelineBucket.MONTH -> date.year.toLong() * 12L + date.monthValue.toLong()
            TimelineBucket.YEAR -> date.year.toLong()
        }
    }
    // Carry the first ordinal of the next run instead of converting the same
    // boundary timestamp again when the outer loop advances. The sparse-day
    // lookahead carries it forward as well. No O(n) ordinal cache is allocated.
    var cursor = 0
    var nextRunOrdinal = if (items.isNotEmpty()) bucketOrdinal(items[0].takenAtMillis)
        else unknownBucket
    while (cursor < items.size) {
        if ((cursor and 1023) == 0) cancellationCheckpoint?.invoke()
        val timestamp = items[cursor].takenAtMillis
        val ordinal = nextRunOrdinal
        val (groupKey, label) = groupFor(timestamp)
        var end = cursor + 1
        var afterRunOrdinal = unknownBucket
        while (end < items.size) {
            val candidateOrdinal = bucketOrdinal(items[end].takenAtMillis)
            if (candidateOrdinal != ordinal) {
                afterRunOrdinal = candidateOrdinal
                break
            }
            if ((end and 1023) == 0) cancellationCheckpoint?.invoke()
            end++
        }
        var headerGroupKey = groupKey
        var headerLabel = label
        if (mergeSparseDaysBelow > 0 && bucket == TimelineBucket.DAY &&
            ordinal != unknownBucket && end - cursor < mergeSparseDaysBelow) {
            val firstDay = LocalDate.ofEpochDay(ordinal)
            var lastOrdinal = ordinal
            while (end < items.size) {
                val nextOrdinal = afterRunOrdinal
                if (nextOrdinal == unknownBucket) break
                val nextDay = LocalDate.ofEpochDay(nextOrdinal)
                if (nextDay.year != firstDay.year || nextDay.month != firstDay.month) break
                var nextEnd = end + 1
                var nextAfterOrdinal = unknownBucket
                while (nextEnd < items.size) {
                    val candidateOrdinal = bucketOrdinal(items[nextEnd].takenAtMillis)
                    if (candidateOrdinal != nextOrdinal) {
                        nextAfterOrdinal = candidateOrdinal
                        break
                    }
                    // The old sparse lookahead had no in-run checkpoint.
                    if ((nextEnd and 1023) == 0) cancellationCheckpoint?.invoke()
                    nextEnd++
                }
                if (nextEnd - end >= mergeSparseDaysBelow) break
                end = nextEnd
                afterRunOrdinal = nextAfterOrdinal
                lastOrdinal = nextOrdinal
            }
            if (lastOrdinal != ordinal) {
                val a = LocalDate.ofEpochDay(minOf(ordinal, lastOrdinal))
                val b = LocalDate.ofEpochDay(maxOf(ordinal, lastOrdinal))
                headerGroupKey = a.toString() + ".." + b.toString()
                headerLabel = a.dayOfMonth.toString() + "–" +
                    b.format(if (b.year == currentYear) shortDayFormat else dayFormat)
            }
        }
        val baseHeaderKey = "header:" + bucket.name + ":" + headerGroupKey
        // Keep the established key on the first appearance, preserving normal
        // scroll identity; disambiguate only a repeated, noncontiguous bucket.
        val stableHeaderKey = if (usedHeaderKeys.add(baseHeaderKey)) baseHeaderKey
            else (baseHeaderKey + "@" + cursor).also { check(usedHeaderKeys.add(it)) }
        val header = TimelineSlot.Header(stableHeaderKey, headerLabel, cursor, end - cursor)
        headers.add(TimelineHeaderPosition(slots.size, header))
        slots.add(header)
        for (i in cursor until end) {
            positions[i] = slots.size
            slots.add(TimelineSlot.Media("media:" + items[i].key, i))
        }
        cursor = end
        nextRunOrdinal = afterRunOrdinal
    }
    cancellationCheckpoint?.invoke()
    return GridTimelineIndex(slots, positions, headers, grouped = true)
}
