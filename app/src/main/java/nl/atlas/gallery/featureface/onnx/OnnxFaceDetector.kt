package nl.atlas.gallery.featureface.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import nl.atlas.gallery.featureface.AsyncFaceLocator
import nl.atlas.gallery.featureface.FaceDispatchNotStarted
import nl.atlas.gallery.featureface.FacePixelBounds

/**
 * On-device YuNet face detector on ONNX Runtime (CPU only, no NNAPI, no network).
 * Loads the SHA-256-pinned model from app assets and refuses anything else.
 *
 * [detect] is blocking and serialised; call it from a worker thread. The caller keeps
 * ownership of the input bitmap, which is only read. Plug it into the scan pipeline with
 * [asyncLocator], which satisfies P's [AsyncFaceLocator] contract.
 */
class OnnxFaceDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val options: OrtSession.SessionOptions,
    private val scoreThreshold: Float
) : Closeable {
    private val lock = Any()
    private var closed = false
    private val input = FloatArray(3 * YuNetModel.INPUT_SIZE * YuNetModel.INPUT_SIZE)

    val detectorVersion: String get() = YuNetModel.DETECTOR_VERSION

    /** Faces in [bitmap] pixel coordinates, with landmarks, highest score first, at most 32. */
    fun detectFaces(bitmap: Bitmap): List<YuNetFace> {
        val (box, faces) = detectOnCanvas(bitmap)
        return faces.map(box::faceToFrame)
    }

    /** Boxes only, clipped to the frame, for [nl.atlas.gallery.featureface.FaceScanEngine]. */
    fun detect(bitmap: Bitmap): List<FacePixelBounds> {
        val (box, faces) = detectOnCanvas(bitmap)
        return faces.mapNotNull(box::toFrame)
    }

    private fun detectOnCanvas(bitmap: Bitmap): Pair<YuNetLetterbox, List<YuNetFace>> = synchronized(lock) {
        check(!closed) { "Face detector closed" }
        check(!bitmap.isRecycled) { "Face frame already released" }
        val box = YuNetLetterbox(bitmap.width, bitmap.height)
        val scaled = if (bitmap.width == box.scaledWidth && bitmap.height == box.scaledHeight) bitmap
            else Bitmap.createScaledBitmap(bitmap, box.scaledWidth, box.scaledHeight, true)
        try {
            val pixels = IntArray(box.scaledWidth * box.scaledHeight)
            scaled.getPixels(pixels, 0, box.scaledWidth, 0, 0, box.scaledWidth, box.scaledHeight)
            box.packBgrPlanar(pixels, input)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        box to YuNetDecoder.decode(run(input), scoreThreshold = scoreThreshold)
    }

    /**
     * A single-flight locator for the scan engine. [executor] must be a dedicated
     * background executor; a rejected submission is reported as "not started", so the
     * bitmap lease is released at once.
     */
    fun asyncLocator(executor: Executor, timeoutMillis: Long = 15_000L): AsyncFaceLocator =
        AsyncFaceLocator(submit = { bitmap, callback ->
            try {
                executor.execute { callback(runCatching { detect(bitmap) }) }
            } catch (_: RejectedExecutionException) {
                throw FaceDispatchNotStarted()
            }
        }, timeoutMillis = timeoutMillis)

    private fun run(pixels: FloatArray): Map<String, FloatArray> {
        val shape = longArrayOf(1, 3, YuNetModel.INPUT_SIZE.toLong(), YuNetModel.INPUT_SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), shape).use { tensor ->
            session.run(mapOf(YuNetModel.INPUT_NAME to tensor)).use { result ->
                return YuNetModel.outputNames().associateWith { name ->
                    val value = result.get(name).orElseThrow { IllegalStateException("YuNet output $name missing") }
                    val buffer = (value as OnnxTensor).floatBuffer
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
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
            assets: AssetManager,
            scoreThreshold: Float = YuNetModel.DEFAULT_SCORE_THRESHOLD,
            intraOpThreads: Int = FaceOnnxThreads.intraOp
        ): OnnxFaceDetector {
            val bytes = assets.open(YuNetModel.ASSET_PATH).use { it.readBytes() }
            return fromModelBytes(bytes, scoreThreshold, intraOpThreads)
        }

        fun fromModelBytes(
            bytes: ByteArray,
            scoreThreshold: Float = YuNetModel.DEFAULT_SCORE_THRESHOLD,
            intraOpThreads: Int = FaceOnnxThreads.intraOp
        ): OnnxFaceDetector {
            require(scoreThreshold in 0f..1f)
            require(intraOpThreads in 1..8)
            YuNetModel.requirePinned(bytes)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(intraOpThreads) }
            val session = try { env.createSession(bytes, options) }
                catch (error: Throwable) { options.close(); throw error }
            try {
                check(session.inputNames == setOf(YuNetModel.INPUT_NAME)) { "Unexpected YuNet inputs ${session.inputNames}" }
                check(session.outputNames.containsAll(YuNetModel.outputNames())) { "Unexpected YuNet outputs ${session.outputNames}" }
            } catch (error: Throwable) {
                try { session.close() } finally { options.close() }
                throw error
            }
            return OnnxFaceDetector(env, session, options, scoreThreshold)
        }
    }
}
