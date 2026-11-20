package nl.atlas.gallery.featureidentity.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/*
 * IDS-17 (#516): InsightFace `w600k_r50` (ArcFace R50) as the identity encoder that
 * feeds HyperSwap's `source` input, allowed by owner decision OD-1. The file comes from
 * [IdentityModelManifest.INSIGHTFACE_W600K_R50] (extracted from InsightFace's own
 * buffalo_l release) through [IdentityModelStore].
 *
 * Bench parity (tools/identity_bench/pipeline.py, `Embedder.embed` / `embed_crop`):
 * references are aligned to the 112 px ArcFace template (`arcface_112_v2`, which is
 * [FaceAlignWarp.ARCFACE_112]) by the engine, then fed as RGB planes x / 127.5 - 1 into
 * a [1,3,112,112] input. The raw 512-d output is returned unnormalised: the engine
 * averages the raw vectors and normalises once ([WeakSwap.meanUnit]), which is what the
 * bench does (`np.mean` of raw embeddings, then `Swapper.source_vector`).
 */
object ArcFacePreprocess {
    const val CROP = 112

    /** ARGB 112 x 112 crop to the NCHW float input, RGB, x / 127.5 - 1. */
    fun toTensor(alignedArgb: IntArray): FloatArray {
        val plane = CROP * CROP
        require(alignedArgb.size == plane) { "ArcFace takes a 112 px crop" }
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = alignedArgb[i]
            out[i] = ((p shr 16) and 255) / 127.5f - 1f
            out[plane + i] = ((p shr 8) and 255) / 127.5f - 1f
            out[2 * plane + i] = (p and 255) / 127.5f - 1f
        }
        return out
    }
}

/**
 * ONNX Runtime session over the installed w600k_r50 file. The input and output are taken
 * by position, because the InsightFace file names them `input.1` and `683` while
 * FaceFusion's copy of the same network (the bench's) names them `input` and `output`.
 */
class ArcFaceEmbedder(modelFile: File, intraOpThreads: Int = 2) : IdentityEncoder {
    override val cropSize: Int = ArcFacePreprocess.CROP
    override val template: FloatArray = FaceAlignWarp.ARCFACE_112.copyOf()

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(intraOpThreads) }
    private val session: OrtSession = try {
        env.createSession(modelFile.absolutePath, options)
    } catch (t: Throwable) { options.close(); throw t }
    private val inputName: String = session.inputNames.first()

    override fun encode(alignedArgb: IntArray): FloatArray {
        val input = ArcFacePreprocess.toTensor(alignedArgb)
        val shape = longArrayOf(1, 3, cropSize.toLong(), cropSize.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val value = result.get(0) as OnnxTensor
                val buffer = value.floatBuffer
                val vector = FloatArray(buffer.remaining()).also { buffer.get(it) }
                require(vector.size == HyperSwapNetwork.IDENTITY_DIM) { "Identity encoder gave ${vector.size} values" }
                return vector
            }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }
}
