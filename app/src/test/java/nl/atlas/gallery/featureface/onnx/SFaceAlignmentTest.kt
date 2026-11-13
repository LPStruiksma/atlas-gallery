package nl.atlas.gallery.featureface.onnx

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SFaceAlignmentTest {
    private val size = SFaceModel.INPUT_SIZE
    private val plane = size * size

    /** Landmarks near the right edge of a 200x150 frame, so part of the crop is padding. */
    private val edgeLandmarks = floatArrayOf(150f, 60f, 190f, 55f, 172f, 85f, 155f, 110f, 188f, 106f)

    /** A sharp, deterministic test pattern (no photo): R, G, B as functions of x, y. */
    private fun pattern(width: Int, height: Int) = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val r = (x * 7 + y * 13) % 256
        val g = (x * 3 + y * 5) % 256
        val b = (x xor y) and 255
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun matrixMatchesOpenCvUmeyamaResult() {
        // OpenCV 4.10 getSimilarityTransformMatrix (SVD) for edgeLandmarks, computed offline.
        val expected = doubleArrayOf(
            8.26359595e-01, -4.82527174e-02, -8.12666647e+01,
            4.82527174e-02, 8.26359595e-01, -5.10353300e+00
        )
        val m = SFaceAlignment.similarityTransform(edgeLandmarks)
        expected.indices.forEach { assertEquals("m[$it]", expected[it], m[it], 1e-5) }
    }

    @Test
    fun templateLandmarksMapToThemselves() {
        val m = SFaceAlignment.similarityTransform(SFaceAlignment.TEMPLATE)
        // OpenCV uses a rounded template mean, so the identity is exact to ~1e-4.
        assertEquals(1.0, m[0], 1e-6); assertEquals(0.0, m[1], 1e-6)
        assertEquals(0.0, m[2], 2e-4); assertEquals(0.0, m[5], 2e-4)
    }

    @Test
    fun rotatedScaledShiftedTemplateIsUndone() {
        val angle = 0.6
        val scale = 2.7
        val lm = FloatArray(10) { 0f }
        for (i in 0 until 5) {
            val x = SFaceAlignment.TEMPLATE[2 * i].toDouble()
            val y = SFaceAlignment.TEMPLATE[2 * i + 1].toDouble()
            lm[2 * i] = (scale * (cos(angle) * x - sin(angle) * y) + 300).toFloat()
            lm[2 * i + 1] = (scale * (sin(angle) * x + cos(angle) * y) + 40).toFloat()
        }
        val m = SFaceAlignment.similarityTransform(lm)
        for (i in 0 until 5) {
            val x = m[0] * lm[2 * i] + m[1] * lm[2 * i + 1] + m[2]
            val y = m[3] * lm[2 * i] + m[4] * lm[2 * i + 1] + m[5]
            assertEquals(SFaceAlignment.TEMPLATE[2 * i].toDouble(), x, 1e-3)
            assertEquals(SFaceAlignment.TEMPLATE[2 * i + 1].toDouble(), y, 1e-3)
        }
        val inv = SFaceAlignment.invert(m)
        assertEquals(scale * cos(angle), inv[0], 1e-4)
    }

    @Test
    fun degenerateLandmarksAreRefused() {
        refused(FloatArray(10) { 42f })
        refused(FloatArray(10) { if (it == 3) Float.NaN else it.toFloat() })
        refused(FloatArray(8))
    }

    @Test
    fun warpMatchesOpenCvAlignCropIncludingBlackPadding() {
        val width = 200
        val height = 150
        val m = SFaceAlignment.similarityTransform(edgeLandmarks)
        val window = checkNotNull(SFaceAlignment.sourceWindow(m, width, height))
        assertTrue(window.left + window.width <= width && window.top + window.height <= height)
        val frame = pattern(width, height)
        val pixels = IntArray(window.width * window.height) { i ->
            frame[(window.top + i / window.width) * width + window.left + i % window.width]
        }
        val crop = SFaceAlignment.warpToRgbPlanar(pixels, window, m)
        // OpenCV 4.10 FaceRecognizerSF.alignCrop on the same pattern, converted to RGB (offline).
        val samples = mapOf(
            (0 to 0) to intArrayOf(182, 41, 98),
            (56 to 72) to intArrayOf(232, 161, 250),
            (20 to 30) to intArrayOf(50, 38, 93),
            (100 to 20) to intArrayOf(0, 0, 0),
            (111 to 111) to intArrayOf(0, 0, 0),
            (80 to 100) to intArrayOf(0, 0, 0),
            (38 to 52) to intArrayOf(29, 234, 169),
            (73 to 51) to intArrayOf(21, 86, 135)
        )
        for ((xy, rgb) in samples) {
            val i = xy.second * size + xy.first
            for (c in 0 until 3) assertEquals("pixel $xy channel $c", rgb[c].toFloat(), crop[c * plane + i], 1f)
        }
        // OpenCV leaves 3568 crop pixels fully black (outside the frame); the port matches to a pixel or two.
        val black = (0 until plane).count { crop[it] == 0f && crop[plane + it] == 0f && crop[2 * plane + it] == 0f }
        assertTrue("black=$black", abs(black - 3568) <= 4)
    }

    @Test
    fun cropFullyOutsideTheFrameIsBlack() {
        val lm = floatArrayOf(1050f, 1050f, 1090f, 1050f, 1070f, 1075f, 1055f, 1100f, 1085f, 1100f)
        val m = SFaceAlignment.similarityTransform(lm)
        assertNull(SFaceAlignment.sourceWindow(m, 200, 150))
        val crop = SFaceAlignment.warpToRgbPlanar(IntArray(0), null, m)
        assertTrue(crop.all { it == 0f })
    }

    @Test
    fun flatColourStaysFlatInsideTheFrame() {
        val width = 400
        val height = 400
        val lm = floatArrayOf(170f, 180f, 230f, 180f, 200f, 215f, 175f, 245f, 225f, 245f)
        val m = SFaceAlignment.similarityTransform(lm)
        val window = checkNotNull(SFaceAlignment.sourceWindow(m, width, height))
        val argb = (0xFF shl 24) or (10 shl 16) or (120 shl 8) or 250
        val crop = SFaceAlignment.warpToRgbPlanar(IntArray(window.width * window.height) { argb }, window, m)
        for (i in 0 until plane) {
            assertEquals(10f, crop[i], 0f)
            assertEquals(120f, crop[plane + i], 0f)
            assertEquals(250f, crop[2 * plane + i], 0f)
        }
    }

    @Test
    fun windowCountMismatchIsRefused() {
        val m = SFaceAlignment.similarityTransform(edgeLandmarks)
        val window = checkNotNull(SFaceAlignment.sourceWindow(m, 200, 150))
        try {
            SFaceAlignment.warpToRgbPlanar(IntArray(window.width * window.height - 1), window, m)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun refused(lm: FloatArray) {
        try {
            SFaceAlignment.similarityTransform(lm)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
