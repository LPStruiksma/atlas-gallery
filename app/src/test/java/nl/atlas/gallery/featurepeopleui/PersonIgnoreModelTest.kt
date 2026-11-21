package nl.atlas.gallery.featurepeopleui

import java.time.LocalDate
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YEARS-1 (#586): Ignore is its own state, separate from Hide. Synthetic vectors
 * and invented names only.
 */
class PersonIgnoreModelTest {
    private fun id(n: Int) = NativeFaceObservationId(n.toString(16).padStart(32, '0'))
    private fun face(n: Int, x: Float, y: Float) =
        FaceEmbedding(id(n), "synthetic-v1", floatArrayOf(x, y))
    private val ada = PersonKey("synthetic-ada")
    private val bo = PersonKey("synthetic-bo")

    private fun people(): PeopleClusterReviewState {
        var s = PeopleClusterReviewState("grant-1", "rev-1", FaceClusterer.update(
            FaceClusterState.empty("synthetic-v1", 2, ClusterPolicy.SFACE_CONSERVATIVE),
            add = listOf(face(1, 1f, 0f), face(2, 1f, 0f), face(3, 1f, 0f),
                face(4, 0f, 1f), face(5, 0f, 1f), face(6, -1f, 0f))))
        for ((n, key, name) in listOf(Triple(1, ada, "Ada"), Triple(4, bo, "Bo"))) {
            val group = s.clusters.unknownClusters.single { id(n) in it.members }
            s = reviewPeopleCluster(s, PeopleReviewAction.NameCluster(group.id, key, name))!!
        }
        return s.copy(undoHistory = emptyList())
    }

    private fun names(s: PeopleClusterReviewState, includeHidden: Boolean = false,
                      hiddenOnly: Boolean = false, ignoredOnly: Boolean = false) =
        peopleAlbumsForGrant(s, s.permissionEpoch, s.sourceGeneration,
            includeHidden = includeHidden, hiddenOnly = hiddenOnly, ignoredOnly = ignoredOnly)
            .map { it.name }

    @Test fun ignoredPersonLeavesPeopleAndIsListedOnlyInIgnoredPeople() {
        val s = people().copy(ignored = setOf(ada))
        assertEquals(listOf("Bo"), names(s))
        assertEquals(listOf("Bo"), names(s, includeHidden = true))
        assertEquals(emptyList<String>(), names(s, hiddenOnly = true))
        assertEquals(listOf("Ada"), names(s, includeHidden = true, ignoredOnly = true))
    }

    @Test fun ignoredPersonIsNotOfferedAsSearchMergeOrSuggestion() {
        val s = people().copy(ignored = setOf(ada))
        assertEquals(emptyList<String>(), peopleSearchForGrant(s, s.permissionEpoch,
            s.sourceGeneration, "ada").map { it.name })
        assertEquals(emptyList<String>(),
            mergeCandidates(s, PersonPageTarget.Named(bo)).map { it.name })
        assertEquals(emptyList<String>(),
            nameSuggestions(s, PersonPageTarget.Named(bo), "a").map { it.name })
    }

    @Test fun hideAndIgnoreAreIndependent() {
        val hiddenOnly = apply(people(), PeopleReviewAction.SetPersonHidden(ada, true))
        assertEquals(listOf("Ada"), names(hiddenOnly, hiddenOnly = true))
        assertEquals(listOf("Ada", "Bo"), names(hiddenOnly, includeHidden = true))

        val both = hiddenOnly.copy(ignored = setOf(ada))
        assertEquals(emptyList<String>(), names(both, hiddenOnly = true))
        assertEquals(listOf("Ada"), names(both, includeHidden = true, ignoredOnly = true))

        // Stop ignoring clears only the ignore flag: Hide still applies.
        val restored = both.copy(ignored = emptySet())
        assertEquals(listOf("Bo"), names(restored))
        assertEquals(listOf("Ada"), names(restored, hiddenOnly = true))
        assertTrue(ada in restored.hidden)
    }

    @Test fun ignoredFlagSurvivesEveryReviewAction() {
        var s = people().copy(ignored = setOf(ada))
        s = apply(s, PeopleReviewAction.RenamePerson(bo, "Bo Two"))
        assertEquals(setOf(ada), s.ignored)
        s = apply(s, PeopleReviewAction.Undo)
        assertEquals(setOf(ada), s.ignored)
    }

    @Test fun ignoredPersonAsksNoSamePersonQuestion() {
        val s = people().copy(ignored = setOf(ada))
        assertNull(samePersonQuestion(s, PersonPageTarget.Named(ada)))
    }

    @Test fun queueAnswerIsRefusedOnceTheSubjectIsIgnored() {
        val s = people()
        val group = s.clusters.unknownClusters.single()
        val item = SamePersonQueueItem(PersonPageTarget.Named(ada), "Ada", 3, listOf(id(1)),
            group.id, group.members.size, listOf(id(6)))
        assertTrue(samePersonQueueStillAsks(s, item))
        assertFalse(samePersonQueueStillAsks(s.copy(ignored = setOf(ada)), item))
    }

    @Test fun pageFactsCarryIgnoreAndBirthDate() {
        val born = LocalDate.of(2010, 8, 20)
        val s = people().copy(ignored = setOf(ada), birthDates = mapOf(ada to born))
        val facts = personPageFacts(s, PersonPageTarget.Named(ada))!!
        assertTrue(facts.ignored)
        assertEquals(born, facts.birthDate)
        val other = personPageFacts(s, PersonPageTarget.Named(bo))!!
        assertFalse(other.ignored)
        assertNull(other.birthDate)
    }

    private fun apply(s: PeopleClusterReviewState, a: PeopleReviewAction) =
        reviewPeopleCluster(s, a)!!
}
