package nl.atlas.gallery.featureidentity.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** IDS-9 (#475): Lab colour match on synthetic crops. */
class FaceColourMatchTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun labRoundTripIsWithinOneLevel() {
        val lab = DoubleArray(3)
        for (c in listOf(argb(0, 0, 0), argb(255, 255, 255), argb(200, 120, 90), argb(12, 200, 250), argb(128, 128, 128))) {
            FaceColourMatch.toLab(c, lab)
            val back = FaceColourMatch.fromLab(lab)
            for (shift in listOf(16, 8, 0)) assertTrue(abs(((c shr shift) and 255) - ((back shr shift) and 255)) <= 1)
        }
    }

    @Test fun matchMovesTheMaskedMeanToTheTarget() {
        val n = 32 * 32
        val mask = FloatArray(n) { 1f }
        val swapped = IntArray(n) { i -> argb(150 + i % 20, 100 + i % 10, 90) }
        val target = IntArray(n) { i -> argb(200 + i % 20, 150 + i % 10, 120) }
        val out = FaceColourMatch.match(swapped, target, mask)
        val o = FaceColourMatch.stats(out, mask); val t = FaceColourMatch.stats(target, mask)
        for (c in 0 until 3) assertEquals(t.mean[c], o.mean[c], 1.0)
        val before = FaceColourMatch.stats(swapped, mask)
        assertTrue(abs(before.mean[0] - t.mean[0]) > 10)
    }

    @Test fun statisticsIgnoreUnmaskedPixels() {
        val n = 100
        val mask = FloatArray(n) { if (it < 50) 1f else 0f }
        val a = IntArray(n) { if (it < 50) argb(100, 100, 100) else argb(255, 0, 0) }
        val s = FaceColourMatch.stats(a, mask)
        assertEquals(50, s.count)
        assertEquals(0.0, s.spread[0], 1e-6)
    }

    @Test fun emptyMaskLeavesTheCropUnchangedAndAlphaIsKept() {
        val swapped = IntArray(16) { (0x40 shl 24) or 0x102030 }
        val target = IntArray(16) { argb(200, 200, 200) }
        assertArrayEquals(swapped, FaceColourMatch.match(swapped, target, FloatArray(16)))
        val matched = FaceColourMatch.match(swapped, target, FloatArray(16) { 1f })
        assertTrue(matched.all { (it ushr 24) == 0x40 })
    }

    @Test fun spreadRatioIsClamped() {
        val n = 64
        val mask = FloatArray(n) { 1f }
        val flat = IntArray(n) { argb(120, 120, 120 + it % 2) }
        val wild = IntArray(n) { if (it % 2 == 0) argb(0, 0, 0) else argb(255, 255, 255) }
        val out = FaceColourMatch.match(flat, wild, mask)
        // A near-flat crop is not stretched into black and white.
        assertTrue(out.all { ((it shr 16) and 255) in 60..200 })
    }

    @Test fun weakSwapFlagFollowsSimilarities() {
        val profile = floatArrayOf(1f, 0f); val original = floatArrayOf(0f, 1f)
        assertEquals(false, WeakSwap.isWeak(floatArrayOf(0.9f, 0.1f), profile, original))
        assertEquals(true, WeakSwap.isWeak(floatArrayOf(0.2f, 0.9f), profile, original))
        assertEquals(null, WeakSwap.isWeak(null, profile, original))
        val mean = WeakSwap.meanUnit(listOf(floatArrayOf(2f, 0f), floatArrayOf(0f, 2f)))
        assertEquals(1.0, mean.sumOf { (it * it).toDouble() }, 1e-6)
    }
}
