package nl.atlas.gallery.featurevisual.search

import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import nl.atlas.gallery.perf.threadAllocatedBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * PERF-15 (#222): the unrolled exact dot product is bit-identical to the
 * plain loop, so every score, band, near-duplicate, tie order and video
 * collapse is unchanged; and the 250k x 768 scan gets cheaper. Synthetic
 * unit vectors only; JVM numbers, not a phone.
 */
class VisualQueryThroughputTest {
    private val model = "siglip-perf15:1"
    private val dim = 768

    /** Frozen pre-PERF-15 kernel. */
    private fun referenceDot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (k in a.indices) sum += a[k] * b[k]
        return sum
    }

    @Test fun dotIsBitIdenticalForEveryLengthAndMagnitude() {
        val rng = Random(222)
        val scales = floatArrayOf(1f, 1e-20f, 1e-38f, 3e18f, 1e-3f)
        for (n in listOf(1, 2, 3, 4, 5, 7, 8, 9, 31, 767, 768, 769)) repeat(400) {
            val sa = scales[rng.nextInt(scales.size)]
            val sb = scales[rng.nextInt(scales.size)]
            val a = FloatArray(n) { (rng.nextFloat() * 2f - 1f) * sa }
            val b = FloatArray(n + rng.nextInt(2)) { (rng.nextFloat() * 2f - 1f) * sb }
            assertEquals("n=$n", referenceDot(a, b).toRawBits(), VisualQueryEngine.dot(a, b).toRawBits())
        }
        // Cancellation-heavy sums, where any reassociation would show.
        val a = FloatArray(768) { if (it % 2 == 0) 1e8f else 1f }
        val b = FloatArray(768) { if (it % 4 < 2) 1f else -1f }
        assertEquals(referenceDot(a, b).toRawBits(), VisualQueryEngine.dot(a, b).toRawBits())
    }

    @Test fun mixedPhotoAndVideoIndexMatchesNaiveReferenceExactly() {
        val rng = Random(2_220)
        val probe = unitRandom(rng)
        val rows = ArrayList<Pair<VisualMedia, FloatArray>>()
        val shared = at(probe, 0.80f, rng) // identical vectors: tie order by key
        for (i in 0 until 6_000) {
            val key = "content://synthetic/m%05d".format(i)
            val c = cosineFor(rng)
            if (i % 5 == 0) {
                for (f in 0 until 1 + rng.nextInt(6)) {
                    rows += VisualMedia(key, "r1", f * 1_000L) to at(probe, cosineFor(rng), rng)
                }
            } else {
                rows += VisualMedia(key, "r1") to (if (i % 37 == 0) shared else at(probe, c, rng))
            }
        }
        val reference = VisualMedia("content://synthetic/m00001", "r1")
        for (limit in listOf(1, 25, 500, 5_000)) {
            val source = ListSource(rows)
            val got = VisualQueryEngine(source).search(
                VisualQuery(model, VisualReference(reference) { probe }, limit))
            // The engine reuses the reference's stored vector when indexed.
            val stored = rows.first { it.first == reference }.second
            val want = naive(rows, stored, reference, limit)
            assertTrue(got.reusedStoredVector)
            assertEquals(VisualQueryStatus.OK, got.status)
            assertEquals("limit=$limit", want.hits, got.hits)
            assertEquals(want.nearDuplicates, got.nearDuplicates)
            assertEquals(rows.size, got.scanned)
            assertEquals(want.truncated, got.truncated)
        }
    }

    @Test fun mismatchRevokedAndStaleStillFailClosed() {
        val rng = Random(7)
        val probe = unitRandom(rng)
        val rows = (0 until 1_200).map { VisualMedia("content://synthetic/s%05d".format(it), "r1") to at(probe, 0.8f, rng) }
        val query = VisualQuery(model, VisualReference(embed = { probe }))
        val bad = rows.toMutableList().also { it[700] = it[700].first to FloatArray(dim - 1) }
        assertEquals(VisualQueryStatus.MODEL_MISMATCH, VisualQueryEngine(ListSource(bad)).search(query).status)
        var grants = 0
        assertEquals(VisualQueryStatus.REVOKED,
            VisualQueryEngine(ListSource(rows)) { ++grants < 2 }.search(query).status)
        assertEquals(VisualQueryStatus.STALE,
            VisualQueryEngine(ListSource(rows, current = false)).search(query).status)
    }

    @Test fun reports250kScanBeforeAfter() {
        val rng = Random(250_000)
        val probe = unitRandom(rng)
        val pool = Array(4_096) { if (it % 512 == 0) at(probe, 0.8f, rng) else unitRandom(rng) }
        val total = 250_000
        val source = PooledSource(total, pool)
        val engine = VisualQueryEngine(source)
        val query = VisualQuery(model, VisualReference(embed = { probe }))
        var sink = 0f
        fun kernel(dot: (FloatArray, FloatArray) -> Float): Long {
            val t = System.nanoTime()
            for (i in 0 until total) sink += dot(probe, pool[i and 4_095])
            return System.nanoTime() - t
        }
        repeat(3) { kernel(::referenceDot); kernel(VisualQueryEngine::dot); engine.search(query) }
        val runs = 9
        val before = LongArray(runs) { kernel(::referenceDot) }.sorted()
        val after = LongArray(runs) { kernel(VisualQueryEngine::dot) }.sorted()
        val searchNs = LongArray(runs)
        var searchBytes = 0L
        repeat(runs) {
            val a0 = threadAllocatedBytes()
            val t = System.nanoTime()
            val r = engine.search(query)
            searchNs[it] = System.nanoTime() - t
            val a1 = threadAllocatedBytes()
            if (a0 != null && a1 != null) searchBytes += a1 - a0
            assertEquals(VisualQueryStatus.OK, r.status)
            assertEquals(total, r.scanned)
        }
        searchNs.sort()
        fun ms(v: Long) = v / 1e6
        println(("PERF-15 250k x $dim (JVM, n=$runs): dot kernel before p50=%.1f ms p95=%.1f ms; " +
            "after p50=%.1f ms p95=%.1f ms. Whole search after p50=%.1f ms p95=%.1f ms, " +
            "allocated %s per search (synthetic page objects included)").format(
            ms(before[runs / 2]), ms(before[runs - 1]), ms(after[runs / 2]), ms(after[runs - 1]),
            ms(searchNs[runs / 2]), ms(searchNs[runs - 1]),
            if (threadAllocatedBytes() == null) "n/a" else "${searchBytes / runs} B"))
        assertTrue(sink.isFinite() || sink.isNaN())
    }

    // --- reference pipeline -------------------------------------------------

    private data class Naive(val hits: List<VisualHit>, val nearDuplicates: Int, val truncated: Boolean)

    private fun naive(
        rows: List<Pair<VisualMedia, FloatArray>>, raw: FloatArray, self: VisualMedia?, limit: Int
    ): Naive {
        val probe = VisualQueryEngine.unit(raw)
        val ordered = rows.sortedWith(compareBy({ it.first.key }, { it.first.frameMs ?: -1L }))
        val offers = ArrayList<VisualHit>()
        val videoMoments = LinkedHashMap<String, MutableList<VisualHit>>()
        var nearDuplicates = 0
        for ((media, vector) in ordered) {
            if (self != null && media.key == self.key && media.frameMs == self.frameMs) continue
            val c = referenceDot(probe, vector)
            if (c >= VisualQueryBands.NEAR_DUPLICATE) { nearDuplicates++; continue }
            val band = VisualQueryBands.bandOf(c) ?: continue
            val hit = VisualHit(media, c, band)
            if (media.frameMs != null) videoMoments.getOrPut(media.key) { ArrayList() } += hit
            else offers += hit
        }
        for (moments in videoMoments.values) offers += VisualQueryEngine.collapseMoments(moments)
        val sorted = offers.sortedWith(VisualQueryEngine.BEST_FIRST)
        return Naive(sorted.take(limit), nearDuplicates, offers.size > limit)
    }

    // --- synthetic sources --------------------------------------------------

    private inner class ListSource(
        rows: List<Pair<VisualMedia, FloatArray>>, private val current: Boolean = true
    ) : VisualVectorSource {
        override val modelVersion = model
        private val sorted = rows.sortedWith(compareBy({ it.first.key }, { it.first.frameMs ?: -1L }))
        private var next = 0
        override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> {
            if (after == null) next = 0
            val out = sorted.subList(next, minOf(sorted.size, next + limit)).map { (m, v) ->
                VisualIndexedVector(m, model, v, VisualVectorCursor("e", model, m.key, m.frameMs))
            }
            next += out.size
            return out
        }
        override fun lookup(media: VisualMedia): FloatArray? = sorted.firstOrNull { it.first == media }?.second
        override fun isCurrent(): Boolean = current
    }

    private inner class PooledSource(val total: Int, val pool: Array<FloatArray>) : VisualVectorSource {
        override val modelVersion = model
        private fun key(i: Int) = "content://synthetic/%06d".format(i)
        override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> {
            val start = if (after == null) 0 else after.mediaKey.takeLast(6).toInt() + 1
            return (start until minOf(total, start + limit)).map { i ->
                val media = VisualMedia(key(i), "r1")
                VisualIndexedVector(media, model, pool[i and 4_095], VisualVectorCursor("e", model, media.key, null))
            }
        }
        override fun lookup(media: VisualMedia): FloatArray? = null
    }

    // --- vectors ------------------------------------------------------------

    private fun cosineFor(rng: Random): Float = when (rng.nextInt(6)) {
        0 -> 0.96f + 0.03f * rng.nextFloat()          // near duplicate
        1 -> VisualQueryBands.STRONG                     // exact band edge
        2 -> VisualQueryBands.FLOOR                      // exact floor edge
        3 -> 0.85f + 0.09f * rng.nextFloat()           // strong
        4 -> 0.75f + 0.08f * rng.nextFloat()           // related
        else -> 0.2f + 0.5f * rng.nextFloat()          // below floor
    }

    private fun gaussian(rng: Random): Float {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * rng.nextDouble())).toFloat()
    }

    private fun unitRandom(rng: Random) = VisualQueryEngine.unit(FloatArray(dim) { gaussian(rng) })

    private fun at(base: FloatArray, c: Float, rng: Random): FloatArray {
        val r = unitRandom(rng)
        val p = referenceDot(r, base)
        val orth = VisualQueryEngine.unit(FloatArray(dim) { r[it] - p * base[it] })
        val s = sqrt(1f - c * c)
        return VisualQueryEngine.unit(FloatArray(dim) { c * base[it] + s * orth[it] })
    }
}
