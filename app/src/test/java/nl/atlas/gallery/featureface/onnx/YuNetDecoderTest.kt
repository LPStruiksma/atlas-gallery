package nl.atlas.gallery.featureface.onnx

import kotlin.math.ln
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic tensors only: each test plants scores and offsets at chosen anchors. */
class YuNetDecoderTest {
    private val inputSize = YuNetModel.INPUT_SIZE

    private fun emptyOutputs(): MutableMap<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        for (s in YuNetModel.STRIDES) {
            val n = (inputSize / s) * (inputSize / s)
            out["cls_$s"] = FloatArray(n); out["obj_$s"] = FloatArray(n)
            out["bbox_$s"] = FloatArray(n * 4); out["kps_$s"] = FloatArray(n * 10)
        }
        return out
    }

    /** Plants a face centred at anchor (row, col) + (dx, dy), size (w, h) in input pixels. */
    private fun MutableMap<String, FloatArray>.plant(
        stride: Int, row: Int, col: Int, cls: Float, obj: Float,
        dx: Float = 0.5f, dy: Float = 0.5f, w: Float = 64f, h: Float = 80f
    ) {
        val i = row * (inputSize / stride) + col
        getValue("cls_$stride")[i] = cls
        getValue("obj_$stride")[i] = obj
        val b = getValue("bbox_$stride")
        b[i * 4] = dx; b[i * 4 + 1] = dy
        b[i * 4 + 2] = ln(w / stride); b[i * 4 + 3] = ln(h / stride)
        val k = getValue("kps_$stride")
        for (p in 0 until 5) { k[i * 10 + 2 * p] = p * 0.25f; k[i * 10 + 2 * p + 1] = 0.1f * p }
    }

    @Test
    fun blankOutputsGiveNoFaces() {
        assertTrue(YuNetDecoder.decode(emptyOutputs()).isEmpty())
    }

    @Test
    fun decodesBoxScoreAndLandmarksLikeOpenCv() {
        val out = emptyOutputs().apply { plant(16, row = 10, col = 20, cls = 0.95f, obj = 0.99f) }
        val face = YuNetDecoder.decode(out).single()
        val cx = (20 + 0.5f) * 16; val cy = (10 + 0.5f) * 16
        assertEquals(cx - 32f, face.left, 1e-3f)
        assertEquals(cy - 40f, face.top, 1e-3f)
        assertEquals(64f, face.width, 1e-3f)
        assertEquals(80f, face.height, 1e-3f)
        assertEquals(sqrt(0.95f * 0.99f), face.score, 1e-6f)
        assertEquals((0.25f + 20) * 16, face.landmarks[2], 1e-3f)
        assertEquals((0.1f + 10) * 16, face.landmarks[3], 1e-3f)
    }

    @Test
    fun scoresBelowThresholdAndNaNAreDropped() {
        val out = emptyOutputs().apply {
            plant(8, 5, 5, cls = 0.85f, obj = 0.9f) // sqrt = 0.874 < 0.9
            plant(32, 3, 3, cls = Float.NaN, obj = 1f)
        }
        assertTrue(YuNetDecoder.decode(out).isEmpty())
        assertEquals(1, YuNetDecoder.decode(out, scoreThreshold = 0.8f).size)
    }

    @Test
    fun overlappingDetectionsAcrossStridesKeepTheBestOnly() {
        val out = emptyOutputs().apply {
            plant(8, 20, 20, cls = 0.92f, obj = 0.92f)          // centre (164, 164)
            plant(16, 10, 10, cls = 0.99f, obj = 0.99f)         // centre (168, 168): IoU > 0.3
            plant(32, 15, 15, cls = 0.95f, obj = 0.95f)         // centre (496, 496): separate face
        }
        val faces = YuNetDecoder.decode(out)
        assertEquals(2, faces.size)
        assertEquals(0.99f, faces[0].score, 1e-5f)
        assertEquals(0.95f, faces[1].score, 1e-5f)
    }

    @Test
    fun resultIsCappedAtTheContractFaceLimit() {
        val out = emptyOutputs()
        for (r in 0 until 6) for (c in 0 until 8) out.plant(32, r * 3, c * 2 + 1, 0.95f, 0.95f, w = 32f, h = 32f)
        val faces = YuNetDecoder.decode(out)
        assertEquals(32, faces.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingOutputFailsClosed() {
        YuNetDecoder.decode(emptyOutputs().apply { remove("kps_32") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongOutputSizeFailsClosed() {
        YuNetDecoder.decode(emptyOutputs().apply { put("cls_8", FloatArray(10)) })
    }
}
