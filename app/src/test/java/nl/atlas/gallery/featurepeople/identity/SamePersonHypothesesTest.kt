package nl.atlas.gallery.featurepeople.identity

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.FacePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #580: "same person?" questions between unnamed groups. Synthetic vectors
 * only: one person seen in two "looks" (two directions at cosine 0.37, faces
 * tight around each), which grouping keeps as two groups, plus strangers.
 */
class SamePersonHypothesesTest {
    private val model = "m"
    private val dim = 128
    private val policy = ClusterPolicy.SFACE_MEASURED.copy(metric = null)
    private var nextId = 1
    private var nextFrame = 1L

    private fun Random.gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
    private fun unit(rng: Random): DoubleArray {
        val v = DoubleArray(dim) { rng.gaussian() }
        val n = sqrt(v.sumOf { it * it })
        return DoubleArray(dim) { v[it] / n }
    }
    private fun mix(a: DoubleArray, b: DoubleArray, s: Double): DoubleArray {
        // Remove b's part along a first, so the cosine to a is exactly s.
        val along = (0 until dim).sumOf { a[it] * b[it] }
        val o = DoubleArray(dim) { b[it] - along * a[it] }
        val n = sqrt(o.sumOf { it * it })
        return DoubleArray(dim) { s * a[it] + sqrt(1 - s * s) * o[it] / n }
    }
    private fun face(look: DoubleArray, rng: Random, frame: Long = nextFrame++): FaceEmbedding {
        val f = mix(look, unit(rng), 0.95)
        return FaceEmbedding(NativeFaceObservationId("%032x".format(nextId++)), model,
            FloatArray(dim) { f[it].toFloat() }, 0f, frame)
    }

    private class Scene(val state: FaceClusterState, val lookA: List<FaceEmbedding>,
                        val lookB: List<FaceEmbedding>, val stranger: List<FaceEmbedding>)

    private fun scene(reviews: ClusterReviewFacts? = null, sharedFrame: Boolean = false,
                      policy: ClusterPolicy = this.policy): Scene {
        nextId = 1; nextFrame = 1L
        val rng = Random(580)
        val a = unit(rng)
        val b = mix(a, unit(rng), 0.37)
        val other = unit(rng)
        val lookA = List(6) { face(a, rng) }
        val lookB = List(5) { face(b, rng, if (sharedFrame && it == 0) lookA[0].frame else nextFrame++) }
        val stranger = List(4) { face(other, rng) }
        val facts = reviews ?: ClusterReviewFacts.NONE
        val state = FaceClusterer.update(FaceClusterState.empty(model, dim, policy),
            add = lookA + lookB + stranger, reviews = facts)
        return Scene(state, lookA, lookB, stranger)
    }

    @Test fun twoLooksOfOnePersonStayTwoGroupsAndBecomeOneQuestion() {
        val s = scene()
        assertEquals(listOf(6, 5, 4), s.state.unknownClusters.map { it.members.size })
        val asked = SamePersonHypotheses.of(s.state)
        assertEquals(1, asked.size)
        val q = asked.single()
        assertEquals(s.state.clusterOf(s.lookA[0].observation), q.first)
        assertEquals(s.state.clusterOf(s.lookB[0].observation), q.second)
        assertEquals(6, q.firstFaces); assertEquals(5, q.secondFaces)
        assertTrue("mean ${q.meanPair}", q.meanPair in 0.30f..0.35f)
        assertEquals(listOf(EvidenceLayer.FACE), q.evidence.map { it.layer })
        // Asking changes nothing.
        assertEquals(listOf(6, 5, 4), s.state.unknownClusters.map { it.members.size })
        assertEquals(asked, SamePersonHypotheses.of(s.state))
    }

    @Test fun notTheSamePersonIsNeverAskedAgain() {
        val probe = scene()
        val no = ClusterReviewFacts(cannotLink = setOf(
            FacePair.of(probe.lookA[3].observation, probe.lookB[2].observation)))
        assertTrue(SamePersonHypotheses.of(scene(reviews = no).state).isEmpty())
    }

    @Test fun yesJoinsTheTwoGroupsForGoodAndTheQuestionIsGone() {
        val probe = scene()
        val q = SamePersonHypotheses.of(probe.state).single()
        // The answer is stored between one face of each group (their anchors).
        val yes = ClusterReviewFacts(sameUnknown = setOf(FacePair.of(q.first.anchor, q.second.anchor)))
        val joined = FaceClusterer.update(probe.state, reviews = yes)
        assertEquals(listOf(11, 4), joined.unknownClusters.map { it.members.size })
        assertTrue(SamePersonHypotheses.of(joined).isEmpty())
        // It survives later scans and a regroup from scratch.
        val rng = Random(1)
        val later = FaceClusterer.update(joined, add = List(3) { face(unit(rng), rng) })
        assertEquals(11, later.unknownClusters.first().members.size)
        assertEquals(listOf(11, 4), scene(reviews = yes).state.unknownClusters.map { it.members.size })
    }

    @Test fun notTheSamePersonBeatsALaterYes() {
        val probe = scene()
        val q = SamePersonHypotheses.of(probe.state).single()
        val both = ClusterReviewFacts(
            cannotLink = setOf(FacePair.of(probe.lookA[3].observation, probe.lookB[2].observation)),
            sameUnknown = setOf(FacePair.of(q.first.anchor, q.second.anchor)))
        assertEquals(listOf(6, 5, 4), scene(reviews = both).state.unknownClusters.map { it.members.size })
        val state = FaceClusterer.update(probe.state, reviews = both)
        for (c in state.unknownClusters) assertTrue(
            !(probe.lookA[3].observation in c.members && probe.lookB[2].observation in c.members))
    }

    @Test fun twoFacesInOnePictureAreNeverAskedAbout() {
        assertTrue(SamePersonHypotheses.of(scene(sharedFrame = true).state).isEmpty())
    }

    @Test fun strangersAndLoneFacesAreNotAskedAbout() {
        val s = scene()
        val ids = SamePersonHypotheses.of(s.state).flatMap { listOf(it.first, it.second) }
        assertTrue(s.state.clusterOf(s.stranger[0].observation) !in ids)
    }

    @Test fun centroidGroupingGetsNoQuestions() {
        val s = scene(policy = ClusterPolicy.SFACE_CONSERVATIVE)
        assertTrue(SamePersonHypotheses.of(s.state).isEmpty())
    }

    @Test fun anExtraLayerCanVetoOrSupportButNeverSpeakForFaces() {
        val s = scene()
        assertTrue(SamePersonHypotheses.of(s.state, listOf(EvidenceSource {
            EvidenceReading(EvidenceLayer.CONTEXT, 0f, veto = true) })).isEmpty())
        val plain = SamePersonHypotheses.of(s.state).single()
        val backed = SamePersonHypotheses.of(s.state, listOf(EvidenceSource {
            EvidenceReading(EvidenceLayer.ATTIRE, 1f) })).single()
        assertEquals(listOf(EvidenceLayer.FACE, EvidenceLayer.ATTIRE), backed.evidence.map { it.layer })
        assertTrue(backed.strength > plain.strength)
        assertEquals(plain.meanPair, backed.meanPair, 0f)
        try {
            SamePersonHypotheses.of(s.state, listOf(EvidenceSource {
                EvidenceReading(EvidenceLayer.FACE, 1f) }))
            fail("a source claimed the face layer")
        } catch (_: IllegalArgumentException) { }
        assertTrue(SamePersonHypotheses.of(s.state, limit = 0).isEmpty())
    }
}
