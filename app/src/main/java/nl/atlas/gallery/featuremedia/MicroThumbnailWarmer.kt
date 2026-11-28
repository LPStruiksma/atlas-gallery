package nl.atlas.gallery.featuremedia

import android.content.ContentResolver
import android.content.Intent
import android.os.BatteryManager
import android.content.Context
import android.content.IntentFilter
import android.os.Process
import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.MediaRecord
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * Cancels blocking provider IPC as soon as the parent coroutine is cancelled,
 * not when its blocked worker thread finally completes. The child is suspended
 * without occupying a thread and resumes on Default independently of IO.
 */
internal fun CoroutineScope.watchMicroWarmCancellation(onCancel: () -> Unit): Job =
    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            onCancel()
        }
    }

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

    /**
     * Deferred charging fill. The provider is scanned into a *disk-backed*
     * global order; only one bounded page of MediaRecord is materialized here.
     * The UI's in-memory list path [warm] is deliberately unchanged.
     */
    suspend fun warmPaged(
        resolver: ContentResolver,
        source: MicroWarmMediaSource,
        signal: CancellationSignal,
        gate: () -> Boolean,
        yieldWhile: () -> Boolean = { ThumbnailPipeline.gridScrolling }
    ): Report = withContext(dispatcher) {
        val store = MicroThumbnails.store
        if (store == null || !MicroThumbnails.active || !gate())
            return@withContext Report(0, 0, 0, 0)
        source.openNewest().use { reader ->
            fillMicroStorePaged(
                store, reader, source.totalItems, oldest = { source.openOldest() },
                active = { MicroThumbnails.active }, yieldWhile = yieldWhile,
                gate = gate, sweepOrphans = {
                    source.sweepOrphans(store, gate)
                }
            ) { media, key ->
                var made: android.graphics.Bitmap? = null
                try {
                    made = makeAndStore(resolver, store, MicroThumbnailData(media, key), signal)
                    // A grant can disappear during a long ImageDecoder call.
                    // Do not leave a copy written after revoke/stop.
                    if (!gate()) {
                        store.remove(key)
                        false
                    } else made != null
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    store.remove(key)
                    throw cancelled
                } finally {
                    made?.recycle()
                }
            }
        }
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
    store.awaitBudget()
    if (!active() || !gate()) return MicroThumbnailWarmer.Report(0, 0, 0, 0)
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


/** A bounded producer over one global, stable newest-first or oldest-first order. */
internal interface MicroWarmPageReader : java.io.Closeable {
    /** Returns at most [MICRO_WARM_PAGE_SIZE] rows, or empty when exhausted. */
    fun nextPage(): List<MediaRecord>
}

internal const val MICRO_WARM_PAGE_SIZE = 128

/**
 * Streaming equivalent of [fillMicroStore]. The second reader starts at the
 * global oldest row, not at the end of a single page. A full cache sacrifices
 * only older captured entries; a complete-catalogue orphan sweep, when
 * supplied, is permitted ONCE after those entries are exhausted.
 *
 * Neither reader, the eviction queue, nor this loop holds the complete library
 * in heap. In particular, a page must NEVER be passed to retainOnly().
 */
internal suspend fun fillMicroStorePaged(
    store: MicroThumbnailStore,
    newest: MicroWarmPageReader,
    totalItems: Int,
    oldest: () -> MicroWarmPageReader,
    active: () -> Boolean,
    yieldWhile: () -> Boolean,
    limit: Int = Int.MAX_VALUE,
    gate: () -> Boolean = { true },
    sweepOrphans: (() -> Boolean)? = null,
    make: (MediaRecord, String) -> Boolean
): MicroThumbnailWarmer.Report {
    require(totalItems >= 0)
    val started = System.currentTimeMillis()
    store.awaitBudget()
    if (!active() || !gate()) return MicroThumbnailWarmer.Report(0, 0, 0, 0)
    if (store.bytesUsed < 0L) store.scan()
    var checked = 0
    var generated = 0
    var failed = 0
    var index = 0
    var tail = totalItems
    var swept = false
    var tailReader: MicroWarmPageReader? = null
    var tailPage: List<MediaRecord> = emptyList()
    var tailOffset = 0

    fun report() = MicroThumbnailWarmer.Report(checked, generated, failed,
        System.currentTimeMillis() - started)

    fun nextOldest(): MediaRecord {
        if (tailReader == null) tailReader = oldest()
        if (tailOffset >= tailPage.size) {
            tailPage = tailReader!!.nextPage()
            require(tailPage.isNotEmpty() && tailPage.size <= MICRO_WARM_PAGE_SIZE) {
                "Incomplete or unbounded micro warm tail"
            }
            tailOffset = 0
        }
        return tailPage[tailOffset++]
    }

    try {
        while (index < tail && checked < limit) {
            coroutineContext.ensureActive()
            if (!active() || !gate()) return report()
            val page = newest.nextPage()
            require(page.size <= MICRO_WARM_PAGE_SIZE) { "Unbounded micro warm page" }
            if (page.isEmpty()) break
            for (media in page) {
                coroutineContext.ensureActive()
                if (index >= tail || checked >= limit || !active() || !gate())
                    return report()
                while (yieldWhile()) {
                    coroutineContext.ensureActive()
                    if (!active() || !gate()) return report()
                    delay(MICRO_WARM_YIELD_MS)
                }
                if (!active() || !gate()) return report()
                checked++
                val key = microThumbnailDiskKey(media)
                if (key != null && !store.contains(key)) {
                    if (!active() || !gate()) return report()
                    while (store.full() && tail > index + 1) {
                        coroutineContext.ensureActive()
                        if (!active() || !gate()) return report()
                        microThumbnailDiskKey(nextOldest())?.let(store::remove)
                        tail--
                    }
                    if (store.full() && !swept) {
                        swept = true
                        if (!active() || !gate()) return report()
                        if (sweepOrphans?.invoke() == true) {
                            // As in the list path, fresh space may let older
                            // entries survive. Restart the *global* tail.
                            tailReader?.close()
                            tailReader = null
                            tailPage = emptyList()
                            tailOffset = 0
                            tail = totalItems
                        }
                    }
                    if (store.full()) return report()
                    if (!active() || !gate()) return report()
                    if (make(media, key)) generated++ else failed++
                }
                index++
            }
        }
    } finally {
        tailReader?.close()
    }
    return report()
}
