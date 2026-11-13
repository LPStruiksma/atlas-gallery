package nl.atlas.gallery.featuremedia

import android.content.ContentResolver
import android.content.Intent
import android.os.BatteryManager
import android.content.Context
import android.content.IntentFilter
import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.MediaRecord
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * #555: fills the micro-thumbnail store in the background, newest photos
 * first, so scrolling and scrubbing later only read small files (Samsung
 * writes its thumbnail cache when idle, T9).
 *
 * - One background-priority thread; it waits while a grid scrolls, so it
 *   never takes CPU from a fling.
 * - Already stored photos cost one file-exists check.
 * - At the disk budget, a missing newer photo makes room by deleting the
 *   stored copies of the oldest-taken photos, only as many as it needs
 *   ([fillMicroStore]); a full store with nothing new costs no deletes.
 * - Stops as soon as full access ends ([MicroThumbnails.active]).
 * - Not started automatically under instrumentation tests
 *   ([ThumbnailPipeline.autoWarm]); the fling benchmark calls [warm] itself.
 */
internal object MicroThumbnailWarmer {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "atlas-micro-warm")
    }.asCoroutineDispatcher()

    data class Report(val checked: Int, val generated: Int, val failed: Int, val millis: Long) {
        fun json(): org.json.JSONObject = org.json.JSONObject().put("checked", checked)
            .put("generated", generated).put("failed", failed).put("ms", millis)
            .put("ms_per_generated", if (generated == 0) 0.0 else Math.round(millis * 10.0 / generated) / 10.0)
    }

    suspend fun warm(
        resolver: ContentResolver,
        items: List<MediaRecord>,
        yieldWhile: () -> Boolean = { ThumbnailPipeline.gridScrolling },
        limit: Int = Int.MAX_VALUE,
        gate: () -> Boolean = { true }
    ): Report = withContext(dispatcher) {
        val store = MicroThumbnails.store
        if (store == null || !MicroThumbnails.active) return@withContext Report(0, 0, 0, 0)
        fillMicroStore(store, items, { MicroThumbnails.active }, yieldWhile, limit = limit, gate = gate) { media, key ->
            val made = makeAndStore(resolver, store, MicroThumbnailData(media, key))
            made?.recycle()
            made != null
        }
    }
}

/**
 * The warmer's loop without Android: stores a micro thumbnail for every item,
 * newest-taken first, with [make] writing one (true when it did).
 *
 * At the budget, a missing item evicts the stored copies of the
 * oldest-taken items, from the end of the list towards it, until the store
 * has room; an item never evicts a newer one. Eviction follows capture age,
 * not file times: the files are written newest-first, so the newest photos
 * carry the oldest write times. Only if every older copy is gone and the
 * store is still full does it delete, once, the files that belong to no
 * current item (deleted photos, old revisions; [MicroThumbnailStore.retainOnly]).
 */
internal suspend fun fillMicroStore(
    store: MicroThumbnailStore,
    items: List<MediaRecord>,
    active: () -> Boolean,
    yieldWhile: () -> Boolean,
    limit: Int = Int.MAX_VALUE,
    gate: () -> Boolean = { true },
    make: (MediaRecord, String) -> Boolean
): MicroThumbnailWarmer.Report {
    val started = System.currentTimeMillis()
    val ordered = items.sortedByDescending { it.takenAtMillis }
    if (store.bytesUsed < 0L) store.scan()
    var checked = 0
    var generated = 0
    var failed = 0
    var tail = ordered.size // items at index >= tail had their copies evicted
    var swept = false
    for ((index, media) in ordered.withIndex()) {
        coroutineContext.ensureActive()
        if (!active() || !gate() || index >= tail || checked >= limit) break
        while (yieldWhile()) {
            coroutineContext.ensureActive()
            if (!active() || !gate()) return MicroThumbnailWarmer.Report(checked, generated, failed,
                System.currentTimeMillis() - started)
            delay(MICRO_WARM_YIELD_MS)
        }
        if (!active() || !gate()) break
        checked++
        val key = microThumbnailDiskKey(media) ?: continue
        if (store.contains(key)) continue
        if (!active() || !gate()) break
        while (store.full() && tail > index + 1) {
            microThumbnailDiskKey(ordered[--tail])?.let(store::remove)
        }
        if (store.full() && !swept) {
            swept = true
            store.retainOnly(ordered.mapNotNull(::microThumbnailDiskKey))
            tail = ordered.size // older items may fit again in the space freed
        }
        if (store.full()) break
        if (!active() || !gate()) break
        if (make(media, key)) generated++ else failed++
    }
    return MicroThumbnailWarmer.Report(checked, generated, failed, System.currentTimeMillis() - started)
}

private const val MICRO_WARM_YIELD_MS = 120L

/** Conservative charging check: an unknown battery state is treated as battery power. */
internal fun microWarmCharging(context: Context): Boolean {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return false
    return when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
        else -> false
    }
}

/**
 * Plan and execute the same path used by the UI, without blocking its dispatcher
 * with hash/file-existence checks for a mostly-cached 150k+ item catalogue.
 * A post-scan grant check and coroutine cancellation protect superseded epochs.
 */
internal suspend fun <T> microWarmForCatalog(
    items: List<T>,
    charging: Boolean,
    isMissing: (T) -> Boolean,
    authorized: () -> Boolean,
    warm: suspend (Int) -> Unit,
    deferRemainder: () -> Unit,
    cancelDeferred: () -> Unit
) {
    val plan = if (charging) microWarmPlan(0, charging = true) else
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            var missing = 0
            for (item in items) {
                coroutineContext.ensureActive()
                if (isMissing(item) && ++missing > MICRO_WARM_LARGE_MISSING) break
            }
            microWarmPlan(missing, charging = false)
        }
    coroutineContext.ensureActive()
    if (!authorized()) return
    warm(plan.immediateLimit)
    coroutineContext.ensureActive()
    if (!authorized()) return
    if (plan.deferRemainder) deferRemainder() else cancelDeferred()
}

/** A large first fill warms just the newest items on battery, then defers the rest. */
internal data class MicroWarmPlan(val immediateLimit: Int, val deferRemainder: Boolean)

/**
 * The immediate limit is a count of catalogue entries visited, not a count
 * of cache misses. Small top-ups and charging must visit the whole catalogue.
 */
internal fun microWarmPlan(missing: Int, charging: Boolean): MicroWarmPlan =
    if (!charging && missing > MICRO_WARM_LARGE_MISSING) {
        MicroWarmPlan(MICRO_WARM_BATTERY_LIMIT, true)
    } else {
        MicroWarmPlan(Int.MAX_VALUE, false)
    }

internal const val MICRO_WARM_LARGE_MISSING = 3_000
internal const val MICRO_WARM_BATTERY_LIMIT = 512
