package nl.atlas.gallery

/**
 * Descriptive timing for an explicitly opt-in synthetic emulator experiment.
 * The first URI acquisition is kept separate from repeated same-URI requests.
 * No inference of OS-cold latency, hardware parity or user scroll jank.
 */
internal data class ThumbnailBenchmarkStats(
    val firstNs: Long,
    val warmCount: Int,
    val warmP50Ns: Long,
    val warmP95Ns: Long
)

/**
 * Nearest-rank percentiles of positive completed operations.
 *
 * A failed decode must throw/abort before a timing is admitted. Do not
 * manufacture zeros, catch failures as successes or interpolate between
 * samples. With twenty warm samples, p95 is the 19th ordered sample.
 */
internal fun summarizeThumbnailBenchmark(samplesNs: List<Long>): ThumbnailBenchmarkStats {
    require(samplesNs.size >= 3) { "Need first acquisition and at least two warm samples" }
    require(samplesNs.all { it > 0L }) { "Missing or invalid successful timing" }
    val warm = samplesNs.drop(1).sorted()
    fun percentile(p: Int): Long = warm[((warm.size * p + 99) / 100 - 1)
        .coerceIn(0, warm.lastIndex)]
    return ThumbnailBenchmarkStats(
        firstNs = samplesNs.first(),
        warmCount = warm.size,
        warmP50Ns = percentile(50),
        warmP95Ns = percentile(95)
    )
}
