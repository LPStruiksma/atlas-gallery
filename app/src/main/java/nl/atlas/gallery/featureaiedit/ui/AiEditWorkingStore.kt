package nl.atlas.gallery.featureaiedit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import java.io.File
import java.util.UUID

/**
 * Work stays in app-private cache, not MediaStore or an external folder. Every path is a generated
 * basename beneath a single session. The original is read-only and never copied for preview.
 */
internal class AiEditWorkingStore(context: Context, sessionId: String) {
    init { require(sessionId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    private val resolver = context.contentResolver
    private val dir = File(context.cacheDir, "atlas-ai-edit/$sessionId").apply {
        check(isDirectory || mkdirs()) { "Cannot create private AI Edit workspace" }
    }.canonicalFile

    fun existing(): Set<String> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(CANDIDATE) }
        .mapTo(HashSet()) { it.name }

    fun candidateFile(fileName: String): File {
        require(fileName.matches(CANDIDATE))
        val file = File(dir, fileName).canonicalFile
        check(file.parentFile == dir && file.exists() && file.length() in 1L..MAX_CANDIDATE_BYTES) {
            "Temporary AI Edit candidate is no longer available"
        }
        return file
    }

    /** Private folder a hosted adapter downloads into; results are adopted from here. */
    fun resultsDirectory(): File = File(dir, "results").apply { check(isDirectory || mkdirs()) }

    /** The session folder itself, for the request journal. */
    fun sessionDirectory(): File = dir

    /**
     * Moves one verified adapter output (in [resultsDirectory]) into the session as a candidate.
     * Only files inside that folder, with an image extension, within the size cap, are accepted.
     */
    fun adoptResult(path: String): String {
        val source = File(path).canonicalFile
        check(source.parentFile == resultsDirectory().canonicalFile && source.isFile &&
            source.length() in 1L..MAX_CANDIDATE_BYTES) { "Result is not a private adapter output" }
        val ext = source.extension.lowercase().takeIf { it in setOf("png", "jpg", "webp") }
            ?: error("Unsupported result format")
        val name = "candidate-${UUID.randomUUID()}.$ext"
        check(source.renameTo(File(dir, name))) { "Could not keep the result" }
        return name
    }

    /** Decode a bounded 64px synthetic input. Neither original bytes nor metadata reach a provider. */
    fun mockSource(source: Uri): EditImage {
        require(source.scheme == "content" && source.authority == "media")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(source)
            ?: error("Selected photo is not available")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image" }
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > 16_384L) {
            sample = sample shl 1
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val original = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Could not read selected photo")
        return try { syntheticInput(original, "selected-photo") }
        finally { original.recycle() }
    }

    fun mockSourceFromCandidate(fileName: String): EditImage {
        val bitmap = BitmapFactory.decodeFile(candidateFile(fileName).path)
            ?: error("Temporary candidate could not be read")
        return try { syntheticInput(bitmap, "previous-candidate") }
        finally { bitmap.recycle() }
    }

    private fun syntheticInput(bitmap: Bitmap, handle: String): EditImage {
        val factor = minOf(1f, 64f / maxOf(bitmap.width, bitmap.height))
        val width = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val height = (bitmap.height * factor).toInt().coerceAtLeast(1)
        val pixels = IntArray(width * height)
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try { small.getPixels(pixels, 0, width, 0, 0, width, height) }
        finally { if (small !== bitmap) small.recycle() }
        return EditImage(handle, width, height, ImageFormat.PNG, pixels.toList())
    }

    fun writeCandidate(image: EditImage): String {
        require(image.width > 0 && image.height > 0 &&
            image.width.toLong() * image.height <= 4096L &&
            image.pixels.size == image.width * image.height)
        val name = "candidate-${UUID.randomUUID()}.png"
        val tmp = File(dir, "$name.partial")
        val target = File(dir, name)
        val bitmap = Bitmap.createBitmap(image.pixels.toIntArray(),
            image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            tmp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(tmp.renameTo(target)) { "Could not commit private candidate" }
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
        return name
    }

    fun cleanUp() {
        // Only files created for this session; never touch any other app/private gallery data.
        File(dir, "results").listFiles()?.forEach { if (it.isFile) it.delete() }
        File(dir, "results").delete()
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        dir.delete()
    }

    companion object {
        private val CANDIDATE = Regex("candidate-[a-f0-9-]{36}\\.(png|jpg|webp)")
        private const val MAX_CANDIDATE_BYTES = 40_000_000L
    }
}
