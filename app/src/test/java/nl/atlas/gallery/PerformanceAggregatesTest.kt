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

    @Test fun coldAndWarmAreIndependentNumericDistributions() {
        val samples = PerformanceAggregates()
        listOf(300.0, 400.0, 500.0).forEach { samples.record("cold_start_ms", it) }
        listOf(12.0, 20.0, 40.0).forEach { assertTrue(samples.record("warm_resume_ms", it)) }
        assertFalse(samples.record("warm_resume_ms", Double.NaN))
        assertEquals(3, samples.snapshot("cold_start_ms").count)
        assertEquals(400.0, samples.snapshot("cold_start_ms").p50!!, 0.0)
        assertEquals(3, samples.snapshot("warm_resume_ms").count)
        assertEquals(20.0, samples.snapshot("warm_resume_ms").p50!!, 0.0)
        assertEquals(40.0, samples.snapshot("warm_resume_ms").p90!!, 0.0)
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

    private class FakeStore(private val initial: Map<String, List<Double>> = emptyMap()) :
        PerformanceAggregates.PerformanceStore {
        val saved = HashMap<String, List<Double>>()
        override fun load(): Map<String, List<Double>> = initial
        override fun save(metric: String, values: List<Double>) { saved[metric] = values }
    }

    @Test fun restoresSamplesFromStoreAndKeepsNewestCapacity() {
        val store = FakeStore(mapOf("grid_frame_ms" to listOf(1.0, 2.0, 3.0, 4.0, 5.0), "not_a_metric" to listOf(9.0)))
        val samples = PerformanceAggregates(capacity = 3, store = store)
        samples.restore(store.load())
        val row = samples.snapshot("grid_frame_ms")
        assertEquals(3, row.count)
        assertEquals(5.0, row.max!!, 0.0)
        assertEquals(0, samples.snapshot("not_a_metric").count)
    }

    @Test fun restoreKeepsSavedSamplesBeforeNewOnes() {
        val samples = PerformanceAggregates(capacity = 3)
        samples.record("viewer_ms", 40.0)
        samples.restore(mapOf("viewer_ms" to listOf(10.0, 20.0, 30.0), "not_a_metric" to listOf(1.0)))
        val row = samples.snapshot("viewer_ms")
        assertEquals(3, row.count)
        assertEquals(40.0, row.max!!, 0.0)
        assertEquals(30.0, row.p50!!, 0.0)
    }

    @Test fun savesEveryFewSamplesAndOnFlush() {
        val store = FakeStore()
        val samples = PerformanceAggregates(capacity = 256, store = store)
        samples.restore(emptyMap())
        repeat(PerformanceAggregates.SAVE_EVERY - 1) { samples.record("viewer_ms", 10.0) }
        assertFalse(store.saved.containsKey("viewer_ms"))
        samples.record("viewer_ms", 10.0)
        assertEquals(PerformanceAggregates.SAVE_EVERY, store.saved["viewer_ms"]!!.size)
        samples.record("viewer_ms", 20.0)
        samples.flush()
        assertEquals(PerformanceAggregates.SAVE_EVERY + 1, store.saved["viewer_ms"]!!.size)
    }

    @Test fun fullRingSavesOnlyEverySaveEveryRecords() {
        var saves = 0
        val counting = object : PerformanceAggregates.PerformanceStore {
            override fun load() = emptyMap<String, List<Double>>()
            override fun save(metric: String, values: List<Double>) { saves++ }
        }
        val full = PerformanceAggregates(capacity = 16, store = counting)
        full.restore(emptyMap())
        repeat(16 * PerformanceAggregates.SAVE_EVERY) { full.record("viewer_ms", 1.0) }
        assertEquals(16, saves)
    }

    @Test fun doesNotOverwritePriorHistoryBeforeRestore() {
        val store = FakeStore(mapOf("viewer_ms" to listOf(1.0, 2.0)))
        val samples = PerformanceAggregates(capacity = 256, store = store)
        repeat(PerformanceAggregates.SAVE_EVERY * 2) { samples.record("viewer_ms", 9.0) }
        samples.flush()
        assertFalse(store.saved.containsKey("viewer_ms"))
        samples.restore(store.load())
        samples.flush()
        assertEquals(2 + PerformanceAggregates.SAVE_EVERY * 2, store.saved["viewer_ms"]!!.size)
    }
}
