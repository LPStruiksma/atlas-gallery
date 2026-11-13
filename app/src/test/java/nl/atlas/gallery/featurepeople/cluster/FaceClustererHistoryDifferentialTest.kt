package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic differential witness for #423. This probes the pure Kotlin algorithm;
 * it does not benchmark SQLite, prove historical equivalence or use real media.
 *
 * In particular a single sorted replay from an empty state is NOT the same
 * operation as restoring committed membership and appending pending photos.
 */
class FaceClustererHistoryDifferentialTest {
    private val model = "synthetic-model-v1"
    private val dimension = 128
    private val policy = ClusterPolicy.SFACE_CONSERVATIVE

    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
    private fun face(n: Int, degrees: Double, version: String = model): FaceEmbedding {
        val radians = degrees * PI / 180.0
        return FaceEmbedding(id(n), version, FloatArray(dimension) {
            when (it) {
                0 -> cos(radians).toFloat()
                1 -> sin(radians).toFloat()
                else -> 0f
            }
        })
    }

    private fun empty(version: String = model) =
        FaceClusterState.empty(version, dimension, policy)

    private fun partition(state: FaceClusterState): Set<Set<NativeFaceObservationId>> =
        state.unknownClusters.map { it.members.toSet() }.toSet()

    private fun canonical(state: FaceClusterState): List<String> =
        state.unknownClusters.map { c ->
            c.members.joinToString(",") { it.localHex }
        }.sorted()

    private fun assertConservative(state: FaceClusterState) {
        val unknown = state.unknownClusters.flatMap { it.members }
        assertEquals(unknown.size, unknown.toSet().size)
        assertEquals(state.faceCount - state.reviews.confirmed.keys.count { it in state },
            unknown.size)
        for (cluster in state.unknownClusters) {
            assertEquals(cluster.members.sortedBy { it.localHex }, cluster.members)
            assertEquals(cluster.members.first(), cluster.id.anchor)
            for (a in cluster.members) {
                assertFalse(a in state.reviews.confirmed)
                assertEquals(cluster.id, state.clusterOf(a))
            }
            for (link in state.reviews.cannotLink) {
                assertFalse(link.first in cluster.members && link.second in cluster.members)
            }
        }
        for (f in state.reviews.confirmed.keys) assertEquals(null, state.clusterOf(f))
    }

    private fun sequential(photos: List<List<FaceEmbedding>>): FaceClusterState =
        photos.fold(empty()) { state, photo -> FaceClusterer.update(state, add = photo) }

    private fun replay(photos: List<List<FaceEmbedding>>): FaceClusterState =
        FaceClusterer.update(empty(), add = photos.flatten().sortedBy { it.observation.localHex })

    private fun restored(seed: FaceClusterState, pending: List<FaceEmbedding>,
                         reviews: ClusterReviewFacts = seed.reviews): FaceClusterState {
        // Mirrors the persisted-membership seed, then one-face-at-a-time replay.
        // Suppressed/confirmed faces belong in the seed's face collection too.
        var state = FaceClusterer.restore(seed.modelVersion, policy, seed.faces.values,
            seed.unknownClusters.map { it.members }, reviews)
        for (f in pending.sortedBy { it.observation.localHex }) {
            state = FaceClusterer.update(state, add = listOf(f))
        }
        return state
    }

    @Test fun photoAndBatchOrderMatrixProducesExplicitDifferentialRecord() {
        // 0° and 110° are distinct; the 55° bridge is borderline and order
        // matters because a join is looser than a cluster-to-cluster merge.
        val a = face(10, 0.0)
        val b = face(20, 110.0)
        val bridge = face(30, 55.0)
        val histories = listOf(
            listOf(listOf(a), listOf(b), listOf(bridge)),
            listOf(listOf(bridge), listOf(a), listOf(b)),
            listOf(listOf(a, b), listOf(bridge)),
            listOf(listOf(a, bridge, b)),
            listOf(listOf(b), listOf(bridge), listOf(a))
        )
        val witnesses = mutableListOf<String>()
        for ((index, photos) in histories.withIndex()) {
            val old = sequential(photos)
            val coalesced = replay(photos)
            assertConservative(old)
            assertConservative(coalesced)
            assertEquals(photos.flatten().map { it.observation }.toSet(),
                old.faces.keys)
            assertEquals(old.faces.keys, coalesced.faces.keys)
            if (partition(old) != partition(coalesced)) {
                witnesses += "history=$index sequential=${canonical(old)} replay=${canonical(coalesced)}"
            }
        }
        // A differential is a finding, not a failed invariant. Pin its
        // existence so later refactors cannot silently claim equivalence.
        assertTrue("Expected at least one borderline order witness; histories=$histories",
            witnesses.isNotEmpty())
        assertTrue(witnesses.joinToString("\n"), witnesses.all { "sequential=" in it && "replay=" in it })
    }

    @Test fun persistedAnchorSeedRespectsRemovalRescanAndRestart() {
        val a = face(1, 0.0)
        val b = face(2, 8.0)
        val c = face(3, 110.0)
        val first = sequential(listOf(listOf(a), listOf(b), listOf(c)))
        assertConservative(first)
        val afterDeletion = FaceClusterer.update(first, remove = listOf(a.observation))
        assertFalse(a.observation in afterDeletion)
        assertConservative(afterDeletion)
        val rescanned = face(40, 3.0)
        val historical = FaceClusterer.update(afterDeletion, add = listOf(rescanned))
        val restored = restored(afterDeletion, listOf(rescanned))
        assertConservative(historical)
        assertConservative(restored)
        assertEquals(partition(historical), partition(restored))
        assertEquals(afterDeletion.faces.keys + rescanned.observation, historical.faces.keys)
        assertEquals(id(2), historical.unknownClusters.first {
            b.observation in it.members
        }.id.anchor)
    }

    @Test fun confirmationAndCannotLinkAreHardBoundariesAcrossReplay() {
        val a = face(1, 0.0)
        val b = face(2, 4.0)
        val c = face(3, 9.0)
        val d = face(4, 2.0)
        val seed = sequential(listOf(listOf(a), listOf(b), listOf(c)))
        val facts = ClusterReviewFacts(
            confirmed = mapOf(a.observation to PersonKey("confirmed-synthetic")),
            cannotLink = setOf(FacePair.of(b.observation, c.observation)),
            rejectedPeople = setOf(RejectedPerson(d.observation,
                PersonKey("confirmed-synthetic")))
        )
        val historical = FaceClusterer.update(seed, add = listOf(d), reviews = facts)
        val replayed = restored(seed, listOf(d), reviews = facts)
        val fromScratch = FaceClusterer.update(empty(), add = listOf(a, b, c, d),
            reviews = facts)
        for (state in listOf(historical, replayed, fromScratch)) {
            assertConservative(state)
            assertEquals(facts, state.reviews)
            assertEquals(null, state.clusterOf(a.observation))
            assertTrue(state.unknownClusters.none {
                b.observation in it.members && c.observation in it.members
            })
        }
    }

    @Test fun modelResetRequiresNewStateAndDoesNotCarryStaleAnchors() {
        val old = sequential(listOf(listOf(face(1, 0.0)), listOf(face(2, 3.0))))
        val fresh = face(100, 2.0, "synthetic-model-v2")
        val reset = FaceClusterer.update(empty("synthetic-model-v2"), add = listOf(fresh))
        assertConservative(old)
        assertConservative(reset)
        assertEquals(setOf(fresh.observation), reset.faces.keys)
        assertEquals(fresh.observation, reset.unknownClusters.single().id.anchor)
        assertTrue(old.faces.keys.intersect(reset.faces.keys).isEmpty())
        var rejected = false
        try {
            FaceClusterer.update(old, add = listOf(fresh))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("A model upgrade must never reuse incompatible embedding state", rejected)
    }
}
