package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.identity.PeopleNumbers
import nl.atlas.gallery.featurepeople.identity.ReviewTally
import nl.atlas.gallery.featurepeople.identity.ReviewTap
import nl.atlas.gallery.featurepeople.identity.TapCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #580: the copyable People numbers and the counted review taps. Synthetic
 * vectors only: one person in two looks (two groups), and a stranger.
 */
class PeopleNumbersTest {
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
    private fun face(look: DoubleArray, rng: Random): FaceEmbedding {
        val f = mix(look, unit(rng), 0.95)
        val id = nextId++
        return FaceEmbedding(NativeFaceObservationId("%032x".format(id)), "m",
            FloatArray(dim) { f[it].toFloat() }, 0f, id.toLong())
    }

    private lateinit var lookA: DoubleArray

    private fun state(): PeopleClusterReviewState {
        nextId = 1
        val rng = Random(580)
        val a = unit(rng)
        lookA = a
        val b = mix(a, unit(rng), 0.37)
        val other = unit(rng)
        val faces = List(6) { face(a, rng) } + List(5) { face(b, rng) } + List(4) { face(other, rng) }
        val clusters = FaceClusterer.update(FaceClusterState.empty("m", dim,
            ClusterPolicy.SFACE_MEASURED.copy(metric = null)), add = faces)
        assertEquals(listOf(6, 5, 4), clusters.unknownClusters.map { it.members.size })
        return PeopleClusterReviewState("epoch", "generation", clusters)
    }

    /** What the store does with each committed tap. */
    private class Taps {
        val counts = LinkedHashMap<ReviewTap, TapCount>()
        fun commit(state: PeopleClusterReviewState, action: PeopleReviewAction): PeopleClusterReviewState {
            val tally = reviewTally(state, action)
            val next = requireNotNull(reviewPeopleCluster(state, action))
            if (tally != null) {
                val old = counts[tally.tap] ?: TapCount(0, 0)
                counts[tally.tap] = TapCount(old.taps + 1, old.faces + tally.faces)
                tally.undoes?.let { undone ->
                    val was = counts.getValue(undone.tap)
                    counts[undone.tap] = TapCount(was.taps - 1, was.faces - undone.faces)
                }
            }
            return next
        }
    }

    @Test fun aFreshLibraryCountsGroupsAndTheOpenQuestion() {
        val n = PeopleNumbers.of(state().clusters)
        assertEquals(15, n.faces)
        assertEquals(0, n.namedPeople)
        assertEquals(3, n.groupsOf3)
        assertEquals(15, n.facesInGroupsOf3)
        assertEquals(1, n.openQuestions)
        assertEquals(listOf(
            PeopleNumbers.Top(false, 6, 1, 5),
            PeopleNumbers.Top(false, 5, 1, 6),
            PeopleNumbers.Top(false, 4, 0, 0)), n.top)
        assertEquals(1, n.topInOneGroup)
        assertNull(n.wrongShare)
        assertTrue(n.line(), n.line().endsWith(" top20=u6+1/5,u5+1/6,u4+0/0 taps=named:0/0," +
            "added:0/0,merged:0/0,taken_out:0/0,not_this_person:0/0,same_yes:0/0,same_no:0/0," +
            "undone:0/0,auto_taken_out:0/0 wrong_share=- auto_wrong_share=-"))
    }

    @Test fun reviewTapsAreCountedAndTheLineHoldsNoName() {
        val taps = Taps()
        var s = state()
        val first = s.clusters.unknownClusters[0].id
        val second = s.clusters.unknownClusters[1].id
        s = taps.commit(s, PeopleReviewAction.JoinUnknownClusters(first, second))
        val joined = s.clusters.unknownClusters.first { it.members.size == 11 }
        val person = PersonKey("native-secret-key")
        s = taps.commit(s, PeopleReviewAction.NameCluster(joined.id, person, "Ada Lovelace"))
        s = taps.commit(s, PeopleReviewAction.RenamePerson(person, "Ada"))
        s = taps.commit(s, PeopleReviewAction.SplitPerson(person, setOf(joined.members.last())))
        val stranger = s.clusters.unknownClusters.first { it.members.size == 4 }
        s = taps.commit(s, PeopleReviewAction.SplitUnknownCluster(stranger.id,
            setOf(stranger.members.first())))
        s = taps.commit(s, PeopleReviewAction.Undo)

        assertEquals(mapOf(
            ReviewTap.SAME_YES to TapCount(1, 11),
            ReviewTap.NAMED to TapCount(1, 11),
            // The undone take-out of the stranger's face no longer counts.
            ReviewTap.TAKEN_OUT to TapCount(1, 1),
            ReviewTap.UNDONE to TapCount(1, 0)), taps.counts)

        val n = PeopleNumbers.of(s.clusters, taps.counts)
        assertEquals(1, n.namedPeople)
        assertEquals(10, n.namedFaces)
        assertEquals(1, n.groupsOf3)
        assertEquals(1, n.singletons)
        assertEquals(0, n.openQuestions)
        assertEquals(listOf(PeopleNumbers.Top(true, 10, 0, 0), PeopleNumbers.Top(false, 4, 0, 0)), n.top)
        assertEquals(1.0 / 11, n.wrongShare!!, 1e-9)
        val line = n.line()
        assertTrue(line, line.startsWith("people_numbers v=1 policy="))
        assertTrue(line, line.endsWith(" faces=15 named_people=1 named_faces=10 auto_added=0 groups_3plus=1" +
            " faces_in_3plus=4 pairs=0 singletons=1 open_questions=0 top20=n10+0/0,u4+0/0" +
            " taps=named:1/11,added:0/0,merged:0/0,taken_out:1/1,not_this_person:0/0," +
            "same_yes:1/11,same_no:0/0,undone:1/0,auto_taken_out:0/0 wrong_share=0.0909" +
            " auto_wrong_share=-"))
        val shown = n.summary().joinToString("\n")
        for (private in listOf("Ada", "secret", joined.members.first().localHex)) {
            assertFalse(line.contains(private))
            assertFalse(shown.contains(private))
        }
        assertTrue(shown, shown.contains("Taken out as wrong: 1 face (9.1%)"))
        assertTrue(shown, shown.contains("1 named person with 10 faces"))
        assertTrue(shown, shown.contains("Same person? Yes 1 · No 0 · 0 questions open"))
    }

    @Test fun aTapThatJudgesNoFaceOrCannotApplyCountsNothing() {
        val s = state()
        val a = s.clusters.unknownClusters[0]
        assertNull(reviewTally(s, PeopleReviewAction.SetPersonHidden(PersonKey("p"), true)))
        assertNull(reviewTally(s, PeopleReviewAction.Undo)) // nothing to undo
        assertNull(reviewTally(s, PeopleReviewAction.JoinUnknownClusters(a.id,
            nl.atlas.gallery.featurepeople.cluster.FaceClusterId(
                NativeFaceObservationId("f".repeat(32))))))
        // A face moved out of a group says the group was wrong; so does "No".
        val named = requireNotNull(reviewPeopleCluster(s,
            PeopleReviewAction.NameCluster(s.clusters.unknownClusters[2].id, PersonKey("p"), "Bo")))
        assertEquals(ReviewTally(ReviewTap.TAKEN_OUT, 1), reviewTally(named,
            PeopleReviewAction.ReassignFace(a.members.first(), PersonKey("p"))))
        assertEquals(ReviewTally(ReviewTap.SAME_NO, 11), reviewTally(s,
            PeopleReviewAction.SeparateUnknownClusters(a.id, s.clusters.unknownClusters[1].id)))
    }

    @Test fun facesAddedWithoutAskingAreCountedAndTakingOneOutIsTheirOwnKind() {
        val taps = Taps()
        var s = state()
        val ada = PersonKey("native-secret-key")
        s = taps.commit(s, PeopleReviewAction.NameCluster(s.clusters.unknownClusters[0].id, ada, "Ada"))
        val rng = Random(7)
        val later = List(3) { face(lookA, rng) }
        s = s.copy(clusters = FaceClusterer.update(s.clusters, add = later))
        assertEquals(later.map { it.observation }.toSet(), s.clusters.matched.keys)

        var n = PeopleNumbers.of(s.clusters, taps.counts)
        assertEquals(3, n.autoAdded)
        assertEquals(6, n.namedFaces)
        assertEquals(18, n.faces)
        assertEquals(0.0, n.autoWrongShare!!, 0.0)
        assertTrue(n.line(), n.line().contains(" named_people=1 named_faces=6 auto_added=3 "))
        // The person's entry counts the faces shown under them, confirmed or not.
        assertEquals(PeopleNumbers.Top(true, 9, 1, 5), n.top.first())

        s = taps.commit(s, PeopleReviewAction.SplitPerson(ada, setOf(later[0].observation)))
        assertEquals(TapCount(1, 1), taps.counts[ReviewTap.AUTO_TAKEN_OUT])
        assertNull(taps.counts[ReviewTap.TAKEN_OUT])
        n = PeopleNumbers.of(s.clusters, taps.counts)
        assertEquals(2, n.autoAdded)
        assertEquals(1.0 / 3, n.autoWrongShare!!, 1e-9)
        assertEquals(1.0 / 9, n.wrongShare!!, 1e-9)
        assertTrue(n.line(), n.line().endsWith(",auto_taken_out:1/1 wrong_share=0.1111 auto_wrong_share=0.3333"))
        val shown = n.summary().joinToString("\n")
        assertTrue(shown, shown.contains("Added to named people without asking: 2 faces, 1 face taken out again"))
        assertTrue(shown, shown.contains("Taken out as wrong: 1 face (11.1%)"))

        s = taps.commit(s, PeopleReviewAction.Undo)
        assertEquals(TapCount(0, 0), taps.counts[ReviewTap.AUTO_TAKEN_OUT])
        assertEquals(3, PeopleNumbers.of(s.clusters, taps.counts).autoAdded)
    }
}
