package nl.atlas.gallery.featuremedia.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only: no Android bitmap, video file, or real media. */
class VideoKeyframePlanTest {
    @Test fun shortAndBoundaryDurations() {
        assertTrue(VideoKeyframePlan.candidateTimes(-1L).isEmpty())
        assertTrue(VideoKeyframePlan.candidateTimes(0L).isEmpty())
        assertEquals(listOf(0L), VideoKeyframePlan.candidateTimes(1L))
        assertEquals(listOf(0L), VideoKeyframePlan.candidateTimes(2_000L))
        assertEquals(listOf(0L, 2_000L), VideoKeyframePlan.candidateTimes(2_001L))
        assertEquals(listOf(0L, 2_000L, 4_000L),
            VideoKeyframePlan.candidateTimes(5_999L))
    }

    @Test fun exactCapAndLongClipSpansRemainDeterministic() {
        val exact = VideoKeyframePlan.candidateTimes(120_000L)
        assertEquals(60, exact.size)
        assertEquals((0L until 60L).map { it * 2_000L }, exact)
        val long = VideoKeyframePlan.candidateTimes(3_600_000L)
        assertEquals(60, long.size)
        assertEquals(0L, long.first())
        assertEquals(3_540_000L, long.last())
        assertTrue(long.zipWithNext().all { (a, b) -> b - a >= 2_000L })
        assertEquals(long, VideoKeyframePlan.candidateTimes(3_600_000L))
        val nearOverflow = VideoKeyframePlan.candidateTimes(Long.MAX_VALUE)
        assertEquals(60, nearOverflow.size)
        assertTrue(nearOverflow.zipWithNext().all { (a, b) -> b > a })
        assertTrue(nearOverflow.last() < Long.MAX_VALUE)
    }

    private fun histogram(first: Int, second: Int): List<Int> =
        List(VideoKeyframePlan.LUMA_BINS) {
            when (it) { 1 -> first; 2 -> second; else -> 0 }
        }

    @Test fun sceneFilterRejectsNearDuplicatesAndKeepsLargeOrGradualChanges() {
        val first = histogram(100, 0)
        val drift = histogram(90, 10)
        val transition = histogram(70, 30)
        val different = histogram(0, 100)
        assertEquals(0.1, VideoKeyframePlan.sceneDistance(first, drift), 1e-9)
        assertEquals(1.0, VideoKeyframePlan.sceneDistance(first, different), 1e-9)
        val observations = listOf(
            VideoKeyframePlan.FrameLuma(0L, first),
            VideoKeyframePlan.FrameLuma(2_000L, drift),
            VideoKeyframePlan.FrameLuma(4_000L, transition),
            VideoKeyframePlan.FrameLuma(6_000L, transition),
            VideoKeyframePlan.FrameLuma(8_000L, different)
        )
        assertEquals(listOf(0L, 4_000L, 8_000L),
            VideoKeyframePlan.selectSceneTimes(observations))
        assertEquals(listOf(0L, 8_000L),
            VideoKeyframePlan.selectSceneTimes(observations, threshold = 0.5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidHistogramFailsClosed() {
        VideoKeyframePlan.sceneDistance(emptyList(), histogram(100, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unorderedSceneEvidenceFailsClosed() {
        VideoKeyframePlan.selectSceneTimes(listOf(
            VideoKeyframePlan.FrameLuma(2_000L, histogram(100, 0)),
            VideoKeyframePlan.FrameLuma(0L, histogram(0, 100))
        ))
    }
}
