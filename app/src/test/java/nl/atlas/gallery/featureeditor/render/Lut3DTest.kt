package nl.atlas.gallery.featureeditor.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Lut3DTest {
    @Test fun identityLutIsExactAtAnyPoint() {
        val lut = Lut3D.identity(9)
        val out = FloatArray(3)
        lut.sample(0.123f, 0.5f, 0.987f, out)
        assertArrayEquals(floatArrayOf(0.123f, 0.5f, 0.987f), out, 1e-6f)
    }

    @Test fun identityLutKeepsWideGamutColourThroughTheResidual() {
        val p3Red = floatArrayOf(1f, 0f, 0f, 1f)
        Lut3D.identity(17).apply(p3Red, 1f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), p3Red, 1e-4f)
        val bright = floatArrayOf(1.6f, 1.6f, 1.6f, 1f)
        Lut3D.identity(17).apply(bright, 1f)
        assertEquals(1.6f, bright[0], 1e-4f)
    }

    @Test fun strengthMixesInEncodedSpace() {
        val invert = Lut3D.of(5) { r, g, b, o -> o[0] = 1 - r; o[1] = 1 - g; o[2] = 1 - b }
        val grey = ColourScience.decode(0.25f)
        val c = floatArrayOf(grey, grey, grey, 1f)
        invert.apply(c, 0.5f)
        // Halfway between 0.25 and 0.75, encoded: 0.5.
        for (i in 0..2) assertEquals(0.5f, ColourScience.encode(c[i]), 1e-4f)
        val untouched = floatArrayOf(grey, grey, grey, 1f)
        invert.apply(untouched, 0f)
        assertEquals(grey, untouched[0], 0f)
    }

    @Test fun parsesCubeFiles() {
        val text = buildString {
            append("# synthetic\nTITLE \"swap\"\nLUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) append("$g $r $b\n") // swap red and green
        }
        val lut = Lut3D.parseCube(text)
        val out = FloatArray(3)
        lut.sample(0.8f, 0.1f, 0.4f, out)
        assertArrayEquals(floatArrayOf(0.1f, 0.8f, 0.4f), out, 1e-6f)
    }

    @Test fun refusesWhatItCannotRenderFaithfully() {
        assertThrows(IllegalArgumentException::class.java) { Lut3D.parseCube("LUT_1D_SIZE 4\n0 0 0\n") }
        assertThrows(IllegalArgumentException::class.java) { Lut3D.parseCube("LUT_3D_SIZE 2\nDOMAIN_MAX 2 2 2\n") }
        val e = assertThrows(IllegalArgumentException::class.java) { Lut3D.parseCube("LUT_3D_SIZE 2\n0.5 secret 0.25\n") }
        assertTrue(e.message!!.contains("line 2") && !e.message!!.contains("secret"))
    }
}
