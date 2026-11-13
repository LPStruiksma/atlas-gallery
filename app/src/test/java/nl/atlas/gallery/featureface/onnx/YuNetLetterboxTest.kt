package nl.atlas.gallery.featureface.onnx

import nl.atlas.gallery.featureface.FacePixelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YuNetLetterboxTest {
    @Test
    fun landscapeFrameFitsLongEdge() {
        val box = YuNetLetterbox(960, 720)
        assertEquals(640f / 960f, box.scale, 1e-6f)
        assertEquals(640, box.scaledWidth)
        assertEquals(480, box.scaledHeight)
    }

    @Test
    fun portraitAndSmallFramesScaleToo() {
        val portrait = YuNetLetterbox(540, 960)
        assertEquals(360, portrait.scaledWidth); assertEquals(640, portrait.scaledHeight)
        val small = YuNetLetterbox(320, 200)
        assertEquals(640, small.scaledWidth); assertEquals(400, small.scaledHeight)
    }

    @Test
    fun packsBgrPlanesTopLeftAndZeroPadsTheRest() {
        val box = YuNetLetterbox(2, 1, inputSize = 4) // scaled to 4 x 2
        val red = 0xFFFF0000.toInt(); val green = 0xFF00FF00.toInt(); val blue = 0xFF0000FF.toInt()
        val pixels = intArrayOf(red, green, blue, 0xFF102030.toInt(), red, red, red, red)
        val t = box.packBgrPlanar(pixels)
        val plane = 16
        // pixel (0,0) red: B=0 G=0 R=255
        assertEquals(0f, t[0]); assertEquals(0f, t[plane]); assertEquals(255f, t[2 * plane])
        // pixel (2,0) blue: B=255
        assertEquals(255f, t[2])
        // pixel (3,0) 0x102030: B=0x30 G=0x20 R=0x10
        assertEquals(48f, t[3]); assertEquals(32f, t[plane + 3]); assertEquals(16f, t[2 * plane + 3])
        // rows 2..3 are padding
        for (i in 8 until 16) { assertEquals(0f, t[i]); assertEquals(0f, t[plane + i]); assertEquals(0f, t[2 * plane + i]) }
    }

    @Test
    fun mapsBoxesBackToFramePixelsAndClips() {
        val box = YuNetLetterbox(960, 720)
        val face = YuNetFace(100f, 50f, 64f, 80f, 0.95f, FloatArray(10) { 64f })
        assertEquals(FacePixelBounds(150, 75, 246, 195), box.toFrame(face))
        val edge = YuNetFace(-20f, 470f, 60f, 40f, 0.95f, FloatArray(10))
        assertEquals(FacePixelBounds(0, 705, 60, 720), box.toFrame(edge))
        val mapped = box.faceToFrame(face)
        assertEquals(96f, mapped.landmarks[0], 1e-3f)
    }

    @Test
    fun faceEntirelyInPaddingIsDropped() {
        val box = YuNetLetterbox(960, 720) // content ends at y = 480 in input pixels
        assertNull(box.toFrame(YuNetFace(10f, 500f, 40f, 40f, 0.95f, FloatArray(10))))
    }
}
