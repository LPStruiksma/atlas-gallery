package nl.atlas.gallery.featureidentity.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.io.IOException
import java.nio.FloatBuffer

/*
 * IDS-18: optional offline MediaPipe multiclass ONNX adapter. The binary is
 * deliberately not present until hash, parity and Android proofs are complete.
 * Six NHWC classes: 0 background, 1 hair, 2 body, 3 face-skin, 4 clothes,
 * 5 other. Invalid/missing asset never requires a download.
 */
object MediaPipeMaskMath {
    const val SIZE = 256
    const val CLASSES = 6
    const val HAIR = 1
    const val FACE_SKIN = 3

    fun rgb(crop: IntArray, size: Int): FloatArray {
        require(size == SIZE && crop.size == SIZE * SIZE)
        val out = FloatArray(SIZE * SIZE * 3)
        for (i in crop.indices) {
            val pixel = crop[i]
            out[i * 3] = ((pixel ushr 16) and 255) / 255f
            out[i * 3 + 1] = ((pixel ushr 8) and 255) / 255f
            out[i * 3 + 2] = (pixel and 255) / 255f
        }
        return out
    }

    /** The same argmax class decision as the IDS-2 LiteRT bench. */
    fun classMask(logits: FloatArray, includeHair: Boolean): FloatArray {
        require(logits.size == SIZE * SIZE * CLASSES) { "Expected six NHWC classes" }
        return FloatArray(SIZE * SIZE) { pixel ->
            val offset = pixel * CLASSES
            var best = Float.NEGATIVE_INFINITY
            var winner = 0
            for (klass in 0 until CLASSES) {
                val score = logits[offset + klass]
                require(score.isFinite()) { "Non-finite segmentation output" }
                if (score > best) {
                    best = score
                    winner = klass
                }
            }
            if (winner == FACE_SKIN || (includeHair && winner == HAIR)) 1f else 0f
        }
    }
}

class MultiClassFaceSegmenter private constructor(
    private val session: OrtSession,
    private val options: OrtSession.SessionOptions
) : FaceSegmenter {
    private val env = OrtEnvironment.getEnvironment()
    private val input = session.inputNames.single()

    override fun faceSkin(cropArgb: IntArray, size: Int): FloatArray =
        MediaPipeMaskMath.classMask(infer(cropArgb, size), false)

    /** Future HEAD mode: HEAD is not exposed until separately accepted. */
    fun headSkinAndHair(cropArgb: IntArray, size: Int): FloatArray =
        MediaPipeMaskMath.classMask(infer(cropArgb, size), true)

    private fun infer(crop: IntArray, size: Int): FloatArray {
        val inputRgb = MediaPipeMaskMath.rgb(crop, size)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(inputRgb), longArrayOf(1, 256, 256, 3)).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                val tensorOut = result.get(0) as OnnxTensor
                val buffer = tensorOut.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                require(values.size == MediaPipeMaskMath.SIZE * MediaPipeMaskMath.SIZE * MediaPipeMaskMath.CLASSES) {
                    "Unexpected segmentation output layout"
                }
                return values
            }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        private const val ASSET = "models/mediapipe_multiclass/selfie_multiclass_256x256.onnx"

        /** Absent asset returns null so the engine records mask=fallback. */
        fun fromAssets(assets: AssetManager, intraOpThreads: Int = 2): MultiClassFaceSegmenter? {
            val bytes = try { assets.open(ASSET).use { it.readBytes() } }
                catch (_: IOException) { return null }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(intraOpThreads) }
            return try {
                val session = OrtEnvironment.getEnvironment().createSession(bytes, opts)
                if (session.inputNames.size != 1 || session.outputNames.size != 1) {
                    session.close()
                    opts.close()
                    null
                } else MultiClassFaceSegmenter(session, opts)
            } catch (_: Exception) {
                opts.close()
                null
            }
        }
    }
}
