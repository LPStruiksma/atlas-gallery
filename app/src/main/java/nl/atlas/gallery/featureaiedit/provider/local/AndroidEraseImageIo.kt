package nl.atlas.gallery.featureaiedit.provider.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.ImageFormat

/**
 * Android IO for [LocalEraseProvider]. Handles are absolute paths of app-private files
 * (optionally `file://`), as the AI Edit working store keeps them; nothing is read from
 * MediaStore here and nothing is sent anywhere.
 *
 * - Source: decoded once at full resolution, unscaled, as a mutable, unpremultiplied
 *   ARGB_8888 bitmap in stored orientation (EXIF orientation is NOT applied; the mask must
 *   use the same stored pixel grid, e.g. via `ImageOrientation.normalizedToOriginal`).
 *   A 50 MP photo is a 200 MB native bitmap; the erase itself streams in bands.
 * - Mask: a PNG of the same size, read in bands with [BitmapRegionDecoder]. A pixel is
 *   erased when it is opaque and bright ([EraseMaskRule]): white = erase, black or
 *   transparent = keep.
 * - Output: PNG (lossless) written to [outputDir] via a temp file. Only erased pixels are
 *   written back, so every other pixel equals the decoded source exactly.
 */
class AndroidEraseImageIo(private val outputDir: File, private val maskBandRows: Int = 256) : EraseImageIo {

    override fun open(source: EditImage, mask: EditImage?, jobId: String): EraseCanvas {
        val sourceFile = fileOf(source.handle)
        val maskFile = mask?.let { fileOf(it.handle) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw EraseEngineFailure(FailureKind.INVALID_REQUEST, false, "Source is not a decodable image")
        }
        if (bounds.outWidth != source.width || bounds.outHeight != source.height) {
            throw EraseEngineFailure(FailureKind.INVALID_REQUEST, false, "Source size differs from the request")
        }
        val maskReader = maskFile?.let { openMask(it, source.width, source.height) }
        val bitmap = try {
            BitmapFactory.decodeFile(sourceFile.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                inPremultiplied = false
                inScaled = false
                inSampleSize = 1
            }) ?: throw EraseEngineFailure(FailureKind.INVALID_REQUEST, false, "Source could not be decoded")
        } catch (error: Throwable) {
            maskReader?.close()
            throw error
        }
        return BitmapCanvas(bitmap, maskReader, File(outputDir, "erase-${safe(jobId)}.png"))
    }

    private fun openMask(file: File, width: Int, height: Int): BandMask {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth != width || bounds.outHeight != height) {
            throw EraseEngineFailure(FailureKind.INVALID_REQUEST, false, "Mask does not align with source")
        }
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(file.path, false)
            ?: throw EraseEngineFailure(FailureKind.INVALID_REQUEST, false, "Mask could not be decoded")
        return BandMask(decoder, width, height)
    }

    private class BandMask(private val decoder: BitmapRegionDecoder, override val width: Int, override val height: Int) :
        MaskRegionReader, AutoCloseable {
        private val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        private var scratch = IntArray(0)

        override fun read(rect: PixelRect, out: ByteArray) {
            if (rect.isEmpty) return
            val region = decoder.decodeRegion(Rect(rect.left, rect.top, rect.right, rect.bottom), options)
                ?: throw EraseEngineFailure(FailureKind.UNKNOWN, false, "Mask region could not be decoded")
            try {
                check(region.width == rect.width && region.height == rect.height) { "Mask region has the wrong size" }
                val count = rect.width * rect.height
                if (scratch.size < count) scratch = IntArray(count)
                region.getPixels(scratch, 0, rect.width, 0, 0, rect.width, rect.height)
                for (i in 0 until count) out[i] = if (EraseMaskRule.isErase(scratch[i])) 1 else 0
            } finally {
                region.recycle()
            }
        }

        override fun close() = decoder.recycle()
    }

    private class BitmapRegions(private val bitmap: Bitmap) : ArgbRegionReader, ArgbRegionWriter {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override fun read(rect: PixelRect, out: IntArray) {
            if (!rect.isEmpty) bitmap.getPixels(out, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
        }
        /** Writes runs of changed pixels only, so kept pixels are never re-encoded through setPixels. */
        override fun write(rect: PixelRect, argb: IntArray, changed: BooleanArray) {
            val w = rect.width
            for (y in 0 until rect.height) {
                var x = 0
                while (x < w) {
                    if (!changed[y * w + x]) { x++; continue }
                    val start = x
                    while (x < w && changed[y * w + x]) x++
                    bitmap.setPixels(argb, y * w + start, w, rect.left + start, rect.top + y, x - start, 1)
                }
            }
        }
    }

    private class BitmapCanvas(
        private val bitmap: Bitmap,
        private val maskReader: BandMask?,
        private val outFile: File,
    ) : EraseCanvas {
        private val regions = BitmapRegions(bitmap)
        override val source: ArgbRegionReader = regions
        override val target: ArgbRegionWriter = regions
        override val mask: MaskRegionReader? = maskReader

        override fun commit(format: ImageFormat): EditImage {
            require(format == ImageFormat.PNG) { "On-device erase writes PNG only" }
            val dir = outFile.parentFile ?: throw IllegalStateException("No output directory")
            if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("Cannot create $dir")
            val tmp = File(dir, outFile.name + ".tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" }
                    out.fd.sync()
                }
                if (!tmp.renameTo(outFile)) throw IllegalStateException("Cannot move output into place")
            } catch (error: Throwable) {
                tmp.delete()
                throw error
            }
            return EditImage(outFile.absolutePath, bitmap.width, bitmap.height, ImageFormat.PNG)
        }

        override fun discard() {
            File(outFile.path + ".tmp").delete()
            outFile.delete()
        }

        override fun close() {
            runCatching { maskReader?.close() }
            bitmap.recycle()
        }
    }

    companion object {
        fun fileOf(handle: String): File {
            val path = handle.removePrefix("file://")
            val file = File(path)
            if (!file.isAbsolute || !file.isFile) {
                throw EraseEngineFailure(FailureKind.INVALID_REQUEST, false, "Image handle is not a readable file")
            }
            return file
        }

        private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
