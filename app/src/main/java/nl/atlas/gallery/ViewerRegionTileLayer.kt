package nl.atlas.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Number of full-resolution tiles drawn over the base photo right now. */
internal val ViewerPhotoTilesShownKey = SemanticsPropertyKey<Int>("AtlasViewerTilesShown")

/** Number of tiles the current pose wants (0 while the base layer is enough). */
internal val ViewerPhotoTilesWantedKey = SemanticsPropertyKey<Int>("AtlasViewerTilesWanted")

/** True once this photo's region decoders are open (so the zoom limit has risen). */
internal val ViewerPhotoTilesReadyKey = SemanticsPropertyKey<Boolean>("AtlasViewerTilesReady")

/** The live zoom, to one decimal, on the tile layer (tests and TalkBack-free evidence). */
internal val ViewerPhotoTileZoomKey = SemanticsPropertyKey<Float>("AtlasViewerTileZoom")

/**
 * PERF-32 (#334) process-wide plumbing: the bounded decode dispatcher, a
 * scope for closing native decoders off the main thread, and counters that
 * tests read. Counters hold numbers only, never URIs or pixels.
 */
internal object ViewerRegionTiles {
    /** Test hook: open the source (so the zoom limit rises) but decode nothing. */
    @Volatile var baseOnlyForTest = false

    val decodes = AtomicInteger()
    val mainThreadDecodes = AtomicInteger()
    val cancelledDecodes = AtomicInteger()
    val failedDecodes = AtomicInteger()

    private val epochs = AtomicLong()

    /** Each tile layer gets its own epoch, so tiles never outlive their page. */
    fun nextEpoch(): Long = epochs.incrementAndGet()

    val decodeDispatcher = Dispatchers.IO.limitedParallelism(ViewerTileZoom.MAX_DECODERS)

    /** Not tied to any page, so a source opened or closed late is still released. */
    val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val FADE_NANOS = 150_000_000L
}

/**
 * Up to [ViewerTileZoom.MAX_DECODERS] BitmapRegionDecoders over one photo,
 * each with its own file descriptor (a shared descriptor would share its
 * read offset). Thread-safe; [decode] must run off the main thread.
 */
internal class ViewerRegionSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    val orientation: ViewerSourceOrientation,
    first: Pair<ParcelFileDescriptor, BitmapRegionDecoder>
) {
    private val lock = Object()
    private val idle = ArrayDeque<Pair<ParcelFileDescriptor, BitmapRegionDecoder>>()
    private var created = 1
    private var closed = false

    init { idle.addLast(first) }

    /** One upright tile for the RAW rectangle [raw], or null if refused or failed. */
    fun decode(raw: ViewerSourceRect, sampleSize: Int): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Never decode on the UI thread; count it so tests can prove it.
            ViewerRegionTiles.mainThreadDecodes.incrementAndGet()
            return null
        }
        val entry = borrow() ?: return null
        try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            }
            val rawBitmap = entry.second.decodeRegion(
                Rect(raw.left, raw.top, raw.right, raw.bottom), options) ?: return null
            ViewerRegionTiles.decodes.incrementAndGet()
            if (orientation.clockwiseDegrees == 0 && !orientation.mirrorHorizontally) {
                rawBitmap.prepareToDraw()
                return rawBitmap
            }
            // Same transform as viewerTilePixelTransform: rotate, then mirror.
            val transform = Matrix().apply {
                postRotate(orientation.clockwiseDegrees.toFloat())
                if (orientation.mirrorHorizontally) postScale(-1f, 1f)
            }
            val upright = Bitmap.createBitmap(rawBitmap, 0, 0,
                rawBitmap.width, rawBitmap.height, transform, true)
            // The raw copy was never published, so it is safe to free now.
            if (upright !== rawBitmap) rawBitmap.recycle()
            upright.prepareToDraw()
            return upright
        } catch (_: RuntimeException) {
            ViewerRegionTiles.failedDecodes.incrementAndGet()
            return null
        } catch (_: OutOfMemoryError) {
            ViewerRegionTiles.failedDecodes.incrementAndGet()
            return null
        } finally {
            giveBack(entry)
        }
    }

    private fun borrow(): Pair<ParcelFileDescriptor, BitmapRegionDecoder>? {
        synchronized(lock) {
            while (!closed) {
                idle.removeFirstOrNull()?.let { return it }
                if (created < ViewerTileZoom.MAX_DECODERS) {
                    val extra = openDecoder(resolver, uri) ?: return null
                    created++
                    return extra
                }
                lock.wait()
            }
            return null
        }
    }

    private fun giveBack(entry: Pair<ParcelFileDescriptor, BitmapRegionDecoder>) {
        synchronized(lock) {
            if (!closed) {
                idle.addLast(entry)
                lock.notifyAll()
                return
            }
        }
        release(entry)
    }

    /** Releases idle decoders now and busy ones as soon as their decode returns. */
    fun close() {
        val toRelease = synchronized(lock) {
            closed = true
            lock.notifyAll()
            idle.toList().also { idle.clear() }
        }
        toRelease.forEach(::release)
    }

    companion object {
        /**
         * Opens the photo for region decoding, or null when the format has no
         * region decoder (GIF, some DNG), the file is unreadable, or the
         * oriented size disagrees with the base decode. Blocking I/O.
         */
        fun open(
            resolver: ContentResolver, uri: Uri, mediaStoreDegrees: Int,
            baseWidth: Int, baseHeight: Int
        ): ViewerRegionSource? {
            val first = openDecoder(resolver, uri) ?: return null
            val exif = try {
                resolver.openFileDescriptor(uri, "r")?.use {
                    ExifInterface(it.fileDescriptor).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                }
            } catch (_: Exception) {
                null
            }
            val tag = if (exif != null && exif in 1..8) exif else viewerExifForDegrees(mediaStoreDegrees)
            val orientation = viewerOrientationFromExif(first.second.width, first.second.height, tag)
            if (orientation == null || !viewerTileSourceMatchesBase(orientation, baseWidth, baseHeight)) {
                release(first)
                return null
            }
            return ViewerRegionSource(resolver, uri, orientation, first)
        }

        @Suppress("DEPRECATION")
        private fun openDecoder(
            resolver: ContentResolver, uri: Uri
        ): Pair<ParcelFileDescriptor, BitmapRegionDecoder>? {
            val descriptor = try {
                resolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) {
                null
            } ?: return null
            return try {
                val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(descriptor)
                    else BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
                if (decoder == null) {
                    descriptor.close()
                    null
                } else descriptor to decoder
            } catch (_: Exception) {
                runCatching { descriptor.close() }
                null
            }
        }

        private fun release(entry: Pair<ParcelFileDescriptor, BitmapRegionDecoder>) {
            runCatching { entry.second.recycle() }
            runCatching { entry.first.close() }
        }
    }
}

/**
 * Full-resolution tiles over the base photo, drawn inside the photo's zoom
 * layer so they follow pinch and pan exactly like the base image.
 *
 * - Opens the region decoders only once [active] (the photo is focused,
 *   settled, its base decode landed and the user has zoomed).
 * - Plans per pose change with the previous plan reused, cancels decodes
 *   that left the view, and runs at most two decodes at a time, off main.
 * - Publishes only through the bounded [ViewerTileResidentCache], whose
 *   epoch is this layer's own, so nothing survives a page change.
 * - Tiles fade in over the base, which stays underneath; no seams, no holes.
 *
 * Evicted tiles are dropped, not recycled: a recorded frame may still hold
 * the Bitmap, and the GC frees its native pixels once nothing does.
 */
@Composable
internal fun ViewerRegionTileLayer(
    uri: String,
    contentRevision: Long,
    mediaStoreDegrees: Int,
    active: Boolean,
    pose: () -> ViewerZoomPosition,
    viewport: IntSize,
    baseSize: IntSize,
    onTiledSize: (IntSize?) -> Unit
) {
    val context = LocalContext.current
    val latestTiledSize by rememberUpdatedState(onTiledSize)
    val activeNow by rememberUpdatedState(active)
    val viewportNow by rememberUpdatedState(viewport)
    val baseNow by rememberUpdatedState(baseSize)
    val epoch = remember(uri, contentRevision) { ViewerRegionTiles.nextEpoch() }
    val tileSource = remember(epoch) {
        ViewerTileSource(uri, epoch, contentRevision.coerceAtLeast(0L))
    }
    val cacheBytes = remember { viewerTileCacheBytes(Runtime.getRuntime().maxMemory()) }
    val cache = remember(epoch) { ViewerTileResidentCache<Bitmap>(cacheBytes) { } }
    val shown = remember(epoch) { mutableStateMapOf<ViewerTileKey, Long>() }
    val jobs = remember(epoch) { HashMap<ViewerTileKey, Job>() }
    var region by remember(epoch) { mutableStateOf<ViewerRegionSource?>(null) }
    var openRefused by remember(epoch) { mutableStateOf(false) }
    var fadeClock by remember(epoch) { mutableLongStateOf(0L) }
    var wanted by remember(epoch) { mutableIntStateOf(0) }
    var zoomTenths by remember(epoch) { mutableFloatStateOf(1f) }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val drawRect = remember { RectF() }

    LaunchedEffect(epoch, active, baseSize) {
        if (!active || region != null || openRefused || baseSize == IntSize.Zero) return@LaunchedEffect
        val opening = ViewerRegionTiles.lifecycleScope.async {
            ViewerRegionSource.open(context.contentResolver, Uri.parse(uri), mediaStoreDegrees,
                baseSize.width, baseSize.height)
        }
        val opened = try {
            opening.await()
        } catch (e: CancellationException) {
            // The page went away while opening: release whatever it opens.
            ViewerRegionTiles.lifecycleScope.launch { opening.await()?.close() }
            throw e
        }
        if (opened == null) {
            openRefused = true
            latestTiledSize(null)
        } else region = opened
    }

    DisposableEffect(epoch) {
        onDispose {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            cache.close()
            shown.clear()
            region?.let { open -> ViewerRegionTiles.lifecycleScope.launch { open.close() } }
            region = null
            latestTiledSize(null)
        }
    }

    LaunchedEffect(region) {
        val source = region ?: return@LaunchedEffect
        val effectScope = this
        val orientation = source.orientation
        latestTiledSize(IntSize(orientation.uprightWidth, orientation.uprightHeight))
        val budget = viewerTilePlanBudgetBytes(cacheBytes)
        var previous: ViewerOrientedTilePlan? = null
        snapshotFlow { Triple(pose(), viewportNow, baseNow) to activeNow }.collect { (state, isActive) ->
            val (p, vp, base) = state
            zoomTenths = (p.scale * 10f).roundToInt() / 10f
            val plan = if (isActive && !ViewerRegionTiles.baseOnlyForTest &&
                viewerTilesWanted(p.scale, vp.width, vp.height, base.width, base.height,
                    orientation.uprightWidth, orientation.uprightHeight))
                viewerTilePlanForPose(orientation, vp.width, vp.height, p, budget, previous)
            else null
            previous = plan
            val batch = plan?.let { viewerTileRequests(tileSource, orientation, it, cache.residentKeys()) }
            val activeKeys = batch?.activeVisibleKeys ?: emptySet()
            wanted = activeKeys.size
            cache.show(tileSource, activeKeys)
            shown.keys.filter { it !in activeKeys }.forEach { shown.remove(it) }
            val work = viewerTileWork(batch?.missing.orEmpty().map { it.key }, activeKeys, jobs.keys)
            for (key in work.cancel) {
                jobs.remove(key)?.cancel()
                ViewerRegionTiles.cancelledDecodes.incrementAndGet()
            }
            for (key in work.start) {
                jobs[key] = launch {
                    try {
                        val bitmap = withContext(ViewerRegionTiles.decodeDispatcher) {
                            ensureActive()
                            source.decode(key.sourceRect, key.sampleSize)
                        } ?: return@launch
                        if (cache.offer(key, bitmap, bitmap.allocationByteCount.toLong())) {
                            shown[key] = System.nanoTime()
                            // Not a child of this decode job: the job ends now.
                            effectScope.launch {
                                val end = System.nanoTime() + ViewerRegionTiles.FADE_NANOS
                                while (System.nanoTime() < end) withFrameNanos { fadeClock = System.nanoTime() }
                                fadeClock = System.nanoTime()
                            }
                        }
                    } finally {
                        if (jobs[key] === coroutineContext[Job]) jobs.remove(key)
                    }
                }
            }
        }
    }

    Canvas(Modifier.fillMaxSize().testTag("atlas-photo-tiles").clearAndSetSemantics {
        this[ViewerPhotoTilesShownKey] = shown.size
        this[ViewerPhotoTilesWantedKey] = wanted
        this[ViewerPhotoTileZoomKey] = zoomTenths
        this[ViewerPhotoTilesReadyKey] = region != null
    }) {
        val source = region ?: return@Canvas
        if (shown.isEmpty()) return@Canvas
        val now = fadeClock
        val orientation = source.orientation
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        drawIntoCanvas { canvas ->
            for ((key, publishedAt) in shown) {
                val bitmap = cache.get(key) ?: continue
                val upright = viewerUprightRegionForRawTile(key.sourceRect, orientation) ?: continue
                val r = viewerTileDrawRect(upright, orientation.uprightWidth, orientation.uprightHeight,
                    width, height) ?: continue
                val fade = ((now - publishedAt).toFloat() / ViewerRegionTiles.FADE_NANOS).coerceIn(0f, 1f)
                if (fade <= 0f) continue
                paint.alpha = (fade * 255f).roundToInt()
                drawRect.set(r.left, r.top, r.right, r.bottom)
                canvas.nativeCanvas.drawBitmap(bitmap, null, drawRect, paint)
            }
        }
    }
}
