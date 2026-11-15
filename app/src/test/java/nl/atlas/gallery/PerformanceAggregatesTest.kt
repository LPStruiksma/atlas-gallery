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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedCapacity() { PerformanceAggregates(capacity = 5000) }
}
