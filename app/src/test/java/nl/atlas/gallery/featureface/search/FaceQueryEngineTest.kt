package nl.atlas.gallery.featureface.search

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** VS-F1 (#468) on synthetic vectors only; no real photos or faces. */
class FaceQueryEngineTest {
    private val model = SFaceModel.EMBEDDER_VERSION
    private val dim = SFaceModel.DIMENSION
    private val rng = Random(468)
    private val box = IndexedFaceBox(0.1f, 0.1f, 0.4f, 0.5f)
    private val alice = PersonKey("person-alice")
    private val bob = PersonKey("person-bob")

    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
    private fun media(n: Int) = IndexedMedia("content://media/external/images/media/$n", "r$n")

    private fun gaussian(): Float {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }
    private fun unit(v: FloatArray) = SFaceModel.l2Normalize(v)
    private fun random() = unit(FloatArray(dim) { gaussian() })
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    /** A unit vector whose cosine to unit [base] is exactly [c]. */
    private fun at(base: FloatArray, c: Float): FloatArray {
        val r = random()
        val p = dot(r, base)
        val orth = unit(FloatArray(dim) { r[it] - p * base[it] })
        val s = sqrt(1f - c * c)
        return unit(FloatArray(dim) { c * base[it] + s * orth[it] })
    }

    private class Row(val face: Int, val media: Int, val vector: FloatArray, val anchor: Int? = null)

    private fun corpus(rows: List<Row>, facts: ClusterReviewFacts = ClusterReviewFacts.NONE,
                       published: Boolean = true, version: String = model) =
        FaceSearchCorpus.Builder(version).apply {
            for (r in rows) add(id(r.face), media(r.media), box, r.anchor?.let { id(it) }, r.vector)
        }.build(facts, published)

    private fun FaceQueryResult.face(n: Int) =
        hits.flatMap { it.faces }.singleOrNull { it.face == id(n) }
    private fun FaceQueryResult.excluded(n: Int) = lookalikes.any { it.face == id(n) }

    @Test fun bandsAreOrderedAndAnchoredOnOpenCv() {
        assertEquals(0.363f, FaceQueryBands.PROBABLE, 0f)
        assertTrue(FaceQueryBands.LOOKALIKE_FLOOR < FaceQueryBands.PROBABLE)
        assertTrue(FaceQueryBands.PROBABLE < FaceQueryBands.SAME_PERSON)
        assertTrue(FaceQueryBands.PROBABLE - FaceQueryBands.LOOKALIKE_FLOOR > 4 * FaceQueryBands.QUANTIZATION_TOLERANCE)
    }

    @Test fun int8CorpusStaysWithinQuantizationTolerance() {
        val rows = List(500) { Row(it + 1, it + 1, random()) }
        val c = corpus(rows)
        var worst = 0f
        repeat(20) {
            val q = random()
            rows.forEachIndexed { i, r -> worst = maxOf(worst, abs(c.cosine(i, q) - dot(r.vector, q))) }
        }
        assertTrue("worst int8 error $worst", worst < FaceQueryBands.QUANTIZATION_TOLERANCE)
    }

    @Test fun directMatchesAreTieredAndRanked() {
        val q = random()
        val c = corpus(listOf(
            Row(1, 1, at(q, 0.80f)), Row(2, 2, at(q, 0.42f)), Row(3, 3, at(q, 0.31f)),
            Row(4, 4, at(q, 0.05f)), Row(5, 5, at(q, 0.65f))))
        val r = FaceQueryEngine.search(c, FaceQuery(model, listOf(q)))
        assertEquals(FaceQueryStatus.OK, r.status)
        assertEquals(listOf(media(1), media(5), media(2)), r.hits.map { it.media })
        assertEquals(listOf(FaceMatchTier.SAME_PERSON, FaceMatchTier.SAME_PERSON, FaceMatchTier.PROBABLE),
            r.hits.map { it.tier })
        assertEquals(0.80f, r.hits[0].bestSimilarity, 0.01f)
        assertEquals(listOf(id(3)), r.lookalikes.map { it.face })
        assertEquals(FaceMatchTier.LOOKALIKE_EXCLUDED, r.lookalikes[0].tier)
        assertEquals(1, r.lookalikeCount)
        assertNull(r.face(4))
        assertEquals(5, r.scannedFaces)
        assertNull(r.resolvedPerson)
    }

    @Test fun facesInOneMediaAreGroupedAndLimitTruncates() {
        val q = random()
        val c = corpus(listOf(Row(1, 1, at(q, 0.7f)), Row(2, 1, at(q, 0.45f)),
            Row(3, 2, at(q, 0.6f)), Row(4, 3, at(q, 0.55f))))
        val all = FaceQueryEngine.search(c, FaceQuery(model, listOf(q)))
        assertEquals(3, all.hits.size)
        assertEquals(listOf(id(1), id(2)), all.hits[0].faces.map { it.face })
        assertFalse(all.truncated)
        val two = FaceQueryEngine.search(c, FaceQuery(model, listOf(q), mediaLimit = 2))
        assertEquals(listOf(media(1), media(2)), two.hits.map { it.media })
        assertTrue(two.truncated)
    }

    @Test fun confirmedPersonExpandsToFacesTheVectorMisses() {
        val q = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(1) to alice, id(2) to alice))
        // Face 2: Alice as a child, far from today's vector; a human confirmed it.
        val c = corpus(listOf(Row(1, 1, at(q, 0.75f)), Row(2, 2, at(q, 0.10f)),
            Row(3, 3, random())), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, listOf(q)))
        assertEquals(alice, r.resolvedPerson)
        assertEquals(FaceMatchReason.CONFIRMED_PERSON, r.face(2)!!.reason)
        assertEquals(FaceMatchTier.SAME_PERSON, r.face(2)!!.tier)
        assertEquals(FaceMatchTier.SAME_PERSON, r.hits.single { it.media == media(2) }.tier)
    }

    @Test fun twoCloseConfirmedPeopleResolveToNobody() {
        val q = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(1) to alice, id(2) to alice,
            id(3) to bob, id(4) to bob))
        val c = corpus(listOf(Row(1, 1, at(q, 0.70f)), Row(2, 2, at(q, 0.05f)),
            Row(3, 3, at(q, 0.68f)), Row(4, 4, at(q, 0.05f))), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, listOf(q)))
        assertNull(r.resolvedPerson)
        assertNull(r.face(2)); assertNull(r.face(4))
        assertTrue(r.face(1) != null && r.face(3) != null)
    }

    @Test fun pickedSeedFaceDrivesTheQuery() {
        val base = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(1) to alice, id(2) to alice))
        val c = corpus(listOf(Row(1, 1, base), Row(2, 2, at(base, 0.05f)),
            Row(3, 3, at(base, 0.7f)), Row(4, 4, random())), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, seedFaces = setOf(id(1))))
        assertEquals(alice, r.resolvedPerson)
        assertEquals(FaceMatchReason.SEED, r.face(1)!!.reason)
        assertEquals(FaceMatchReason.CONFIRMED_PERSON, r.face(2)!!.reason)
        assertEquals(FaceMatchReason.DIRECT, r.face(3)!!.reason)
    }

    @Test fun unknownClusterLiftsMembersWithoutAnyName() {
        val q = random()
        val rows = listOf(Row(1, 1, at(q, 0.7f), anchor = 1), Row(2, 2, at(q, 0.15f), anchor = 1),
            Row(3, 3, at(q, 0.30f), anchor = 1), Row(4, 4, at(q, 0.15f), anchor = 4))
        val r = FaceQueryEngine.search(corpus(rows), FaceQuery(model, listOf(q)))
        assertNull(r.resolvedPerson)
        assertEquals(FaceMatchReason.CLUSTER, r.face(2)!!.reason)
        assertEquals(FaceMatchTier.PROBABLE, r.face(2)!!.tier)
        assertEquals(FaceMatchTier.PROBABLE, r.face(3)!!.tier) // lifted out of the look-alike band
        assertNull(r.face(4))
        // Pending publication: no expansion through partial membership.
        val pending = FaceQueryEngine.search(corpus(rows, published = false), FaceQuery(model, listOf(q)))
        assertNull(pending.face(2))
        assertTrue(pending.excluded(3))
    }

    @Test fun probableMatchDoesNotLiftItsCluster() {
        val q = random()
        val rows = listOf(Row(1, 1, at(q, 0.40f), anchor = 1), Row(2, 2, at(q, 0.1f), anchor = 1))
        val r = FaceQueryEngine.search(corpus(rows), FaceQuery(model, listOf(q)))
        assertEquals(FaceMatchTier.PROBABLE, r.face(1)!!.tier)
        assertNull(r.face(2))
    }

    @Test fun cannotLinkVetoesASimilarFace() {
        val q = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(1) to alice),
            cannotLink = setOf(FacePair.of(id(1), id(2))))
        val c = corpus(listOf(Row(1, 1, at(q, 0.8f)), Row(2, 2, at(q, 0.75f)),
            Row(3, 3, at(q, 0.6f))), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, listOf(q)))
        assertEquals(alice, r.resolvedPerson)
        assertNull(r.face(2))
        assertTrue(r.excluded(2))
        assertEquals(FaceMatchTier.SAME_PERSON, r.face(3)!!.tier)
    }

    @Test fun cannotLinkToTheBestMatchVetoesWithoutAnyPerson() {
        val q = random()
        val facts = ClusterReviewFacts(cannotLink = setOf(FacePair.of(id(1), id(2))))
        val c = corpus(listOf(Row(1, 1, at(q, 0.9f)), Row(2, 2, at(q, 0.6f))), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, listOf(q)))
        assertTrue(r.face(1) != null)
        assertTrue(r.excluded(2))
    }

    @Test fun cannotLinkedFaceDoesNotLiftItsCluster() {
        val q = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(1) to alice),
            cannotLink = setOf(FacePair.of(id(1), id(2))))
        val rows = listOf(Row(1, 1, at(q, 0.8f)),
            Row(2, 2, at(q, 0.7f), anchor = 2), Row(3, 3, at(q, 0.1f), anchor = 2))
        val r = FaceQueryEngine.search(corpus(rows, facts), FaceQuery(model, listOf(q)))
        assertEquals(alice, r.resolvedPerson)
        assertTrue(r.excluded(2))
        assertNull(r.face(3))
        // Without a person: cannot-link to the best direct match also blocks the lift.
        val solo = ClusterReviewFacts(cannotLink = setOf(FacePair.of(id(1), id(2))))
        val r2 = FaceQueryEngine.search(corpus(rows, solo), FaceQuery(model, listOf(q)))
        assertTrue(r2.excluded(2))
        assertNull(r2.face(3))
    }

    @Test fun seedCannotLinkedToAPersonNeverExpandsToThatPerson() {
        // Split A from B, then B's half was named Alice and C confirmed as Alice too.
        val base = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(2) to alice, id(3) to alice),
            cannotLink = setOf(FacePair.of(id(1), id(2))))
        val c = corpus(listOf(Row(1, 1, base), Row(2, 2, at(base, 0.8f)),
            Row(3, 3, at(base, 0.05f))), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, seedFaces = setOf(id(1))))
        assertNull(r.resolvedPerson)
        assertTrue(r.excluded(2))
        assertNull(r.face(3))
        // A confirmed seed contradicted the same way resolves to nobody either.
        val seeded = ClusterReviewFacts(confirmed = mapOf(id(1) to alice, id(2) to alice, id(3) to alice),
            cannotLink = setOf(FacePair.of(id(1), id(2))))
        val r2 = FaceQueryEngine.search(corpus(listOf(Row(1, 1, base), Row(2, 2, at(base, 0.8f)),
            Row(3, 3, at(base, 0.05f))), seeded), FaceQuery(model, seedFaces = setOf(id(1))))
        assertNull(r2.resolvedPerson)
        assertNull(r2.face(3))
    }

    @Test fun seedContradictionLeavesOtherPeopleResolvable() {
        val base = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(2) to alice, id(3) to bob, id(4) to bob),
            cannotLink = setOf(FacePair.of(id(1), id(2))))
        val c = corpus(listOf(Row(1, 1, base), Row(2, 2, at(base, 0.8f)),
            Row(3, 3, at(base, 0.78f)), Row(4, 4, at(base, 0.05f))), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, seedFaces = setOf(id(1))))
        assertEquals(bob, r.resolvedPerson)
        assertEquals(FaceMatchReason.CONFIRMED_PERSON, r.face(4)!!.reason)
        assertTrue(r.excluded(2))
    }

    @Test fun rejectedFaceIsExcludedAndBlocksItsClusterLift() {
        val q = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(1) to alice),
            rejectedPeople = setOf(RejectedPerson(id(2), alice)))
        val rows = listOf(Row(1, 1, at(q, 0.8f)),
            Row(2, 2, at(q, 0.6f), anchor = 2), Row(3, 3, at(q, 0.1f), anchor = 2),
            Row(4, 4, at(q, 0.6f), anchor = 4), Row(5, 5, at(q, 0.1f), anchor = 4))
        val r = FaceQueryEngine.search(corpus(rows, facts), FaceQuery(model, listOf(q)))
        assertEquals(alice, r.resolvedPerson)
        assertTrue(r.excluded(2))
        assertNull(r.face(3))
        assertEquals(FaceMatchReason.CLUSTER, r.face(5)!!.reason)
    }

    @Test fun seedRejectedForPersonBlocksExpansion() {
        val base = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(2) to alice, id(3) to alice),
            rejectedPeople = setOf(RejectedPerson(id(1), alice)))
        val c = corpus(listOf(Row(1, 1, base), Row(2, 2, at(base, 0.8f)), Row(3, 3, at(base, 0.05f))), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, seedFaces = setOf(id(1))))
        assertNull(r.resolvedPerson)
        assertNull(r.face(3))
    }

    @Test fun faceConfirmedAsSomeoneElseIsALookalike() {
        val q = random()
        val facts = ClusterReviewFacts(confirmed = mapOf(id(1) to alice, id(2) to bob))
        val c = corpus(listOf(Row(1, 1, at(q, 0.85f)), Row(2, 2, at(q, 0.62f))), facts)
        val r = FaceQueryEngine.search(c, FaceQuery(model, listOf(q)))
        assertEquals(alice, r.resolvedPerson)
        assertNull(r.face(2))
        assertTrue(r.excluded(2))
    }

    @Test fun otherModelVersionFailsClosed() {
        val q = random()
        val c = corpus(listOf(Row(1, 1, q)))
        val r = FaceQueryEngine.search(c, FaceQuery("sface-other-version", listOf(q)))
        assertEquals(FaceQueryStatus.MODEL_MISMATCH, r.status)
        assertTrue(r.hits.isEmpty() && r.lookalikes.isEmpty())
    }

    @Test fun revokedOrEmptyStoreReturnsNothing() {
        val q = random()
        val revoked = FaceQueryEngine.search(null, FaceQuery(model, listOf(q)))
        assertEquals(FaceQueryStatus.UNAVAILABLE, revoked.status)
        assertTrue(revoked.hits.isEmpty())
        val empty = FaceQueryEngine.search(corpus(emptyList()), FaceQuery(model, listOf(q)))
        assertEquals(FaceQueryStatus.OK, empty.status)
        assertTrue(empty.hits.isEmpty())
        assertEquals(0, empty.scannedFaces)
    }

    @Test fun seedThatLeftTheIndexFindsNothing() {
        val c = corpus(listOf(Row(1, 1, random())))
        val r = FaceQueryEngine.search(c, FaceQuery(model, seedFaces = setOf(id(99))))
        assertEquals(FaceQueryStatus.OK, r.status)
        assertTrue(r.hits.isEmpty())
    }

    @Test fun queryAndCorpusRefuseBadInput() {
        fun fails(block: () -> Unit) = try { block(); false } catch (_: IllegalArgumentException) { true }
        assertTrue(fails { FaceQuery(model) })
        assertTrue(fails { FaceQuery(model, listOf(FloatArray(64) { 1f })) })
        assertTrue(fails { FaceQuery(model, listOf(FloatArray(dim))) })
        assertTrue(fails { FaceQuery(model, listOf(random()), mediaLimit = 0) })
        val b = FaceSearchCorpus.Builder(model).add(id(1), media(1), box, null, random())
        assertTrue(fails { b.add(id(1), media(2), box, null, random()) })
        assertTrue(fails { b.add(id(2), IndexedMedia(media(1).key, "other"), box, null, random()) })
    }

    @Test fun queryVectorIsCopied() {
        val v = random()
        val q = FaceQuery(model, listOf(v))
        v.fill(0f)
        assertTrue(q.embeddings[0].any { it != 0f })
    }
}
