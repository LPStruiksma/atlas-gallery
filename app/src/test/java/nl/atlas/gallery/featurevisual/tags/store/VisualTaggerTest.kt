package nl.atlas.gallery.featurevisual.tags.store

import nl.atlas.gallery.featurevisual.VisualScanIndexPort
import nl.atlas.gallery.featurevisual.store.VisualIndexLease
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualMoment
import nl.atlas.gallery.featurevisual.store.VisualMomentsWrite
import nl.atlas.gallery.featurevisual.store.VisualVectorCodec
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import nl.atlas.gallery.featurevisual.store.VisualVectorWrite
import nl.atlas.gallery.featurevisual.tags.TagMatrix
import nl.atlas.gallery.featurevisual.tags.TagMatrixCodec
import nl.atlas.gallery.featurevisual.tags.TagScorer
import nl.atlas.gallery.featurevisual.tags.TagSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** TAGS-1 (#732): synthetic vectors only; no model, photo or device. */
class VisualTaggerTest {
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

    private val basis: List<FloatArray> = (1..4).map { unit(it) }.let { raw ->
        val out = ArrayList<FloatArray>()
        for (v in raw) {
            val w = v.copyOf()
            for (b in out) { val p = dot(w, b); for (i in 0 until dim) w[i] -= p * b[i] }
            val n = sqrt(dot(w, w)); out += FloatArray(dim) { w[it] / n }
        }
        out
    }

    /** Unit vector with the given cosine to each tag direction plus orthogonal noise. */
    private fun item(vararg cos: Pair<Int, Float>, seed: Int = 99): FloatArray {
        val noise = unit(seed)
        for (b in basis) { val p = dot(noise, b); for (i in 0 until dim) noise[i] -= p * b[i] }
        val nn = sqrt(dot(noise, noise))
        val rest = sqrt(1f - cos.sumOf { (it.second * it.second).toDouble() }.toFloat())
        return FloatArray(dim) { i -> cos.fold(rest * noise[i] / nn) { acc, (k, c) -> acc + c * basis[k][i] } }
    }

    private fun matrix(vocab: String = "0123456789ab"): TagMatrix = TagMatrixCodec.decode(
        TagMatrixCodec.encode(dim, image, text, vocab, listOf(
            TagSpec("dog", 0.10f, true) to basis[0],
            TagSpec("cat", 0.10f, true) to basis[1],
            TagSpec("beach", 0.12f, true) to basis[2],
            TagSpec("receipt", 0.08f, false) to basis[3],
        )))

    private var wall = 1_000_000L
    private var elapsed = 0L

    private fun tagger(store: VisualTagStorePort, m: TagMatrix = matrix(), budget: TagSweepBudget = TagSweepBudget()) =
        VisualTagger(TagScorer(m), store, budget = budget, clockElapsedMs = { elapsed }, wallClockMs = { wall })

    private fun lease(key: String, rev: String = "r1", frame: Long? = null, model: String = image) =
        VisualIndexLease(VisualMedia(key, rev, frame), model, dim, "epoch", "tok-$key")

    /** A stored, version-matched active index, in (key, frame) order. */
    private class FakeIndex(val model: String) {
        val rows = sortedMapOf<Pair<String, Long>, VisualIndexedVector>(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second })
        var reads = 0
        fun put(key: String, rev: String, vector: FloatArray, frame: Long? = null) {
            val q = VisualVectorCodec.encode(vector)
            val stored = VisualVectorCodec.decode(q.bytes, q.scale, vector.size)
            rows[key to (frame ?: -1L)] = VisualIndexedVector(VisualMedia(key, rev, frame), model, stored,
                VisualVectorCursor("epoch", model, key, frame))
        }
        fun read(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> {
            reads++
            val from = after?.let { it.mediaKey to (it.frameMs ?: -1L) }
            return rows.entries.filter { e ->
                from == null || e.key.first > from.first || (e.key.first == from.first && e.key.second > from.second)
            }.take(limit).map { it.value }
        }
    }

    private fun sweepAll(t: VisualTagger, index: FakeIndex, guard: () -> Boolean = { true }): List<TagSweepResult> {
        val out = ArrayList<TagSweepResult>()
        do {
            val r = t.sweepStep("epoch", index.model, index::read, guard)
            out += r
        } while (r.moreWork && out.size < 10_000)
        return out
    }

    @Test fun publishedPhotosAreTaggedWithThresholdsAndOnlyDefaultOnTags() {
        val store = InMemoryTagStore()
        val t = tagger(store)
        val written = t.tagPublished(listOf(
            VisualVectorWrite(lease("a"), item(0 to 0.25f, 2 to 0.20f)),     // dog + beach
            VisualVectorWrite(lease("b"), item(1 to 0.05f, 3 to 0.40f)),     // cat below, receipt off by default
        ), emptyList())
        assertEquals(2, written)
        assertEquals(listOf("dog", "beach"), store.tagsFor("a").map { it.id })
        assertTrue("scored but untagged rows are kept", store.rows.containsKey(TagRowKey("b", -1)))
        assertEquals(emptyList<StoredTag>(), store.tagsFor("b"))
        assertEquals(listOf("a"), store.mediaForTag("dog"))
        assertEquals(emptyList<String>(), store.mediaForTag("receipt"))
        assertEquals(matrix().fence, store.fence)
    }

    @Test fun republishReplacesTheRowAndVideoMomentsReplaceTheWholeVideo() {
        val store = InMemoryTagStore()
        val t = tagger(store)
        t.tagPublished(listOf(VisualVectorWrite(lease("a"), item(0 to 0.3f))), emptyList())
        t.tagPublished(listOf(VisualVectorWrite(lease("a", "r2"), item(1 to 0.3f))), emptyList())
        assertEquals(listOf("cat"), store.tagsFor("a").map { it.id })
        assertEquals(emptyList<StoredTag>(), store.tagsFor("a", revision = "r1"))

        val v = lease("v")
        t.tagPublished(emptyList(), listOf(VisualMomentsWrite(v, listOf(
            VisualMoment(0, item(0 to 0.3f)), VisualMoment(1000, item(0 to 0.2f)), VisualMoment(2000, item(2 to 0.3f))))))
        assertEquals(3, store.rows.keys.count { it.mediaKey == "v" })
        assertEquals(listOf("beach", "dog"), store.tagsFor("v").map { it.id }.sorted())
        t.tagPublished(emptyList(), listOf(VisualMomentsWrite(lease("v", "r2"), listOf(VisualMoment(500, item(1 to 0.3f))))))
        assertEquals(listOf(TagRowKey("v", 500)), store.rows.keys.filter { it.mediaKey == "v" })
        assertEquals(listOf("cat"), store.tagsFor("v").map { it.id })
    }

    @Test fun otherModelsVectorsAreIgnored() {
        val store = InMemoryTagStore()
        assertEquals(0, tagger(store).tagPublished(
            listOf(VisualVectorWrite(lease("a", model = "other:1"), item(0 to 0.3f))), emptyList()))
        assertTrue(store.rows.isEmpty())
    }

    @Test fun backfillTagsEveryStoredVectorInBoundedStepsAndARescanIsStable() {
        val index = FakeIndex(image)
        repeat(2_000) { n ->
            val key = "k%05d".format(n)
            index.put(key, "r1", item((n % 3) to 0.3f, seed = 1_000 + n))
        }
        val store = InMemoryTagStore()
        val t = tagger(store, budget = TagSweepBudget(pageSize = 128, maxPages = 4))
        val steps = sweepAll(t, index)
        assertEquals(TagSweepStop.FINISHED, steps.last().stop)
        assertTrue("bounded: ${steps.size} steps", steps.size >= 2_000 / (128 * 4))
        assertTrue(steps.dropLast(1).all { it.stop == TagSweepStop.MORE && it.examined == 512 })
        assertEquals(2_000, steps.sumOf { it.scored })
        assertEquals(2_000, store.rows.size)
        val before = store.counts()
        assertEquals(2_000, before.hits) // one tag each (dog, cat or beach)
        assertEquals(667, store.mediaForTag("dog", limit = 10_000).size)

        // Within a day: idle, nothing read.
        val reads = index.reads
        assertEquals(TagSweepStop.IDLE, t.sweepStep("epoch", image, index::read) { true }.stop)
        assertEquals(reads, index.reads)

        // A day later the reconcile sweep reads everything and rescores nothing.
        wall += 24L * 60 * 60 * 1000
        val again = sweepAll(t, index)
        assertEquals(2_000, again.sumOf { it.examined })
        assertEquals(0, again.sumOf { it.scored })
        assertEquals(before, store.counts())
    }

    @Test fun sweepRetagsChangedRevisionsPrunesVanishedVectorsAndSkipsIncrementalOnes() {
        val index = FakeIndex(image)
        listOf("a", "b", "c", "d").forEach { index.put(it, "r1", item(0 to 0.3f)) }
        val store = InMemoryTagStore()
        val t = tagger(store, budget = TagSweepBudget(pageSize = 2, maxPages = 1, resweepAfterMs = 0))
        sweepAll(t, index)
        assertEquals(4, store.rows.size)

        index.rows.remove("b" to -1L)                 // deleted photo
        index.rows.remove("d" to -1L)                 // deleted last photo (tail prune)
        index.put("c", "r2", item(1 to 0.3f))         // edited photo
        index.put("e", "r1", item(2 to 0.3f))         // new photo the scan already tagged
        t.tagPublished(listOf(VisualVectorWrite(lease("e"), item(2 to 0.3f))), emptyList())
        val steps = sweepAll(t, index)
        assertEquals(1, steps.sumOf { it.scored })    // only c
        assertEquals(2, steps.sumOf { it.pruned })    // b and d
        assertEquals(listOf("a", "c", "e"), store.rows.keys.map { it.mediaKey })
        assertEquals(listOf("cat"), store.tagsFor("c").map { it.id })
    }

    @Test fun videoMomentsAreSweptPerFrame() {
        val index = FakeIndex(image)
        index.put("v", "r1", item(0 to 0.3f), frame = 0)
        index.put("v", "r1", item(2 to 0.3f), frame = 1500)
        index.put("p", "r1", item(1 to 0.3f))
        val store = InMemoryTagStore()
        sweepAll(tagger(store), index)
        assertEquals(listOf("beach", "dog"), store.tagsFor("v").map { it.id }.sorted())
        assertEquals(listOf("p"), store.mediaForTag("cat"))
        assertEquals(listOf("v"), store.mediaForTag("beach", TagScope.Media(setOf("v", "x"))))
        assertEquals(emptyList<String>(), store.mediaForTag("beach", TagScope.Media(setOf("p"))))
    }

    @Test fun newFenceWipesAndRestartsWhileAnotherIndexModelDeletesEverything() {
        val index = FakeIndex(image)
        listOf("a", "b").forEach { index.put(it, "r1", item(0 to 0.3f)) }
        val store = InMemoryTagStore()
        sweepAll(tagger(store), index)
        assertEquals(2, store.rows.size)

        // New vocabulary (fence) mid-life: every row goes and the sweep starts over.
        val v2 = tagger(store, matrix("fedcba987654"))
        assertEquals(TagSweepStop.FINISHED, v2.sweepStep("epoch", image, index::read) { true }.stop)
        assertEquals(1, store.wipes)
        assertEquals(2, store.rows.size)
        assertEquals(matrix("fedcba987654").fence, store.fence)

        // Visual index moved to another model: tags no longer describe its vectors.
        val other = FakeIndex("siglip3:new")
        other.put("a", "r1", item(0 to 0.3f))
        assertEquals(TagSweepStop.UNSUPPORTED_MODEL, v2.sweepStep("epoch", other.model, other::read) { true }.stop)
        assertTrue(store.rows.isEmpty())
        assertEquals(0, other.reads)
    }

    @Test fun guardStopsBeforeReadingAndTimeBudgetEndsAStep() {
        val index = FakeIndex(image)
        repeat(10) { index.put("k$it", "r1", item(0 to 0.3f)) }
        val store = InMemoryTagStore()
        val held = tagger(store).sweepStep("epoch", image, index::read) { false }
        assertEquals(TagSweepStop.GUARD, held.stop)
        assertTrue(held.moreWork)
        assertEquals(0, index.reads)

        val slow = tagger(store, budget = TagSweepBudget(pageSize = 2, maxPages = 10, maxElapsedMs = 100))
        val r = slow.sweepStep("epoch", image, { a, l -> elapsed += 150; index.read(a, l) }) { true }
        assertEquals(TagSweepStop.MORE, r.stop)
        assertEquals(2, r.examined) // one page, then the budget was spent
    }

    @Test fun taggingPortTagsOnlyAcceptedBatchesAndNeverFailsTheIndexWrite() {
        var accept = true
        val delegate = object : VisualScanIndexPort {
            override fun claimAuthorized(epoch: String, modelVersion: String, authorized: List<VisualMedia>, maxItems: Int) =
                emptyList<VisualIndexLease>()
            override fun completeBatch(writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>) = accept
            override fun completeBatch(writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>,
                                       videos: List<VisualMomentsWrite>) = accept
            override fun releaseBatch(leases: List<VisualIndexLease>) = 0
            override fun recordFailure(lease: VisualIndexLease) = 2
        }
        val seen = ArrayList<Int>()
        val port = TaggingIndexPort(delegate) { w, v -> seen += w.size + v.size }
        val w = listOf(VisualVectorWrite(lease("a"), item(0 to 0.3f)))
        assertTrue(port.completeBatch(w, emptyList()))
        accept = false
        assertFalse(port.completeBatch(w, emptyList()))
        assertEquals(listOf(1), seen)
        assertEquals(2, port.recordFailure(lease("a")))

        accept = true
        val failing = TaggingIndexPort(delegate) { _, _ -> throw IllegalStateException("db closed") }
        assertTrue(failing.completeBatch(w, emptyList(), emptyList()))
    }
}
