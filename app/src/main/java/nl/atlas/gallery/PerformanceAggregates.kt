package nl.atlas.gallery

import kotlin.math.ceil

/**
 * Numeric-only, process-local performance samples. No IDs, paths, URIs, names,
 * file access, persistent storage or network reporting. A fixed allowlist also
 * prevents arbitrary caller-provided metric labels from storing private data.
 */
class PerformanceAggregates(private val capacity: Int = 256) {
    init { require(capacity in 1..4096) }

    data class Summary(val count: Int, val p50: Double?, val p95: Double?)
    private val keys = setOf("cold_start_ms", "viewer_ms", "grid_frame_ms",
        "face_photos_min", "visual_photos_min", "heap_fraction")
    private val samples = HashMap<String, ArrayDeque<Double>>()

    @Synchronized fun record(metric: String, value: Double): Boolean {
        if (metric !in keys || !value.isFinite() || value < 0.0) return false
        val ring = samples.getOrPut(metric) { ArrayDeque() }
        ring.addLast(value)
        if (ring.size > capacity) ring.removeFirst()
        return true
    }

    @Synchronized fun snapshot(metric: String): Summary {
        val sorted = samples[metric]?.sorted().orEmpty()
        if (sorted.isEmpty()) return Summary(0, null, null)
        fun percentile(fraction: Double) =
            sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]
        return Summary(sorted.size, percentile(0.50), percentile(0.95))
    }

    @Synchronized fun metricNames(): List<String> = samples.keys.sorted()
}

/** One-process capture only; UI reads a snapshot on demand. */
internal object PerformanceSession {
    val aggregate = PerformanceAggregates()
    private val viewer = ViewerTimingProbe()
    private var lastForwardedViewer: ViewerTimingProbe.Sample? = null
    private var peakHeapFraction = 0.0
    private val processStartNanos = android.os.SystemClock.elapsedRealtimeNanos()
    private var firstGridReported = false

    fun viewerProbe(): ViewerTimingProbe = viewer

    @Synchronized fun firstGridFrame() {
        if (firstGridReported) return
        firstGridReported = true
        val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - processStartNanos) / 1_000_000.0
        aggregate.record("cold_start_ms", elapsed)
    }

    @Synchronized fun updateViewer() {
        val samples = viewer.snapshot()
        val last = lastForwardedViewer
        val start = if (last == null) 0 else {
            val index = samples.indexOfLast { it === last }
            if (index < 0) 0 else index + 1
        }
        samples.drop(start).forEach { aggregate.record("viewer_ms", it.firstPreviewMs) }
        lastForwardedViewer = samples.lastOrNull()
    }

    fun sampleHeap() {
        val runtime = Runtime.getRuntime()
        val maximum = runtime.maxMemory()
        if (maximum > 0) {
            val allocated = runtime.totalMemory() - runtime.freeMemory()
            peakHeapFraction = maxOf(peakHeapFraction, allocated.toDouble() / maximum)
            aggregate.record("heap_fraction", peakHeapFraction)
        }
    }
}
