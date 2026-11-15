package nl.atlas.gallery.featuregrid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import java.lang.management.ManagementFactory
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * PERF-14: compare the allocation-free event sampler with the exact pre-change
 * filter + FloatArray + Triple path. All coordinates and pointer events here
 * are synthetic; timings are JVM diagnostics, not Android-device evidence.
 */
class GalleryPinchPerfTest {
    private fun change(id: Int, x: Float, y: Float, pressed: Boolean): PointerInputChange =
        PointerInputChange(
            id = PointerId(id.toLong()),
            uptimeMillis = 10L,
            position = Offset(x, y),
            pressed = pressed,
            previousUptimeMillis = 9L,
            previousPosition = Offset(x, y),
            previousPressed = pressed
        )

    private fun stream(): List<List<PointerInputChange>> {
        val random = Random(14L)
        val events = ArrayList<List<PointerInputChange>>()
        // Include 0-5 active pointers, lifts, inactive changes and exact slop edges.
        for (count in 0..5) {
            for (step in 0 until 70) {
                val changes = ArrayList<PointerInputChange>()
                for (id in 0..5) {
                    val distance = (30 + step * (id + 1)).toFloat()
                    var x = 400f + (if (id % 2 == 0) distance else -distance) +
                        random.nextInt(5)
                    var y = 800f + (id - 3) * distance / 3f
                    if (step == 17 && id == 0) x = Float.NaN
                    if (step == 19 && id == 1) y = Float.POSITIVE_INFINITY
                    if (step == 23 && id == 2) x = Float.NEGATIVE_INFINITY
                    if (step == 29 && id == 3) x = Float.MAX_VALUE
                    if (step == 37 && id == 4) x = -0f
                    changes.add(change(id, x, y, id < count))
                }
                events.add(changes)
            }
        }
        // Explicit start, slop boundary, zoom, finger addition, lifts, and release.
        events.add(listOf(change(0, 400f, 800f, true), change(1, 600f, 800f, true)))
        events.add(listOf(change(0, 379f, 800f, true), change(1, 621f, 800f, true)))
        events.add(listOf(change(0, 300f, 800f, true), change(1, 700f, 800f, true)))
        events.add(listOf(change(0, 300f, 800f, true), change(1, 700f, 800f, true),
            change(2, 500f, 1100f, true)))
        events.add(listOf(change(0, 320f, 800f, false), change(1, 690f, 800f, true),
            change(2, 510f, 1000f, true)))
        events.add(listOf(change(0, 320f, 800f, false), change(1, 690f, 800f, false),
            change(2, 510f, 1000f, true)))
        events.add(listOf(change(0, 320f, 800f, false), change(1, 690f, 800f, false),
            change(2, 510f, 1000f, false)))
        return events
    }

    private data class LegacyReading(val count: Int, val span: Float, val cx: Float, val cy: Float)

    private fun legacyRead(changes: List<PointerInputChange>): LegacyReading {
        val pressed = changes.filter { it.pressed }
        val result = pinchSpan(
            FloatArray(pressed.size) { pressed[it].position.x },
            FloatArray(pressed.size) { pressed[it].position.y }
        )
        return LegacyReading(pressed.size, result?.first ?: Float.NaN,
            result?.second ?: Float.NaN, result?.third ?: Float.NaN)
    }

    private fun assertSameFloat(label: String, expected: Float, actual: Float) {
        assertEquals(label, expected.toBits(), actual.toBits())
    }

    @Test fun sampleMatchesOldArithmeticForEveryFingerCountAndInvalidCoordinate() {
        val reused = PinchEventMeasurement()
        for ((index, changes) in stream().withIndex()) {
            val legacy = legacyRead(changes)
            reused.read(changes)
            assertEquals("count event $index", legacy.count, reused.pressedCount)
            assertSameFloat("span event $index", legacy.span, reused.spanPx)
            assertSameFloat("focus x event $index", legacy.cx, reused.centroidX)
            assertSameFloat("focus y event $index", legacy.cy, reused.centroidY)
        }
    }

    @Test fun consumptionFocusZoomAndSettledStopMatchLegacyStream() {
        for (initial in GalleryZoomStop.entries) {
            for (allowOriginal in listOf(false, true)) {
                val old = GalleryPinchTracker(initial, 21f, allowOriginal = allowOriginal)
                val optimized = GalleryPinchTracker(initial, 21f, allowOriginal = allowOriginal)
                val reused = PinchEventMeasurement()
                for ((index, changes) in stream().withIndex()) {
                    val legacy = legacyRead(changes)
                    reused.read(changes)
                    val oldConsumed = old.onSample(legacy.count, legacy.span, legacy.cx, legacy.cy)
                    val newConsumed = optimized.onSample(
                        reused.pressedCount, reused.spanPx, reused.centroidX, reused.centroidY)
                    assertEquals("consume $initial/$allowOriginal/$index", oldConsumed, newConsumed)
                    assertEquals("started $index", old.started, optimized.started)
                    assertSameFloat("zoom $index", old.zoom, optimized.zoom)
                    assertSameFloat("focus x $index", old.focusX, optimized.focusX)
                    assertSameFloat("focus y $index", old.focusY, optimized.focusY)
                    assertSameFloat("ladder $index", old.progress, optimized.progress)
                }
                assertEquals("settled $initial/$allowOriginal", old.destination(),
                    optimized.destination())
            }
        }
    }

    private data class Metrics(
        val p50Ns: Long, val p95Ns: Long, val heapDeltaBytes: Long,
        val threadAllocatedBytes: Long?, val consumed: Int
    )

    private fun threadBytes(): Long? {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            ?: return null
        if (!bean.isThreadAllocatedMemorySupported) return null
        return try {
            if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
            bean.getThreadAllocatedBytes(Thread.currentThread().id).takeIf { it >= 0L }
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private fun heapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun measure(
        events: List<List<PointerInputChange>>,
        process: (List<PointerInputChange>) -> Boolean
    ): Metrics {
        // Identical inputs and warm-up count for each arm on one JVM/thread.
        repeat(10_000) { process(events[it % events.size]) }
        val times = LongArray(10_000)
        val beforeHeap = heapBytes()
        val beforeThread = threadBytes()
        var consumed = 0
        for (index in times.indices) {
            val input = events[index % events.size]
            val start = System.nanoTime()
            if (process(input)) consumed++
            times[index] = System.nanoTime() - start
        }
        val afterThread = threadBytes()
        val heapDelta = heapBytes() - beforeHeap
        times.sort()
        return Metrics(times[4_999], times[9_499], heapDelta,
            if (beforeThread != null && afterThread != null)
                afterThread - beforeThread else null, consumed)
    }

    @Test fun printSameJvmBeforeAfterDiagnosticsForTenThousandEvents() {
        val events = stream() // outside both timed/allocation windows
        val old = GalleryPinchTracker(GalleryZoomStop.MEDIUM, 21f)
        val optimized = GalleryPinchTracker(GalleryZoomStop.MEDIUM, 21f)
        val reused = PinchEventMeasurement()
        val before = measure(events) { changes ->
            val sample = legacyRead(changes)
            old.onSample(sample.count, sample.span, sample.cx, sample.cy)
        }
        val after = measure(events) { changes ->
            reused.read(changes)
            optimized.onSample(reused.pressedCount, reused.spanPx,
                reused.centroidX, reused.centroidY)
        }
        assertEquals(before.consumed, after.consumed)
        assertEquals(old.destination(), optimized.destination())
        println("PERF-14 JVM n=10000 baseline: p50=${before.p50Ns}ns/event " +
            "p95=${before.p95Ns}ns/event heapDelta=${before.heapDeltaBytes}B/10000 " +
            "threadAllocated=${before.threadAllocatedBytes ?: "unavailable"}B/10000")
        println("PERF-14 JVM n=10000 optimized: p50=${after.p50Ns}ns/event " +
            "p95=${after.p95Ns}ns/event heapDelta=${after.heapDeltaBytes}B/10000 " +
            "threadAllocated=${after.threadAllocatedBytes ?: "unavailable"}B/10000")
    }
}
