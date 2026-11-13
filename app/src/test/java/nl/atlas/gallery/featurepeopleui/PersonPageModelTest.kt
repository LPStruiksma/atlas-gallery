package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.PeopleReviewRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #551 person page semantics: name, merge, remove and hide, on synthetic
 * vectors and invented names only (no image, biometric or file access).
 */
class PersonPageModelTest {
    private fun id(n: Int) = NativeFaceObservationId(n.toString(16).padStart(32, '0'))
    private fun face(n: Int, x: Float, y: Float) =
        FaceEmbedding(id(n), "synthetic-v1", floatArrayOf(x, y))
    private val ada = PersonKey("synthetic-ada")
    private val bo = PersonKey("synthetic-bo")

    /** Ada owns faces 1-3 (one direction), Bo owns 4-5, face 6 stays unknown. */
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
    private fun apply(s: PeopleClusterReviewState, a: PeopleReviewAction?) =
        reviewPeopleCluster(s, requireNotNull(a))!!

    @Test fun pageFactsShowNamedAndUnnamedPeople() {
        val s = people()
        val named = personPageFacts(s, PersonPageTarget.Named(ada))!!
        assertEquals("Ada", named.name)
        assertEquals(listOf(id(1), id(2), id(3)), named.faces)
        assertEquals(id(1), named.coverFace)
        val unknown = s.clusters.unknownClusters.single()
        val unnamed = personPageFacts(s, PersonPageTarget.Unnamed(unknown.id))!!
        assertNull("an unknown group reads \"Add name\"", unnamed.name)
        assertEquals(listOf(id(6)), unnamed.faces)
        assertNull(personPageFacts(s, PersonPageTarget.Named(PersonKey("nobody"))))
    }

    @Test fun editNameRenamesAndUndoRestores() {
        val s = people()
        val naming = personNaming(s, PersonPageTarget.Named(ada), "  Ada Lovelace ")
        val action = (naming as PersonNaming.Apply).action
        assertEquals(PeopleReviewAction.RenamePerson(ada, "Ada Lovelace"), action)
        val renamed = apply(s, action)
        assertEquals("Ada Lovelace", renamed.names[ada])
        assertSame("a rename never re-clusters", s.clusters, renamed.clusters)
        assertEquals("Ada", apply(renamed, PeopleReviewAction.Undo).names[ada])
        assertEquals(PersonNaming.Invalid, personNaming(s, PersonPageTarget.Named(ada), "Ada"))
        assertEquals(PersonNaming.Invalid, personNaming(s, PersonPageTarget.Named(ada), "   "))
        assertNull(reviewPeopleCluster(s, PeopleReviewAction.RenamePerson(ada, "x".repeat(81))))
    }

    @Test fun namingAnUnknownGroupCreatesAPersonTheRouteFollows() {
        val s = people()
        val target = PersonPageTarget.Unnamed(s.clusters.unknownClusters.single().id)
        val action = (personNaming(s, target, "Cy") as PersonNaming.Apply).action
        val named = apply(s, action)
        val next = targetAfter(target, action) as PersonPageTarget.Named
        assertEquals("Cy", personPageFacts(named, next)!!.name)
        assertEquals(listOf(id(6)), personPageFacts(named, next)!!.faces)
    }

    @Test fun typingAnExistingNameOffersAMergeInsteadOfADuplicate() {
        val s = people()
        val offer = personNaming(s, PersonPageTarget.Named(ada), "bo")
        assertEquals(bo, (offer as PersonNaming.OfferMerge).into.person)
        val unknown = PersonPageTarget.Unnamed(s.clusters.unknownClusters.single().id)
        assertEquals(bo, (personNaming(s, unknown, "BO") as PersonNaming.OfferMerge).into.person)
        assertEquals(listOf("Bo"), nameSuggestions(s, PersonPageTarget.Named(ada), "b").map { it.name })
        assertTrue("a person never suggests itself",
            nameSuggestions(s, PersonPageTarget.Named(ada), "ad").isEmpty())
        assertTrue(nameSuggestions(s, unknown, " ").isEmpty())
    }

    @Test fun mergeMovesEveryFaceToTheChosenPersonAndIsUndoable() {
        val s = people()
        val action = mergeAction(PersonPageTarget.Named(ada), bo)
        val merged = apply(s, action)
        assertNull(personPageFacts(merged, PersonPageTarget.Named(ada)))
        assertEquals((1..5).map(::id), personPageFacts(merged, PersonPageTarget.Named(bo))!!.faces)
        assertEquals(PersonPageTarget.Named(bo), targetAfter(PersonPageTarget.Named(ada), action!!))
        val undone = apply(merged, PeopleReviewAction.Undo)
        assertEquals(listOf(id(1), id(2), id(3)),
            personPageFacts(undone, PersonPageTarget.Named(ada))!!.faces)
        assertNull(mergeAction(PersonPageTarget.Named(ada), ada))
        // An unnamed group merges by joining the person.
        val unknown = PersonPageTarget.Unnamed(s.clusters.unknownClusters.single().id)
        val joined = apply(s, mergeAction(unknown, ada))
        assertEquals(ada, joined.clusters.reviews.confirmed[id(6)])
        assertEquals(listOf(ada, bo), mergeCandidates(s, unknown).map { it.person })
        assertEquals(listOf(bo), mergeCandidates(s, PersonPageTarget.Named(ada)).map { it.person })
    }

    @Test fun removeTakesTheSelectedPhotosFacesOutAndKeepsThemApart() {
        val s = people()
        val facts = personPageFacts(s, PersonPageTarget.Named(ada))!!
        val byUri = mapOf("content://a/1" to setOf(id(1)), "content://a/2" to setOf(id(2)),
            "content://a/3" to setOf(id(3), id(4)))
        val action = removeAction(facts, byUri, setOf("content://a/3"))
        // Bo's face in the same photo is not Ada's to remove.
        assertEquals(PeopleReviewAction.SplitPerson(ada, setOf(id(3))), action)
        val removed = apply(s, action)
        assertEquals(listOf(id(1), id(2)), personPageFacts(removed, PersonPageTarget.Named(ada))!!.faces)
        assertNull(removed.clusters.reviews.confirmed[id(3)])
        assertTrue("removed face is kept apart from Ada",
            removed.clusters.reviews.cannotLink.any { id(3) in listOf(it.first, it.second) })
        assertNull("nothing selected", removeAction(facts, byUri, emptySet()))
        assertNull("every photo selected", removeAction(facts, byUri, byUri.keys))
        val unknownFacts = personPageFacts(s,
            PersonPageTarget.Unnamed(s.clusters.unknownClusters.single().id))!!
        assertNull("one-face group cannot lose its only photo",
            removeAction(unknownFacts, mapOf("u" to setOf(id(6))), setOf("u")))
    }

    @Test fun hideLeavesPeopleButStaysInSearchAndHiddenAndIsUndoable() {
        val s = people()
        val hidden = apply(s, PeopleReviewAction.SetPersonHidden(ada, true))
        assertSame("hiding never re-clusters", s.clusters, hidden.clusters)
        assertEquals(listOf("Bo"), peopleAlbumsForGrant(hidden, "grant-1", "rev-1").map { it.name })
        assertEquals(listOf("Ada"), peopleAlbumsForGrant(hidden, "grant-1", "rev-1",
            hiddenOnly = true).map { it.name })
        assertEquals(listOf("Ada"), peopleSearchForGrant(hidden, "grant-1", "rev-1", "ad")
            .map { it.name })
        assertTrue(personPageFacts(hidden, PersonPageTarget.Named(ada))!!.hidden)
        assertNull("already hidden", reviewPeopleCluster(hidden,
            PeopleReviewAction.SetPersonHidden(ada, true)))
        val shown = apply(hidden, PeopleReviewAction.SetPersonHidden(ada, false))
        assertTrue(shown.hidden.isEmpty())
        assertEquals(setOf(ada), apply(shown, PeopleReviewAction.Undo).hidden)
        assertTrue(apply(hidden, PeopleReviewAction.Undo).hidden.isEmpty())
        assertNull(reviewPeopleCluster(s, PeopleReviewAction.SetPersonHidden(PersonKey("x"), true)))
    }

    @Test fun mergingAHiddenPersonAwayDropsItsHideAndKeepsTheTargets() {
        val s = apply(people(), PeopleReviewAction.SetPersonHidden(ada, true))
        val merged = apply(s, PeopleReviewAction.MergePeople(ada, bo))
        assertTrue(merged.hidden.isEmpty())
        val intoHidden = apply(s, PeopleReviewAction.MergePeople(bo, ada))
        assertEquals(setOf(ada), intoHidden.hidden)
        PeopleReviewRecords.requireConsistent(merged.clusters, merged.names, merged.hidden)
    }

    @Test fun newPersonKeysNeverCollide() {
        val s = people()
        val cluster = s.clusters.unknownClusters.single().id
        val first = newPersonKey(s, cluster)
        assertEquals("native-" + cluster.anchor.localHex, first.value)
        val taken = s.copy(names = s.names + (first to "Taken"))
        assertEquals(first.value + "-2", newPersonKey(taken, cluster).value)
    }

    @Test fun countLineReadsLikeSamsung() {
        assertEquals("1 photo", personPageCount(1, 0))
        assertEquals("1,204 photos", personPageCount(1204, 0))
        assertEquals("2 videos", personPageCount(0, 2))
        assertEquals("5 items", personPageCount(3, 2))
        assertNotNull(personPageCount(0, 0))
    }
}
