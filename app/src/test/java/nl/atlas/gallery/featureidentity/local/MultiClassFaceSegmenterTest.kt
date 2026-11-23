package nl.atlas.gallery.featureidentity.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Synthetic maths only; model parity and Android runtime are separate gates. */
class MultiClassFaceSegmenterTest {
    @Test fun rgbIsNhwcInTheBenchRange() {
        val crop = IntArray(256 * 256) { 0xFF804020.toInt() }
        val rgb = MediaPipeMaskMath.rgb(crop, 256)
        assertEquals(128f / 255f, rgb[0], 0f)
        assertEquals(64f / 255f, rgb[1], 0f)
        assertEquals(32f / 255f, rgb[2], 0f)
        assertEquals(crop.size * 3, rgb.size)
    }

    @Test fun faceMaskExcludesHairWhileHeadIncludesIt() {
        val logits = FloatArray(256 * 256 * 6)
        for (p in 0 until 256 * 256) {
            logits[p * 6 + if (p % 2 == 0) 1 else 3] = 10f
        }
        val face = MediaPipeMaskMath.classMask(logits, false)
        val head = MediaPipeMaskMath.classMask(logits, true)
        assertEquals(0f, face[0], 0f)
        assertEquals(1f, head[0], 0f)
        assertEquals(1f, face[1], 0f)
        assertEquals(1f, head[1], 0f)
    }

    @Test fun invalidShapeAndNanFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaPipeMaskMath.classMask(FloatArray(4), false)
        }
        val logits = FloatArray(256 * 256 * 6)
        logits[1] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            MediaPipeMaskMath.classMask(logits, false)
        }
    }
}
