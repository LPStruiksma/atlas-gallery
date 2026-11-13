package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.identity.ReviewTally
import nl.atlas.gallery.featurepeople.identity.ReviewTap

/**
 * #580: what [action] counts as in the People numbers, read from the state it
 * is applied to. Null for taps that judge no face (rename, hide) and for an
 * action that state cannot take. A kind and a face count only.
 */
fun reviewTally(state: PeopleClusterReviewState, action: PeopleReviewAction): ReviewTally? {
    fun size(id: FaceClusterId): Int? =
        state.clusters.unknownClusters.firstOrNull { it.id == id }?.members?.size
    val confirmed = state.clusters.reviews.confirmed
    return when (action) {
        is PeopleReviewAction.NameCluster ->
            size(action.cluster)?.let { ReviewTally(ReviewTap.NAMED, it) }
        is PeopleReviewAction.AssignCluster ->
            size(action.cluster)?.let { ReviewTally(ReviewTap.ADDED, it) }
        is PeopleReviewAction.MergePeople ->
            ReviewTally(ReviewTap.MERGED, confirmed.values.count { it == action.source })
        is PeopleReviewAction.SplitUnknownCluster ->
            ReviewTally(ReviewTap.TAKEN_OUT, action.selected.size)
        is PeopleReviewAction.SplitPerson ->
            // Only faces the engine added on its own: that is the auto-add being wrong.
            ReviewTally(if (action.selected.all { it in state.clusters.matched }) ReviewTap.AUTO_TAKEN_OUT
                else ReviewTap.TAKEN_OUT, action.selected.size)
        is PeopleReviewAction.ReassignFace -> {
            // Moving a face out of a person or a group says that grouping was
            // wrong; giving a lone face a person does not.
            val grouped = action.face in confirmed || action.face in state.clusters.matched ||
                (state.clusters.clusterOf(action.face)?.let(::size) ?: 0) > 1
            ReviewTally(if (grouped) ReviewTap.TAKEN_OUT else ReviewTap.ADDED, 1)
        }
        is PeopleReviewAction.UnassignFace -> ReviewTally(
            if (action.face in state.clusters.matched) ReviewTap.AUTO_TAKEN_OUT else ReviewTap.TAKEN_OUT, 1)
        is PeopleReviewAction.RejectSuggestion ->
            size(action.cluster)?.let { ReviewTally(ReviewTap.NOT_THIS_PERSON, it) }
        is PeopleReviewAction.SeparateClusterFromPerson ->
            size(action.cluster)?.let { ReviewTally(ReviewTap.NOT_THIS_PERSON, it) }
        is PeopleReviewAction.JoinUnknownClusters -> {
            val a = size(action.first) ?: return null
            val b = size(action.second) ?: return null
            ReviewTally(ReviewTap.SAME_YES, a + b)
        }
        is PeopleReviewAction.SeparateUnknownClusters -> {
            val a = size(action.first) ?: return null
            val b = size(action.second) ?: return null
            ReviewTally(ReviewTap.SAME_NO, a + b)
        }
        // The undone tap comes off its kind; an Undo is not itself undoable.
        PeopleReviewAction.Undo -> state.undoHistory.lastOrNull()?.let {
            ReviewTally(ReviewTap.UNDONE, 0, undoes = it.tally?.copy(undoes = null))
        }
        is PeopleReviewAction.RenamePerson, is PeopleReviewAction.SetPersonHidden -> null
    }
}
