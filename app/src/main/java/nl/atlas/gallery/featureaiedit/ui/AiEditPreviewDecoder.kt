package nl.atlas.gallery.featureaiedit.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import nl.atlas.gallery.featureaiedit.preview.CompareImageSize
import nl.atlas.gallery.featureaiedit.preview.ComparePreviewDecode
import nl.atlas.gallery.featureaiedit.ui.AiEditCompareMath.IntRect
import nl.atlas.gallery.featureaiedit.ui.AiEditCompareMath.Orientation

/**
 * Bounded preview decoding for the compare viewer (AIE-21 rev 5, gesture contract item 2). The
 * CompareViewportPlanner only proposes a crop and a power-of-two sample; this decoder ENFORCES
 * them: it reads just the planned crop with a region decoder at the planned sample and refuses
 * any output larger than the plan, so a 200 MP photo is never decoded at full size here.
 * Reads only the two Uris the screen passes (the opened photo and a private session result).
 */
internal class AiEditPreviewDecoder(private val resolver: ContentResolver) {

    /** Stored size, EXIF orientation and the upright size the planner works in. */
    data class Info(val rawWidth: Int, val rawHeight: Int, val orientation: Int) {
        val upright: CompareImageSize get() =
            if (Orientation.swapsAxes(orientation)) CompareImageSize(rawHeight, rawWidth)
            else CompareImageSize(rawWidth, rawHeight)
    }

    /** One decoded crop of a frame, with what it covers in upright source pixels. */
    class Frame(val bitmap: Bitmap, val source: IntRect, val bytes: Long)

    fun info(uri: Uri): Info? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        if (options.outWidth <= 0 || options.outHeight <= 0) null
        else Info(options.outWidth, options.outHeight, orientation)
    } catch (_: Exception) { null }

    /**
     * Decodes exactly [plan]'s crop at its sample. Returns null on any failure or when the decoder
     * returns more pixels than planned; the caller then keeps its previous frame.
     */
    fun decode(uri: Uri, info: Info, plan: ComparePreviewDecode): Frame? {
        val upright = IntRect(plan.source.left, plan.source.top, plan.source.right, plan.source.bottom)
        val raw = AiEditCompareMath.uprightToRaw(upright, info.orientation, info.rawWidth, info.rawHeight)
        if (raw.width <= 0 || raw.height <= 0) return null
        val sample = plan.downsampleBy.coerceIn(1L, 1L shl 30).toInt()
        val decoded = try {
            resolver.openInputStream(uri)?.use { stream ->
                @Suppress("DEPRECATION")
                val region = BitmapRegionDecoder.newInstance(stream, false) ?: return null
                try {
                    region.decodeRegion(Rect(raw.left, raw.top, raw.right, raw.bottom),
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        })
                } finally { region.recycle() }
            }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null } ?: return null
        // Enforce the plan: the stored-grid output may be the planned size with axes swapped.
        val (maxW, maxH) = if (Orientation.swapsAxes(info.orientation)) plan.height to plan.width
            else plan.width to plan.height
        if (decoded.width > maxW + 1 || decoded.height > maxH + 1) {
            decoded.recycle()
            return null
        }
        val oriented = try { orient(decoded, info.orientation) } catch (_: OutOfMemoryError) {
            decoded.recycle(); return null
        }
        if (oriented !== decoded) decoded.recycle()
        return Frame(oriented, upright, oriented.allocationByteCount.toLong())
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            Orientation.ROTATE_90 -> m.postRotate(90f)
            Orientation.ROTATE_180 -> m.postRotate(180f)
            Orientation.ROTATE_270 -> m.postRotate(270f)
            Orientation.FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            Orientation.FLIP_VERTICAL -> m.postScale(1f, -1f)
            Orientation.TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            Orientation.TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}
