package nl.atlas.gallery.featurevisual.search

import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * VS-S2a (#468) scale evidence: exact brute force over 150k synthetic 768-d
 * vectors (no real photos), streamed in store-sized pages. It reports query
 * wall-clock time on the JVM that runs it (not a phone) and never fails on
 * time; it does fail if planted matches are not found. It excludes SQLite
 * reads and int8 decoding in VisualIndexStore. Output: line `VS-S2a scale:`.
 */
class VisualQueryScaleBenchmarkTest {
    private val dim = 768
    private val model = "siglip-bench:1"
    private val total = 150_000
    private val rng = Random(150_000)

    private fun gaussian(): Float {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }
    private fun random() = VisualQueryEngine.unit(FloatArray(dim) { gaussian() })
    private fun at(base: FloatArray, c: Float): FloatArray {
        val r = random()
        val p = VisualQueryEngine.dot(r, base)
        val orth = VisualQueryEngine.unit(FloatArray(dim) { r[it] - p * base[it] })
        val s = sqrt(1f - c * c)
        return VisualQueryEngine.unit(FloatArray(dim) { c * base[it] + s * orth[it] })
    }

    /**
     * Pages over [total] items without holding 150k × 768 floats: rows reuse a
     * pool of random vectors, except every planted row, which is near [target].
     */
    private inner class SyntheticSource(target: FloatArray) : VisualVectorSource {
        override val modelVersion = model
        private val pool = Array(2048) { random() }
        val planted = (0 until total step total / 40).associateWith { at(target,
            // Inside the shown bands whatever their calibration (VS-S2c).
            VisualQueryBands.FLOOR + 0.01f +
                (VisualQueryBands.NEAR_DUPLICATE - VisualQueryBands.FLOOR - 0.02f) * rng.nextFloat()) }
        private fun key(i: Int) = "content://synthetic/%06d".format(i)
        override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> {
            val start = if (after == null) 0 else after.mediaKey.takeLast(6).toInt() + 1
            return (start until minOf(total, start + limit)).map { i ->
                val media = VisualMedia(key(i), "r1")
                VisualIndexedVector(media, model, planted[i] ?: pool[i % pool.size],
                    VisualVectorCursor("e1", model, media.key, null))
            }
        }
        override fun lookup(media: VisualMedia): FloatArray? = null
    }

    @Test fun bruteForceOver150kVectors() {
        val target = random()
        val source = SyntheticSource(target)
        val engine = VisualQueryEngine(source)
        val query = VisualQuery(model, VisualReference { target })
        val times = ArrayList<Double>()
        var result: VisualQueryResult? = null
        repeat(4) {
            val start = System.nanoTime()
            result = engine.search(query)
            times += (System.nanoTime() - start) / 1e6
        }
        val r = result!!
        assertEquals(VisualQueryStatus.OK, r.status)
        assertEquals(total, r.scanned)
        val found = r.hits.map { it.media.key.takeLast(6).toInt() }.toSet()
        assertTrue("planted matches missing", found.containsAll(source.planted.keys))
        val warm = times.drop(1).sorted()
        println("VS-S2a scale: $total x $dim vectors, cold %.1f ms, warm median %.1f ms, hits ${r.hits.size}"
            .format(times[0], warm[warm.size / 2]))
    }
}
