package nl.atlas.gallery.featuregrid

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featuremedia.MicroThumbnails
import nl.atlas.gallery.featuremedia.microThumbnailMemoryKey
import nl.atlas.gallery.presentationCacheKeyOrNull

/**
 * FLOW-1 (#711): thumbnail request sizes of the other grid levels on this
 * screen, best first. A grid tile whose own size is not in the memory cache
 * yet shows one of these already decoded copies (cropped to its cell) until
 * its own decode lands, so a pinch commit never blinks tiles to grey (Samsung
 * GRID:G7: the new layout binds with the bitmaps already on screen).
 * Null (the default) keeps every other grid exactly as before.
 */
internal val LocalGridTileFallbackPixels = staticCompositionLocalOf<IntArray?> { null }

/** Request sizes of every square level in a viewport, ordered by closeness to [preferredPixels] (larger first on ties). */
internal fun gridLevelPreviewPixels(bounds: GridViewportGeometry, preferredPixels: Int): IntArray =
    GridDensity.entries.map { level ->
        val side = bounds.cellSidePx(level.columns)
        gridPreviewRequestPixels(side, side)
    }.distinct().sortedWith(compareBy<Int>({ kotlin.math.abs(it - preferredPixels) }, { -it }))
        .toIntArray()

/**
 * The best memory-cached bitmap of [media] among [candidatePixels], then its
 * micro thumbnail. Memory lookups only: never decodes, never touches disk, so
 * it is safe on every frame of a gesture. The keys carry the publication
 * epoch, so a revoked or republished catalogue never reuses a bitmap.
 */
internal fun cachedGridTileBitmap(
    context: Context, media: MediaRecord, publicationEpoch: Long, candidatePixels: IntArray
): Bitmap? {
    val cache = SingletonImageLoader.get(context).memoryCache
    if (cache != null) for (pixels in candidatePixels) {
        val key = media.presentationCacheKeyOrNull(pixels, publicationEpoch) ?: return null
        val bitmap = (cache[MemoryCache.Key(key)]?.image as? BitmapImage)?.bitmap
        if (bitmap != null && !bitmap.isRecycled) return bitmap
    }
    if (!MicroThumbnails.active) return null
    return microThumbnailMemoryKey(media, publicationEpoch)?.let { MicroThumbnails.cached(it) }
        ?.takeIf { !it.isRecycled }
}
