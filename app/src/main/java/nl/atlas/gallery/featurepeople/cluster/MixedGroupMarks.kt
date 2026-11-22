package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId

/**
 * HUB-1B (#211): what a stored [MixedGroup] ("not one person") fact does.
 *
 * A fact speaks about one exact membership ([MixedGroup.matches]): it names
 * the group it was answered for while that group keeps exactly those faces,
 * in any order. Clustering itself still ignores the facts.
 *
 * Membership drift is handled where membership changes, not when reading:
 * the fingerprint is a one-way hash, so it cannot be compared with a later
 * membership. Every regroup that the app persists (a review commit and a
 * cluster publication) passes through [carry] with the membership from
 * before and after it. A marked group that stays similar enough, by the
 * Jaccard index |before ∩ after| / |before ∪ after| of its members, keeps
 * its mark, re-stated for its new membership. A group that changed
 * substantially (a split, most faces moving elsewhere, a join with a group
 * of similar size) loses it and may be asked about again.
 *
 * Known limits, both conservative (the question can come back, nothing is
 * joined or split): a face removed outside a regroup (its photo changed or
 * was deleted) and a policy regroup leave the old fact inert, and so does a
 * backup restored onto a library whose groups differ.
 */
object MixedGroupMarks {

    /**
     * The smallest Jaccard index at which a marked group keeps its mark
     * through a regroup. 0.75 keeps it when one face joins a group of 3 or
     * more, or one leaves a group of 4 or more. It drops the mark when more
     * than a quarter of the group's faces leave (a split, faces moving to
     * other groups), or when the group joins faces numbering more than a
     * third of its own size.
     */
    const val KEEP_JACCARD = 0.75

    /** The unknown clusters of [state] that one of its mixed facts names. */
    fun marked(state: FaceClusterState): Set<FaceClusterId> =
        marked(state.unknownClusters, state.reviews.mixedGroups)

    fun marked(clusters: Collection<UnknownFaceCluster>, facts: Set<MixedGroup>): Set<FaceClusterId> {
        if (facts.isEmpty()) return emptySet()
        val byAnchor = facts.groupBy { it.anchor }
        val out = LinkedHashSet<FaceClusterId>()
        for (c in clusters) {
            // A fact's anchor is its smallest member, as is a cluster's id.
            val candidates = byAnchor[c.id.anchor] ?: continue
            if (candidates.any { it.matches(c.members) }) out += c.id
        }
        return out
    }

    /** |a ∩ b| / |a ∪ b|; 0 for two empty sets. */
    fun jaccard(a: Set<NativeFaceObservationId>, b: Set<NativeFaceObservationId>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        val common = small.count { it in large }
        return common.toDouble() / (a.size + b.size - common)
    }

    /**
     * [after]'s mixed facts once each fact that named a group of [before] has
     * followed that group into [after]:
     * - the group kept exactly its members: the fact is unchanged;
     * - otherwise the [after] group with the highest Jaccard index to it
     *   (ties: larger group, then smaller anchor) takes the mark, re-stated
     *   for its membership, if that index is at least [KEEP_JACCARD];
     * - else the fact is dropped: the mark cleared itself.
     * A fact that named no group of [before] is left as it is. Pure; returns
     * [after] itself when nothing changes.
     */
    fun carry(before: FaceClusterState, after: FaceClusterState): FaceClusterState {
        val facts = after.reviews.mixedGroups
        if (facts.isEmpty()) return after
        val byAnchor = before.unknownClusters.associateBy { it.id.anchor }
        val afterById = after.unknownClusters.associateBy { it.id }
        val carried = LinkedHashSet<MixedGroup>()
        for (fact in facts) {
            val old = byAnchor[fact.anchor]?.takeIf { fact.matches(it.members) }
            if (old == null) { carried += fact; continue }
            val oldSet = old.members.toHashSet()
            var best: UnknownFaceCluster? = null
            var bestScore = -1.0
            for (id in old.members.mapNotNullTo(HashSet()) { after.clusterOf(it) }) {
                val candidate = afterById[id] ?: continue
                val score = jaccard(oldSet, candidate.members.toHashSet())
                val better = score > bestScore || score == bestScore && best != null && (
                    candidate.members.size > best.members.size ||
                        candidate.members.size == best.members.size &&
                        candidate.id.anchor.localHex < best.id.anchor.localHex)
                if (better) { best = candidate; bestScore = score }
            }
            val target = best ?: continue
            if (bestScore >= KEEP_JACCARD &&
                target.members.size in MixedGroup.MIN_MEMBERS..MixedGroup.MAX_MEMBERS) {
                carried += MixedGroup.of(target.members)
            }
        }
        if (carried == facts) return after
        return after.withMixedGroups(carried)
    }
}

/** The same grouping with other mixed facts; never re-clusters. */
internal fun FaceClusterState.withMixedGroups(groups: Set<MixedGroup>): FaceClusterState =
    if (groups == reviews.mixedGroups) this
    else FaceClusterState(modelVersion, dimension, policy, faces, unknownClusters,
        reviews.copy(mixedGroups = groups), matched)

/**
 * The same grouping with [reviews] that differ from the current ones only in
 * their mixed facts (HUB-1B, #211): a "not one person" answer joins, splits
 * and rejects nothing, so it needs no regroup.
 */
fun FaceClusterState.withOnlyMixedGroupsChanged(reviews: ClusterReviewFacts): FaceClusterState {
    require(reviews.copy(mixedGroups = this.reviews.mixedGroups) == this.reviews) {
        "Only mixed-group facts may change without a regroup"
    }
    return withMixedGroups(reviews.mixedGroups)
}
