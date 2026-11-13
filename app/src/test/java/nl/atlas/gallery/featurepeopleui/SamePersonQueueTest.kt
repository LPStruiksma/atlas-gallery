package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #580: every open "Same person?" question in one list. Synthetic vectors
 * only: two people, each in two looks (a large and a small group), and a
 * stranger.
 */
class SamePersonQueueTest {
    private val dim = 128
    private var nextId = 1
    private val rng = Random(580)
    private val ada = PersonKey("native-ada")

    private fun Random.gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
    private fun unit(): DoubleArray {
        val v = DoubleArray(dim) { rng.gaussian() }
        val n = sqrt(v.sumOf { it * it })
        return DoubleArray(dim) { v[it] / n }
    }
    private fun mix(a: DoubleArray, s: Double): DoubleArray {
        val b = unit()
        val along = (0 until dim).sumOf { a[it] * b[it] }
        val o = DoubleArray(dim) { b[it] - along * a[it] }
        val n = sqrt(o.sumOf { it * it })
        return DoubleArray(dim) { s * a[it] + sqrt(1 - s * s) * o[it] / n }
    }
    private fun face(look: DoubleArray): FaceEmbedding {
        val f = mix(look, 0.95)
        val id = nextId++
        return FaceEmbedding(NativeFaceObservationId("%032x".format(id)), "m",
            FloatArray(dim) { f[it].toFloat() }, 0f, id.toLong())
    }

    /** Groups of 8, 6, 5, 4 and 3 faces: 8+3 are one person, 6+5 another, 4 a stranger. */
    private fun state(): PeopleClusterReviewState {
        val a = unit(); val a2 = mix(a, 0.37)
        val c = unit(); val c2 = mix(c, 0.37)
        val stranger = unit()
        val faces = List(6) { face(a) } + List(5) { face(a2) } + List(8) { face(c) } +
            List(3) { face(c2) } + List(4) { face(stranger) }
        val clusters = FaceClusterer.update(FaceClusterState.empty("m", dim,
            ClusterPolicy.SFACE_MEASURED.copy(metric = null)), add = faces)
        assertEquals(listOf(8, 6, 5, 4, 3), clusters.unknownClusters.map { it.members.size })
        return PeopleClusterReviewState("epoch", "generation", clusters)
    }
    private fun PeopleClusterReviewState.group(size: Int) =
        clusters.unknownClusters.single { it.members.size == size }

    @Test fun theQuestionThatMovesTheMostFacesComesFirstAndAnsweringEmptiesTheList() {
        var s = state()
        val queue = samePersonQueue(s)
        assertEquals(2, queue.open)
        assertEquals(listOf(6 to 5, 8 to 3), queue.items.map { it.subjectFaces to it.otherFaces })
        val first = queue.items[0]
        assertEquals(PersonPageTarget.Unnamed(s.group(6).id), first.subject)
        assertNull(first.subjectName)
        assertEquals(s.group(5).id, first.other)
        // Up to four faces of each side, all really from that side, no face twice.
        assertEquals(4, first.subjectSamples.size)
        assertEquals(4, first.otherSamples.toSet().size)
        assertTrue(s.group(6).members.containsAll(first.subjectSamples))
        assertTrue(s.group(5).members.containsAll(first.otherSamples))
        assertEquals(3, queue.items[1].otherSamples.size)
        // The same state reads the same list.
        assertEquals(queue, samePersonQueue(s))

        // Yes joins the two groups; No keeps them apart. Either way the question is gone.
        s = requireNotNull(reviewPeopleCluster(s, samePersonQueueAnswer(first, same = true)))
        assertEquals(11, s.clusters.unknownClusters.first().members.size)
        val second = samePersonQueue(s).items.single()
        assertEquals(8 to 3, second.subjectFaces to second.otherFaces)
        s = requireNotNull(reviewPeopleCluster(s, samePersonQueueAnswer(second, same = false)))
        assertEquals(SamePersonQueue(emptyList(), 0), samePersonQueue(s))
        assertEquals(listOf(11, 8, 4, 3), s.clusters.unknownClusters.map { it.members.size })
    }

    @Test fun skippedQuestionsStayOpenButAreNotShownAndTheLimitHolds() {
        val s = state()
        val all = samePersonQueue(s)
        val skipped = samePersonQueue(s, skipped = setOf(all.items[0].key))
        assertEquals(2, skipped.open)
        assertEquals(listOf(all.items[1]), skipped.items)
        assertEquals(listOf(all.items[0]), samePersonQueue(s, limit = 1).items)
        assertEquals(2, samePersonQueue(s, limit = 1).open)
        assertEquals(1, samePersonQueue(s, samples = 1).items[0].subjectSamples.size)
        assertEquals(SamePersonQueue(emptyList(), 0), samePersonQueue(null))
    }

    @Test fun aNamedPersonAsksByNameAndAHiddenPersonAsksNothing() {
        val fresh = state()
        var s = requireNotNull(reviewPeopleCluster(fresh,
            PeopleReviewAction.NameCluster(fresh.group(6).id, ada, "Ada")))
        val queue = samePersonQueue(s)
        assertEquals(2, queue.open)
        val asked = queue.items[0]
        assertEquals(PersonPageTarget.Named(ada), asked.subject)
        assertEquals("Ada", asked.subjectName)
        assertEquals(6 to 5, asked.subjectFaces to asked.otherFaces)
        assertTrue(asked.subjectSamples.all { s.clusters.reviews.confirmed[it] == ada })
        // The queue asks what the person's page asks.
        assertEquals(samePersonQuestion(s, PersonPageTarget.Named(ada))?.other, asked.other)

        val hidden = requireNotNull(reviewPeopleCluster(s,
            PeopleReviewAction.SetPersonHidden(ada, true)))
        assertEquals(listOf(8 to 3),
            samePersonQueue(hidden).items.map { it.subjectFaces to it.otherFaces })

        // Yes adds the group to Ada as confirmed faces.
        s = requireNotNull(reviewPeopleCluster(s, samePersonQueueAnswer(asked, same = true)))
        assertEquals(11, s.clusters.reviews.confirmed.count { it.value == ada })
        assertEquals(listOf(8 to 3),
            samePersonQueue(s).items.map { it.subjectFaces to it.otherFaces })
    }
}
