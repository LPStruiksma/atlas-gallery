package nl.atlas.gallery.featuremedia

import android.content.ContentResolver
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
        yieldWhile: () -> Boolean = { ThumbnailPipeline.gridScrolling }
    ): Report = withContext(dispatcher) {
        val store = MicroThumbnails.store
        if (store == null || !MicroThumbnails.active) return@withContext Report(0, 0, 0, 0)
        fillMicroStore(store, items, { MicroThumbnails.active }, yieldWhile) { media, key ->
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
        if (!active() || index >= tail) break
        while (yieldWhile()) delay(MICRO_WARM_YIELD_MS)
        checked++
        val key = microThumbnailDiskKey(media) ?: continue
        if (store.contains(key)) continue
        while (store.full() && tail > index + 1) {
            microThumbnailDiskKey(ordered[--tail])?.let(store::remove)
        }
        if (store.full() && !swept) {
            swept = true
            store.retainOnly(ordered.mapNotNull(::microThumbnailDiskKey))
            tail = ordered.size // older items may fit again in the space freed
        }
        if (store.full()) break
        if (make(media, key)) generated++ else failed++
    }
    return MicroThumbnailWarmer.Report(checked, generated, failed, System.currentTimeMillis() - started)
}

private const val MICRO_WARM_YIELD_MS = 120L
