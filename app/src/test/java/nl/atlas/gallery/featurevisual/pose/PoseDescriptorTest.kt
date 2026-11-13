package nl.atlas.gallery.featurevisual.pose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PoseDescriptor] against `tools/visual_search_bench/pose_descriptor.py` on synthetic landmark
 * arrays ([BlazePoseGolden], written by `pose_golden.py`), plus its invariances.
 */
class PoseDescriptorTest {
    @Test
    fun descriptorsMatchPythonGolden() {
        BlazePoseGolden.worlds.forEachIndexed { i, w ->
            val d = PoseDescriptor.fromWorld(w)
            assertEquals(PoseDescriptor.SIZE, d.size)
            assertArrayEquals("world $i", BlazePoseGolden.descriptors[i], d, 2e-5f)
        }
    }

    @Test
    fun similaritiesMatchPythonGolden() {
        val d = BlazePoseGolden.worlds.map(PoseDescriptor::fromWorld)
        for (i in d.indices) for (j in d.indices) {
            assertEquals("sim($i, $j)", BlazePoseGolden.similarities[i][j], PoseDescriptor.similarity(d[i], d[j]), 2e-5f)
        }
    }

    @Test
    fun mirroredBodyMatchesAndInvertedBodyDoesNot() {
        val w = BlazePoseGolden.worlds[0]
        val mirrored = FloatArray(w.size)
        for (k in 0 until PoseDescriptor.LANDMARKS) {
            val src = PoseDescriptor.SWAP[k] ?: k
            mirrored[3 * k] = -w[3 * src]; mirrored[3 * k + 1] = w[3 * src + 1]; mirrored[3 * k + 2] = w[3 * src + 2]
        }
        val inverted = FloatArray(w.size) { if (it % 3 == 1) -w[it] else w[it] }
        val d = PoseDescriptor.fromWorld(w)
        assertEquals(1f, PoseDescriptor.similarity(d, d), 1e-5f)
        assertEquals(1f, PoseDescriptor.similarity(d, PoseDescriptor.fromWorld(mirrored)), 1e-5f)
        assertTrue(PoseDescriptor.similarity(d, PoseDescriptor.fromWorld(inverted)) < 0.5f)
    }

    @Test
    fun yawDoesNotChangeTheDescriptor() {
        val w = BlazePoseGolden.worlds[1]
        val a = Math.toRadians(70.0)
        val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
        val turned = FloatArray(w.size)
        for (k in 0 until w.size / 3) {
            val x = w[3 * k]; val z = w[3 * k + 2]
            turned[3 * k] = c * x + s * z; turned[3 * k + 1] = w[3 * k + 1]; turned[3 * k + 2] = -s * x + c * z
        }
        assertEquals(1f, PoseDescriptor.similarity(PoseDescriptor.fromWorld(w), PoseDescriptor.fromWorld(turned)), 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooFewLandmarksAreRejected() {
        PoseDescriptor.fromWorld(FloatArray(32 * 3))
    }
}
