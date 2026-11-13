package nl.atlas.gallery.featuremedia

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import nl.atlas.gallery.LocalGalleryPublicationEpoch
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.mediaPreviewRequest
import nl.atlas.gallery.presentationCacheKeyOrNull

/**
 * #555: warms tiles about to scroll on screen, so they draw a photo on their
 * first frame instead of a grey cell.
 *
 * - Window from [ThumbnailPrefetchPlan]: ahead of the scroll, longer when
 *   flinging, nearest first (Samsung T4).
 * - Every slot in the window gets its micro thumbnail (a small file read);
 *   only the nearest rows, and only when not scrubbing, also get the sharp
 *   tile (T8 small first, sharp later).
 * - At most [MAX_IN_FLIGHT] requests at a time; a slot that leaves the
 *   window is cancelled at once (T7).
 * - Requests are the same ones the tile makes ([MicroThumbnails.request],
 *   [mediaPreviewRequest]), so a warmed entry is a real memory-cache hit.
 * - Publishes [ThumbnailPipeline.gridScrolling] so the background warmer
 *   steps aside while the user scrolls.
 *
 * The grid passes [mediaAtSlot] to map a lazy slot to its photo (null for a
 * header); no layout is read or changed here.
 */
@Composable
internal fun GridThumbnailPrefetch(
    gridState: LazyGridState,
    columns: Int,
    pixels: Int,
    layoutKey: Any?,
    scrubbing: Boolean = false,
    mediaAtSlot: (Int) -> MediaRecord?
) {
    DisposableEffect(gridState) {
        onDispose {
            ThumbnailPipeline.gridScrolling = false
            ThumbnailPipeline.fastScroll.value = false
        }
    }
    if (!ThumbnailPipeline.enabled) return
    val context = LocalContext.current
    val epoch = LocalGalleryPublicationEpoch.current
    val mediaAt by rememberUpdatedState(mediaAtSlot)
    val scrubbingNow by rememberUpdatedState(scrubbing)
    LaunchedEffect(gridState, columns, pixels, layoutKey, epoch) {
        if (columns <= 0 || epoch < 0L) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val tracker = ScrollVelocityTracker()
        val jobs = HashMap<Long, Job>()
        coroutineScope {
            var wanted: List<Long> = emptyList()
            var lastSignature: Signature? = null

            /** The work to warm one slot, or null if it is already in memory. */
            fun workFor(slot: Int, sharp: Boolean): (suspend () -> Unit)? {
                val media = mediaAt(slot) ?: return null
                return if (sharp) {
                    val key = media.presentationCacheKeyOrNull(pixels, epoch) ?: return null
                    if (loader.memoryCache?.get(MemoryCache.Key(key)) != null) return null
                    val request = mediaPreviewRequest(context, media, pixels, epoch);
                    { loader.execute(request) }
                } else {
                    // Micro thumbnails load off the main thread into their own cache.
                    val key = microThumbnailMemoryKey(media, epoch) ?: return null
                    if (MicroThumbnails.cached(key) != null) return null
                    { MicroThumbnails.load(media, epoch) }
                }
            }

            fun pump() {
                for (packed in wanted) {
                    val sharp = sharpOf(packed)
                    if (jobs.size >= MAX_IN_FLIGHT) return
                    if (jobs.containsKey(packed)) continue
                    val work = workFor(slotOf(packed), sharp) ?: continue
                    jobs[packed] = launch {
                        try { work() } finally {
                            jobs.remove(packed)
                            pump()
                        }
                    }
                }
            }

            snapshotFlow {
                val info = gridState.layoutInfo
                val visible = info.visibleItemsInfo
                if (visible.isEmpty()) null else {
                    val firstCell = visible.first()
                    val rowPx = (firstCell.size.height + info.mainAxisItemSpacing).coerceAtLeast(1)
                    val position = gridState.firstVisibleItemIndex +
                        columns * gridState.firstVisibleItemScrollOffset.toFloat() / rowPx
                    Window(firstCell.index, visible.last().index, info.totalItemsCount,
                        position, gridState.isScrollInProgress)
                }
            }.collect { window ->
                if (window == null) return@collect
                ThumbnailPipeline.gridScrolling = window.scrolling
                tracker.sample(window.position, System.nanoTime())
                val direction = if (window.scrolling) tracker.direction else 0
                val rowsPerSecond = if (window.scrolling) tracker.slotsPerSecond / columns else 0f
                val fast = nextFastScroll(ThumbnailPipeline.fastScroll.value, rowsPerSecond, window.scrolling)
                if (fast != ThumbnailPipeline.fastScroll.value) ThumbnailPipeline.fastScroll.value = fast
                val scrub = scrubbingNow || fast
                val signature = Signature(window.first, window.last, window.count, direction,
                    ThumbnailPrefetchPlan.rowsAhead(rowsPerSecond), scrub)
                if (signature == lastSignature) return@collect
                lastSignature = signature
                // A fast-scroll handle drag jumps far each frame, so the rows
                // next to the current screen are never shown: warming them
                // would only take the read threads from the visible tiles.
                val slots = if (scrubbingNow) IntArray(0) else ThumbnailPrefetchPlan.slots(
                    window.first, window.last, window.count, columns, direction, rowsPerSecond)
                val sharpLimit = if (scrub) 0 else ThumbnailPrefetchPlan.IDLE_ROWS_AHEAD * columns
                val micro = MicroThumbnails.active
                val next = ArrayList<Long>(slots.size + sharpLimit)
                // Micro first for the whole window (cheap), then sharp for the nearest rows.
                if (micro) slots.forEach { next.add(pack(it, sharp = false)) }
                for (rank in 0 until minOf(sharpLimit, slots.size)) next.add(pack(slots[rank], sharp = true))
                wanted = next
                val keep = next.toHashSet()
                jobs.keys.filter { it !in keep }.forEach { jobs.remove(it)?.cancel() }
                pump()
            }
        }
    }
}

private data class Window(
    val first: Int, val last: Int, val count: Int,
    val position: Float, val scrolling: Boolean
)

private data class Signature(
    val first: Int, val last: Int, val count: Int, val direction: Int,
    val rowsAhead: Int, val scrubbing: Boolean
)

// Micro loads cost no main-thread time (only their completion hops back).
private const val MAX_IN_FLIGHT = 12

private fun pack(slot: Int, sharp: Boolean): Long = (slot.toLong() shl 1) or (if (sharp) 1L else 0L)
private fun slotOf(packed: Long): Int = (packed ushr 1).toInt()
private fun sharpOf(packed: Long): Boolean = (packed and 1L) == 1L
