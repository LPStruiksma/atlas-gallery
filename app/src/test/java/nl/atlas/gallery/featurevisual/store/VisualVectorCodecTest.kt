package nl.atlas.gallery.featurevisual.store

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class VisualVectorCodecTest {
    @Test fun unitVectorsRoundTripWithoutNaNs() {
        val vector = FloatArray(768) { i -> if (i % 3 == 0) 0.8f else -0.2f }
        val packed = VisualVectorCodec.encode(vector)
        assertEquals(768, packed.bytes.size)
        assertTrue(packed.scale > 0f)
        val roundtrip = VisualVectorCodec.decode(packed.bytes, packed.scale, 768)
        val norm = kotlin.math.sqrt(roundtrip.sumOf { it.toDouble() * it })
        assertEquals(1.0, norm, 1e-5)
        val originalNorm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it })
        val cosine = vector.indices.sumOf {
            vector[it].toDouble() * roundtrip[it] / originalNorm
        }
        assertTrue("cosine=$cosine", cosine > 0.999)
    }

    @Test fun distinctVectorsHaveIndependentScales() {
        val a = VisualVectorCodec.encode(floatArrayOf(1f, 0f, 0f, 0f))
        val b = VisualVectorCodec.encode(floatArrayOf(1f, 1f, 1f, 1f))
        assertNotEquals(a.scale, b.scale)
        assertEquals(127, a.bytes[0].toInt())
        assertEquals(127, b.bytes[0].toInt())
        assertTrue(abs(VisualVectorCodec.decode(b.bytes, b.scale, 4)[0] - 0.5f) < 1e-4)
    }

    @Test fun corruptDimensionsScalesAndInputsAreRejected() {
        assertFails { VisualVectorCodec.encode(floatArrayOf(0f, 0f)) }
        assertFails { VisualVectorCodec.encode(floatArrayOf(Float.NaN, 1f)) }
        assertFails { VisualVectorCodec.encode(floatArrayOf(Float.POSITIVE_INFINITY)) }
        assertFails { VisualVectorCodec.decode(byteArrayOf(1,2), 0.01f, 3) }
        assertFails { VisualVectorCodec.decode(byteArrayOf(1,2), Float.NaN, 2) }
        assertFails { VisualVectorCodec.decode(byteArrayOf(0,0), 0.01f, 2) }
    }

    private inline fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Corrupt vector rejected fail closed.
        }
    }
}
