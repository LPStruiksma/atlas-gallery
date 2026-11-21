package nl.atlas.gallery.featureaiedit.engine

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.ResizePlan
import nl.atlas.gallery.featureaiedit.provider.hosted.EncodedImage
import nl.atlas.gallery.featureaiedit.provider.hosted.SourceImageLoader
import java.io.ByteArrayOutputStream

/**
 * Supplies exactly the one photo the user chose (#431 rev 4 "Source preparation"). The handle is
 * that photo's own content Uri; any other handle is refused, so nothing else can be uploaded.
 *
 * - EXIF orientation is applied to the pixels, so the service sees the photo upright.
 * - The photo is downscaled to the provider's planned size.
 * - Location: a new pixel encoding carries no EXIF at all (no GPS, no camera or file name). Only
 *   when "Keep location" is on, no resize is needed and the file is already upright JPEG or PNG are
 *   the original bytes sent unchanged.
 * - Nothing is written to disk: the upload copy lives only in memory for the one request.
 */
class AndroidSourceImageLoader(
    private val resolver: ContentResolver,
    private val source: Uri,
    private val keepLocation: Boolean
) : SourceImageLoader {

    data class Bounds(val width: Int, val height: Int, val orientation: Int)

    /** Upright size of the selected photo, or null when it can't be read. */
    fun bounds(): Bounds? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
        val orientation = resolver.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        if (options.outWidth <= 0 || options.outHeight <= 0) null
        else if (swapsAxes(orientation)) Bounds(options.outHeight, options.outWidth, orientation)
        else Bounds(options.outWidth, options.outHeight, orientation)
    } catch (_: Exception) { null }

    /** The request's source image for this photo, sized as the photo is shown upright. */
    fun editImage(): EditImage? {
        val b = bounds() ?: return null
        val format = when (resolver.getType(source)) {
            "image/png" -> ImageFormat.PNG
            "image/webp" -> ImageFormat.WEBP
            else -> ImageFormat.JPEG
        }
        return EditImage(source.toString(), b.width, b.height, format)
    }

    override fun load(image: EditImage, plan: ResizePlan): EncodedImage? {
        if (image.handle != source.toString()) return null
        return try { encode(image, plan) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }

    private fun encode(image: EditImage, plan: ResizePlan): EncodedImage? {
        val b = bounds() ?: return null
        val upright = b.orientation == ExifInterface.ORIENTATION_NORMAL || b.orientation == ExifInterface.ORIENTATION_UNDEFINED
        if (keepLocation && plan is ResizePlan.Keep && upright &&
            (image.format == ImageFormat.JPEG || image.format == ImageFormat.PNG)) {
            val bytes = resolver.openInputStream(source)?.use { it.readBytes() } ?: return null
            if (bytes.size > MAX_BYTES) return null
            return EncodedImage(bytes, image.format)
        }
        val (targetW, targetH) = when (plan) {
            ResizePlan.Keep -> b.width to b.height
            is ResizePlan.Downscale -> plan.width to plan.height
        }
        // Decode near the target size first so a 200 MP photo never needs full-size memory.
        val rawW = if (swapsAxes(b.orientation)) targetH else targetW
        val rawH = if (swapsAxes(b.orientation)) targetW else targetH
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, probe) }
        var sample = 1
        while (probe.outWidth / (sample * 2) >= rawW && probe.outHeight / (sample * 2) >= rawH) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val oriented = orient(decoded, b.orientation)
        val scaled = if (oriented.width == targetW && oriented.height == targetH) oriented
            else Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        try {
            val out = ByteArrayOutputStream()
            val format = if (image.format == ImageFormat.PNG) ImageFormat.PNG else ImageFormat.JPEG
            val ok = if (format == ImageFormat.PNG) scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                else scaled.compress(Bitmap.CompressFormat.JPEG, 95, out)
            if (!ok || out.size() > MAX_BYTES) return null
            return EncodedImage(out.toByteArray(), format)
        } finally {
            if (scaled !== oriented) scaled.recycle()
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    override fun toString(): String = "AndroidSourceImageLoader(selected photo)"

    companion object {
        /** Upload bodies are capped at 20 MB by the transport; base64 grows bytes by a third. */
        private const val MAX_BYTES = 14_000_000

        fun swapsAxes(orientation: Int): Boolean = orientation in setOf(
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
    }
}
