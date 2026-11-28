package nl.atlas.gallery

import kotlin.math.ceil

/**
 * Numeric-only performance samples. No IDs, paths, URIs, names, file access or
 * network reporting. Samples may be kept per app version through [PerformanceStore]
 * (plain numbers only, so comparisons across versions survive an app restart). A
 * fixed allowlist also prevents arbitrary caller-provided metric labels from
 * storing private data.
 */
class PerformanceAggregates(
    private val capacity: Int = 256,
    private val store: PerformanceStore? = null
) {
    init { require(capacity in 1..4096) }

    /** Numeric-only persistence for one app version. Values are doubles; no labels. */
    interface PerformanceStore {
        fun load(): Map<String, List<Double>>
        fun save(metric: String, values: List<Double>)
    }

    data class Summary(
        val count: Int, val p50: Double?, val p90: Double?, val p95: Double?,
        val max: Double? = null
    )
    private val keys = setOf("cold_start_ms", "warm_resume_ms", "viewer_ms", "grid_frame_ms",
        "grid_frame_over_budget",
        "face_photos_min", "visual_photos_min", "heap_fraction") +
        (1..22).map { "action_a${it}_ms" }
    private val samples = HashMap<String, ArrayDeque<Double>>()
    private val unsaved = HashMap<String, Int>()
    // Nothing is written until restore() has merged the saved history, so an early save
    // cannot replace the previous run's samples before they are read back.
    private var restored = false

    /**
     * Puts samples saved by earlier runs in front of anything recorded since start, keeping
     * the newest `capacity`. Call off the main thread: the store reads SharedPreferences.
     */
    @Synchronized fun restore(saved: Map<String, List<Double>>) {
        saved.forEach { (metric, values) ->
            if (metric !in keys) return@forEach
            val older = values.filter { it.isFinite() && it >= 0.0 }
            val merged = ArrayDeque(older + samples[metric].orEmpty())
            while (merged.size > capacity) merged.removeFirst()
            if (merged.isNotEmpty()) samples[metric] = merged
        }
        restored = true
    }

    @Synchronized fun record(metric: String, value: Double): Boolean {
        if (metric !in keys || !value.isFinite() || value < 0.0) return false
        val ring = samples.getOrPut(metric) { ArrayDeque() }
        ring.addLast(value)
        if (ring.size > capacity) ring.removeFirst()
        val pending = (unsaved[metric] ?: 0) + 1
        if (restored && store != null && pending >= SAVE_EVERY) {
            store?.save(metric, ring.toList())
            unsaved[metric] = 0
        } else {
            unsaved[metric] = pending
        }
        return true
    }

    /** Writes any samples not yet saved, so a normal shutdown loses nothing. */
    @Synchronized fun flush() {
        val store = store ?: return
        if (!restored) return
        samples.forEach { (metric, ring) ->
            if ((unsaved[metric] ?: 0) > 0) {
                store.save(metric, ring.toList())
                unsaved[metric] = 0
            }
        }
    }

    companion object {
        /** Up to this many samples per metric may be lost if the process dies without a flush. */
        const val SAVE_EVERY = 16
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
    var aggregate = PerformanceAggregates()
        private set

    private var store: PerformancePrefsStore? = null

    /**
     * Keeps samples for the installed app version across restarts (#PERF-KEEP). Creates the
     * store only; no SharedPreferences read happens here, so it is safe on the main thread.
     */
    fun install(context: android.content.Context) {
        @Suppress("DEPRECATION")
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrNull() ?: return
        val installed = PerformancePrefsStore(context, version)
        store = installed
        aggregate = PerformanceAggregates(store = installed)
    }

    /**
     * Reads the saved samples and prunes old versions (keeps the newest ten). Call from a
     * background thread: reading SharedPreferences on the main thread trips the disk-read policy in debug builds.
     */
    fun restoreSaved() {
        val installed = store ?: return
        installed.pruneOldVersions(keepVersions = 10)
        aggregate.restore(installed.load())
    }

    fun flush() = aggregate.flush()
    private val viewer = ViewerTimingProbe()
    private var lastForwardedViewer: ViewerTimingProbe.Sample? = null
    private var peakHeapFraction = 0.0
    private var firstGridReported = false
    // Only the first preview after opening a viewer counts as A3; later
    // filmstrip selections are A4. No selected index or URI is retained here.
    private var pendingViewerOpenPreview = false

    /**
     * [usage] false for per-frame callers, which feed [UsageRecorder.scrollFrame]
     * themselves so one scroll counts once (USAGE-0, #173).
     */
    fun recordActionMs(action: Int, elapsedMs: Double, usage: Boolean = true): Boolean {
        if (action !in 1..11) return false
        val accepted = aggregate.record("action_a${action}_ms", elapsedMs)
        if (accepted && usage) UsageRecorder.measured(action, elapsedMs)
        return accepted
    }

    fun recordActionElapsedNs(action: Int, beganNs: Long): Boolean {
        if (action !in 1..11 || beganNs <= 0L) return false
        val elapsed = android.os.SystemClock.elapsedRealtimeNanos() - beganNs
        if (elapsed <= 0L) return false
        return recordActionMs(action, elapsed / 1_000_000.0)
    }

    /** Only while a grid is actively scrolling; consecutive Compose/Choreographer frames. */
    fun recordGridFrame(frameMs: Double, budgetMs: Double, usageA2: Boolean = true) {
        if (!frameMs.isFinite() || frameMs <= 0.0 ||
            !budgetMs.isFinite() || budgetMs <= 0.0) return
        if (aggregate.record("grid_frame_ms", frameMs)) {
            aggregate.record("grid_frame_over_budget", if (frameMs > budgetMs) 1.0 else 0.0)
            recordActionMs(2, frameMs, usage = false)
            if (usageA2) UsageRecorder.scrollFrame(2, frameMs, frameMs > budgetMs)
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
        if (accepted) {
            recordActionMs(5, elapsedMs.toDouble())
            UsageRecorder.scanner(completed, elapsedMs)
        }
        return accepted
    }

    fun viewerProbe(): ViewerTimingProbe = viewer

    /** Renderer-completed cached Photos frame, once per process (A1). */
    @Synchronized fun firstGridFrame(elapsedMs: Double): Boolean {
        if (firstGridReported || elapsedMs <= 0.0) return false
        if (!aggregate.record("cold_start_ms", elapsedMs)) return false
        recordActionMs(1, elapsedMs)
        firstGridReported = true
        return true
    }

    /** Separate sample: a same-Activity stopped -> started Photos return. */
    fun warmGridFrame(elapsedMs: Double): Boolean =
        elapsedMs > 0.0 && aggregate.record("warm_resume_ms", elapsedMs)

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
    }
}
