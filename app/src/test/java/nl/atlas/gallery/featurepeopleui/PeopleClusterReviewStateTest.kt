package nl.atlas.gallery.featurepeopleui

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

/** Entirely synthetic vectors and media keys; no image, biometric or filesystem access. */
class PeopleClusterReviewStateTest {
    private fun id(value: Int) = NativeFaceObservationId(value.toString(16).padStart(32, '0'))
    private fun face(value: Int, x: Float, y: Float) =
        FaceEmbedding(id(value), "synthetic-v1", floatArrayOf(x, y))
    private val alice = PersonKey("reviewed-alice")
    private val bob = PersonKey("reviewed-bob")
    private fun start(vararg faces: FaceEmbedding): PeopleClusterReviewState {
        val empty = FaceClusterState.empty("synthetic-v1", 2, ClusterPolicy.SFACE_CONSERVATIVE)
        return PeopleClusterReviewState("grant-1", "revision-1",
            FaceClusterer.update(empty, add = faces.toList()))
    }
    private fun changed(
        state: PeopleClusterReviewState, action: PeopleReviewAction
    ): PeopleClusterReviewState = reviewPeopleCluster(state, action)!!

    @Test fun nameCreatesOnlyExplicitConfirmedPeopleAndUndoRestoresExactSnapshot() {
        val initial = start(face(1, 1f, 0f), face(2, 1f, 0f))
        val group = initial.clusters.unknownClusters.single()
        val named = changed(initial, PeopleReviewAction.NameCluster(group.id, alice, "Alice"))
        assertEquals(group.members.toSet(), named.clusters.reviews.confirmed.keys)
        assertTrue(named.clusters.unknownClusters.isEmpty())
        assertEquals("Alice", peopleAlbumsForGrant(named, "grant-1", "revision-1").single().name)
        val undone = changed(named, PeopleReviewAction.Undo)
        assertTrue(undone.clusters === initial.clusters)
        assertEquals(initial.names, undone.names)
        assertTrue(undone.undoHistory.isEmpty())
    }

    @Test fun mergeAndUndoKeepBothNamesAndAllAssignmentsReversible() {
        val s0 = start(face(1, 1f, 0f), face(2, 0f, 1f))
        val a = changed(s0, PeopleReviewAction.NameCluster(
            s0.clusters.unknownClusters.first { id(1) in it.members }.id, alice, "Alice"))
        val b = changed(a, PeopleReviewAction.NameCluster(
            a.clusters.unknownClusters.single().id, bob, "Bob"))
        val merged = changed(b, PeopleReviewAction.MergePeople(bob, alice))
        assertEquals(setOf(alice), merged.clusters.reviews.confirmed.values.toSet())
        assertEquals(setOf(alice), merged.names.keys)
        val undone = changed(merged, PeopleReviewAction.Undo)
        assertEquals(setOf(alice, bob), undone.names.keys)
        assertEquals(b.clusters.reviews, undone.clusters.reviews)
    }

    @Test fun splitUnknownAndSplitNamedAreBothUndoable() {
        val s0 = start(face(1, 1f, 0f), face(2, 1f, 0f), face(3, 1f, 0f))
        val cluster = s0.clusters.unknownClusters.single()
        val separated = changed(s0, PeopleReviewAction.SplitUnknownCluster(cluster.id, setOf(id(1))))
        assertTrue(separated.clusters.clusterOf(id(1)) != separated.clusters.clusterOf(id(2)))
        assertTrue(changed(separated, PeopleReviewAction.Undo).clusters === s0.clusters)
        val named = changed(s0, PeopleReviewAction.NameCluster(cluster.id, alice, "Alice"))
        val split = changed(named, PeopleReviewAction.SplitPerson(alice, setOf(id(1))))
        assertNull(split.clusters.reviews.confirmed[id(1)])
        assertEquals(alice, split.clusters.reviews.confirmed[id(2)])
        assertTrue(split.clusters.unknownClusters.any { id(1) in it.members })
        assertEquals(named.clusters.reviews,
            changed(split, PeopleReviewAction.Undo).clusters.reviews)
    }

    @Test fun explicitReassignAndUnassignCanBeUndone() {
        val s0 = start(face(1, 1f, 0f), face(2, 0f, 1f))
        val a = changed(s0, PeopleReviewAction.NameCluster(
            s0.clusters.unknownClusters.first { id(1) in it.members }.id, alice, "Alice"))
        val b = changed(a, PeopleReviewAction.NameCluster(
            a.clusters.unknownClusters.single().id, bob, "Bob"))
        val moved = changed(b, PeopleReviewAction.ReassignFace(id(1), bob))
        assertEquals(bob, moved.clusters.reviews.confirmed[id(1)])
        assertEquals(alice, changed(moved, PeopleReviewAction.Undo)
            .clusters.reviews.confirmed[id(1)])
        val unassigned = changed(moved, PeopleReviewAction.UnassignFace(id(1)))
        assertNull(unassigned.clusters.reviews.confirmed[id(1)])
        assertEquals(bob, changed(unassigned, PeopleReviewAction.Undo)
            .clusters.reviews.confirmed[id(1)])
    }

    @Test fun suggestionsAreNeverAppliedWithoutAReviewAction() {
        val start = start(face(1, 1f, 0f))
        val named = changed(start, PeopleReviewAction.NameCluster(
            start.clusters.unknownClusters.single().id, alice, "Alice"))
        val withUnknown = named.copy(clusters = FaceClusterer.update(
            named.clusters, add = listOf(face(2, 1f, 0f))), undoHistory = emptyList())
        val suggestion = withUnknown.clusters.unknownClusters.single()
        assertEquals(alice, suggestion.suggestion?.person)
        assertNull(withUnknown.clusters.reviews.confirmed[id(2)])
        assertFalse(id(2) in peopleAlbumsForGrant(
            withUnknown, "grant-1", "revision-1").single().observations)
        val assigned = changed(withUnknown, PeopleReviewAction.AssignCluster(
            suggestion.id, alice))
        assertEquals(alice, assigned.clusters.reviews.confirmed[id(2)])
        assertNull(changed(assigned, PeopleReviewAction.Undo)
            .clusters.reviews.confirmed[id(2)])
    }

    @Test fun rejectionSuppressesSuggestionAndUndoRestoresIt() {
        val initial = start(face(1, 1f, 0f))
        val named = changed(initial, PeopleReviewAction.NameCluster(
            initial.clusters.unknownClusters.single().id, alice, "Alice"))
        val refreshed = named.copy(clusters = FaceClusterer.update(
            named.clusters, add = listOf(face(2, 1f, 0f))), undoHistory = emptyList())
        val c = refreshed.clusters.unknownClusters.single()
        val rejected = changed(refreshed, PeopleReviewAction.RejectSuggestion(c.id, alice))
        assertNull(rejected.clusters.unknownClusters.single().suggestion)
        assertTrue(rejected.clusters.reviews.confirmed[id(2)] == null)
        assertEquals(alice, changed(rejected, PeopleReviewAction.Undo)
            .clusters.unknownClusters.single().suggestion?.person)
    }

    @Test fun staleGrantSourceOrReviewObjectCannotSendCommands() {
        val state = start(face(1, 1f, 0f))
        val action = PeopleReviewAction.NameCluster(
            state.clusters.unknownClusters.single().id, alice, "Alice")
        assertNull(currentPeopleReviewActionOrNull(
            state, action, "other-grant", "revision-1", state))
        assertNull(currentPeopleReviewActionOrNull(
            state, action, "grant-1", "other-revision", state))
        assertNull(currentPeopleReviewActionOrNull(
            state, action, "grant-1", "revision-1", state.copy()))
        assertNull(currentPeopleReviewActionOrNull(
            state, action, "grant-1", "revision-1", null))
        assertEquals(action, currentPeopleReviewActionOrNull(
            state, action, "grant-1", "revision-1", state))
    }

    @Test fun queuedReviewCommandMustMatchTheExactLiveTransactionState() {
        val s0 = start(face(1, 1f, 0f))
        val action = PeopleReviewAction.NameCluster(
            s0.clusters.unknownClusters.single().id, alice, "Alice")
        val command = PeopleClusterReviewCommand(s0, action)
        val changed = applyCurrentPeopleReviewCommand(s0, command)!!
        assertEquals(alice, changed.clusters.reviews.confirmed[id(1)])
        assertNull(applyCurrentPeopleReviewCommand(changed, command))
        assertNull(applyCurrentPeopleReviewCommand(s0.copy(), command))
    }

    @Test fun invalidTargetsAndUnsafeMergesFailClosed() {
        val s0 = start(face(1, 1f, 0f), face(2, 0f, 1f))
        val group = s0.clusters.unknownClusters.first { id(1) in it.members }
        assertNull(reviewPeopleCluster(s0, PeopleReviewAction.NameCluster(
            group.id, alice, " \n ")))
        assertNull(reviewPeopleCluster(s0, PeopleReviewAction.SplitUnknownCluster(
            group.id, setOf(id(2)))))
        val a = changed(s0, PeopleReviewAction.NameCluster(group.id, alice, "Alice"))
        assertNull(reviewPeopleCluster(a, PeopleReviewAction.NameCluster(
            a.clusters.unknownClusters.single().id, alice, "Another")))
        assertNull(reviewPeopleCluster(a, PeopleReviewAction.ReassignFace(
            id(2), PersonKey("missing"))))
        assertEquals(s0.clusters.reviews,
            changed(a, PeopleReviewAction.Undo).clusters.reviews)
    }

    @Test fun albumsAndSearchOnlyReturnConfirmedGrantVisibleMedia() {
        val s0 = start(face(1, 1f, 0f), face(2, 0f, 1f))
        val named = changed(s0, PeopleReviewAction.NameCluster(
            s0.clusters.unknownClusters.first { id(1) in it.members }.id, alice, "Alice"))
        val media = mapOf(id(1) to "authorized-media-1", id(2) to "authorized-media-2")
        assertTrue(peopleAlbumsForGrant(named, "other", "revision-1", media).isEmpty())
        assertEquals(listOf("authorized-media-1"),
            peopleAlbumsForGrant(named, "grant-1", "revision-1", media).single().mediaKeys)
        assertEquals(listOf("Alice"),
            peopleSearchForGrant(named, "grant-1", "revision-1", "ali").map { it.name })
        assertTrue(peopleSearchForGrant(named, "grant-1", "revision-1", "bob").isEmpty())
        assertTrue(peopleSearchForGrant(named, "grant-1", "other", "ali").isEmpty())
    }
}
