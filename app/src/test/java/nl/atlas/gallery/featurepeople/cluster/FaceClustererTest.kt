package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * All vectors here are synthetic (seeded random "identities" plus noise).
 * No real faces, photos or model outputs are used, so these tests prove the
 * algorithm's rules, not its accuracy on real SFace embeddings.
 */
class FaceClustererTest {
    private val model = "sface-2021dec-int8"
    private val dim = 128
    private val policy = ClusterPolicy.SFACE_CONSERVATIVE

    private var nextId = 1
    private fun id(): NativeFaceObservationId =
        NativeFaceObservationId("%032x".format(nextId++))

    private fun unitRandom(rng: Random): FloatArray {
        val v = FloatArray(dim) { rng.nextGaussian() }
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(dim) { v[it] / n }
    }

    /** Noise of norm ~0.8 gives face-to-identity cosine ~0.78, face-to-face ~0.6. */
    private fun faceOf(identity: FloatArray, rng: Random, noise: Float = 0.8f): FaceEmbedding {
        val sigma = noise / sqrt(dim.toFloat())
        return FaceEmbedding(id(), model,
            FloatArray(dim) { identity[it] + sigma * rng.nextGaussian() })
    }

    private fun Random.nextGaussian(): Float {
        // Box-Muller; kotlin.random has no Gaussian.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }

    private fun empty() = FaceClusterState.empty(model, dim, policy)

    private fun partition(state: FaceClusterState): Set<Set<NativeFaceObservationId>> =
        state.unknownClusters.map { it.members.toSet() }.toSet()

    private fun dataset(identities: Int, perIdentity: Int, seed: Int):
        List<List<FaceEmbedding>> {
        val rng = Random(seed)
        return List(identities) {
            val identity = unitRandom(rng)
            List(perIdentity) { faceOf(identity, rng) }
        }
    }

    private inline fun assertRejected(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun separatesSyntheticIdentitiesExactly() {
        val groups = dataset(identities = 6, perIdentity = 10, seed = 7)
        val state = FaceClusterer.update(empty(), add = groups.flatten())
        assertEquals(groups.map { g -> g.map { it.observation }.toSet() }.toSet(),
            partition(state))
        assertTrue(state.unknownClusters.all { it.suggestion == null })
    }

    @Test fun resultIsIndependentOfInputOrderAndIdsAreAnchors() {
        val faces = dataset(5, 8, seed = 11).flatten()
        val a = FaceClusterer.update(empty(), add = faces)
        val b = FaceClusterer.update(empty(), add = faces.shuffled(Random(3)))
        assertEquals(a.unknownClusters, b.unknownClusters)
        for (c in a.unknownClusters) {
            assertEquals(c.members.minByOrNull { it.localHex }, c.id.anchor)
            assertEquals(c.members.sortedBy { it.localHex }, c.members)
        }
    }

    @Test fun incrementalBatchesReachTheSamePartitionOnSeparableData() {
        val groups = dataset(4, 12, seed = 5)
        val all = groups.flatten()
        val oneShot = FaceClusterer.update(empty(), add = all)
        var inc = empty()
        for (chunk in all.shuffled(Random(9)).chunked(7)) {
            inc = FaceClusterer.update(inc, add = chunk)
        }
        assertEquals(partition(oneShot), partition(inc))
    }

    @Test fun confirmedFacesNeverEnterUnknownClustersAndGetOnlySuggestions() {
        val rng = Random(21)
        val identity = unitRandom(rng)
        val confirmedFaces = List(5) { faceOf(identity, rng) }
        val unknownFaces = List(5) { faceOf(identity, rng) }
        val alice = PersonKey("person-alice")
        val reviews = ClusterReviewFacts(confirmed = confirmedFaces.associate { it.observation to alice })
        val state = FaceClusterer.update(empty(), add = confirmedFaces + unknownFaces,
            reviews = reviews)
        assertEquals(1, state.unknownClusters.size)
        val cluster = state.unknownClusters.single()
        assertEquals(unknownFaces.map { it.observation }.toSet(), cluster.members.toSet())
        confirmedFaces.forEach { assertNull(state.clusterOf(it.observation)) }
        assertEquals(alice, cluster.suggestion?.person)
        // The suggestion did not change the review facts.
        assertEquals(reviews, state.reviews)
    }

    @Test fun twoConfirmedPeopleWithIdenticalVectorsAreNeverMerged() {
        val rng = Random(4)
        val v = unitRandom(rng)
        val a = FaceEmbedding(id(), model, v)
        val b = FaceEmbedding(id(), model, v.copyOf())
        val u = FaceEmbedding(id(), model, v.copyOf())
        val reviews = ClusterReviewFacts(confirmed = mapOf(
            a.observation to PersonKey("p1"), b.observation to PersonKey("p2")))
        val state = FaceClusterer.update(empty(), add = listOf(a, b, u), reviews = reviews)
        assertNull(state.clusterOf(a.observation))
        assertNull(state.clusterOf(b.observation))
        // Equally similar to two confirmed people: no suggestion, no guess.
        assertNull(state.unknownClusters.single().suggestion)
        assertEquals(listOf(u.observation), state.unknownClusters.single().members)
    }

    @Test fun cannotLinkSplitsAnExistingClusterAndVetoesRejoining() {
        val faces = dataset(1, 6, seed = 13).single()
        val before = FaceClusterer.update(empty(), add = faces)
        assertEquals(1, before.unknownClusters.size)
        val x = faces[0].observation
        val y = faces[1].observation
        val split = ClusterReviewFacts(cannotLink = setOf(FacePair.of(x, y)))
        val after = FaceClusterer.update(before, reviews = split)
        assertNotNull(after.clusterOf(x))
        assertTrue(after.clusterOf(x) != after.clusterOf(y))
        // Adding more faces of the same synthetic identity still cannot rejoin them.
        val rng = Random(99)
        val more = List(10) { FaceEmbedding(id(), model, faces[it % faces.size].unit.copyOf()
            .also { v -> v[it] += 0.05f * rng.nextFloat() }) }
        val later = FaceClusterer.update(after, add = more)
        assertTrue(later.clusterOf(x) != later.clusterOf(y))
        for (c in later.unknownClusters) assertFalse(x in c.members && y in c.members)
    }

    @Test fun cannotLinkWithAConfirmedFaceSuppressesThatSuggestion() {
        val rng = Random(31)
        val identity = unitRandom(rng)
        val confirmed = List(4) { faceOf(identity, rng) }
        val unknown = List(3) { faceOf(identity, rng) }
        val bob = PersonKey("person-bob")
        val base = ClusterReviewFacts(confirmed = confirmed.associate { it.observation to bob })
        val s1 = FaceClusterer.update(empty(), add = confirmed + unknown, reviews = base)
        assertEquals(bob, s1.unknownClusters.single().suggestion?.person)
        val split = base.copy(cannotLink = setOf(FacePair.of(unknown[0].observation,
            confirmed[0].observation)))
        val s2 = FaceClusterer.update(s1, reviews = split)
        assertTrue(s2.unknownClusters.none { it.suggestion?.person == bob &&
            unknown[0].observation in it.members })
    }

    @Test fun rejectedPersonSuppressesSuggestion() {
        val rng = Random(17)
        val identity = unitRandom(rng)
        val confirmed = List(4) { faceOf(identity, rng) }
        val unknown = List(3) { faceOf(identity, rng) }
        val carol = PersonKey("person-carol")
        val reviews = ClusterReviewFacts(
            confirmed = confirmed.associate { it.observation to carol },
            rejectedPeople = setOf(RejectedPerson(unknown[1].observation, carol)))
        val state = FaceClusterer.update(empty(), add = confirmed + unknown, reviews = reviews)
        assertTrue(state.unknownClusters.all { it.suggestion == null })
    }

    @Test fun newlyConfirmedFacesLeaveClustersAndUndoReplacesThem() {
        val faces = dataset(1, 5, seed = 41).single()
        val s1 = FaceClusterer.update(empty(), add = faces)
        val named = ClusterReviewFacts(confirmed = faces.associate { it.observation to PersonKey("d") })
        val s2 = FaceClusterer.update(s1, reviews = named)
        assertTrue(s2.unknownClusters.isEmpty())
        val s3 = FaceClusterer.update(s2, reviews = ClusterReviewFacts.NONE)
        assertEquals(partition(s1), partition(s3))
    }

    @Test fun ambiguousFaceBetweenTwoClustersStaysAlone() {
        val rng = Random(2)
        val a = unitRandom(rng)
        val b0 = unitRandom(rng)
        // Make b ~0.2 similar to a, so a midpoint is close to both centroids.
        val b = FloatArray(dim) { 0.2f * a[it] + 0.98f * b0[it] }
        val groupA = List(8) { faceOf(a, rng, noise = 0.4f) }
        val groupB = List(8) { faceOf(b, rng, noise = 0.4f) }
        val s1 = FaceClusterer.update(empty(), add = groupA + groupB)
        assertEquals(2, s1.unknownClusters.size)
        val mid = FaceEmbedding(id(), model, FloatArray(dim) { a[it] + b[it] })
        val s2 = FaceClusterer.update(s1, add = listOf(mid))
        assertEquals(3, s2.unknownClusters.size)
        assertEquals(listOf(mid.observation),
            s2.unknownClusters.single { mid.observation in it.members }.members)
    }

    @Test fun removalDropsFacesAndMovesTheAnchor() {
        val faces = dataset(1, 4, seed = 8).single()
        val s1 = FaceClusterer.update(empty(), add = faces)
        val anchor = s1.unknownClusters.single().id.anchor
        val s2 = FaceClusterer.update(s1, remove = listOf(anchor))
        assertFalse(anchor in s2)
        assertEquals(3, s2.unknownClusters.single().members.size)
        assertTrue(s2.unknownClusters.single().id.anchor != anchor)
        // Removing an absent face is a no-op.
        assertEquals(s2.unknownClusters, FaceClusterer.update(s2, remove = listOf(anchor)).unknownClusters)
    }

    @Test fun restoreKeepsPersistedMembershipAndPlacesTheRest() {
        val groups = dataset(3, 5, seed = 19)
        val s1 = FaceClusterer.update(empty(), add = groups.flatten())
        val extra = faceOf(groups[0][0].unit, Random(1), noise = 0.2f)
        val restored = FaceClusterer.restore(model, policy, groups.flatten() + extra,
            s1.unknownClusters.map { it.members }, ClusterReviewFacts.NONE)
        assertEquals(s1.clusterOf(groups[0][0].observation), restored.clusterOf(extra.observation))
        assertEquals(15 + 1, restored.unknownClusters.sumOf { it.members.size })
        assertRejected {
            FaceClusterer.restore(model, policy, groups.flatten(),
                listOf(listOf(groups[0][0].observation), listOf(groups[0][0].observation)),
                ClusterReviewFacts.NONE)
        }
    }

    @Test fun rejectsInvalidOrIncompatibleEmbeddings() {
        assertRejected { FaceEmbedding(id(), model, FloatArray(dim)) }
        assertRejected { FaceEmbedding(id(), model, FloatArray(dim) { Float.NaN }) }
        assertRejected { FaceEmbedding(id(), " ", FloatArray(dim) { 1f }) }
        val other = FaceEmbedding(id(), "other-model", FloatArray(dim) { 1f })
        assertRejected { FaceClusterer.update(empty(), add = listOf(other)) }
        val short = FaceEmbedding(id(), model, FloatArray(dim - 1) { 1f })
        assertRejected { FaceClusterer.update(empty(), add = listOf(short)) }
        val e = FaceEmbedding(id(), model, FloatArray(dim) { 1f })
        val s = FaceClusterer.update(empty(), add = listOf(e))
        assertRejected { FaceClusterer.update(s, add = listOf(e)) }
        assertRejected { FacePair.of(e.observation, e.observation) }
        assertRejected { ClusterPolicy(0.6f, 0.05f, 0.5f, 0.5f, 0.1f) }
    }

    @Test fun embeddingCopiesInputVector() {
        val raw = FloatArray(dim) { 1f }
        val e = FaceEmbedding(id(), model, raw)
        raw[0] = -100f
        assertTrue(e.unit.all { it > 0f })
    }

    @Test fun moderateGalleryClustersCleanly() {
        val groups = dataset(identities = 40, perIdentity = 30, seed = 77)
        var state = empty()
        for (chunk in groups.flatten().shuffled(Random(5)).chunked(100)) {
            state = FaceClusterer.update(state, add = chunk)
        }
        assertEquals(1200, state.faceCount)
        // Purity: no cluster mixes two synthetic identities.
        val identityOf = HashMap<NativeFaceObservationId, Int>()
        groups.forEachIndexed { i, g -> g.forEach { identityOf[it.observation] = i } }
        for (c in state.unknownClusters) {
            assertEquals(1, c.members.map { identityOf.getValue(it) }.toSet().size)
        }
        assertEquals(40, state.unknownClusters.size)
    }

    @Test fun zeroNormRestoredClusterGetsNoSuggestionInsteadOfCrashing() {
        // Review finding on #409: opposite vectors cancel to a zero centroid,
        // whose -Infinity similarity tied the initial best and hit best!!.
        val v = FloatArray(dim) { if (it == 0) 1f else 0f }
        val up = FaceEmbedding(id(), model, v)
        val down = FaceEmbedding(id(), model, FloatArray(dim) { -v[it] })
        val known = FaceEmbedding(id(), model, v.copyOf())
        val reviews = ClusterReviewFacts(confirmed = mapOf(known.observation to PersonKey("p")))
        val state = FaceClusterer.restore(model, policy, listOf(up, down, known),
            listOf(listOf(up.observation, down.observation)), reviews)
        assertNull(state.unknownClusters.single().suggestion)
        assertEquals(2, state.unknownClusters.single().members.size)
    }
}
