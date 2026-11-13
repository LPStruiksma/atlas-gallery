package nl.atlas.gallery.featuremedia

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.StatFs
import android.util.Size as AndroidSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.clearViewerMetadataCache
import nl.atlas.gallery.presentationCacheKeyOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * #555 micro-thumbnail tier: what a grid tile shows while it scrolls, so a
 * fast fling or scrub shows photos instead of grey cells (Samsung T1-T3, T8).
 *
 * - Read path: memory first (synchronous, so a prefetched tile is never
 *   grey), else one ~3 KB file from [MicroThumbnailStore] decoded on its own
 *   small thread pool. It bypasses Coil, whose request chain runs on the
 *   main thread (#530) and so queued behind busy fling frames.
 * - Miss: made from the photo's EXIF preview when it matches the photo's
 *   shape, else a subsampled decode (JPEG decodes at 1/8 scale cheaply),
 *   else Android's own thumbnail for videos; then written for next time.
 * - [MicroThumbnailWarmer] fills the store newest-first in the background,
 *   so steady-state scrolling only ever reads.
 */
internal object MicroThumbnails {
    @Volatile var store: MicroThumbnailStore? = null
        private set
    @Volatile private var allowed = false
    @Volatile private var grantKnown = false
    @Volatile private var resolver: ContentResolver? = null

    /** Reads stay cheap; four at once keeps up with a 30k px/s fling. */
    val readDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** Misses decode the original; two at once leaves CPU for the frame. */
    val generateDispatcher = Dispatchers.IO.limitedParallelism(2)

    /**
     * Decoded micro thumbnails in memory (Samsung T1 keeps ~1,000 tiny ones).
     * Checked synchronously while a tile composes, so a prefetched tile draws
     * its photo on its very first frame. Never goes through the main thread
     * to fill: the prefetcher and tiles load on [readDispatcher].
     */
    val memory = MicroThumbnailMemory(MICRO_MEMORY_BYTES)

    fun install(context: Context) {
        if (store != null) return
        resolver = context.applicationContext.contentResolver
        val total = runCatching { StatFs(context.filesDir.absolutePath).totalBytes }.getOrDefault(0L)
        store = MicroThumbnailStore(File(context.cacheDir, "atlas-micro-v1"),
            microThumbnailDiskBudgetBytes(total))
    }

    /**
     * Called with every Android grant read. Without full access the copies
     * are deleted (once per transition) and nothing new is kept or shown.
     */
    fun applyGrant(images: Boolean, videos: Boolean, selected: Boolean) {
        // Permission rechecks also invalidate process-only Details EXIF cached
        // across media-grant epochs; never display stale metadata after revoke.
        clearViewerMetadataCache()
        val now = microThumbnailsAllowed(images, videos, selected)
        val wasAllowed = allowed
        val first = !grantKnown
        allowed = now
        grantKnown = true
        if (!now) memory.clear()
        if (!now && (wasAllowed || first)) {
            val current = store ?: return
            Thread({ current.clear() }, "atlas-micro-clear").start()
        }
    }

    val active: Boolean get() = ThumbnailPipeline.enabled && allowed && store != null

    /** The decoded micro thumbnail if it is already in memory (main-thread safe). */
    fun cached(memoryKey: String): Bitmap? = memory.get(memoryKey)

    /**
     * Memory, then disk ([readDispatcher]), then made from the photo
     * ([generateDispatcher]) and stored. Null when the tier is off, the
     * revision is unknown or the photo cannot be read. Cancellable.
     */
    suspend fun load(media: MediaRecord, epoch: Long, generate: Boolean = true): Bitmap? {
        if (!active) return null
        val memoryKey = microThumbnailMemoryKey(media, epoch) ?: return null
        memory.get(memoryKey)?.let { return it }
        val diskKey = microThumbnailDiskKey(media) ?: return null
        val currentStore = store ?: return null
        val stats = ThumbnailPipeline.stats
        try {
            val started = System.nanoTime()
            val cached = withContext(readDispatcher) { readCached(currentStore, diskKey) }
            if (cached != null) {
                stats.diskHits.incrementAndGet()
                stats.diskHitNanos.addAndGet(System.nanoTime() - started)
                memory.put(memoryKey, cached)
                return cached
            }
            if (!generate) return null
            val currentResolver = resolver ?: return null
            val generatedAt = System.nanoTime()
            val made = withContext(generateDispatcher) {
                coroutineContext.ensureActive()
                // Coroutine cancel (tile scrolled away) → CancellationSignal
                // for the provider's video thumbnail call (Samsung T7).
                val signal = CancellationSignal()
                val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) signal.cancel() }
                try {
                    makeAndStore(currentResolver, currentStore, MicroThumbnailData(media, diskKey), signal)
                } finally {
                    handle?.dispose()
                }
            }
            if (made == null) {
                stats.failures.incrementAndGet()
                return null
            }
            stats.generated.incrementAndGet()
            stats.generateNanos.addAndGet(System.nanoTime() - generatedAt)
            memory.put(memoryKey, made)
            return made
        } catch (e: CancellationException) {
            stats.cancels.incrementAndGet()
            throw e
        }
    }

    private fun readCached(store: MicroThumbnailStore, diskKey: String): Bitmap? {
        val bytes = store.read(diskKey) ?: return null
        val bitmap = decodeMicro(bytes)
        if (bitmap == null) store.remove(diskKey) // corrupt: make it again
        return bitmap
    }

    /** ~1,000 micro thumbnails of ~44 KB (RGB_565): a few screens of a fast fling. */
    private const val MICRO_MEMORY_BYTES = 48 * 1024 * 1024
}

/** Byte-bounded LRU of decoded micro thumbnails. */
internal class MicroThumbnailMemory(maxBytes: Int) {
    private val lru = object : android.util.LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    fun get(key: String): Bitmap? = lru.get(key)
    fun put(key: String, bitmap: Bitmap) { lru.put(key, bitmap) }
    fun clear() = lru.evictAll()
}

/**
 * Durable key of one photo revision: the presentation key without the
 * session's catalogue epoch (which changes on every re-read), so a copy
 * survives restarts but not an edit, rotation, resize or provider reset.
 */
internal fun microThumbnailDiskKey(media: MediaRecord): String? =
    media.presentationCacheKeyOrNull(ThumbnailPipeline.MICRO_PIXELS, catalogueEpoch = 0L)
        ?.let { "atlas-micro-v1|$it" }

/** In-memory key: scoped to the current catalogue publication, like every tile. */
internal fun microThumbnailMemoryKey(media: MediaRecord, epoch: Long): String? =
    media.presentationCacheKeyOrNull(ThumbnailPipeline.MICRO_PIXELS, epoch)?.let { "atlas-micro|$it" }

/** One micro thumbnail to make: the photo and its durable key. */
internal data class MicroThumbnailData(val media: MediaRecord, val diskKey: String)

private fun decodeMicro(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })

/**
 * Makes one micro thumbnail and stores it while the budget allows. Shared by
 * the tile fetcher and the background warmer. Returns null if the photo
 * cannot be read (revoked, deleted, unsupported).
 */
internal fun makeAndStore(
    resolver: ContentResolver, store: MicroThumbnailStore, data: MicroThumbnailData,
    signal: CancellationSignal? = null
): Bitmap? {
    val bitmap = try {
        makeMicroThumbnail(resolver, data.media, signal)
    } catch (e: OperationCanceledException) {
        throw CancellationException("micro thumbnail cancelled")
    } catch (e: Exception) {
        null
    } ?: return null
    if (!store.full()) {
        val bytes = ByteArrayOutputStream(4096).use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, MICRO_JPEG_QUALITY, out)) out.toByteArray() else null
        }
        if (bytes != null) store.write(data.diskKey, bytes)
    }
    return bitmap
}

private const val MICRO_JPEG_QUALITY = 75

private fun makeMicroThumbnail(resolver: ContentResolver, media: MediaRecord, signal: CancellationSignal?): Bitmap? {
    val uri = android.net.Uri.parse(media.uri)
    val micro = ThumbnailPipeline.MICRO_PIXELS
    val raw = if (media.isVideo) {
        resolver.loadThumbnail(uri, AndroidSize(micro * 2, micro * 2), signal)
    } else {
        exifPreviewOrNull(resolver, uri, media) ?: run {
            signal?.throwIfCanceled()
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setTargetSampleSize(microSampleSize(info.size.width, info.size.height, micro))
            }
        }
    }
    signal?.throwIfCanceled()
    return scaleToMicro(raw, micro)
}

/**
 * Largest power-of-two subsample that keeps the shortest side at or above
 * [micro] pixels (JPEG decoders do this at a fraction of a full decode).
 */
internal fun microSampleSize(width: Int, height: Int, micro: Int): Int {
    val shortest = min(width, height)
    if (shortest <= 0 || micro <= 0) return 1
    var sample = 1
    while (shortest / (sample * 2) >= micro && sample < 64) sample *= 2
    return sample
}

/** Target size: shortest side [micro], aspect kept; null when already small enough. */
internal fun microTargetSize(width: Int, height: Int, micro: Int): Pair<Int, Int>? {
    val shortest = min(width, height)
    if (width <= 0 || height <= 0 || shortest <= micro * 5 / 4) return null
    val scale = micro.toDouble() / shortest
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

private fun scaleToMicro(bitmap: Bitmap, micro: Int): Bitmap {
    val target = microTargetSize(bitmap.width, bitmap.height, micro) ?: return bitmap
    val scaled = Bitmap.createScaledBitmap(bitmap, target.first, target.second, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

/**
 * The JPEG/HEIF's embedded preview (camera photos carry a 160-512 px one),
 * read in well under a millisecond instead of decoding megabytes. Used only
 * when it matches the photo's catalogued shape within 2%, so a cropped or
 * edited photo with a stale preview falls back to a real decode.
 */
private fun exifPreviewOrNull(resolver: ContentResolver, uri: android.net.Uri, media: MediaRecord): Bitmap? {
    val expected = expectedOrientedAspect(media) ?: return null
    return runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            if (!exif.hasThumbnail()) return@use null
            val thumb = exif.thumbnailBitmap ?: return@use null
            val oriented = applyExifOrientation(thumb,
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            val ok = exifPreviewUsable(oriented.width, oriented.height, expected,
                ThumbnailPipeline.MICRO_PIXELS)
            if (ok) oriented else { oriented.recycle(); null }
        }
    }.getOrNull()
}

/** Width/height of the photo as displayed, from the catalogue; null if unknown. */
internal fun expectedOrientedAspect(media: MediaRecord): Double? {
    if (media.widthPixels <= 0 || media.heightPixels <= 0) return null
    val swap = when (((media.orientationDegrees % 360) + 360) % 360) {
        0, 180 -> false
        90, 270 -> true
        else -> return null
    }
    val w = if (swap) media.heightPixels else media.widthPixels
    val h = if (swap) media.widthPixels else media.heightPixels
    return w.toDouble() / h.toDouble()
}

/** An embedded preview may stand in only at the right shape and a usable size. */
internal fun exifPreviewUsable(width: Int, height: Int, expectedAspect: Double, micro: Int): Boolean {
    if (width <= 0 || height <= 0 || expectedAspect <= 0.0) return false
    if (min(width, height) < micro * 3 / 4) return false
    val aspect = width.toDouble() / height.toDouble()
    return abs(aspect - expectedAspect) / expectedAspect <= 0.02
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> return bitmap
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap
    }
    val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (out !== bitmap) bitmap.recycle()
    return out
}
