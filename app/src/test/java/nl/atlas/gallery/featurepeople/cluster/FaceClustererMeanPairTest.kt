package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepersistence.ClusterPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #580: mean-pair linkage, the crowd penalty and the same-picture veto.
 * Synthetic vectors only. The real-face numbers behind
 * [ClusterPolicy.SFACE_MEASURED] are in docs/people/PEOPLE_BENCH.md.
 */
class FaceClustererMeanPairTest {
    private val model = "sface-2021dec-int8"
    /** The shipped thresholds on ready-made synthetic vectors (the metric has its own test). */
    private val measured = ClusterPolicy.SFACE_MEASURED.copy(metric = null)
    private val dim = 128
    private var nextId = 1
    private fun id() = NativeFaceObservationId("%032x".format(nextId++))

    private fun Random.gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
    private fun unit(rng: Random): DoubleArray {
        val v = DoubleArray(dim) { rng.gaussian() }
        val n = sqrt(v.sumOf { it * it })
        return DoubleArray(dim) { v[it] / n }
    }
    /** Unit vector with cosine about [s] to [a]. */
    private fun mix(a: DoubleArray, rng: Random, s: Double): DoubleArray {
        val b = unit(rng)
        val r = DoubleArray(dim) { s * a[it] + sqrt(1 - s * s) * b[it] }
        val n = sqrt(r.sumOf { it * it })
        return DoubleArray(dim) { r[it] / n }
    }
    /** A face whose cosine to another face of the same [identity] is about [pair]. */
    private fun face(identity: DoubleArray, rng: Random, pair: Double, crowd: Float = 0f, frame: Long = 0L) =
        mix(identity, rng, sqrt(pair)).let { v ->
            FaceEmbedding(id(), model, FloatArray(dim) { v[it].toFloat() }, crowd, frame)
        }

    private fun meanPair(join: Float = 0.45f, crowdWeight: Float = 0f, group: Float = 1f, groupMin: Int = 0,
                         frames: Boolean = false) = ClusterPolicy(
        joinThreshold = join, ambiguityMargin = 0f, mergeThreshold = join,
        suggestThreshold = 0.5f, suggestMargin = 0.08f, linkage = ClusterLinkage.MEAN_PAIR,
        crowdWeight = crowdWeight, sameFrameVeto = frames, groupThreshold = group, groupMinSize = groupMin)

    private fun centroid(join: Float) = ClusterPolicy(join, 0f, join, 0.5f, 0.08f)

    private fun publish(policy: ClusterPolicy, faces: List<FaceEmbedding>, batch: Int = 256): FaceClusterState {
        var state = FaceClusterState.empty(model, dim, policy)
        for (chunk in faces.chunked(batch)) state = ClusterPublication.publish(state, chunk)
        return state
    }
    private fun sizes(state: FaceClusterState) = state.unknownClusters.map { it.members.size }

    @Test fun aMixedGroupSnowballsUnderCentroidLinkageButNotUnderMeanPair() {
        // Strangers share a direction: any two score about 0.2, below the 0.3 cut.
        val rng = Random(1)
        val common = unit(rng)
        val strangers = List(260) { face(common, rng, 0.2) }
        val seed = strangers.take(10).map { it.observation }
        val rest = strangers.drop(10)
        fun grow(policy: ClusterPolicy): FaceClusterState {
            // A mixed group of ten different people already exists (say, from an older policy).
            val start = FaceClusterer.restore(model, policy, strangers.take(10), listOf(seed),
                ClusterReviewFacts.NONE)
            return FaceClusterer.update(start, add = rest)
        }
        val byCentroid = grow(centroid(0.3f))
        assertTrue("centroid linkage: the mixed group absorbs most strangers, got ${sizes(byCentroid).first()}",
            sizes(byCentroid).first() > 200)
        val byMeanPair = grow(meanPair(join = 0.3f))
        val seedGroup = byMeanPair.unknownClusters.single { seed[0] in it.members }
        assertEquals("mean-pair linkage: the mixed group does not grow", seed.toSet(), seedGroup.members.toSet())
        assertTrue("no other group snowballs either: ${sizes(byMeanPair).take(3)}",
            sizes(byMeanPair).all { it <= 10 })
    }

    @Test fun onePersonStaysOneGroupAndStrangersStayOut() {
        val rng = Random(2)
        val person = unit(rng)
        val mine = List(60) { face(person, rng, 0.55) }
        val others = List(200) { face(unit(rng), rng, 0.9) }
        val state = publish(measured, (mine + others).shuffled(Random(3)))
        val big = state.unknownClusters.first()
        assertEquals(mine.map { it.observation }.toSet(), big.members.toSet())
        assertTrue(sizes(state).drop(1).all { it == 1 })
    }

    @Test fun crowdedFacesNeedMoreEvidence() {
        // Two different babies score 0.55 with each other: above the cut for typical faces.
        val rng = Random(4)
        val babyRegion = unit(rng)
        fun pair(crowd: Float) = listOf(face(babyRegion, rng, 0.55, crowd), face(babyRegion, rng, 0.55, crowd))
        assertEquals(listOf(2), sizes(publish(meanPair(crowdWeight = 1f), pair(crowd = 0f))))
        assertEquals(listOf(1, 1), sizes(publish(meanPair(crowdWeight = 1f), pair(crowd = 0.25f))))
        // The weight is what applies the penalty.
        assertEquals(listOf(2), sizes(publish(meanPair(crowdWeight = 0f), pair(crowd = 0.25f))))
        // The same child seen again scores far higher and is still grouped.
        val child = unit(rng)
        val same = listOf(face(child, rng, 0.85, 0.25f), face(child, rng, 0.85, 0.25f))
        assertEquals(listOf(2), sizes(publish(meanPair(crowdWeight = 1f), same)))
    }

    @Test fun groupsOfThreeAcceptAWeakerFaceThanALoneFaceDoes() {
        val rng = Random(5)
        val person = unit(rng)
        val core = List(4) { face(person, rng, 0.8) }
        // About 0.4 to each core face: under the lone-face cut 0.45, over the group cut 0.35.
        val weak = mix(person, rng, 0.4 / sqrt(0.8)).let { FaceEmbedding(id(), model, FloatArray(dim) { i -> it[i].toFloat() }) }
        val withGroupCut = publish(meanPair(group = 0.35f, groupMin = 3), core + weak)
        assertEquals(listOf(5), sizes(withGroupCut))
        val without = publish(meanPair(), core + weak)
        assertEquals(listOf(4, 1), sizes(without))
        // Against a single face the lone-face cut still applies.
        assertEquals(listOf(1, 1), sizes(publish(meanPair(group = 0.35f, groupMin = 3), listOf(core[0], weak))))
    }

    @Test fun twoFacesInOnePictureAreNeverGrouped() {
        val rng = Random(6)
        val twin = unit(rng)
        val a = face(twin, rng, 0.9, frame = 11L)
        val b = face(twin, rng, 0.9, frame = 11L)
        assertEquals(listOf(1, 1), sizes(publish(meanPair(frames = true), listOf(a, b))))
        assertEquals(listOf(2), sizes(publish(meanPair(frames = false), listOf(a, b))))
        // Unknown frames (0) never veto.
        val c = face(twin, rng, 0.9)
        val d = face(twin, rng, 0.9)
        assertEquals(listOf(2), sizes(publish(meanPair(frames = true), listOf(c, d))))
    }

    @Test fun groupsThatShareAPictureAreNotUnioned() {
        val rng = Random(7)
        val twinA = unit(rng)
        // Twins: every pair across the two scores like the same person, but they stand together in picture 99.
        val left = List(5) { face(twinA, rng, 0.8, frame = if (it == 0) 99L else 100L + it) }
        val right = List(5) { face(twinA, rng, 0.8, frame = if (it == 0) 99L else 200L + it) }
        val policy = meanPair(frames = true)
        val start = FaceClusterer.restore(model, policy, left + right,
            listOf(left.map { it.observation }, right.map { it.observation }), ClusterReviewFacts.NONE)
        val next = FaceClusterer.update(start, add = listOf(face(twinA, rng, 0.8, frame = 300L)))
        assertEquals(listOf(6, 5), sizes(next))
    }

    @Test fun cannotLinkStillWins() {
        val rng = Random(8)
        val person = unit(rng)
        val faces = List(6) { face(person, rng, 0.8) }
        val split = ClusterReviewFacts(cannotLink = setOf(FacePair.of(faces[0].observation, faces[1].observation)))
        val state = FaceClusterer.update(FaceClusterState.empty(model, dim, measured),
            add = faces, reviews = split)
        assertNotEquals(state.clusterOf(faces[0].observation), state.clusterOf(faces[1].observation))
    }

    @Test fun aStoredYesJoinsTwoGroupsButNeverTwoFacesOfOnePicture() {
        val rng = Random(21)
        val policy = meanPair(frames = true)
        val (a, b) = unit(rng) to unit(rng)
        fun group(identity: DoubleArray, frames: List<Long>) = frames.map { face(identity, rng, 0.8, frame = it) }
        fun joined(first: List<FaceEmbedding>, second: List<FaceEmbedding>): Boolean {
            val yes = ClusterReviewFacts(
                sameUnknown = setOf(FacePair.of(first[0].observation, second[0].observation)))
            val state = FaceClusterer.update(FaceClusterState.empty(model, dim, policy),
                add = first + second, reviews = yes)
            return state.clusterOf(first[0].observation) == state.clusterOf(second[0].observation)
        }
        // Different pictures throughout: the owner's yes makes them one group.
        assertTrue(joined(group(a, listOf(1L, 2L, 3L)), group(b, listOf(4L, 5L, 6L))))
        // One picture holds a face of each group: the yes is not applied.
        assertTrue(!joined(group(a, listOf(11L, 12L, 13L)), group(b, listOf(14L, 15L, 13L))))
    }

    @Test fun inputOrderDoesNotChangeTheResult() {
        val rng = Random(9)
        val people = List(8) { unit(rng) }
        val faces = people.flatMap { p -> List(12) { face(p, rng, 0.6) } } + List(60) { face(unit(rng), rng, 0.9) }
        fun groups(order: List<FaceEmbedding>) =
            FaceClusterer.update(FaceClusterState.empty(model, dim, measured), add = order)
                .unknownClusters.map { it.members }
        assertEquals(groups(faces), groups(faces.shuffled(Random(10))))
    }

    @Test fun oldPoliciesKeepTheirFingerprintAndNewOnesDiffer() {
        // A changed fingerprint regroups every library; the pre-#580 ones must not move.
        assertEquals("j=0.45;a=0.05;m=0.5;t=1.0;n=0", ClusterPolicy.SFACE_CONSERVATIVE.fingerprint)
        assertEquals("j=0.38;a=0.0;m=0.42;t=0.32;n=3", ClusterPolicy.SFACE_GALLERY.fingerprint)
        val measured = ClusterPolicy.SFACE_MEASURED.fingerprint
        assertTrue(measured, measured.endsWith(
            ";l=MEAN_PAIR;c=1.0;f=true;g=0.35;k=3;x=${FaceMetric.SFACE.version};p=0.45"))
        // Without the add-on-its-own rule the fingerprint is the one #592 shipped.
        assertTrue(ClusterPolicy.SFACE_MEASURED.copy(personMatchFloor = 0f).fingerprint.endsWith(
            ";l=MEAN_PAIR;c=1.0;f=true;g=0.35;k=3;x=${FaceMetric.SFACE.version}"))
        assertNotEquals(measured, ClusterPolicy.SFACE_MEASURED.copy(crowdWeight = 0.5f).fingerprint)
        assertNotEquals(measured, ClusterPolicy.SFACE_MEASURED.copy(sameFrameVeto = false).fingerprint)
    }

    @Test fun aPolicyWithAMetricGroupsTransformedVectors() {
        val shared = FloatArray(dim).also { it[0] = 1f }
        val metric = FaceMetric.of("t", FloatArray(dim) { if (it == 0) 0.3f else 0f }, listOf(shared), baseline = 2f)
        val policy = meanPair(frames = true).copy(metric = metric)
        val raw = FloatArray(dim).also { it[0] = 0.3f; it[1] = 0.95f }
        val e = policy.embedding(id(), model, raw, frame = 5L)
        assertEquals(5L, e.frame)
        assertEquals(0f, e.crowd, 0f)
        assertEquals(0f, e.unit[0], 0.02f) // the shared direction is gone
        assertEquals(1f, e.unit[1], 0.02f)
        // Without the veto the frame is dropped; without a metric the vector is the raw one.
        assertEquals(0L, meanPair().embedding(id(), model, raw, frame = 5L).frame)
        assertTrue(ClusterPolicy.SFACE_CONSERVATIVE.embedding(id(), model, raw, frame = 5L).unit[0] > 0.25f)
    }

    @Test fun policyFieldsAreValidated() {
        val base = ClusterPolicy.SFACE_CONSERVATIVE
        assertThrows(IllegalArgumentException::class.java) { base.copy(crowdWeight = 1f) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(groupMinSize = 3, groupThreshold = 0.3f) }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterPolicy.SFACE_MEASURED.copy(attachMinSize = 3, attachThreshold = 0.3f)
        }
        assertThrows(IllegalArgumentException::class.java) { ClusterPolicy.SFACE_MEASURED.copy(crowdWeight = -1f) }
        assertThrows(IllegalArgumentException::class.java) {
            FaceEmbedding(id(), model, FloatArray(dim) { 1f }, crowd = -0.1f)
        }
    }
}
