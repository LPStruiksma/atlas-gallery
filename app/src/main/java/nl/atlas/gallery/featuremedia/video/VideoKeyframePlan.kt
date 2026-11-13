package nl.atlas.gallery.featuremedia.video

import kotlin.math.abs

/**
 * Deterministic, Android-free candidate and scene selection. An indexer can
 * evaluate these timestamps on any authorized video without storing pixels.
 */
object VideoKeyframePlan {
    const val MIN_SAMPLE_SPACING_MS = 2_000L
    const val MAX_FRAMES_PER_VIDEO = 60
    const val MAX_LONG_EDGE_PX = 512
    const val LUMA_BINS = 16
    const val SCENE_CHANGE_THRESHOLD = 0.18

    /**
     * Never sample more than 60 frames or more frequently than every 2 seconds.
     * The stride increases for long videos to cover the entire duration.
     * No floating-point rounding or Long overflow is involved.
     */
    fun candidateTimes(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return emptyList()
        val capStride = durationMs / MAX_FRAMES_PER_VIDEO +
            if (durationMs % MAX_FRAMES_PER_VIDEO != 0L) 1L else 0L
        val stride = maxOf(MIN_SAMPLE_SPACING_MS, capStride)
        val result = ArrayList<Long>(MAX_FRAMES_PER_VIDEO)
        var time = 0L
        while (time < durationMs && result.size < MAX_FRAMES_PER_VIDEO) {
            result.add(time)
            if (time > Long.MAX_VALUE - stride) break
            time += stride
        }
        return result
    }

    /** A sampled luma histogram, not the original frame or any identity data. */
    data class FrameLuma(val frameMs: Long, val bins: List<Int>)

    /** Normalized histogram L1 / 2; 0 means identical and 1 means disjoint. */
    fun sceneDistance(previous: List<Int>, next: List<Int>): Double {
        require(previous.size == LUMA_BINS && next.size == LUMA_BINS)
        require(previous.all { it >= 0 } && next.all { it >= 0 })
        val pTotal = previous.sumOf { it.toLong() }
        val nTotal = next.sumOf { it.toLong() }
        require(pTotal > 0 && nTotal > 0) { "Empty luma histogram" }
        var distance = 0.0
        for (index in 0 until LUMA_BINS) {
            distance += abs(previous[index].toDouble() / pTotal -
                next[index].toDouble() / nTotal)
        }
        return distance / 2.0
    }

    fun isSceneChange(
        lastKept: List<Int>?,
        candidate: List<Int>,
        threshold: Double = SCENE_CHANGE_THRESHOLD
    ): Boolean {
        require(threshold in 0.0..1.0)
        return lastKept == null || sceneDistance(lastKept, candidate) >= threshold
    }

    /**
     * Pure decision for a given ordered set of measured candidate histograms.
     * Compare to the last *kept* scene, not the preceding rejected frame, so
     * a gradual transition eventually qualifies.
     */
    fun selectSceneTimes(
        frames: List<FrameLuma>,
        threshold: Double = SCENE_CHANGE_THRESHOLD
    ): List<Long> {
        require(frames.size <= MAX_FRAMES_PER_VIDEO)
        require(frames.zipWithNext().all { (a, b) -> a.frameMs < b.frameMs })
        require(frames.all { it.frameMs >= 0L })
        val selected = ArrayList<Long>(frames.size)
        var lastKept: List<Int>? = null
        for (frame in frames) {
            if (isSceneChange(lastKept, frame.bins, threshold)) {
                selected.add(frame.frameMs)
                lastKept = frame.bins
            }
        }
        return selected
    }
}
