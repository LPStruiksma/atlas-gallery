package nl.atlas.gallery

/**
 * Opt-in, memory-only viewer timing probe.
 *
 * The clock is injectable for deterministic JVM tests. Indices are transient
 * positions in an already-local list, never media IDs, filenames, URIs, paths,
 * people, image bytes, network requests or Logcat data.
 *
 * Timings measure "filmstrip selected centered index" -> "pager selected" ->
 * "Coil delivered initial preview". They do NOT measure raw finger-down
 * latency, display-vsync completion, smoothness, or full-resolution decode.
 * Full device frame timing belongs in Perfetto/Macrobenchmark.
 */
class ViewerTimingProbe(
    private val nowNanos: () -> Long = System::nanoTime,
    private val maxSamples: Int = 256
) {
    init { require(maxSamples in 1..4096) { "Probe sample budget must be bounded" } }

    data class Sample(
        val selectedIndex: Int,
        val pagerSelectedMs: Double,
        val firstPreviewMs: Double
    )

    private data class Pending(
        val index: Int,
        val requestAt: Long,
        var pagerAt: Long? = null,
        var previewAt: Long? = null
    )

    private val samples = ArrayDeque<Sample>()
    private var current: Pending? = null
    var superseded: Int = 0
        private set

    @Synchronized fun selectionRequested(index: Int) {
        if (index < 0) return
        if (current != null) superseded++
        current = Pending(index, nowNanos())
    }

    @Synchronized fun pagerSelected(index: Int) {
        current?.takeIf { it.index == index }?.let {
            if (it.pagerAt == null) it.pagerAt = nowNanos()
            completeIfReady(it)
        }
    }

    @Synchronized fun firstPreviewReady(index: Int) {
        current?.takeIf { it.index == index }?.let {
            if (it.previewAt == null) it.previewAt = nowNanos()
            completeIfReady(it)
        }
    }

    private fun completeIfReady(p: Pending) {
        val pager = p.pagerAt ?: return
        val preview = p.previewAt ?: return
        if (current !== p) return
        val pagerMs = (pager - p.requestAt).coerceAtLeast(0) / 1_000_000.0
        val previewMs = (preview - p.requestAt).coerceAtLeast(0) / 1_000_000.0
        samples.addLast(Sample(p.index, pagerMs, previewMs))
        if (samples.size > maxSamples) samples.removeFirst()
        current = null
    }

    @Synchronized fun snapshot(): List<Sample> = samples.toList()

    /** Can be written to a test report only, never printed on live gallery use. */
    @Synchronized fun aggregate(): Map<String, Double> {
        fun percentile(sorted: List<Double>, fraction: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val index = kotlin.math.ceil(sorted.size * fraction).toInt().coerceIn(1, sorted.size)
            return sorted[index - 1]
        }
        val pager = samples.map { it.pagerSelectedMs }.sorted()
        val preview = samples.map { it.firstPreviewMs }.sorted()
        return mapOf(
            "samples" to samples.size.toDouble(),
            "superseded" to superseded.toDouble(),
            "page_p50_ms" to percentile(pager, .50),
            "page_p95_ms" to percentile(pager, .95),
            "preview_p50_ms" to percentile(preview, .50),
            "preview_p95_ms" to percentile(preview, .95)
        )
    }
}
