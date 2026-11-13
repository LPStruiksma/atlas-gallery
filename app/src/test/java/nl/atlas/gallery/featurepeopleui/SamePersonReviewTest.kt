package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.identity.ReviewTally
import nl.atlas.gallery.featurepeople.identity.ReviewTap
import nl.atlas.gallery.featurepeople.identity.SamePersonHypotheses
import nl.atlas.gallery.featurepersistence.PeopleReviewRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #580: answering "Same person?" on an unnamed person's page. Synthetic
 * vectors only: one person in two looks (two groups), and a stranger.
 */
class SamePersonReviewTest {
    private val dim = 128
    private var nextId = 1

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
        val along = (0 until dim).sumOf { a[it] * b[it] }
        val o = DoubleArray(dim) { b[it] - along * a[it] }
        val n = sqrt(o.sumOf { it * it })
        return DoubleArray(dim) { s * a[it] + sqrt(1 - s * s) * o[it] / n }
    }
    private fun face(
        look: DoubleArray, rng: Random, frame: Long? = null, closeness: Double = 0.95
    ): FaceEmbedding {
        val f = mix(look, unit(rng), closeness)
        val id = nextId++
        return FaceEmbedding(NativeFaceObservationId("%032x".format(id)), "m",
            FloatArray(dim) { f[it].toFloat() }, 0f, frame ?: id.toLong())
    }
    private val rng = Random(580)
    private lateinit var lookA: DoubleArray
    private lateinit var lookOther: DoubleArray

    private fun state(): PeopleClusterReviewState {
        nextId = 1
        val a = unit(rng)
        val b = mix(a, unit(rng), 0.37)
        val other = unit(rng)
        lookA = a
        lookOther = other
        val faces = List(6) { face(a, rng) } + List(5) { face(b, rng) } + List(4) { face(other, rng) }
        val clusters = FaceClusterer.update(FaceClusterState.empty("m", dim,
            ClusterPolicy.SFACE_MEASURED.copy(metric = null)), add = faces)
        assertEquals(listOf(6, 5, 4), clusters.unknownClusters.map { it.members.size })
        return PeopleClusterReviewState("epoch", "generation", clusters)
    }
    private fun PeopleClusterReviewState.sizes() = clusters.unknownClusters.map { it.members.size }
    private fun PeopleClusterReviewState.page(n: Int) =
        PersonPageTarget.Unnamed(clusters.unknownClusters[n].id)

    @Test fun bothPagesAskAboutEachOtherAndAStrangerOrNamedPageAsksNothing() {
        val s = state()
        val big = s.page(0); val small = s.page(1)
        val q = requireNotNull(samePersonQuestion(s, big))
        assertEquals(small.cluster, q.other)
        assertEquals(5, q.otherFaces)
        assertEquals(small.cluster.anchor, q.otherCover)
        assertEquals(big.cluster, samePersonQuestion(s, small)?.other)
        assertNull(samePersonQuestion(s, s.page(2)))
        assertNull(samePersonQuestion(s, PersonPageTarget.Named(PersonKey("p"))))
        assertNull(samePersonQuestion(null, big))
    }

    @Test fun yesJoinsThePageFollowsUndoRestoresAndTheAnswerIsStored() {
        val s = state()
        val page = s.page(1)
        val q = requireNotNull(samePersonQuestion(s, page))
        val action = samePersonAnswer(page, q, same = true)
        val joined = requireNotNull(reviewPeopleCluster(s, action))
        assertEquals(listOf(11, 4), joined.sizes())
        val after = targetAfter(page, action)
        assertEquals(11, personPageFacts(joined, after)?.faces?.size)
        assertNull(samePersonQuestion(joined, after))
        // The stored rows round-trip, so the join survives a restart and a regroup.
        val rows = PeopleReviewRecords.rows(joined.clusters.reviews)
        assertEquals(1, rows.sameUnknown.size)
        assertEquals(joined.clusters.reviews, PeopleReviewRecords.facts(rows))
        // Another answer keeps it; naming the joined group names all 11 faces.
        val named = requireNotNull(reviewPeopleCluster(joined,
            PeopleReviewAction.NameCluster((after as PersonPageTarget.Unnamed).cluster,
                PersonKey("p1"), "Ada")))
        assertEquals(11, named.clusters.reviews.confirmed.size)
        assertEquals(1, named.clusters.reviews.sameUnknown.size)
        val undone = requireNotNull(reviewPeopleCluster(joined, PeopleReviewAction.Undo))
        assertEquals(listOf(6, 5, 4), undone.sizes())
        assertTrue(undone.clusters.reviews.sameUnknown.isEmpty())
    }

    @Test fun noKeepsThemApartAndIsNeverAskedAgain() {
        val s = state()
        val page = s.page(0)
        val q = requireNotNull(samePersonQuestion(s, page))
        val apart = requireNotNull(reviewPeopleCluster(s, samePersonAnswer(page, q, same = false)))
        assertEquals(listOf(6, 5, 4), apart.sizes())
        assertEquals(1, apart.clusters.reviews.cannotLink.size)
        assertNull(samePersonQuestion(apart, page))
        assertNull(samePersonQuestion(apart, apart.page(1)))
        // A later yes for the same two is refused: "not the same person" stands.
        assertNull(reviewPeopleCluster(apart,
            PeopleReviewAction.JoinUnknownClusters(page.cluster, q.other)))
    }

    @Test fun staleOrSelfAnswersAreRefused() {
        val s = state()
        val a = s.page(0).cluster
        val gone = FaceClusterId(NativeFaceObservationId("f".repeat(32)))
        assertNull(reviewPeopleCluster(s, PeopleReviewAction.JoinUnknownClusters(a, a)))
        assertNull(reviewPeopleCluster(s, PeopleReviewAction.JoinUnknownClusters(a, gone)))
        assertNull(reviewPeopleCluster(s, PeopleReviewAction.SeparateUnknownClusters(gone, a)))
        assertNotNull(reviewPeopleCluster(s,
            PeopleReviewAction.JoinUnknownClusters(a, s.page(2).cluster))) // the user may join any two
    }

    private val ada = PersonKey("p-ada")
    private fun named(): PeopleClusterReviewState {
        val s = state()
        return requireNotNull(reviewPeopleCluster(s,
            PeopleReviewAction.NameCluster(s.page(0).cluster, ada, "Ada")))
    }

    @Test fun aNamedPersonsPageAsksAboutTheUnnamedGroupAndYesAddsIt() {
        val s = named()
        assertEquals(listOf(5, 4), s.sizes())
        val page = PersonPageTarget.Named(ada)
        val q = requireNotNull(samePersonQuestion(s, page))
        assertEquals(s.page(0).cluster, q.other)
        assertEquals(5, q.otherFaces)
        // The stranger is not offered, and an unknown person asks nothing.
        assertEquals(1, SamePersonHypotheses.forPeople(s.clusters).size)
        assertNull(samePersonQuestion(s, PersonPageTarget.Named(PersonKey("nobody"))))
        val action = samePersonAnswer(page, q, same = true)
        assertEquals(ReviewTally(ReviewTap.ADDED, 5), reviewTally(s, action))
        val added = requireNotNull(reviewPeopleCluster(s, action))
        assertEquals(11, added.clusters.reviews.confirmed.size)
        assertEquals(listOf(4), added.sizes())
        assertEquals(page, targetAfter(page, action))
        assertNull(samePersonQuestion(added, page))
    }

    @Test fun noOnANamedPersonsPageIsNeverAskedAgainAlsoAfterTheGroupGrows() {
        val s = named()
        val page = PersonPageTarget.Named(ada)
        val q = requireNotNull(samePersonQuestion(s, page))
        val action = samePersonAnswer(page, q, same = false)
        assertEquals(ReviewTally(ReviewTap.NOT_THIS_PERSON, 5), reviewTally(s, action))
        val apart = requireNotNull(reviewPeopleCluster(s, action))
        assertEquals(listOf(5, 4), apart.sizes())
        assertEquals(6, apart.clusters.reviews.confirmed.size)
        assertEquals(5, apart.clusters.reviews.rejectedPeople.size)
        assertNull(samePersonQuestion(apart, page))
        // The answer is stored as rows and comes back from them.
        assertEquals(apart.clusters.reviews,
            PeopleReviewRecords.facts(PeopleReviewRecords.rows(apart.clusters.reviews)))
        // The same answer twice changes nothing and is refused.
        assertNull(reviewPeopleCluster(apart, action))
    }

    @Test fun aSureLaterPhotoJoinsOnItsOwnALooserOneIsOfferedAndNeverOneFromAPictureTheyAreIn() {
        val s = named()
        val sure = face(lookA, rng)
        // About 0.40 to each of Ada's faces: over the question cut, under the sure one.
        val loose = face(lookA, rng, closeness = 0.42)
        val stranger = face(lookOther, rng)
        val grown = s.copy(clusters = FaceClusterer.update(s.clusters, add = listOf(sure, loose, stranger)))
        fun PeopleClusterReviewState.offered() = SamePersonHypotheses.forPeople(clusters, setOf(ada))
            .flatMap { q -> clusters.unknownClusters.first { it.id == q.group }.members }
        // The sure face is Ada's without a question, and is not a confirmed example.
        assertEquals(mapOf(sure.observation to ada), grown.clusters.matched.mapValues { it.value.person })
        assertFalse(sure.observation in grown.clusters.reviews.confirmed)
        assertFalse(sure.observation in grown.offered())
        assertTrue(loose.observation in grown.offered())
        assertFalse(stranger.observation in grown.offered())
        // A face from a picture Ada is already in is someone else, however alike.
        val samePicture = face(lookA, rng, frame = 1L)
        val twin = s.copy(clusters = FaceClusterer.update(s.clusters, add = listOf(samePicture)))
        assertTrue(twin.clusters.matched.isEmpty())
        assertFalse(samePicture.observation in twin.offered())
        // Taking a face out of Ada keeps that face from being offered back.
        val second = NativeFaceObservationId("%032x".format(2))
        val out = requireNotNull(reviewPeopleCluster(grown,
            PeopleReviewAction.SplitPerson(ada, setOf(second))))
        // (and with it the group that face now sits in: the user said it is not Ada)
        assertFalse(second in out.offered())
        assertEquals(setOf(sure.observation), out.clusters.matched.keys)
    }

    @Test fun aFaceAddedOnItsOwnCanBeTakenOutAndNeverComesBack() {
        val s = named()
        val sure = face(lookA, rng)
        val grown = s.copy(clusters = FaceClusterer.update(s.clusters, add = listOf(sure)))
        val page = PersonPageTarget.Named(ada)
        assertEquals(7, personPageFacts(grown, page)?.faces?.size)
        assertEquals(1, personPageFacts(grown, page)?.addedFaces)
        val action = PeopleReviewAction.SplitPerson(ada, setOf(sure.observation))
        assertEquals(ReviewTally(ReviewTap.AUTO_TAKEN_OUT, 1), reviewTally(grown, action))
        val out = requireNotNull(reviewPeopleCluster(grown, action))
        assertTrue(out.clusters.matched.isEmpty())
        assertEquals(6, out.clusters.reviews.confirmed.size)
        assertEquals(6, personPageFacts(out, page)?.faces?.size)
        // It is an unnamed face again, and the answer is kept as a stored fact.
        assertTrue(out.clusters.unknownClusters.any { sure.observation in it.members })
        assertEquals(out.clusters.reviews,
            PeopleReviewRecords.facts(PeopleReviewRecords.rows(out.clusters.reviews)))
        // Neither a new scan nor a rebuild from the stored facts adds it again, or asks.
        val again = FaceClusterer.update(out.clusters, add = listOf(face(lookOther, rng)))
        assertTrue(again.matched.isEmpty())
        val rebuilt = FaceClusterer.update(FaceClusterState.empty("m", dim, out.clusters.policy),
            add = out.clusters.faces.values.toList(), reviews = out.clusters.reviews)
        assertTrue(rebuilt.matched.isEmpty())
        assertTrue(SamePersonHypotheses.forPeople(again, setOf(ada)).none { q ->
            sure.observation in again.unknownClusters.first { it.id == q.group }.members })
        // Undo puts it back under Ada.
        val undone = requireNotNull(reviewPeopleCluster(out, PeopleReviewAction.Undo))
        assertEquals(setOf(sure.observation), undone.clusters.matched.keys)
        // A mixed selection of a confirmed and an added face counts as an ordinary take-out.
        val first = NativeFaceObservationId("%032x".format(1))
        assertEquals(ReviewTally(ReviewTap.TAKEN_OUT, 2), reviewTally(grown,
            PeopleReviewAction.SplitPerson(ada, setOf(first, sure.observation))))
    }
}
