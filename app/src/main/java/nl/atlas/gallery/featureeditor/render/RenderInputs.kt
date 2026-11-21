package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Rect
import nl.atlas.gallery.featureeditor.engine.Matrix3
import nl.atlas.gallery.featureeditor.engine.OverlayLayer
import kotlin.math.max
import kotlin.math.min

/**
 * A block of input pixels for a backend. [bitmap] is software `ARGB_8888` whose bytes are
 * encoded in [space]; texel (0, 0) starts at source pixel ([originX], [originY]) and [scale] is
 * texels per source pixel. Backends read the bytes raw, so the label never causes a conversion.
 */
class SourceRegion(
    val bitmap: Bitmap,
    val originX: Float,
    val originY: Float,
    val scale: Float,
    val space: PixelSpace,
)

/** Supplies regions of a segment's input picture (the decoded photo, or a rendered base). */
interface SourceProvider {
    val width: Int
    val height: Int

    /**
     * Pixels covering [rect] (full-resolution source pixels) at about [scale] texels per source
     * pixel (at most 1). A provider may return a larger area or a different scale; the region
     * says what it holds.
     */
    fun region(rect: IntRect, scale: Float): SourceRegion
}

/**
 * Content-addressed assets the recipe refers to by hash: raster patches and masks (AI Edit, Face
 * Swap, Erase), LUTs and sticker art. EDIT-3/EDIT-5 own the store; the renderer only reads.
 */
interface EditorAssets {
    fun bitmap(contentHash: String): Bitmap?
    fun lut(lutId: String, lutHash: String?): Lut3D?

    /** Rasterises one decoration layer; null when this kind is not drawable yet. */
    fun overlay(layer: OverlayLayer, sourceWidth: Int, sourceHeight: Int): PlacedRaster? = null

    companion object {
        val NONE = object : EditorAssets {
            override fun bitmap(contentHash: String): Bitmap? = null
            override fun lut(lutId: String, lutHash: String?): Lut3D? = null
        }
    }
}

/** A rasterised decoration: [bitmap] placed in source pixels by [rasterToSource]. */
class PlacedRaster(val bitmap: Bitmap, val rasterToSource: Matrix3)

/** A whole in-memory picture (preview proxy, test fixture, or a small photo). */
class BitmapSource(
    bitmap: Bitmap,
    /** Full-resolution size of the photo this bitmap shows (it may be a downsampled proxy). */
    override val width: Int = bitmap.width,
    override val height: Int = bitmap.height,
) : SourceProvider {
    private val pixels: Bitmap = RenderBitmaps.normalized(bitmap)
    private val space = RenderBitmaps.spaceOf(pixels)

    override fun region(rect: IntRect, scale: Float): SourceRegion =
        SourceRegion(pixels, 0f, 0f, pixels.width.toFloat() / width, space)
}

/**
 * Decodes only the requested part of an encoded photo, subsampled by a power of two when the
 * request allows, so a 50 MP export never holds the whole decoded photo.
 */
class RegionDecoderSource(private val decoder: BitmapRegionDecoder) : SourceProvider {
    override val width: Int = decoder.width
    override val height: Int = decoder.height
    private val lock = Any()

    override fun region(rect: IntRect, scale: Float): SourceRegion {
        require(!rect.isEmpty) { "Empty region" }
        var sample = 1
        while (sample * 2 <= 1f / max(scale, 1e-6f) && sample < 64) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = synchronized(lock) {
            decoder.decodeRegion(Rect(rect.left, rect.top, rect.right, rect.bottom), options)
        } ?: throw IllegalStateException("Region decode failed")
        val bitmap = RenderBitmaps.normalized(decoded)
        return SourceRegion(
            bitmap, rect.left.toFloat(), rect.top.toFloat(),
            bitmap.width.toFloat() / rect.width, RenderBitmaps.spaceOf(bitmap),
        )
    }
}

/** Bitmap helpers shared by sources, backends and sinks. */
object RenderBitmaps {
    fun colorSpace(space: PixelSpace): ColorSpace = ColorSpace.get(
        if (space == PixelSpace.DISPLAY_P3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB,
    )

    fun spaceOf(bitmap: Bitmap): PixelSpace =
        if (bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3)) PixelSpace.DISPLAY_P3 else PixelSpace.SRGB

    /**
     * Returns [bitmap] as software `ARGB_8888` encoded in sRGB or Display P3. Other colour spaces
     * (Adobe RGB, linear or HDR F16 decodes) are converted into Display P3, which holds sRGB and
     * P3 exactly and keeps wide-gamut colour.
     */
    fun normalized(bitmap: Bitmap): Bitmap {
        val cs = bitmap.colorSpace
        val known = cs == null || cs == ColorSpace.get(ColorSpace.Named.SRGB) || cs == ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        if (bitmap.config == Bitmap.Config.ARGB_8888 && known) return bitmap
        val target = if (cs == null || cs == ColorSpace.get(ColorSpace.Named.SRGB)) PixelSpace.SRGB else PixelSpace.DISPLAY_P3
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, true, colorSpace(target))
        val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.RGBA_F16, false) else bitmap
        Canvas(out).drawBitmap(src, 0f, 0f, null)
        return out
    }

    fun mutableCopy(bitmap: Bitmap): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    fun clampScale(s: Float) = min(1f, max(1e-4f, s))
}
