package nl.atlas.gallery.featureidentity.local

import nl.atlas.gallery.featureface.onnx.SFaceAlignment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** IDS-9 (#475): alignment maths on synthetic points and pixels. */
class FaceAlignWarpTest {
    private val t256 = FaceAlignWarp.arcfaceTemplate(256)

    /** The template moved by a known similarity: rotation [deg], scale [s], shift. */
    private fun transformed(template: FloatArray, deg: Double, s: Double, dx: Double, dy: Double): FloatArray {
        val c = cos(Math.toRadians(deg)) * s; val n = sin(Math.toRadians(deg)) * s
        return FloatArray(10) { i ->
            val x = template[i - i % 2].toDouble(); val y = template[i - i % 2 + 1].toDouble()
            (if (i % 2 == 0) c * x - n * y + dx else n * x + c * y + dy).toFloat()
        }
    }

    @Test fun recoversAKnownSimilarityExactly() {
        val photo = transformed(t256, 17.0, 3.2, 1200.0, 800.0)
        val m = FaceAlignWarp.similarity(photo, t256)
        for (i in 0 until 5) {
            assertEquals(t256[2 * i].toDouble(), m.mapX(photo[2 * i].toDouble(), photo[2 * i + 1].toDouble()), 1e-3)
            assertEquals(t256[2 * i + 1].toDouble(), m.mapY(photo[2 * i].toDouble(), photo[2 * i + 1].toDouble()), 1e-3)
        }
        assertEquals(1 / 3.2, m.scale, 1e-6)
        val back = m.inverse()
        assertEquals(1200.0, back.mapX(0.0, 0.0), 1e-3)
    }

    @Test fun matchesTheShippedSFaceAlignmentAt112() {
        val photo = floatArrayOf(410f, 520f, 520f, 515f, 465f, 580f, 425f, 640f, 515f, 636f)
        val mine = FaceAlignWarp.similarity(photo, FaceAlignWarp.ARCFACE_112).m
        val sface = SFaceAlignment.similarityTransform(photo)
        // SFace uses OpenCV's hard-coded template mean; the two differ by under 0.01 px.
        for (i in listOf(0, 1, 3, 4)) assertEquals(sface[i], mine[i], 1e-4)
        for (i in listOf(2, 5)) assertEquals(sface[i], mine[i], 0.01)
    }

    @Test fun refusesDegenerateLandmarks() {
        assertThrows(IllegalArgumentException::class.java) { FaceAlignWarp.similarity(FloatArray(10) { 5f }, t256) }
        assertThrows(IllegalArgumentException::class.java) { FaceAlignWarp.similarity(FloatArray(8), t256) }
        assertThrows(IllegalArgumentException::class.java) {
            FaceAlignWarp.similarity(FloatArray(10) { if (it == 3) Float.NaN else it.toFloat() }, t256)
        }
    }

    @Test fun sourceRectCoversTheCropAndClipsToTheImage() {
        val m = FaceAlignWarp.similarity(transformed(t256, 0.0, 2.0, 100.0, 50.0), t256)
        val rect = FaceAlignWarp.sourceRect(m, 256, 4000, 3000)!!
        assertTrue(rect.left in 99..100 && rect.top in 49..50)
        assertTrue(rect.right >= 100 + 512 && rect.bottom >= 50 + 512)
        val clipped = FaceAlignWarp.sourceRect(m, 256, 300, 200)!!
        assertEquals(300, clipped.right); assertEquals(200, clipped.bottom)
        assertNull(FaceAlignWarp.sourceRect(m, 256, 80, 40))
    }

    @Test fun identityWarpCopiesPixels() {
        val size = 64
        val template = FaceAlignWarp.arcfaceTemplate(size)
        val rect = PixelRect(0, 0, size, size)
        val pixels = IntArray(size * size) { (0xFF shl 24) or ((it * 7919) and 0xFFFFFF) }
        val m = FaceAlignWarp.similarity(template, template)
        assertArrayEquals(pixels, FaceAlignWarp.warpToCrop(pixels, rect, m, size))
    }

    @Test fun halfScaleWarpAveragesNeighbours() {
        // A 2x larger photo of a flat colour warps to the same flat colour.
        val size = 64
        val template = FaceAlignWarp.arcfaceTemplate(size)
        val photo = FloatArray(10) { template[it] * 2 }
        val rect = PixelRect(0, 0, 2 * size, 2 * size)
        val grey = (0xFF shl 24) or 0x808080
        val crop = FaceAlignWarp.warpToCrop(IntArray(4 * size * size) { grey }, rect, FaceAlignWarp.similarity(photo, template), size)
        assertTrue(crop.all { abs((it and 255) - 0x80) <= 1 })
    }
}
