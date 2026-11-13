package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepersistence.ClusterPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * #580: the store publishes with [ClusterPolicy.DEFAULT], so the synthetic
 * vectors the instrumented store tests use (one-hot axes, pairs at a fixed
 * cosine) must keep their meaning once they pass through the face metric:
 * equal vectors are one person, different axes are strangers.
 */
class DefaultPolicyFixtureTest {
    private val model = "sface-2021dec-int8"
    private val dim = 128
    private val policy = ClusterPolicy.DEFAULT
    private var nextId = 1
    private fun face(raw: FloatArray, frame: Long = nextId.toLong()) = policy.embedding(
        NativeFaceObservationId("%032x".format(nextId++)), model, raw, frame)
    private fun axis(i: Int) = FloatArray(dim) { if (it == i) 1f else 0f }
    private fun sizes(state: FaceClusterState) = state.unknownClusters.map { it.members.size }.sorted()
    private fun publish(faces: List<FaceEmbedding>, reviews: ClusterReviewFacts = ClusterReviewFacts.NONE) =
        ClusterPublication.publish(FaceClusterer.update(
            FaceClusterState.empty(model, dim, policy), reviews = reviews), faces)

    @Test fun defaultIsTheMeasuredPolicy() {
        assertEquals(ClusterPolicy.SFACE_MEASURED, policy)
        assertTrue(policy.fingerprint != ClusterPolicy.SFACE_CONSERVATIVE.fingerprint)
    }

    @Test fun equalAxesAreOnePersonAndOtherAxesAreStrangers() {
        for (a in 0 until dim step 7) {
            val state = publish(List(3) { face(axis(a)) } + face(axis((a + 5) % dim)))
            assertEquals("axis $a", listOf(1, 3), sizes(state))
        }
        val all = publish(List(dim) { face(axis(it)) })
        assertEquals(dim, all.unknownClusters.size)
    }

    @Test fun aLaterEqualFaceJoinsTheConfirmedPersonOnItsOwn() {
        val known = List(3) { face(axis(0)) }
        val person = PersonKey("p1")
        val newcomer = face(axis(0))
        val all = known + newcomer + face(axis(5))
        val facts = ClusterReviewFacts(confirmed = known.associate { it.observation to person })
        val state = publish(all, facts)
        // The default adds a face it is sure about without asking (owner, 2026-11-12).
        assertEquals(person, state.matched[newcomer.observation]?.person)
        assertEquals(setOf(newcomer.observation), state.matched.keys)
        assertTrue(state.unknownClusters.none { newcomer.observation in it.members })
        // With the rule off the face waits as a suggestion, as before.
        val asking = FaceClusterer.update(FaceClusterState.empty(model, dim,
            policy.copy(personMatchFloor = 0f)), add = all, reviews = facts)
        val group = asking.unknownClusters.single { newcomer.observation in it.members }
        assertEquals(1, group.members.size)
        assertEquals(person, group.suggestion?.person)
        assertTrue(asking.matched.isEmpty())
    }

    /** FaceIndexStoreRegroupTest's vectors: three faces per person, every pair at 0.55. */
    @Test fun pairsAtPointFiveFiveFormGroupsOfThree() {
        fun vector(photo: Int): FloatArray {
            val a = (photo / 3) * 3
            val v = FloatArray(dim)
            when (photo % 3) {
                0 -> v[a] = 1f
                1 -> { v[a] = 0.55f; v[a + 1] = sqrt(1f - 0.55f * 0.55f) }
                else -> {
                    val y = (0.55f - 0.55f * 0.55f) / sqrt(1f - 0.55f * 0.55f)
                    v[a] = 0.55f; v[a + 1] = y; v[a + 2] = sqrt(1f - 0.55f * 0.55f - y * y)
                }
            }
            return v
        }
        for (base in 0 until 120 step 6) {
            val faces = List(6) { face(vector(it).let { v -> FloatArray(dim) { i -> v[(i - base + dim) % dim] } }) }
            assertEquals("base $base", listOf(3, 3), sizes(publish(faces)))
        }
    }

    @Test fun twoEqualFacesInOnePictureStayApart() {
        val state = publish(listOf(face(axis(1), frame = 9), face(axis(1), frame = 9)))
        assertEquals(listOf(1, 1), sizes(state))
    }
}
