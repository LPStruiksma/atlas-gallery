package nl.atlas.gallery.featurevisual.pose

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each pre- and post-processing step of [BlazePoseMath] against `tools/visual_search_bench/blazepose.py`
 * (OpenCV 5.0, NumPy) on synthetic arrays and formula images; expected values in [BlazePoseGolden].
 */
class BlazePoseMathTest {
    /** Same formula as `pose_golden.argb_formula`. */
    private fun formulaImage(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        (0xFF shl 24) or (((x * 37 + y * 11) % 256) shl 16) or (((x * x + 3 * y) % 256) shl 8) or ((x * y * 5 + 17) % 256)
    }

    @Test
    fun anchorsMatchSsdAnchors() {
        val a = BlazePoseMath.anchors
        assertEquals(BlazePoseGolden.ANCHOR_COUNT, BlazePoseMath.ANCHORS)
        assertEquals(2 * BlazePoseGolden.ANCHOR_COUNT, a.size)
        BlazePoseGolden.anchorIndices.forEachIndexed { k, i ->
            assertEquals(BlazePoseGolden.anchorValues[2 * k], a[2 * i], 1e-7f)
            assertEquals(BlazePoseGolden.anchorValues[2 * k + 1], a[2 * i + 1], 1e-7f)
        }
    }

    @Test
    fun letterboxMatchesOpenCvResize() {
        val out = FloatArray(3 * BlazePoseMath.DET_SIZE * BlazePoseMath.DET_SIZE)
        for (g in BlazePoseGolden.letterboxes) {
            val box = BlazePoseMath.letterboxInto(formulaImage(g.width, g.height), g.width, g.height, out)
            assertEquals("left ${g.width}x${g.height}", g.left, box.left)
            assertEquals("top ${g.width}x${g.height}", g.top, box.top)
            // OpenCV's float resize rounds its tap weights slightly differently (seen: 2e-5 when
            // downscaling); 1e-4 is under 1/75 of one 8-bit input step (1 / 127.5).
            g.sampleIndices.forEachIndexed { k, i ->
                assertEquals("${g.width}x${g.height} value $i", g.samples[k], out[i], 1e-4f)
            }
            assertEquals("${g.width}x${g.height} sum", g.sum, out.sumOf { it.toDouble() }, 0.05)
        }
    }

    @Test
    fun bestDetectionMatchesPythonDetect() {
        val reg = FloatArray(BlazePoseMath.ANCHORS * 12) { k -> (((k / 12) * 7 + (k % 12) * 13) % 97 - 48).toFloat() * 0.5f }
        val cls = FloatArray(BlazePoseMath.ANCHORS) { i -> ((i * 31) % 211 - 150).toFloat() / 10f }
        val canvas = FloatArray(3 * BlazePoseMath.DET_SIZE * BlazePoseMath.DET_SIZE)
        for (g in BlazePoseGolden.detections) {
            val box = BlazePoseMath.letterboxInto(IntArray(g.width * g.height), g.width, g.height, canvas)
            val d = BlazePoseMath.decodeBest(reg, cls, box, 0.5f)
            assertNotNull(d)
            assertEquals(g.score, d!!.score, 1e-6f)
            assertArrayEquals(g.point, floatArrayOf(d.hipX, d.hipY, d.fullX, d.fullY), 1e-3f)
        }
        assertNull("no score above threshold", BlazePoseMath.decodeBest(reg, FloatArray(BlazePoseMath.ANCHORS) { -3f },
            BlazePoseMath.letterboxInto(IntArray(4), 2, 2, canvas), 0.5f))
    }

    @Test
    fun roiMatchesOpenCvRotationAndScale() {
        val p = BlazePoseGolden.roiHipFull
        val roi = BlazePoseMath.roi(PersonDetection(0.9f, p[0], p[1], p[2], p[3]))!!
        assertEquals(BlazePoseGolden.ROI_DEGREES, roi.degrees, 1e-4)
        for (i in 0 until 6) assertEquals("forward[$i]", BlazePoseGolden.roiForward[i], roi.forward[i], 1e-3)
        assertNull("radius under 4 px", BlazePoseMath.roi(PersonDetection(0.9f, 10f, 10f, 12f, 12f)))
    }

    @Test
    fun cropMatchesOpenCvWarpAffine() {
        val w = BlazePoseGolden.ROI_WIDTH
        val h = BlazePoseGolden.ROI_HEIGHT
        val out = FloatArray(BlazePoseMath.POSE_SIZE * BlazePoseMath.POSE_SIZE * 3)
        BlazePoseMath.warpCropInto(formulaImage(w, h), w, h, BlazePoseGolden.roiForward, out)
        val hex = BlazePoseGolden.CROP_HEX
        var exact = 0
        var worst = 0
        val n = hex.length / 2
        for (k in 0 until n) {
            val want = hex.substring(2 * k, 2 * k + 2).toInt(16)
            val got = Math.round(out[k * BlazePoseGolden.CROP_STEP] * 255f)
            val diff = abs(want - got)
            if (diff == 0) exact++
            if (diff > worst) worst = diff
        }
        // OpenCV samples at 1/32-pixel fixed point; this port samples in floating point.
        assertTrue("max byte difference $worst", worst <= 2)
        assertTrue("exact bytes $exact of $n", exact >= n * 0.95)
    }

    @Test
    fun unrotateMatchesPython() {
        val raw = FloatArray(117) { i -> ((i * 17 % 23) - 11).toFloat() / 7f }
        assertArrayEquals(BlazePoseGolden.unrotated, BlazePoseMath.unrotate(raw, BlazePoseGolden.ROI_DEGREES), 1e-5f)
    }

    @Test
    fun invertAffineRoundTrips() {
        val f = BlazePoseGolden.roiForward
        val inv = BlazePoseMath.invertAffine(f)
        val x = 37.5; val y = -12.25
        val u = f[0] * x + f[1] * y + f[2]; val v = f[3] * x + f[4] * y + f[5]
        assertEquals(x, inv[0] * u + inv[1] * v + inv[2], 1e-9)
        assertEquals(y, inv[3] * u + inv[4] * v + inv[5], 1e-9)
    }
}
