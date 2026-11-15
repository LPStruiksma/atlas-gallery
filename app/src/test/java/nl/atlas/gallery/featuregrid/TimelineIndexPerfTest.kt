package nl.atlas.gallery.featuregrid

import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Test

/**
 * PERF-13 differential oracle: legacyBuildGridTimelineIndex is an unchanged copy
 * of main's implementation before the run-boundary optimization. No Android,
 * private media or wall-clock-dependent expectations.
 */
class TimelineIndexPerfTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), zone)
    private fun stamp(s: String): Long = Instant.parse(s).toEpochMilli()

    private fun equivalence(
        tag: String,
        media: List<TimelineMedia>,
        sort: TimelineSort,
        density: GridDensity,
        localZone: ZoneId,
        merge: Int
    ) {
        val before = legacyBuildGridTimelineIndex(
            media, sort, density, localZone, Locale.UK, mergeSparseDaysBelow = merge, clock = clock
        )
        val after = buildGridTimelineIndex(
            media, sort, density, localZone, Locale.UK, mergeSparseDaysBelow = merge, clock = clock
        )
        val context = "$tag $sort $density $localZone merge=$merge"
        assertEquals("$context grouped", before.grouped, after.grouped)
        assertEquals("$context slots including stable keys and media indices", before.slots, after.slots)
        assertEquals("$context headers including occurrence indices", before.headers, after.headers)
        assertArrayEquals("$context media-entry mapping", before.mediaEntryPositions, after.mediaEntryPositions)
        for (i in media.indices) {
            val entry = after.entryForMedia(i)
            assertEquals("$context bubble $i", before.bubbleForEntry(entry), after.bubbleForEntry(entry))
        }
    }

    @Test fun mixedExtremeUnknownHistoricalAndGrantEpochsRemainEquivalent() {
        val historical = listOf(
            "1987-10-25T02:29:30Z", "1987-10-25T02:30:30Z",
            "1987-10-25T02:31:30Z", "1987-10-25T03:31:30Z"
        ).map(::stamp)
        val times = historical + listOf(
            stamp("2026-12-31T23:40:00Z"), stamp("2027-01-01T00:10:00Z"),
            stamp("2026-03-29T00:30:00Z"), stamp("2026-03-29T01:30:00Z"),
            stamp("2026-10-25T00:30:00Z"), stamp("2026-10-25T01:30:00Z"),
            stamp("2011-12-30T12:00:00Z"), stamp("+10000-01-01T00:00:00Z"),
            Long.MAX_VALUE, Long.MIN_VALUE, 0L, 0L
        )
        for (sort in listOf(TimelineSort.NEWEST, TimelineSort.OLDEST)) {
            val ordered = if (sort == TimelineSort.NEWEST) times.sortedDescending() else times.sorted()
            for (epoch in listOf(7, 8)) {
                val media = ordered.mapIndexed { i, t -> TimelineMedia("grant:$epoch:$i", t) }
                for (localZone in listOf(
                    ZoneId.of("UTC"), zone, ZoneId.of("America/St_Johns"), ZoneId.of("Pacific/Apia")
                )) {
                    for (density in GridDensity.entries) {
                        for (merge in listOf(0, 4)) {
                            equivalence("mixed-$epoch", media, sort, density, localZone, merge)
                        }
                    }
                }
            }
        }
    }

    @Test fun sparseMonthsFullRowsAndNonChronologicalSortsRemainEquivalent() {
        val base = stamp("2026-11-30T12:00:00Z")
        val sparse = (0 until 90).flatMap { d ->
            val count = if (d % 11 == 0) 5 else if (d % 7 == 0) 2 else 1
            List(count) { n -> TimelineMedia("d$d:$n", base - d * 86_400_000L - n * 1_000L) }
        }
        for (sort in listOf(TimelineSort.NEWEST, TimelineSort.OLDEST)) {
            val ordered = if (sort == TimelineSort.NEWEST) sparse else sparse.reversed()
            for (density in GridDensity.entries) for (merge in listOf(0, 2, 4, 8)) {
                equivalence("sparse", ordered, sort, density, zone, merge)
            }
        }
        val reversed = sparse.reversed()
        val scrambled = sparse.shuffled(java.util.Random(42))
        for (media in listOf(reversed, scrambled, emptyList())) {
            for (sort in TimelineSort.entries) {
                equivalence("unsorted", media, sort, GridDensity.MEDIUM, zone, 4)
            }
        }
    }

    private data class Sample(
        val wallMs: Double, val cpuMs: Double?, val allocatedBytes: Long?, val heapDeltaBytes: Long
    )

    private fun measure(build: () -> GridTimelineIndex): Sample {
        // Pre-collection is outside the timed region. Heap delta is observational
        // (GC may run during the build); allocated bytes are per-thread when supported.
        System.gc()
        val runtime = ManagementFactory.getMemoryMXBean()
        val threads = ManagementFactory.getThreadMXBean()
        val allocations = threads as? com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        val heap0 = runtime.heapMemoryUsage.used
        val cpu0 = if (threads.isCurrentThreadCpuTimeSupported) threads.currentThreadCpuTime else -1L
        val allocation0 = if (allocations?.isThreadAllocatedMemorySupported == true &&
            allocations.isThreadAllocatedMemoryEnabled) allocations.getThreadAllocatedBytes(tid) else -1L
        val wall0 = System.nanoTime()
        val result = build()
        val wall1 = System.nanoTime()
        val cpu1 = if (cpu0 >= 0) threads.currentThreadCpuTime else -1L
        val allocation1 = if (allocation0 >= 0) allocations!!.getThreadAllocatedBytes(tid) else -1L
        val heap1 = runtime.heapMemoryUsage.used
        assertTrue(result.mediaCount > 0)
        return Sample(
            (wall1 - wall0) / 1_000_000.0,
            if (cpu0 >= 0 && cpu1 >= 0) (cpu1 - cpu0) / 1_000_000.0 else null,
            if (allocation0 >= 0 && allocation1 >= 0) allocation1 - allocation0 else null,
            heap1 - heap0
        )
    }

    private fun formatMetric(values: List<Double>): String {
        if (values.isEmpty()) return "n/a"
        val sorted = values.sorted()
        fun nearest(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        return "p50=%.2f p95=%.2f".format(Locale.ROOT, nearest(.50), nearest(.95))
    }

    private fun report(size: Int, name: String, samples: List<Sample>) {
        val cpu = samples.mapNotNull { it.cpuMs }
        val allocated = samples.mapNotNull { it.allocatedBytes?.toDouble() }
        println(
            "PERF-13 size=$size method=$name n=${samples.size} " +
            "wall_ms[${formatMetric(samples.map { it.wallMs })}] " +
            "cpu_ms[${formatMetric(cpu)}] " +
            "allocated_bytes[${formatMetric(allocated)}] " +
            "heap_delta_bytes[${formatMetric(samples.map { it.heapDeltaBytes.toDouble() })}]"
        )
    }

    @Test fun pairedJvmBeforeAfterLargeSparseAndSmallDense() {
        for (size in listOf(250_000, 1_000)) {
            val origin = stamp("2026-11-15T12:00:00Z")
            val times = if (size == 250_000) {
                List(size) { i -> origin - i.toLong() * 86_400_000L }
            } else {
                List(size) { i -> origin - i.toLong() * 1_000L }
            }
            val media = times.mapIndexed { i, time -> TimelineMedia("synthetic:$i", time) }
            val sparseThreshold = if (size == 250_000) 4 else 0
            val old = { legacyBuildGridTimelineIndex(media, TimelineSort.NEWEST, GridDensity.MEDIUM,
                zone, Locale.UK, sparseThreshold, clock) }
            val fresh = { buildGridTimelineIndex(media, TimelineSort.NEWEST, GridDensity.MEDIUM,
                zone, Locale.UK, sparseThreshold, clock) }
            repeat(2) { old(); fresh() }
            val before = ArrayList<Sample>()
            val after = ArrayList<Sample>()
            // Alternate order to limit directional warmup and thermal drift.
            repeat(if (size == 250_000) 10 else 16) { i ->
                if (i % 2 == 0) {
                    before.add(measure(old)); after.add(measure(fresh))
                } else {
                    after.add(measure(fresh)); before.add(measure(old))
                }
            }
            report(size, "BEFORE", before)
            report(size, "AFTER", after)
        }
    }
}

// Frozen PRE-PERF-13 production implementation, only the function name changed.
// It deliberately repeats date conversion at run boundaries and sparse joins;
// all other semantics (including the old checkpoint locations) are left intact.
private fun legacyBuildGridTimelineIndex(
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
    var cursor = 0
    while (cursor < items.size) {
        if ((cursor and 1023) == 0) cancellationCheckpoint?.invoke()
        val timestamp = items[cursor].takenAtMillis
        val ordinal = bucketOrdinal(timestamp)
        val (groupKey, label) = groupFor(timestamp)
        var end = cursor + 1
        while (end < items.size && bucketOrdinal(items[end].takenAtMillis) == ordinal) {
            if ((end and 1023) == 0) cancellationCheckpoint?.invoke()
            end++
        }
        var headerGroupKey = groupKey
        var headerLabel = label
        if (mergeSparseDaysBelow > 0 && bucket == TimelineBucket.DAY &&
            ordinal != unknownBucket && end - cursor < mergeSparseDaysBelow) {
            val firstDay = java.time.LocalDate.ofEpochDay(ordinal)
            var lastOrdinal = ordinal
            while (end < items.size) {
                val nextOrdinal = bucketOrdinal(items[end].takenAtMillis)
                if (nextOrdinal == unknownBucket) break
                val nextDay = java.time.LocalDate.ofEpochDay(nextOrdinal)
                if (nextDay.year != firstDay.year || nextDay.month != firstDay.month) break
                var nextEnd = end + 1
                while (nextEnd < items.size && bucketOrdinal(items[nextEnd].takenAtMillis) == nextOrdinal) nextEnd++
                if (nextEnd - end >= mergeSparseDaysBelow) break
                end = nextEnd
                lastOrdinal = nextOrdinal
            }
            if (lastOrdinal != ordinal) {
                val a = java.time.LocalDate.ofEpochDay(minOf(ordinal, lastOrdinal))
                val b = java.time.LocalDate.ofEpochDay(maxOf(ordinal, lastOrdinal))
                headerGroupKey = "$a..$b"
                headerLabel = "${a.dayOfMonth}\u2013${b.format(if (b.year == currentYear) shortDayFormat else dayFormat)}"
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
    }
    cancellationCheckpoint?.invoke()
    return GridTimelineIndex(slots, positions, headers, grouped = true)
}

