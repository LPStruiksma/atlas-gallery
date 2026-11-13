package nl.atlas.gallery.featuremedia

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * #555 switches and sizes for the grid thumbnail pipeline (Samsung T-series in the
 * private parity research).
 *
 * [enabled] exists so the opt-in fling benchmark can measure the old path and
 * the new path in one emulator run; the app never turns it off.
 */
internal object ThumbnailPipeline {
    @Volatile var enabled: Boolean = true

    /**
     * Whether the app starts [MicroThumbnailWarmer] after each catalogue
     * publication. Off under instrumentation tests, where a background fill
     * would compete with timing-sensitive journeys on a two-core emulator;
     * the fling benchmark runs the warmer itself.
     */
    @Volatile var autoWarm: Boolean = true

    /** Shortest side of a micro thumbnail in pixels (Samsung's small tier, T1/T8). */
    const val MICRO_PIXELS = 128

    /** Micro-thumbnail counters (a few atomic adds per tile; read by the benchmark). */
    val stats = MicroThumbnailStats()

    /** Benchmark-only per-tile visibility probe; null in the app. */
    @Volatile var probe: TileImageProbe? = null

    /** True while a grid is being scrolled; the background warmer yields then. */
    @Volatile var gridScrolling: Boolean = false

    /**
     * True while a grid flings faster than [FAST_ROWS_PER_SECOND]: tiles then
     * show their micro thumbnail only and ask for the sharp tile once the
     * fling slows (Samsung T8: the sharp pass runs when the small queues are
     * empty). Compose state, read by every visible tile.
     */
    val fastScroll = androidx.compose.runtime.mutableStateOf(false)

    /** Fast above this many rows per second; slow again below half of it. */
    const val FAST_ROWS_PER_SECOND = 12f
}

/** True when an instrumentation test runner is loaded in this process. */
internal fun runningUnderInstrumentation(): Boolean = try {
    Class.forName("androidx.test.platform.app.InstrumentationRegistry")
    true
} catch (e: ClassNotFoundException) {
    false
}

/** Hysteresis for [ThumbnailPipeline.fastScroll], so it does not flicker at the edge. */
internal fun nextFastScroll(currentlyFast: Boolean, rowsPerSecond: Float, scrolling: Boolean): Boolean {
    if (!scrolling || !rowsPerSecond.isFinite()) return false
    val speed = kotlin.math.abs(rowsPerSecond)
    return if (currentlyFast) speed >= ThumbnailPipeline.FAST_ROWS_PER_SECOND / 2
        else speed > ThumbnailPipeline.FAST_ROWS_PER_SECOND
}

internal class MicroThumbnailStats {
    val diskHits = AtomicLong()
    val generated = AtomicLong()
    val failures = AtomicLong()
    val cancels = AtomicLong()
    val diskHitNanos = AtomicLong()
    val generateNanos = AtomicLong()
    fun reset() {
        diskHits.set(0); generated.set(0); failures.set(0); cancels.set(0)
        diskHitNanos.set(0); generateNanos.set(0)
    }
    fun json(): org.json.JSONObject = org.json.JSONObject()
        .put("disk_hits", diskHits.get()).put("generated", generated.get())
        .put("failures", failures.get()).put("cancels", cancels.get())
        .put("disk_hit_mean_ms", meanMs(diskHitNanos.get(), diskHits.get()))
        .put("generate_mean_ms", meanMs(generateNanos.get(), generated.get()))

    private fun meanMs(nanos: Long, count: Long): Double =
        if (count == 0L) 0.0 else Math.round(nanos / 1e5 / count) / 10.0
}

/** Last known "tile shows pixels" state per media URI and layer (benchmark only). */
internal class TileImageProbe {
    private val micro = ConcurrentHashMap<String, Boolean>()
    private val sharp = ConcurrentHashMap<String, Boolean>()
    fun record(uri: String, isMicroLayer: Boolean, hasImage: Boolean) {
        (if (isMicroLayer) micro else sharp)[uri] = hasImage
    }
    fun showsImage(uri: String): Boolean = sharp[uri] == true || micro[uri] == true
    fun clear() { micro.clear(); sharp.clear() }
}

/**
 * Whether ATLAS may keep and show its own small copies of photos. Only with
 * full image or video access: under Android 14's "selected photos" scope the
 * user can narrow access at any time without a change callback, and a kept
 * copy would outlive that choice. No access at all, or a selection scope,
 * also means the existing copies are deleted ([MicroThumbnails.applyGrant]).
 */
internal fun microThumbnailsAllowed(images: Boolean, videos: Boolean, selected: Boolean): Boolean {
    val any = images || videos || selected
    val selectionScoped = selected && !(images && videos)
    return any && !selectionScoped
}

private const val MB = 1024L * 1024L

/**
 * Memory budget for decoded thumbnails, by device class (Samsung T1 keeps
 * ~1,000 tiny plus a few hundred medium tiles in memory). Coil's own default
 * is 20% of the app heap class, which on a phone with a 256 MB heap holds
 * only about two screens of 400 px tiles. Grid tiles are GPU-backed hardware
 * bitmaps, so a high-RAM phone can afford RAM/48 (12 GB → 256 MB). Low-RAM
 * devices keep Coil's default.
 */
internal fun thumbnailMemoryCacheBytes(totalRamBytes: Long, heapClassMb: Int, lowRam: Boolean): Long {
    val coilDefault = (heapClassMb.coerceAtLeast(16) * MB * 0.20).toLong()
    if (lowRam || totalRamBytes <= 0L) return coilDefault
    val byRam = (totalRamBytes / 48).coerceAtMost(256 * MB)
    return maxOf(coilDefault, byRam)
}

internal fun thumbnailMemoryCacheBytes(context: Context): Long {
    val am = context.getSystemService(ActivityManager::class.java)
        ?: return thumbnailMemoryCacheBytes(0L, 64, true)
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return thumbnailMemoryCacheBytes(info.totalMem, am.memoryClass, am.isLowRamDevice)
}

/**
 * Disk budget for micro thumbnails: 3% of the data partition, between 64 MB
 * and 768 MB. At ~3-4 KB each, 768 MB holds about 200k photos (Samsung T2:
 * one file per thumbnail, a size budget per tier).
 */
internal fun microThumbnailDiskBudgetBytes(totalStorageBytes: Long): Long {
    if (totalStorageBytes <= 0L) return 64 * MB
    return (totalStorageBytes / 33).coerceIn(64 * MB, 768 * MB)
}
