package nl.atlas.gallery.featureeditor.render

import nl.atlas.gallery.featureeditor.engine.AdjustParam
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColourMathTest {
    private fun adjust(vararg pairs: Pair<AdjustParam, Float>) =
        AdjustValues(FloatArray(AdjustParam.entries.size).also { v -> pairs.forEach { v[it.first.ordinal] = it.second } })

    @Test fun transferRoundTripsExtendedValues() {
        for (e in listOf(-0.5f, -0.01f, 0f, 0.02f, 0.2f, 0.5f, 0.99f, 1f, 1.4f)) {
            assertEquals(e, ColourScience.encode(ColourScience.decode(e)), 1e-5f)
        }
        assertEquals(-ColourScience.decode(0.6f), ColourScience.decode(-0.6f), 0f)
    }

    @Test fun primaryMatricesAreInversesAndKeepWhite() {
        val out = FloatArray(3)
        ColourScience.apply(ColourScience.SRGB_TO_P3, 1f, 1f, 1f, out)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), out, 1e-5f)
        val c = floatArrayOf(0.3f, 0.6f, 0.1f)
        ColourScience.apply(ColourScience.SRGB_TO_P3, c[0], c[1], c[2], out)
        val back = FloatArray(3)
        ColourScience.apply(ColourScience.P3_TO_SRGB, out[0], out[1], out[2], back)
        assertArrayEquals(c, back, 1e-5f)
    }

    @Test fun displayP3RedIsOutsideSrgbAndSurvivesNeutralControls() {
        val red = floatArrayOf(1f, 0f, 0f, 1f)
        ColourOps.apply(AdjustValues.NONE, red, 0.5f, 0.5f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), red, 0f)
        val srgb = FloatArray(3)
        ColourScience.apply(ColourScience.P3_TO_SRGB, red[0], red[1], red[2], srgb)
        assertTrue("P3 red needs sRGB red above 1", srgb[0] > 1.2f)
        assertTrue("and negative green", srgb[1] < -0.03f)
    }

    @Test fun everyControlIsIdentityAtZeroAndChangesPixelsOtherwise() {
        for (p in AdjustParam.entries) {
            val base = when (p) {
                AdjustParam.SHADOWS -> floatArrayOf(0.02f, 0.03f, 0.05f, 1f)
                AdjustParam.HIGHLIGHTS -> floatArrayOf(0.6f, 0.7f, 0.8f, 1f)
                else -> floatArrayOf(0.18f, 0.25f, 0.4f, 1f)
            }
            val c = base.copyOf()
            ColourOps.apply(adjust(p to 0f), c, 0.1f, 0.1f, 0.05f, 0.05f)
            assertArrayEquals(p.key, base, c, 0f)
            val d = base.copyOf()
            ColourOps.apply(adjust(p to 0.5f), d, 0.05f, 0.05f, 0.05f, 0.05f)
            assertTrue("${p.key} changes the pixel", (0..2).any { abs(d[it] - base[it]) > 1e-4f })
        }
    }

    @Test fun exposureIsStopsInLinearLight() {
        val c = floatArrayOf(0.1f, 0.2f, 0.3f, 1f)
        ColourOps.apply(adjust(AdjustParam.EXPOSURE to 0.5f), c, 0.5f, 0.5f)
        assertArrayEquals(floatArrayOf(0.2f, 0.4f, 0.6f, 1f), c, 1e-5f)
    }

    @Test fun saturationMinusOneIsGreyOfEqualLuminance() {
        val c = floatArrayOf(0.6f, 0.2f, 0.1f, 1f)
        val y = ColourOps.luma(c)
        ColourOps.apply(adjust(AdjustParam.SATURATION to -1f), c, 0.5f, 0.5f)
        assertArrayEquals(floatArrayOf(y, y, y, 1f), c, 1e-6f)
    }

    @Test fun hueKeepsGreysAndTemperatureWarms() {
        val grey = floatArrayOf(0.3f, 0.3f, 0.3f, 1f)
        ColourOps.apply(adjust(AdjustParam.HUE to 0.7f), grey, 0.5f, 0.5f)
        assertArrayEquals(floatArrayOf(0.3f, 0.3f, 0.3f, 1f), grey, 1e-6f)
        val c = floatArrayOf(0.3f, 0.3f, 0.3f, 1f)
        ColourOps.apply(adjust(AdjustParam.TEMPERATURE to 0.5f), c, 0.5f, 0.5f)
        assertTrue(c[0] > 0.3f && c[2] < 0.3f)
    }

    @Test fun vignetteDarkensCornersNotCentre() {
        val centre = floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
        val corner = floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
        ColourOps.apply(adjust(AdjustParam.VIGNETTE to 1f), centre, 0.5f, 0.5f)
        ColourOps.apply(adjust(AdjustParam.VIGNETTE to 1f), corner, 0.01f, 0.01f)
        assertEquals(0.5f, centre[0], 0f)
        assertTrue(corner[0] < 0.2f)
    }

    @Test fun highlightsAndShadowsTargetTheirTones() {
        val dark = floatArrayOf(0.01f, 0.01f, 0.01f, 1f)
        val bright = floatArrayOf(0.8f, 0.8f, 0.8f, 1f)
        val d2 = dark.copyOf(); val b2 = bright.copyOf()
        ColourOps.apply(adjust(AdjustParam.SHADOWS to 1f), d2, 0.5f, 0.5f)
        ColourOps.apply(adjust(AdjustParam.SHADOWS to 1f), b2, 0.5f, 0.5f)
        assertTrue(d2[0] > dark[0] * 1.8f)
        assertEquals(bright[0], b2[0], 1e-6f)
        val d3 = dark.copyOf(); val b3 = bright.copyOf()
        ColourOps.apply(adjust(AdjustParam.HIGHLIGHTS to -1f), d3, 0.5f, 0.5f)
        ColourOps.apply(adjust(AdjustParam.HIGHLIGHTS to -1f), b3, 0.5f, 0.5f)
        assertEquals(dark[0], d3[0], 1e-7f)
        assertTrue(b3[0] < bright[0])
    }
}
