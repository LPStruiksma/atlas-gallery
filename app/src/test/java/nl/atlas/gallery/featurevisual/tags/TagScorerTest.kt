package nl.atlas.gallery.featurevisual.tags

import nl.atlas.gallery.featurevisual.store.VisualVectorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic vectors only: no model, no asset, no photo. */
class TagScorerTest {
    private val dim = 768
    private val image = "siglip2_b16_224.int8:74da3ac2b46e"
    private val text = "siglip2_b16_224.text.int8:6fd97706a619"

    private fun unit(seed: Int): FloatArray {
        val r = Random(seed)
        val v = FloatArray(dim) { r.nextFloat() * 2f - 1f }
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(dim) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat()

    /** Orthonormal tag directions, so every expected cosine below is exact. */
    private val basis: List<FloatArray> = (1..4).map { unit(it) }.let { raw ->
        val out = ArrayList<FloatArray>()
        for (v in raw) {
            val w = v.copyOf()
            for (b in out) { val p = dot(w, b); for (i in 0 until dim) w[i] -= p * b[i] }
            val n = sqrt(dot(w, w)); out += FloatArray(dim) { w[it] / n }
        }
        out
    }

    /** A unit item with exactly the given cosine to each tag direction plus orthogonal noise. */
    private fun item(vararg cos: Pair<Int, Float>, seed: Int = 99): FloatArray {
        val noise = unit(seed)
        for (b in basis) { val p = dot(noise, b); for (i in 0 until dim) noise[i] -= p * b[i] }
        val nn = sqrt(dot(noise, noise))
        val rest = sqrt(1f - cos.sumOf { (it.second * it.second).toDouble() }.toFloat())
        return FloatArray(dim) { i -> cos.fold(rest * noise[i] / nn) { acc, (k, c) -> acc + c * basis[k][i] } }
    }

    private fun matrix(vararg rows: Pair<TagSpec, FloatArray>, imageModel: String = image): TagMatrix =
        TagMatrixCodec.decode(TagMatrixCodec.encode(dim, imageModel, text, "0123456789ab", rows.toList()))

    private val dog get() = basis[0]
    private val cat get() = basis[1]
    private val beach get() = basis[2]
    private val receipt get() = basis[3]

    private val m by lazy {
        matrix(
            TagSpec("dog", 0.10f, true) to dog,
            TagSpec("cat", 0.10f, true) to cat,
            TagSpec("beach", 0.12f, true) to beach,
            TagSpec("receipt", 0.08f, false) to receipt,
        )
    }

    @Test fun codecRoundTripKeepsOrderThresholdsAndDirection() {
        assertEquals(listOf("dog", "cat", "beach", "receipt"), m.tags.map { it.id })
        assertEquals(0.12f, m.tags[2].threshold)
        assertFalse(m.tags[3].defaultOn)
        assertEquals("$image|$text|0123456789ab", m.fence)
        val row = m.row(0)
        assertEquals(1.0, sqrt(row.sumOf { it.toDouble() * it }), 1e-5)
        val cos = row.indices.sumOf { row[it].toDouble() * dog[it] }
        assertTrue("int8 row cosine $cos", cos > 0.999)
    }

    @Test fun clearsThresholdAndRanksByMargin() {
        // cos to dog 0.20 (margin 0.10), to beach 0.17 (margin 0.05), cat 0.05 (below).
        val hits = TagScorer(m).score(item(0 to 0.20f, 2 to 0.17f, 1 to 0.05f), image)
        assertEquals(listOf("dog", "beach"), hits.map { it.id })
        assertTrue(hits[0].margin > hits[1].margin)
        assertTrue(hits.all { it.margin >= 0f && abs(it.cosine - it.margin - m.tags.first { t -> t.id == it.id }.threshold) < 1e-6 })
    }

    @Test fun belowThresholdGivesNoTag() {
        assertTrue(TagScorer(m).score(item(1 to 0.09f), image).isEmpty())
        assertEquals(listOf("cat"), TagScorer(m).score(item(1 to 0.11f), image).map { it.id })
    }

    @Test fun offByDefaultTagsStayOffUnlessAskedFor() {
        val v = item(3 to 0.30f)
        assertTrue(TagScorer(m).score(v, image).isEmpty())
        assertEquals(listOf("receipt"), TagScorer(m).score(v, image, TagPolicy(includeOffByDefault = true)).map { it.id })
    }

    @Test fun maxTagsAndMinMarginCapTheList() {
        val v = item(0 to 0.30f, 1 to 0.25f, 2 to 0.20f)
        val all = TagScorer(m).score(v, image)
        assertEquals(listOf("dog", "cat", "beach"), all.map { it.id })
        assertEquals(listOf("dog"), TagScorer(m).score(v, image, TagPolicy(maxTags = 1)).map { it.id })
        val strict = TagScorer(m).score(v, image, TagPolicy(minMargin = all[1].margin + 1e-4f))
        assertEquals(listOf("dog"), strict.map { it.id })
    }

    @Test fun storedInt8RowScoresLikeTheFloatVector() {
        val v = item(0 to 0.25f)
        val q = VisualVectorCodec.encode(v)
        val a = TagScorer(m).score(v, image)
        val b = TagScorer(m).scoreStored(q.bytes, q.scale, image)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a[0].cosine, b[0].cosine, 0.01f)
    }

    @Test fun versionFenceRejectsVectorsFromAnotherIndexModel() {
        val scorer = TagScorer(m)
        assertTrue(scorer.accepts(image))
        assertFalse(scorer.accepts("siglip2_b16_224.int8:000000000000"))
        try {
            scorer.score(dog, "siglip2_b16_224.int8:000000000000"); fail()
        } catch (_: TagModelMismatchException) {}
        val other = matrix(TagSpec("dog", 0.1f, true) to dog, imageModel = "other:1")
        assertFalse(m.fence == other.fence)
    }

    @Test fun malformedMatricesAndVectorsAreRejected() {
        val good = TagMatrixCodec.encode(dim, image, text, "h", listOf(TagSpec("dog", 0.1f, true) to dog))
        for (bad in listOf(
            good.copyOf(good.size - 1),                       // truncated
            good + byteArrayOf(0),                             // trailing
            good.copyOf().also { it[0] = 'X'.code.toByte() },  // magic
        )) {
            try { TagMatrixCodec.decode(bad); fail() } catch (_: IllegalArgumentException) {}
        }
        val zero = good.copyOf().also { for (i in it.size - dim until it.size) it[i] = 0 }
        try { TagMatrixCodec.decode(zero); fail() } catch (_: IllegalArgumentException) {}
        try { TagScorer(m).score(FloatArray(10), image); fail() } catch (_: IllegalArgumentException) {}
        try { TagScorer(m).score(FloatArray(dim), image); fail() } catch (_: IllegalArgumentException) {}
        try { TagSpec("x", Float.NaN, true); fail() } catch (_: IllegalArgumentException) {}
        try { TagPolicy(maxTags = 0); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun probabilityUsesTheCheckpointLogit() {
        // p = 0.5 where 112.669·cos = 16.771725.
        assertEquals(0.5f, TagScorer.probability(16.771725f / 112.669f), 1e-4f)
        assertTrue(TagScorer.probability(0.05f) < 0.01f)
    }

    @Test fun costEstimateFor150kItems() {
        val c = TagCostEstimate(items = 150_000, tags = 159, dimension = 768)
        assertEquals(150_000L * 159 * 768, c.multiplyAdds)
        assertEquals(159L * 768 * 4, c.matrixBytesFloat)
        assertEquals(18.3168, c.secondsAt(1e9), 1e-3)
    }

    @Test fun vocabularyParsesAndMustMatchTheMatrix() {
        val names = TagVocabulary.parse(
            "id\tcategory\ten\tnl\ndog\tpets\tDog\tHond\ncat\tpets\tCat\tKat\nbeach\tscenery\tBeach\tStrand\nreceipt\tdocuments\tReceipts\tBonnetjes\n",
        )
        TagVocabulary.requireMatches(m, names)
        assertEquals("Hond", names[0].label("nl"))
        assertEquals("Dog", names[0].label("en"))
        try { TagVocabulary.requireMatches(m, names.reversed()); fail() } catch (_: IllegalArgumentException) {}
        try { TagVocabulary.parse("id\tcategory\ten\tnl\ndog\tpets\tDog\n"); fail() } catch (_: IllegalArgumentException) {}
    }
}
