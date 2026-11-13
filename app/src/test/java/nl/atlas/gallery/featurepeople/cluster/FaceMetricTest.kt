package nl.atlas.gallery.featurepeople.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** #580: the face metric. Synthetic vectors only; the constants are averages, not faces. */
class FaceMetricTest {
    private val dim = 16
    private fun axis(i: Int, scale: Float = 1f) = FloatArray(dim).also { it[i] = scale }
    private fun dot(a: FloatArray, b: FloatArray): Float { var d = 0f; for (i in a.indices) d += a[i] * b[i]; return d }
    private fun norm(a: FloatArray) = sqrt(dot(a, a))

    @Test fun shippedConstantsDecodeAndMatchTheirChecksum() {
        val m = FaceMetric.SFACE
        assertEquals(128, m.dimension)
        assertEquals("sface-metric:" + SFaceMetricData.SHA256.take(12), m.version)
        val rng = Random(7)
        val face = m.apply(FloatArray(128) { rng.nextFloat() - 0.5f })
        assertEquals(1f, norm(face.vector), 1e-4f)
        assertTrue(face.crowd in 0f..FaceMetric.MAX_CROWD)
    }

    @Test fun corruptedConstantsAreRefused() {
        val bad = SFaceMetricData.BASE64.replaceRange(40, 41, if (SFaceMetricData.BASE64[40] == 'A') "B" else "A")
        assertThrows(IllegalArgumentException::class.java) {
            FaceMetric.decode(bad, SFaceMetricData.DIMENSION, SFaceMetricData.PROTOTYPES,
                SFaceMetricData.BASELINE, SFaceMetricData.SHA256)
        }
    }

    @Test fun theSharedDirectionIsRemoved() {
        // Two "strangers" that have nothing in common but the shared direction.
        val mean = axis(0, 0.3f)
        val metric = FaceMetric.of("t", mean, listOf(axis(5)), baseline = 0.5f)
        val a = FloatArray(dim).also { it[0] = 0.3f; it[1] = 0.95f }
        val b = FloatArray(dim).also { it[0] = 0.3f; it[2] = 0.95f }
        assertTrue("raw strangers resemble each other", dot(a, b) / (norm(a) * norm(b)) > 0.08f)
        val fa = metric.apply(a)
        val fb = metric.apply(b)
        assertEquals(0f, dot(fa.vector, fb.vector), 0.01f)
        assertEquals(1f, norm(fa.vector), 1e-5f)
    }

    @Test fun inputScaleDoesNotMatter() {
        val metric = FaceMetric.of("t", axis(0, 0.2f), listOf(axis(3)), baseline = 0.1f)
        val raw = FloatArray(dim) { (it % 5) - 1.5f }
        val one = metric.apply(raw)
        val big = metric.apply(FloatArray(dim) { raw[it] * 40f })
        for (i in 0 until dim) assertEquals(one.vector[i], big.vector[i], 1e-5f)
        assertEquals(one.crowd, big.crowd, 1e-5f)
    }

    @Test fun crowdIsTheExcessOverATypicalFace() {
        val metric = FaceMetric.of("t", FloatArray(dim), listOf(axis(3), axis(4)), baseline = 0.25f)
        // 0.8 towards prototype 3: 0.55 above the baseline.
        val near = FloatArray(dim).also { it[3] = 0.8f; it[7] = 0.6f }
        assertEquals(0.55f, metric.apply(near).crowd, 1e-4f)
        // Far from every prototype: a typical face, never negative.
        assertEquals(0f, metric.apply(axis(9)).crowd, 0f)
        // The best prototype counts, not the first.
        val other = FloatArray(dim).also { it[4] = 0.6f; it[8] = 0.8f }
        assertEquals(0.35f, metric.apply(other).crowd, 1e-4f)
    }

    @Test fun aFaceWithNoSignalBeyondTheMeanIsMaximallyCrowded() {
        val metric = FaceMetric.of("t", axis(0), listOf(axis(3)), baseline = 0.1f)
        val face = metric.apply(axis(0, 5f))
        assertEquals(FaceMetric.MAX_CROWD, face.crowd, 0f)
        assertEquals(1f, norm(face.vector), 1e-5f)
    }

    @Test fun badVectorsAreRefused() {
        val metric = FaceMetric.of("t", FloatArray(dim), listOf(axis(3)), baseline = 0.1f)
        assertThrows(IllegalArgumentException::class.java) { metric.apply(FloatArray(dim - 1) { 1f }) }
        assertThrows(IllegalArgumentException::class.java) { metric.apply(FloatArray(dim)) }
        assertThrows(IllegalArgumentException::class.java) {
            metric.apply(FloatArray(dim) { 1f }.also { it[2] = Float.NaN })
        }
    }
}
