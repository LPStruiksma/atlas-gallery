package nl.atlas.gallery.featureidentity.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IDS-9 (#475): mask guard and paste-back on synthetic pixels. */
class FaceBlendMaskTest {
    private val size = 256
    private val template = FaceAlignWarp.arcfaceTemplate(size)

    @Test fun landmarkOvalCoversAboutAThirdOfAnArcFaceCrop() {
        val share = FaceBlendMask.share(FaceBlendMask.ovalMask(FaceBlendMask.oval(template), size))
        // BENCH.md: a working face mask covers 29 to 37 % of the crop.
        assertTrue("oval share $share", share in 0.28..0.40)
    }

    @Test fun guardKeepsAWorkingSegmentationAndFlagsNothing() {
        val oval = FaceBlendMask.ovalMask(FaceBlendMask.oval(template), size, featherFraction = 0.0)
        val decision = FaceBlendMask.decide(template, size, oval)
        assertEquals(MaskSource.SEGMENTER, decision.source)
        assertTrue(decision.coverage!! > 0.9)
    }

    @Test fun guardFallsBackWhenTheSegmenterSeesOnlyAPatch() {
        // The IDS-2 failures: 2 to 7 % of the crop labelled face skin.
        for (fraction in listOf(0.02, 0.07)) {
            val pixels = (fraction * size * size).toInt()
            val patch = FloatArray(size * size) { if (it in (size * size / 2) until (size * size / 2 + pixels)) 1f else 0f }
            val decision = FaceBlendMask.decide(template, size, patch)
            assertEquals(MaskSource.FALLBACK, decision.source)
            assertTrue(decision.coverage!! < FaceBlendMask.MIN_COVERAGE)
            assertTrue(FaceBlendMask.share(decision.alpha) > 0.28)
        }
    }

    @Test fun noSegmenterMeansFallbackWithUnknownCoverage() {
        val decision = FaceBlendMask.decide(template, size, null)
        assertEquals(MaskSource.FALLBACK, decision.source)
        assertNull(decision.coverage)
    }

    @Test fun featherStaysInRangeAndSoftensTheEdge() {
        val hard = FloatArray(size * size) { if ((it % size) < size / 2) 1f else 0f }
        val soft = FaceBlendMask.feather(hard, size, 4)
        assertTrue(soft.all { it in 0f..1f })
        val row = size * 100
        assertTrue(soft[row + size / 2 - 1] in 0.05f..0.95f)
        assertEquals(1f, soft[row + 10], 1e-6f)
    }

    /** Synthetic 600x500 photo, face placed by a similarity; paste a solid crop through the oval mask. */
    @Test fun pixelsOutsideTheMaskAreBitIdentical() {
        val w = 600; val h = 500
        val photo = IntArray(w * h) { i -> (0xFF shl 24) or (((i * 2654435761L) ushr 8).toInt() and 0xFFFFFF) }
        val landmarks = FloatArray(10) { if (it % 2 == 0) template[it] * 1.3f + 120f else template[it] * 1.3f + 90f }
        val toCrop = FaceAlignWarp.similarity(landmarks, template)
        val rect = FaceAlignWarp.sourceRect(toCrop, size, w, h)!!
        val region = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) System.arraycopy(photo, (rect.top + y) * w + rect.left, region, y * rect.width, rect.width)
        val before = region.copyOf()
        val mask = FaceBlendMask.decide(template, size, null).alpha
        val swapped = IntArray(size * size) { (0xFF shl 24) or 0x3366CC }
        val paste = FaceBlendMask.pasteBack(region, rect, toCrop, swapped, mask, size)
        assertTrue(paste.changedPixels > 1000)
        var outsideChanged = 0
        var insideChanged = 0
        for (y in 0 until rect.height) for (x in 0 until rect.width) {
            val i = y * rect.width + x
            val cx = toCrop.mapX(rect.left + x + 0.5, rect.top + y + 0.5) - 0.5
            val cy = toCrop.mapY(rect.left + x + 0.5, rect.top + y + 0.5) - 0.5
            val inMask = neighbourhoodMax(mask, cx, cy) > 0f
            if (region[i] != before[i]) { if (inMask) insideChanged++ else outsideChanged++ }
        }
        assertEquals("no pixel outside the mask may change", 0, outsideChanged)
        assertTrue(insideChanged > 1000)
        // The whole changed area sits inside the reported rectangle.
        val box = paste.changed!!
        for (y in 0 until rect.height) for (x in 0 until rect.width) {
            if (region[y * rect.width + x] != before[y * rect.width + x]) {
                assertTrue(rect.left + x in box.left until box.right && rect.top + y in box.top until box.bottom)
            }
        }
    }

    /** Largest mask value among the four crop pixels a bilinear sample at (cx, cy) reads; 0 outside. */
    private fun neighbourhoodMax(mask: FloatArray, cx: Double, cy: Double): Float {
        if (cx < -0.5 || cy < -0.5 || cx > size - 0.5 || cy > size - 0.5) return 0f
        val x0 = kotlin.math.floor(cx).toInt(); val y0 = kotlin.math.floor(cy).toInt()
        var m = 0f
        for (y in y0..y0 + 1) for (x in x0..x0 + 1) m = maxOf(m, mask[y.coerceIn(0, size - 1) * size + x.coerceIn(0, size - 1)])
        return m
    }

    @Test fun zeroMaskChangesNothingAndKeepsAlpha() {
        val rect = PixelRect(0, 0, size, size)
        val region = IntArray(size * size) { (0x7F shl 24) or 0x123456 }
        val before = region.copyOf()
        val toCrop = FaceAlignWarp.similarity(template, template)
        val paste = FaceBlendMask.pasteBack(region, rect, toCrop, IntArray(size * size) { -1 }, FloatArray(size * size), size)
        assertEquals(0, paste.changedPixels)
        assertNull(paste.changed)
        assertTrue(region.contentEquals(before))
        val full = FaceBlendMask.pasteBack(region, rect, toCrop, IntArray(size * size) { -1 }, FloatArray(size * size) { 1f }, size)
        assertNotEquals(0, full.changedPixels)
        assertTrue(region.all { (it ushr 24) == 0x7F })
    }
}
