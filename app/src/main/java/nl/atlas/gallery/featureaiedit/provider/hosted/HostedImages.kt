package nl.atlas.gallery.featureaiedit.provider.hosted

import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.ResizePlan
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Encoded bytes of one caller-selected image, already resized to the admission plan. */
class EncodedImage(val bytes: ByteArray, val format: ImageFormat) {
    val mimeType: String get() = mimeOf(format)
    override fun toString(): String = "EncodedImage(${format.name}, ${bytes.size} bytes)"
}

fun mimeOf(format: ImageFormat): String = when (format) {
    ImageFormat.PNG -> "image/png"
    ImageFormat.JPEG -> "image/jpeg"
    ImageFormat.WEBP -> "image/webp"
}

/**
 * Supplies the bytes of exactly the images the caller put in the request. Android wiring decodes
 * the selected Uri, applies [ResizePlan] and strips location metadata; JVM tests return synthetic
 * bytes. Returning null means the image cannot be read and the edit fails before any upload.
 */
fun interface SourceImageLoader {
    fun load(image: EditImage, plan: ResizePlan): EncodedImage?
}

/** Header-only probe: the format and size come from the bytes, never from labels or URLs. */
object ImageProbe {
    data class Probe(val format: ImageFormat, val width: Int, val height: Int)

    fun probe(bytes: ByteArray): Probe? = try {
        png(bytes) ?: jpeg(bytes) ?: webp(bytes)
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)

    private fun valid(format: ImageFormat, w: Long, h: Long): Probe? =
        if (w in 1..65_535 && h in 1..65_535) Probe(format, w.toInt(), h.toInt()) else null

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private fun png(b: ByteArray): Probe? {
        if (b.size < 33) return null
        for (k in PNG_SIGNATURE.indices) if (b[k] != PNG_SIGNATURE[k]) return null
        if (String(b, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        return valid(ImageFormat.PNG, be32(b, 16), be32(b, 20))
    }

    private fun jpeg(b: ByteArray): Probe? {
        if (b.size < 4 || u8(b, 0) != 0xff || u8(b, 1) != 0xd8) return null
        var i = 2
        while (i + 9 < b.size) {
            if (u8(b, i) != 0xff) return null
            val marker = u8(b, i + 1)
            if (marker == 0xff) { i++; continue }
            if (marker == 0xd8 || marker in 0xd0..0xd7 || marker == 0x01) { i += 2; continue }
            if (marker == 0xd9 || marker == 0xda) return null
            val length = be16(b, i + 2)
            if (length < 2) return null
            val sof = marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
            if (sof) return valid(ImageFormat.JPEG, be16(b, i + 7).toLong(), be16(b, i + 5).toLong())
            i += 2 + length
        }
        return null
    }

    private fun webp(b: ByteArray): Probe? {
        if (b.size < 30) return null
        if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WEBP") return null
        return when (String(b, 12, 4, Charsets.US_ASCII)) {
            "VP8 " -> if (u8(b, 23) == 0x9d && u8(b, 24) == 0x01 && u8(b, 25) == 0x2a)
                valid(ImageFormat.WEBP, (le16(b, 26) and 0x3fff).toLong(), (le16(b, 28) and 0x3fff).toLong()) else null
            "VP8L" -> if (u8(b, 20) == 0x2f) {
                val bits = u8(b, 21).toLong() or (u8(b, 22).toLong() shl 8) or
                    (u8(b, 23).toLong() shl 16) or (u8(b, 24).toLong() shl 24)
                valid(ImageFormat.WEBP, (bits and 0x3fff) + 1, ((bits shr 14) and 0x3fff) + 1)
            } else null
            "VP8X" -> valid(ImageFormat.WEBP, le24(b, 24).toLong() + 1, le24(b, 27).toLong() + 1)
            else -> null
        }
    }
}

/**
 * Writes verified provider output into the adapter's app-private directory. The file appears
 * only complete (temp file plus rename) and the handle is the absolute path of that copy.
 */
class HostedResultStore(private val directory: File) {
    fun store(jobId: String, index: Int, bytes: ByteArray, probe: ImageProbe.Probe): EditImage? {
        if (!directory.isDirectory) return null
        val safeId = jobId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val ext = when (probe.format) {
            ImageFormat.PNG -> "png"
            ImageFormat.JPEG -> "jpg"
            ImageFormat.WEBP -> "webp"
        }
        val target = File(directory, "$safeId-${index + 1}-${UUID.randomUUID().toString().take(8)}.$ext")
        val temp = File(directory, ".${target.name}.part")
        return try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!temp.renameTo(target)) null
            else EditImage(target.absolutePath, probe.width, probe.height, probe.format)
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    /** Adopts an already-downloaded file after verifying its bytes. */
    fun adopt(file: File, probe: ImageProbe.Probe): EditImage =
        EditImage(file.absolutePath, probe.width, probe.height, probe.format)

    fun newDownloadTarget(jobId: String, index: Int): File {
        val safeId = jobId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        return File(directory, "$safeId-${index + 1}-${UUID.randomUUID().toString().take(8)}.download")
    }

    val isUsable: Boolean get() = directory.isDirectory
}
