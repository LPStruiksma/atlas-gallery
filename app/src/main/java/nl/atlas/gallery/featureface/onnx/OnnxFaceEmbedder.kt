package nl.atlas.gallery.featureface.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.FloatBuffer
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding

/**
 * One on-device SFace embedding: 128 floats, L2-normalised, tagged with the embedder version.
 * The vector is copied on construction and on read. Not an identity on its own.
 */
class SFaceEmbedding(vector: FloatArray, val embedderVersion: String) {
    private val unit: FloatArray = SFaceModel.l2Normalize(vector)

    val vector: FloatArray get() = unit.copyOf()

    /** Cosine similarity in [-1, 1]; both sides are unit vectors from the same model version. */
    fun cosine(other: SFaceEmbedding): Float {
        require(other.embedderVersion == embedderVersion) { "Embedder versions differ" }
        var sum = 0.0
        for (i in unit.indices) sum += unit[i].toDouble() * other.unit[i]
        return sum.toFloat()
    }

    /** Hands the vector to D2-3 clustering for one stored observation. */
    fun toClusterEmbedding(observation: NativeFaceObservationId): FaceEmbedding =
        FaceEmbedding(observation, embedderVersion, unit)
}

/**
 * On-device SFace face embedder on ONNX Runtime (CPU only, no NNAPI, no network).
 * Takes [OnnxFaceDetector.detectFaces] output (frame coordinates, five landmarks), aligns
 * each face to the 112x112 template ([SFaceAlignment], which documents the padding rules)
 * and returns L2-normalised 128-d vectors tagged with [SFaceModel.EMBEDDER_VERSION].
 *
 * [embed] is blocking and serialised; call it from a worker thread with the same bitmap the
 * detector saw. The caller keeps ownership of the bitmap, which is only read. Pixels and
 * vectors stay in the process; persisting them on the device is the caller's job.
 */
class OnnxFaceEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val options: OrtSession.SessionOptions
) : Closeable {
    private val lock = Any()
    private var closed = false
    private val input = FloatArray(3 * SFaceModel.INPUT_SIZE * SFaceModel.INPUT_SIZE)

    val embedderVersion: String get() = SFaceModel.EMBEDDER_VERSION

    /** Embeds one detected face. Throws [IllegalArgumentException] for degenerate landmarks. */
    fun embed(bitmap: Bitmap, face: YuNetFace): SFaceEmbedding = synchronized(lock) {
        check(!closed) { "Face embedder closed" }
        check(!bitmap.isRecycled) { "Face frame already released" }
        val m = SFaceAlignment.similarityTransform(face.landmarks)
        val window = SFaceAlignment.sourceWindow(m, bitmap.width, bitmap.height)
        val pixels = if (window == null) IntArray(0) else IntArray(window.width * window.height).also {
            bitmap.getPixels(it, 0, window.width, window.left, window.top, window.width, window.height)
        }
        SFaceAlignment.warpToRgbPlanar(pixels, window, m, input)
        SFaceEmbedding(run(input), SFaceModel.EMBEDDER_VERSION)
    }

    /** Embeds every face of one frame, in order; a face with degenerate landmarks maps to null. */
    fun embedAll(bitmap: Bitmap, faces: List<YuNetFace>): List<SFaceEmbedding?> =
        faces.map { face ->
            try { embed(bitmap, face) } catch (_: IllegalArgumentException) { null }
        }

    private fun run(pixels: FloatArray): FloatArray {
        val size = SFaceModel.INPUT_SIZE.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, size, size)).use { tensor ->
            session.run(mapOf(SFaceModel.INPUT_NAME to tensor)).use { result ->
                val value = result.get(SFaceModel.OUTPUT_NAME)
                    .orElseThrow { IllegalStateException("SFace output ${SFaceModel.OUTPUT_NAME} missing") }
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
        /** Reads, verifies and loads the bundled model. Throws [FaceModelIntegrityFailure] on a pin mismatch. */
        fun fromAssets(
            assets: AssetManager, intraOpThreads: Int = FaceOnnxThreads.intraOp
        ): OnnxFaceEmbedder {
            val bytes = assets.open(SFaceModel.ASSET_PATH).use { it.readBytes() }
            return fromModelBytes(bytes, intraOpThreads)
        }

        fun fromModelBytes(
            bytes: ByteArray, intraOpThreads: Int = FaceOnnxThreads.intraOp
        ): OnnxFaceEmbedder {
            require(intraOpThreads in 1..8)
            SFaceModel.requirePinned(bytes)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOpThreads)
                // The 2021dec export lists its weights as graph inputs; ORT warns once per
                // initializer. Errors only, so the log stays readable.
                setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            }
            val session = try { env.createSession(bytes, options) }
                catch (error: Throwable) { options.close(); throw error }
            try {
                check(session.inputNames == setOf(SFaceModel.INPUT_NAME)) { "Unexpected SFace inputs ${session.inputNames}" }
                check(SFaceModel.OUTPUT_NAME in session.outputNames) { "Unexpected SFace outputs ${session.outputNames}" }
            } catch (error: Throwable) {
                try { session.close() } finally { options.close() }
                throw error
            }
            return OnnxFaceEmbedder(env, session, options)
        }
    }
}
