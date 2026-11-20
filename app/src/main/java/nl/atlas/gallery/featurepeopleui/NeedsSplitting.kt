package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.MixedGroupMarks

/**
 * HUB-1B (#211): one unnamed group the owner marked "not one person", for
 * People ⋮ → "Needs splitting (N)" (stage 3, #212). [target] opens its group
 * page, where the manual split already is; [key] is stable while the group
 * keeps its anchor face. Listing a group splits, joins or rejects nothing.
 */
data class NeedsSplittingGroup(
    val cluster: FaceClusterId,
    val faces: Int,
    /** The group's portrait, as on the People grid. */
    val cover: NativeFaceObservationId
) {
    val target: PersonPageTarget get() = PersonPageTarget.Unnamed(cluster)
    val key: String get() = "u:" + cluster.anchor.localHex
}

/**
 * Every unnamed group of [state] that a "not one person" fact still names,
 * largest first, then by anchor, so the list keeps its order between reads.
 * Empty without a state. Pure; no vectors are read.
 */
fun needsSplitting(state: PeopleClusterReviewState?): List<NeedsSplittingGroup> {
    if (state == null) return emptyList()
    val marked = MixedGroupMarks.marked(state.clusters)
    if (marked.isEmpty()) return emptyList()
    return state.clusters.unknownClusters.asSequence().filter { it.id in marked }
        .map { NeedsSplittingGroup(it.id, it.members.size, it.id.anchor) }
        .sortedWith(compareByDescending<NeedsSplittingGroup> { it.faces }
            .thenBy { it.cluster.anchor.localHex })
        .toList()
}
