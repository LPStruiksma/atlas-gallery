package nl.atlas.gallery.featuregrid

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/**
 * DAILY-5 (#289): isolate the eager per-media key allocation in the existing
 * ungrouped index path, then compare it with the production path on the *same*
 * 250k synthetic media. No private data, Android runtime, or device-jank claim.
 *
 * The baseline copies the pre-DAILY-5 ungrouped branch of TimelineIndex.kt.
 * The two branches allocate the same slot/position/list structure; the only
 * intentional difference is eager versus deferred "media:" string creation.
 * Both paths also serve as a differential correctness oracle on small inputs.
 */
class TimelineIndexScaleTest {
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), zone)
    private var retained: GridTimelineIndex? = null

    private fun eagerUngroupedBaseline(items: List<TimelineMedia>): GridTimelineIndex {
        val positions = IntArray(items.size)
        val slots = ArrayList<TimelineSlot>(items.size)
        val headers = ArrayList<TimelineHeaderPosition>()
        // This was also initialized before the ungrouped early return.
        val usedHeaderKeys = HashSet<String>()
        items.forEachIndexed { index, media ->
            positions[index] = index
            slots.add(TimelineSlot.Media("media:" + media.key, index))
        }
        // Keep the same outward structure as the old ungrouped branch.
        return GridTimelineIndex(slots, positions, headers, grouped = false)
    }

    private fun productionUngrouped(items: List<TimelineMedia>): GridTimelineIndex =
        buildGridTimelineIndex(items, TimelineSort.LARGEST, GridDensity.MEDIUM,
            zone, Locale.UK, clock = clock)

    @Test fun lazyAndEagerSlotsRetainThePublicMediaDataContract() {
        val source = listOf(
            TimelineMedia("content://a", 7),
            TimelineMedia("media:already-part-of-key", 6),
            TimelineMedia("", 5)
        )
        val old = eagerUngroupedBaseline(source)
        val new = productionUngrouped(source)
        assertEquals(old.slots, new.slots)
        assertArrayEquals(old.mediaEntryPositions, new.mediaEntryPositions)
        assertEquals(old.headers, new.headers)
        assertFalse(new.grouped)

        for (i in source.indices) {
            val slot = new.slots[i] as TimelineSlot.Media
            assertEquals("media:" + source[i].key, slot.stableKey)
            assertEquals(i, slot.mediaIndex)
            assertEquals(slot, old.slots[i])
            assertEquals(slot.hashCode(), old.slots[i].hashCode())
            assertEquals("media:" + source[i].key, slot.component1())
            assertEquals(i, slot.component2())
            assertEquals(slot, slot.copy())
            assertEquals(
                TimelineSlot.Media("changed", i + 1),
                slot.copy(stableKey = "changed", mediaIndex = i + 1)
            )
            assertEquals(
                "Media(stableKey=${slot.stableKey}, mediaIndex=$i)",
                slot.toString()
            )
        }
    }

    @Test fun groupedOccurrencesKeepHistoricalRepeatedBucketKeysAndBubbleOrder() {
        val times = listOf(
            "1987-10-25T02:29:30Z", "1987-10-25T02:30:30Z",
            "1987-10-25T02:31:30Z", "1987-10-25T03:31:30Z"
        )
        val source = times.mapIndexed { i, utc ->
            TimelineMedia("rollback:$i", Instant.parse(utc).toEpochMilli())
        }
        val result = buildGridTimelineIndex(
            source, TimelineSort.OLDEST, GridDensity.MEDIUM,
            ZoneId.of("America/St_Johns"), Locale.UK, clock = clock
        )
        assertEquals(listOf(
            "header:DAY:1987-10-24", "header:DAY:1987-10-25",
            "header:DAY:1987-10-24@2", "header:DAY:1987-10-25@3"
        ), result.headers.map { it.header.stableKey })
        assertEquals(listOf(1, 3, 5, 7), source.indices.map(result::entryForMedia))
        for (i in source.indices) {
            val entry = result.entryForMedia(i)
            assertEquals("media:rollback:$i", result.slots[entry].stableKey)
            assertEquals(i, (result.slots[entry] as TimelineSlot.Media).mediaIndex)
            assertEquals(result.headers[i].header.label, result.bubbleForEntry(entry))
        }
    }

    private data class Sample(
        val allocatedBytes: Long?,
        val retainedHeapBytes: Long,
        val wallMs: Double
    )

    // Compile against Android's JVM unit-test classpath without referencing
    // java.lang.management or com.sun.management at compile time. On HotSpot,
    // these reflection calls report current-thread allocated bytes; elsewhere
    // report n/a rather than manufacturing an allocation number.
    private fun threadAllocatedBytes(): Long? = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        val type = Class.forName("com.sun.management.ThreadMXBean")
        if (!type.isInstance(bean)) return null
        if (type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true)
            return null
        if (type.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true)
            return null
        (type.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)
            .invoke(bean, Thread.currentThread().id) as Number).toLong()
    }.getOrNull()

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun sample(build: () -> GridTimelineIndex): Sample {
        retained = null
        System.gc()
        val heapBefore = usedHeap()
        val allocBefore = threadAllocatedBytes()
        val start = System.nanoTime()
        val built = build()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        val allocAfter = threadAllocatedBytes()
        // Keep the full index reachable during the next GC; the delta is
        // observational and can fluctuate with collector/heap behaviour.
        retained = built
        System.gc()
        val retainedBytes = usedHeap() - heapBefore
        assertEquals(250_000, retained?.mediaCount)
        retained = null
        return Sample(
            if (allocBefore != null && allocAfter != null) allocAfter - allocBefore else null,
            retainedBytes,
            elapsed
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun report(name: String, samples: List<Sample>) {
        val alloc = samples.mapNotNull { it.allocatedBytes?.toDouble() }
        val retained = samples.map { it.retainedHeapBytes.toDouble() }
        println(
            "DAILY-5 size=250000 mode=ungrouped method=$name runs=${samples.size} " +
            "allocated_bytes_per_item=${if (alloc.isEmpty()) "n/a" else median(alloc) / 250_000} " +
            "retained_heap_bytes_per_item=${median(retained) / 250_000} " +
            "wall_ms_p50=${median(samples.map { it.wallMs })}"
        )
    }

    @Test fun paired250kJvmAllocationsAndRetainedHeapAreReportedPerMedia() {
        val origin = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
        val source = List(250_000) { i -> TimelineMedia("synthetic:$i", origin - i) }
        val before = { eagerUngroupedBaseline(source) }
        val after = { productionUngrouped(source) }

        // Ensure the JIT sees both paths before recording alternating samples.
        repeat(2) {
            retained = before()
            retained = after()
        }
        retained = null

        val baseline = ArrayList<Sample>()
        val optimized = ArrayList<Sample>()
        repeat(5) { i ->
            if (i % 2 == 0) {
                baseline.add(sample(before))
                optimized.add(sample(after))
            } else {
                optimized.add(sample(after))
                baseline.add(sample(before))
            }
        }
        report("EAGER_BASELINE", baseline)
        report("LAZY_PRODUCTION", optimized)

        // A comparison is meaningful only when the host reports allocated
        // bytes. Retained-heap readings are published, not asserted: GC is not
        // deterministic. The hot loop should save at least 16 B per media.
        val eager = baseline.mapNotNull { it.allocatedBytes?.toDouble() }
        val lazy = optimized.mapNotNull { it.allocatedBytes?.toDouble() }
        if (eager.isNotEmpty() && lazy.isNotEmpty()) {
            assertTrue(
                "expected >=16 bytes/media fewer allocations; see DAILY-5 metrics",
                (median(eager) - median(lazy)) / 250_000 >= 16.0
            )
        }
    }
}
