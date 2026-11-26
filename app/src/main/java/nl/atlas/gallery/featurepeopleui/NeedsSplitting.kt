package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.MixedGroup
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

/**
 * HUB-1C: separate projections of marked groups for ordinary People and
 * Hidden. The caller must supply a currently authorized review snapshot.
 * Ordinary People must consume only [visible], never [hidden] or its count.
 */
data class NeedsSplittingRow(
    val target: PersonPageTarget,
    val name: String?,
    val faces: Int,
    val cover: NativeFaceObservationId
) {
    val key: String get() = when (target) {
        is PersonPageTarget.Named -> "n:" + target.person.value
        is PersonPageTarget.Unnamed -> "u:" + target.cluster.anchor.localHex
    }
}

data class NeedsSplittingLists(
    val visible: List<NeedsSplittingRow>,
    val hidden: List<NeedsSplittingRow>
)

fun needsSplittingLists(state: PeopleClusterReviewState?): NeedsSplittingLists {
    if (state == null) return NeedsSplittingLists(emptyList(), emptyList())
    val unnamed = needsSplitting(state).map {
        NeedsSplittingRow(it.target, null, it.faces, it.cover)
    }
    val facts = state.clusters.reviews.mixedGroups
    val named = state.clusters.reviews.confirmed.entries
        .filter { it.key in state.clusters && it.value in state.names }
        .groupBy({ it.value }, { it.key })
        .mapNotNull { (person, members) ->
            if (members.size !in MixedGroup.MIN_MEMBERS..MixedGroup.MAX_MEMBERS ||
                MixedGroup.of(members) !in facts) null
            else NeedsSplittingRow(PersonPageTarget.Named(person), state.names[person],
                members.size, members.minBy { it.localHex })
        }
    val order = compareByDescending<NeedsSplittingRow> { it.faces }.thenBy { it.key }
    return NeedsSplittingLists(
        visible = (unnamed + named.filterNot {
            (it.target as? PersonPageTarget.Named)?.person in state.hidden
        }).sortedWith(order),
        hidden = named.filter {
            (it.target as? PersonPageTarget.Named)?.person in state.hidden
        }.sortedWith(order)
    )
}
