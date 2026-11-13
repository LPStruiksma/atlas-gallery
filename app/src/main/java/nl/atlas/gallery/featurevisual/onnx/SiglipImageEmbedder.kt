package nl.atlas.gallery.featurevisual.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.sqrt
import nl.atlas.gallery.featurevisual.ImageEmbedder

/**
 * Pins for the bundled SigLIP 2 B/16 224 image tower (big_vision `siglip2_b16_224.npz`,
 * Apache-2.0), exported by `tools/visual_search_bench/export_onnx.py` to ONNX with dynamic
 * int8 weights except `blocks.5/mlp/fc2`. Provenance: `app/src/main/assets/models/siglip2/SOURCE.md`.
 *
 * The file is over GitHub's 100 MB limit, so it ships as ordered asset parts that
 * [SiglipModelFile] joins once into app-private no-backup storage. Nothing is downloaded.
 */
object SiglipModel {
    const val ASSET_DIR = "models/siglip2"
    const val FILE_NAME = "siglip2_b16_224_int8.onnx"
    val PART_ASSETS: List<String> = listOf(
        "$ASSET_DIR/$FILE_NAME.part0",
        "$ASSET_DIR/$FILE_NAME.part1",
    )
    const val LICENSE_ASSET_PATH = "$ASSET_DIR/LICENSE"

    /** SHA-256 and size of the joined int8 ONNX file (also in the VS-S0 export report). */
    const val SHA256 = "74da3ac2b46e4292c4639affc3cca623c2d5551d012ead113b1486c22e0f5336"
    const val SIZE_BYTES = 102_869_345L

    /**
     * Stored with every vector; an index refuses to mix versions. Changes whenever the model
     * bytes change; a preprocessing change must bump it by hand.
     */
    const val MODEL_VERSION = "siglip2_b16_224.int8:" + "74da3ac2b46e"

    const val INPUT_NAME = "pixels"
    const val OUTPUT_NAME = "embedding"
    const val DIMENSION = 768
}

/** The bundled embedder model is missing, truncated or does not match its SHA-256 pin. */
class SiglipModelIntegrityFailure(message: String) : IOException(message)

/**
 * Joins the model's asset parts into one file under [dir], verifying the whole-file SHA-256
 * on every open. Fail closed: on any mismatch the file is deleted and
 * [SiglipModelIntegrityFailure] is thrown, so a bad file never reaches the runtime.
 * Pure `java.io`, so it runs in JVM tests with injected part streams.
 */
object SiglipModelFile {
    private val lock = Any()

    /**
     * Returns the verified model file, writing it from [parts] (opened in order) if it is
     * absent or does not verify. Safe to call from several threads and embedder instances.
     */
    fun materialize(
        dir: File,
        parts: List<() -> InputStream>,
        sha256: String = SiglipModel.SHA256,
        sizeBytes: Long = SiglipModel.SIZE_BYTES,
        fileName: String = SiglipModel.FILE_NAME,
    ): File = synchronized(lock) {
        val target = File(dir, fileName)
        if (target.isFile) {
            if (target.length() == sizeBytes && sha256Hex(target) == sha256) return target
            target.delete()
        }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create model directory $dir")
        val tmp = File(dir, "$fileName.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(1 shl 16)
                for (open in parts) {
                    open().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            written += n
                            if (written > sizeBytes) {
                                throw SiglipModelIntegrityFailure("Bundled image embedder is larger than its pin")
                            }
                        }
                    }
                }
                out.fd.sync()
            }
            val got = digest.digest().toHex()
            if (written != sizeBytes || got != sha256) {
                throw SiglipModelIntegrityFailure("Bundled image embedder does not match its SHA-256 pin")
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot move model file into place at $target")
            return target
        } catch (error: Throwable) {
            tmp.delete()
            target.delete()
            throw error
        }
    }

    /** Removes [dir] and the joined model in it, under the same lock as [materialize]. */
    fun delete(dir: File): Boolean = synchronized(lock) {
        dir.deleteRecursively() || !dir.exists()
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * On-device SigLIP 2 B/16 224 image embedder on ONNX Runtime (CPU only, no network).
 * Returns 768-d L2-normalised vectors, so cosine is a plain dot product, tagged with
 * [SiglipModel.MODEL_VERSION]. Preprocessing is [SiglipPreprocess] (VS-S0 contract).
 *
 * [embed] is blocking and serialised; call it from a worker thread. The caller keeps
 * ownership of bitmaps, which are only read. Pixels and vectors stay in the process.
 * One embedder holds about 100 MB of weights plus ORT buffers: open one, reuse it, close it.
 * It is the [ImageEmbedder] the scan worker's visual stage uses (VS-S1c): the host opens
 * one per scan run, because [open] re-hashes the 103 MB model file every time.
 */
class SiglipImageEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val options: OrtSession.SessionOptions,
) : Closeable, ImageEmbedder {
    private val lock = Any()
    private var closed = false
    private val input = FloatArray(3 * SiglipPreprocess.INPUT_SIZE * SiglipPreprocess.INPUT_SIZE)

    override val modelVersion: String get() = SiglipModel.MODEL_VERSION
    override val dimension: Int get() = SiglipModel.DIMENSION

    /**
     * Embeds one bitmap whose pixels are already upright (EXIF orientation applied, as
     * [decodeOriented] does). Any size and config that [Bitmap.getPixels] can read.
     */
    override fun embed(bitmap: Bitmap): FloatArray {
        check(!bitmap.isRecycled) { "Image already released" }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return embedArgb(pixels, w, h)
    }

    /** Embeds one upright ARGB frame (row-major, [width]×[height]). */
    fun embedArgb(argb: IntArray, width: Int, height: Int): FloatArray = synchronized(lock) {
        check(!closed) { "Image embedder closed" }
        SiglipPreprocess.preprocessInto(argb, width, height, input)
        requireUnit(run(input))
    }

    /** Decodes [source] upright ([decodeOriented]) and embeds it. */
    fun embed(source: ImageDecoder.Source): FloatArray {
        val bitmap = decodeOriented(source)
        try { return embed(bitmap) } finally { bitmap.recycle() }
    }

    private fun run(pixels: FloatArray): FloatArray {
        val size = SiglipPreprocess.INPUT_SIZE.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, size, size)).use { tensor ->
            session.run(mapOf(SiglipModel.INPUT_NAME to tensor)).use { result ->
                val value = result.get(SiglipModel.OUTPUT_NAME)
                    .orElseThrow { IllegalStateException("Embedder output ${SiglipModel.OUTPUT_NAME} missing") }
                val buffer = (value as OnnxTensor).floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { session.close() } finally { options.close() }
        }
    }

    companion object {
        /** Sub-directory of [Context.getNoBackupFilesDir] that holds the joined model. */
        const val MODEL_DIR = "models/siglip2"

        /**
         * Deletes the joined model copy (about 103 MB) from no-backup storage, for the
         * visual index opt-out. The bundled asset parts stay; a later [open] re-joins them.
         * Callers close every open embedder first. Returns true when nothing is left.
         */
        fun deleteModelCopy(context: Context): Boolean = deleteModelCopy(File(context.noBackupFilesDir, MODEL_DIR))

        fun deleteModelCopy(dir: File): Boolean = SiglipModelFile.delete(dir)

        /**
         * Joins and verifies the bundled model on first use (about 100 MB written once to
         * no-backup storage; every later open re-hashes it), then opens an ORT session.
         * Throws [SiglipModelIntegrityFailure] when the bundled bytes do not match the pin.
         */
        fun open(context: Context, threads: Int = 2): SiglipImageEmbedder {
            val file = materializeFromAssets(context.assets, File(context.noBackupFilesDir, MODEL_DIR))
            return fromModelFile(file, threads)
        }

        fun materializeFromAssets(assets: AssetManager, dir: File): File =
            SiglipModelFile.materialize(dir, SiglipModel.PART_ASSETS.map { path -> { assets.open(path) } })

        /** Opens a session on an already verified model file. */
        fun fromModelFile(file: File, threads: Int = 2): SiglipImageEmbedder {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            }
            val session = try { env.createSession(file.absolutePath, options) }
                catch (error: Throwable) { options.close(); throw error }
            try {
                check(session.inputNames == setOf(SiglipModel.INPUT_NAME)) { "Unexpected embedder inputs ${session.inputNames}" }
                check(SiglipModel.OUTPUT_NAME in session.outputNames) { "Unexpected embedder outputs ${session.outputNames}" }
            } catch (error: Throwable) {
                try { session.close() } finally { options.close() }
                throw error
            }
            return SiglipImageEmbedder(env, session, options)
        }

        /**
         * Decodes an image upright: [ImageDecoder] applies the EXIF orientation. Large images
         * are subsampled by a power of two while both sides stay at least [MIN_DECODED_SIDE]
         * pixels, so a 50 MP photo never needs a 200 MB bitmap; the antialiased resize to 224
         * hides the difference. Software ARGB_8888, so [Bitmap.getPixels] works.
         */
        fun decodeOriented(source: ImageDecoder.Source): Bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height))
            }

        const val MIN_DECODED_SIDE = 896

        /** Largest power of two that keeps both sides at or above [MIN_DECODED_SIDE]. */
        fun sampleSizeFor(width: Int, height: Int): Int {
            var n = 1
            while (width / (n * 2) >= MIN_DECODED_SIDE && height / (n * 2) >= MIN_DECODED_SIDE) n *= 2
            return n
        }

        /** Checks the model output: right size, finite, unit length. Returns a renormalised copy. */
        fun requireUnit(raw: FloatArray): FloatArray {
            require(raw.size == SiglipModel.DIMENSION) { "Embedder output has ${raw.size} values, expected ${SiglipModel.DIMENSION}" }
            var sum = 0.0
            for (v in raw) {
                require(v.isFinite()) { "Embedder output contains a non-finite value" }
                sum += v.toDouble() * v
            }
            val norm = sqrt(sum)
            require(norm > 0.5 && norm < 1.5) { "Embedder output is not L2-normalised (norm $norm)" }
            return FloatArray(raw.size) { (raw[it] / norm).toFloat() }
        }
    }
}
