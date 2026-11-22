package nl.atlas.gallery

import kotlin.math.ceil

/**
 * Numeric-only, process-local performance samples. No IDs, paths, URIs, names,
 * file access, persistent storage or network reporting. A fixed allowlist also
 * prevents arbitrary caller-provided metric labels from storing private data.
 */
class PerformanceAggregates(private val capacity: Int = 256) {
    init { require(capacity in 1..4096) }

    data class Summary(
        val count: Int, val p50: Double?, val p90: Double?, val p95: Double?,
        val max: Double? = null
    )
    private val keys = setOf("cold_start_ms", "viewer_ms", "grid_frame_ms",
        "grid_frame_over_budget",
        "face_photos_min", "visual_photos_min", "heap_fraction",
        "native_heap_mib", "process_pss_mib") +
        (1..22).map { "action_a${it}_ms" }
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
        if (sorted.isEmpty()) return Summary(0, null, null, null)
        fun percentile(fraction: Double) =
            sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]
        return Summary(sorted.size, percentile(0.50), percentile(0.90), percentile(0.95), sorted.last())
    }

    /** Proportion of sampled frames exceeding the actual display frame budget. */
    @Synchronized fun positiveFraction(metric: String): Double? {
        if (metric != "grid_frame_over_budget") return null
        val ring = samples[metric] ?: return null
        if (ring.isEmpty()) return null
        return ring.count { it > 0.0 }.toDouble() / ring.size
    }

    @Synchronized fun metricNames(): List<String> = samples.keys.sorted()
}

/**
 * Cold-start origin that does not depend on when [PerformanceSession] is first
 * touched. The default origin is the OS-recorded process start
 * (Process.getStartElapsedRealtime, API 24+), read at measurement time, so a
 * lazily initialised collector cannot move it. Clocks are injectable for JVM tests.
 */
class ColdStartClock(
    private val processStartElapsedMs: () -> Long,
    private val nowElapsedMs: () -> Long
) {
    /** Milliseconds since process start, or null when the clocks disagree. */
    fun elapsedMs(): Double? {
        val start = processStartElapsedMs()
        val now = nowElapsedMs()
        if (start <= 0L || now < start) return null
        return (now - start).toDouble()
    }
}

/** One-process capture only; UI reads a snapshot on demand. */
internal object PerformanceSession {
    val aggregate = PerformanceAggregates()
    private val viewer = ViewerTimingProbe()
    private var lastForwardedViewer: ViewerTimingProbe.Sample? = null
    private var peakHeapFraction = 0.0
    private val coldStart = ColdStartClock(
        processStartElapsedMs = { android.os.Process.getStartElapsedRealtime() },
        nowElapsedMs = { android.os.SystemClock.elapsedRealtime() }
    )
    private var firstGridReported = false
    // Only the first preview after opening a viewer counts as A3; later
    // filmstrip selections are A4. No selected index or URI is retained here.
    private var pendingViewerOpenPreview = false

    fun recordActionMs(action: Int, elapsedMs: Double): Boolean {
        if (action !in 1..11) return false
        return aggregate.record("action_a${action}_ms", elapsedMs)
    }

    fun recordActionElapsedNs(action: Int, beganNs: Long): Boolean {
        if (action !in 1..11 || beganNs <= 0L) return false
        val elapsed = android.os.SystemClock.elapsedRealtimeNanos() - beganNs
        if (elapsed <= 0L) return false
        return recordActionMs(action, elapsed / 1_000_000.0)
    }

    /** Only while a grid is actively scrolling; consecutive Compose/Choreographer frames. */
    fun recordGridFrame(frameMs: Double, budgetMs: Double) {
        if (!frameMs.isFinite() || frameMs <= 0.0 ||
            !budgetMs.isFinite() || budgetMs <= 0.0) return
        if (aggregate.record("grid_frame_ms", frameMs)) {
            aggregate.record("grid_frame_over_budget", if (frameMs > budgetMs) 1.0 else 0.0)
            recordActionMs(2, frameMs)
        }
    }

    @Synchronized fun viewerOpened() {
        updateViewer()
        pendingViewerOpenPreview = true
    }

    /** Records only successful, positive work as aggregate throughput. */
    fun recordThroughput(metric: String, completed: Int, elapsedMs: Long): Boolean {
        if (metric != "face_photos_min" && metric != "visual_photos_min") return false
        if (completed <= 0 || elapsedMs <= 0) return false
        val accepted = aggregate.record(metric, completed * 60_000.0 / elapsedMs)
        if (accepted) recordActionMs(5, elapsedMs.toDouble())
        return accepted
    }

    fun viewerProbe(): ViewerTimingProbe = viewer

    @Synchronized fun firstGridFrame() {
        if (firstGridReported) return
        val elapsed = coldStart.elapsedMs() ?: return
        if (aggregate.record("cold_start_ms", elapsed)) {
            recordActionMs(1, elapsed)
            firstGridReported = true
        }
    }

    @Synchronized fun updateViewer() {
        val samples = viewer.snapshot()
        val last = lastForwardedViewer
        val start = if (last == null) 0 else {
            val index = samples.indexOfLast { it === last }
            if (index < 0) 0 else index + 1
        }
        samples.drop(start).forEach {
            if (aggregate.record("viewer_ms", it.firstPreviewMs)) {
                recordActionMs(if (pendingViewerOpenPreview) 3 else 4, it.firstPreviewMs)
                pendingViewerOpenPreview = false
            }
        }
        lastForwardedViewer = samples.lastOrNull()
    }

    @Synchronized fun sampleHeap() {
        val startNs = android.os.SystemClock.elapsedRealtimeNanos()
        val runtime = Runtime.getRuntime()
        val maximum = runtime.maxMemory()
        if (maximum > 0) {
            val allocated = runtime.totalMemory() - runtime.freeMemory()
            peakHeapFraction = maxOf(peakHeapFraction, allocated.toDouble() / maximum)
            aggregate.record("heap_fraction", peakHeapFraction)
            // A8 is the non-identifying cost of sampling memory; the heap
            // fraction itself remains a separate dimensionless metric.
            recordActionElapsedNs(8, startNs)
        }
        NativeMemoryMetrics.request()
    }
}
