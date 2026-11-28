package nl.atlas.gallery.featureidentity.head

import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.TargetFace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic geometry and pixels only. */
class HeadMaskComposerTest {
    private val w = 100
    private val h = 100
    private val face = FaceBox(40, 40, 60, 60)
    private val target = TargetFace(face, listOf(
        FacePoint(45f, 46f), FacePoint(55f, 46f), FacePoint(50f, 51f), FacePoint(46f, 55f), FacePoint(54f, 55f)
    ))
    private val source = IntArray(w * h) { i -> val v = (i % w + i / w) and 255; (0xff shl 24) or (v shl 16) or (v shl 8) or v }

    @Test fun headCropIsTheWholeHeadNotTheFaceBox() {
        val crop = HeadMaskComposer.headCrop(target, w, h)
        assertEquals(HeadCrop(31, 25, 38, 44), crop)
        assertTrue(crop.top < face.top && crop.left < face.left && crop.right > face.right && crop.bottom > face.bottom)
    }

    @Test fun headCropClampsToTheImage() {
        val edge = TargetFace(FaceBox(0, 0, 20, 20), List(5) { FacePoint(10f, 10f) })
        val crop = HeadMaskComposer.headCrop(edge, 24, 26)
        assertEquals(0, crop.left); assertEquals(0, crop.top)
        assertTrue(crop.right <= 24 && crop.bottom <= 26)
    }

    @Test fun priorMaskCoversHairEarsAndNeckInsideTheCrop() {
        val crop = HeadMaskComposer.headCrop(target, w, h)
        val prior = HeadMaskComposer.priorMask(target, crop, w, h)
        val b = prior.bounds()!!
        assertTrue(b.left >= crop.left && b.top >= crop.top && b.right <= crop.right && b.bottom <= crop.bottom)
        assertEquals(255, prior[50, 50])               // face centre
        assertEquals(255, prior[50, face.top - 6])     // hair above the face box
        assertEquals(255, prior[face.left - 3, 50])    // ear level beside the face box
        assertEquals(255, prior[50, crop.bottom - 1])  // neck column
        assertEquals(0, prior[crop.left, crop.top])    // corners stay background
        for (y in 0 until h) for (x in 0 until w) if (!crop.contains(x, y)) assertEquals(0, prior[x, y])
    }

    @Test fun changeMaskFollowsConnectedChangeAndIgnoresStraySpecks() {
        val crop = HeadMaskComposer.headCrop(target, w, h)
        val prior = HeadMaskComposer.priorMask(target, crop, w, h)
        val original = HeadMaskComposer.cut(source, w, crop)
        val generated = original.copyOf()
        val cw = crop.width
        // Fuller hair above-left of the prior ellipse, touching it (crop rows 3..8, cols 3..9).
        for (y in 3..8) for (x in 3..9) generated[y * cw + x] = 0xff000000.toInt()
        // Model re-rendered background speck, not connected to the head.
        for (y in 38 until 41) for (x in 33 until 36) generated[y * cw + x] = 0xffffffff.toInt()
        // A sub-threshold tweak (encoder noise) next to the hair.
        generated[1 * cw + 1] = original[1 * cw + 1] xor 0x000101
        assertEquals("test hair must lie outside the prior", 0, prior[crop.left + 5, crop.top + 5])
        assertEquals("test speck must lie outside the prior", 0, prior[crop.left + 34, crop.top + 39])
        val alpha = HeadMaskComposer.changeMask(original, generated, crop, prior, featherRadius = 1)
        fun a(x: Int, y: Int) = alpha[y * cw + x].toInt() and 255
        assertEquals("new hair outside the prior is pasted", 255, a(5, 5))
        assertEquals("stray speck stays original", 0, a(34, 39))
        assertEquals("noise stays original", 0, a(1, 1))
        assertEquals(255, a(cw / 2, crop.height / 2))
        val soft = HeadMaskComposer.changeMask(original, generated, crop, prior)
        for (x in 0 until cw) { assertEquals(0, soft[x].toInt()); assertEquals(0, soft[(crop.height - 1) * cw + x].toInt()) }
        for (y in 0 until crop.height) { assertEquals(0, soft[y * cw].toInt()); assertEquals(0, soft[y * cw + cw - 1].toInt()) }
    }

    @Test fun changeMaskClosesHolesInsideTheHead() {
        val crop = HeadMaskComposer.headCrop(target, w, h)
        val prior = HeadMaskComposer.priorMask(target, crop, w, h)
        val original = HeadMaskComposer.cut(source, w, crop)
        val generated = original.copyOf()
        val cw = crop.width
        // Changed hair with one unchanged column (6): closing fills the gap, so no old strand shows.
        for (y in 3..8) for (x in 3..9) if (x != 6) generated[y * cw + x] = 0xff000000.toInt()
        assertEquals(0, prior[crop.left + 6, crop.top + 5])
        val closed = HeadMaskComposer.changeMask(original, generated, crop, prior, featherRadius = 1)
        val open = HeadMaskComposer.changeMask(original, generated, crop, prior, closeRadius = 0, featherRadius = 1)
        assertEquals(255, closed[5 * cw + 6].toInt() and 255)
        assertTrue((open[5 * cw + 6].toInt() and 255) < 255)
    }

    @Test fun compositeIsBitIdenticalOutsideTheMaskAndNeverMutatesTheSource() {
        val crop = HeadMaskComposer.headCrop(target, w, h)
        val copy = source.copyOf()
        val generated = IntArray(crop.width * crop.height) { 0xff00ff00.toInt() }
        val alpha = ByteArray(generated.size) { i ->
            val x = i % crop.width; val y = i / crop.width
            when { x < 5 -> 0; x < 10 -> 128.toByte(); else -> 255.toByte() }
        }
        val out = HeadMaskComposer.composite(source, w, h, crop, generated, alpha)
        assertArrayEquals(copy, source)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!crop.contains(x, y) || x - crop.left < 5) assertEquals("pixel $x,$y", source[i], out[i])
            else if (x - crop.left >= 10) assertEquals(0xff00ff00.toInt(), out[i])
            else assertTrue(out[i] != source[i] && out[i] != 0xff00ff00.toInt())
        }
    }

    @Test fun cutCopiesTheCropRows() {
        val crop = HeadCrop(3, 4, 5, 2)
        val cut = HeadMaskComposer.cut(source, w, crop)
        assertEquals(source[4 * w + 3], cut[0])
        assertEquals(source[5 * w + 7], cut[9])
    }
}
