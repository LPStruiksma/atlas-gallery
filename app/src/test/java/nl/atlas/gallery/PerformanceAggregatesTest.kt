package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceAggregatesTest {
    @Test fun nearestRankPercentilesAreDeterministic() {
        val samples = PerformanceAggregates(capacity = 5)
        for (value in listOf(50.0, 10.0, 30.0, 20.0, 40.0)) samples.record("grid_frame_ms", value)
        val row = samples.snapshot("grid_frame_ms")
        assertEquals(5, row.count)
        assertEquals(30.0, row.p50!!, 0.0)
        assertEquals(50.0, row.p90!!, 0.0)
        assertEquals(50.0, row.p95!!, 0.0)
    }

    @Test fun ringBufferEvictsOldestAndClampsInvalidSamples() {
        val samples = PerformanceAggregates(capacity = 3)
        for (value in 1..6) samples.record("viewer_ms", value.toDouble())
        samples.record("viewer_ms", -1.0)
        samples.record("viewer_ms", Double.NaN)
        samples.record("viewer_ms", Double.POSITIVE_INFINITY)
        assertEquals(3, samples.snapshot("viewer_ms").count)
        assertEquals(5.0, samples.snapshot("viewer_ms").p50!!, 0.0)
        assertEquals(6.0, samples.snapshot("viewer_ms").p95!!, 0.0)
    }

    @Test fun metricsStaySeparateAndUnknownIsNotZero() {
        val samples = PerformanceAggregates(capacity = 2)
        samples.record("cold_start_ms", 123.0)
        assertEquals(1, samples.snapshot("cold_start_ms").count)
        assertEquals(0, samples.snapshot("face_photos_min").count)
        assertEquals(null, samples.snapshot("face_photos_min").p50)
        assertFalse(samples.snapshot("face_photos_min").p95 != null)
    }

    @Test fun capacityAndKeysAreBounded() {
        val samples = PerformanceAggregates(capacity = 2)
        assertFalse(samples.record("", 10.0))
        assertFalse(samples.record("file:///private/photo.jpg", 5.0))
        assertTrue(samples.record("grid_frame_ms", 8.0))
        assertEquals(listOf("grid_frame_ms"), samples.metricNames())
    }

    @Test fun allActionKeysAreAllowedButArbitraryNamesAreNot() {
        val samples = PerformanceAggregates(capacity = 2)
        for (i in 1..22) assertTrue(samples.record("action_a${i}_ms", i.toDouble()))
        assertFalse(samples.record("action_a23_ms", 23.0))
        assertEquals(22, samples.metricNames().size)
    }

    @Test fun nativeMemoryKeysAreAllowlistedBoundedAndUseNearestRankPercentiles() {
        val samples = PerformanceAggregates(capacity = 3)
        listOf(10.0, 40.0, 20.0, 30.0).forEach { assertTrue(samples.record("native_heap_mib", it)) }
        assertTrue(samples.record("process_pss_mib", 64.0))
        assertFalse(samples.record("memory_private_label", 1.0))
        val native = samples.snapshot("native_heap_mib")
        assertEquals(3, native.count)
        assertEquals(30.0, native.p50!!, 0.0)
        assertEquals(40.0, native.p95!!, 0.0)
        assertEquals(40.0, native.max!!, 0.0)
    }

    @Test fun frameBudgetRatioUsesOnlyBoundedValidatedSamples() {
        val samples = PerformanceAggregates(capacity = 4)
        assertEquals(null, samples.positiveFraction("grid_frame_over_budget"))
        for (value in listOf(1.0, 0.0, 1.0, 1.0, 0.0)) {
            assertTrue(samples.record("grid_frame_over_budget", value))
        }
        assertFalse(samples.record("grid_frame_over_budget", Double.NaN))
        assertEquals(4, samples.snapshot("grid_frame_over_budget").count)
        assertEquals(0.5, samples.positiveFraction("grid_frame_over_budget")!!, 0.0)
        assertEquals(null, samples.positiveFraction("unknown"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedCapacity() { PerformanceAggregates(capacity = 5000) }

    @Test fun coldStartOriginIsProcessStartNotFirstCollectorUse() {
        // Process started at t=1_000 ms; the collector is first touched only
        // at t=1_700 ms (after the first grid frame was drawn). The origin
        // must stay the process start, so the sample is 700 ms, not ~0.
        var now = 1_000L
        var processStartReads = 0
        val clock = ColdStartClock(
            processStartElapsedMs = { processStartReads++; 1_000L },
            nowElapsedMs = { now }
        )
        now = 1_700L
        assertEquals(700.0, clock.elapsedMs()!!, 0.0)
        now = 2_500L
        assertEquals(1_500.0, clock.elapsedMs()!!, 0.0)
        assertEquals(2, processStartReads)
    }

    @Test fun coldStartRejectsMissingOrInconsistentClocks() {
        assertEquals(null, ColdStartClock({ 0L }, { 5_000L }).elapsedMs())
        assertEquals(null, ColdStartClock({ 6_000L }, { 5_000L }).elapsedMs())
    }
}
