package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import kotlin.math.sqrt

/**
 * Test-only copy of the #409 clusterer (with the #411 zero-norm guard), kept
 * verbatim as the O(faces x clusters) reference. The production
 * [FaceClusterer] must produce exactly the same states.
 */
/**
 * Conservative incremental clustering:
 * 1. Each unplaced unknown face, in observation-ID order, joins the unknown
 *    cluster whose centroid is most similar, only if that similarity reaches
 *    the join threshold, no cannot-link fact vetoes it, and no second cluster
 *    is within the ambiguity margin. Otherwise it starts its own cluster.
 * 2. Clusters changed in this update are unioned with their most similar
 *    other cluster while the centroid similarity reaches the stricter merge
 *    threshold, no cannot-link fact vetoes the union, and no second cluster
 *    is within the ambiguity margin (so one face cannot bridge two people).
 * 3. Confirmed faces never enter an unknown cluster and are never moved,
 *    merged or reassigned. They only feed per-person centroids, from which an
 *    unknown cluster may receive one [PersonSuggestion] for human review.
 *
 * Review changes are applied first: newly confirmed faces leave their unknown
 * cluster, a cluster that now contains a cannot-link pair is dissolved and its
 * faces are re-placed, and a face whose confirmation was undone is re-placed.
 *
 * Results are deterministic for the same state and inputs, whatever the order
 * of [update]'s collections. Splitting one batch into several updates may
 * group borderline faces differently; it never violates a review fact.
 *
 * Pure CPU work, no I/O; call it on a background dispatcher.
 */
internal object ReferenceFaceClusterer {

    fun update(
        state: FaceClusterState,
        add: Collection<FaceEmbedding> = emptyList(),
        remove: Collection<NativeFaceObservationId> = emptyList(),
        reviews: ClusterReviewFacts = state.reviews
    ): FaceClusterState {
        val removeSet = remove.toHashSet()
        val faces = HashMap(state.faces)
        for (r in removeSet) faces.remove(r)
        for (e in add) {
            require(e.modelVersion == state.modelVersion) {
                "Embedding model version differs from the cluster state; start a new state"
            }
            require(e.dimension == state.dimension) { "Embedding dimension mismatch" }
            require(faces.put(e.observation, e) == null) {
                "Duplicate face observation; a rescan must mint a new observation ID"
            }
        }
        require(faces.size <= FaceClusterState.MAX_FACES) { "Face clustering limit exceeded" }

        val work = Work(state.dimension, faces, reviews)

        // Carry surviving unknown clusters over, applying removals and reviews.
        for (cluster in state.unknownClusters) {
            val kept = cluster.members.filter { it in faces && it !in reviews.confirmed }
            if (kept.isEmpty()) continue
            if (work.containsCannotLinkPair(kept)) continue // dissolved: re-placed below
            work.newCluster(kept, dirty = kept.size != cluster.members.size)
        }

        // Everything unknown and not yet in a cluster: new faces, dissolved
        // clusters, and faces whose confirmation was undone.
        val toPlace = faces.keys
            .filter { it !in reviews.confirmed && !work.isPlaced(it) }
            .sortedBy { it.localHex }
        for (face in toPlace) work.place(faces.getValue(face), state.policy)
        work.mergeDirty(state.policy)

        return FaceClusterState(state.modelVersion, state.dimension, state.policy,
            faces, work.export(state.policy), reviews)
    }

    /**
     * Rebuilds a state from persisted membership (for example after a process
     * restart) without re-deriving clusters. Faces not listed in any cluster
     * are placed as new; [clusters] must be disjoint and reference [faces].
     */
    fun restore(
        modelVersion: String,
        policy: ClusterPolicy,
        faces: Collection<FaceEmbedding>,
        clusters: Collection<Collection<NativeFaceObservationId>>,
        reviews: ClusterReviewFacts
    ): FaceClusterState {
        require(faces.isNotEmpty() || clusters.isEmpty()) { "Clusters without faces" }
        val dimension = faces.firstOrNull()?.dimension ?: 1
        val byId = HashMap<NativeFaceObservationId, FaceEmbedding>(faces.size)
        for (f in faces) {
            require(f.modelVersion == modelVersion) { "Embedding model version mismatch" }
            require(f.dimension == dimension) { "Embedding dimension mismatch" }
            require(byId.put(f.observation, f) == null) { "Duplicate face observation" }
        }
        require(byId.size <= FaceClusterState.MAX_FACES) { "Face clustering limit exceeded" }
        val seen = HashSet<NativeFaceObservationId>()
        val restored = ArrayList<UnknownFaceCluster>(clusters.size)
        for (members in clusters) {
            require(members.isNotEmpty()) { "Empty persisted cluster" }
            for (m in members) {
                require(m in byId) { "Persisted cluster references an unknown face" }
                require(seen.add(m)) { "Persisted clusters overlap" }
            }
            val sorted = members.sortedBy { it.localHex }
            restored.add(UnknownFaceCluster(FaceClusterId(sorted.first()), sorted, null))
        }
        val base = FaceClusterState(modelVersion, dimension, policy, byId, restored,
            ClusterReviewFacts.NONE)
        return update(base, reviews = reviews)
    }

    private class Work(
        private val dimension: Int,
        private val faces: Map<NativeFaceObservationId, FaceEmbedding>,
        private val reviews: ClusterReviewFacts
    ) {
        private val members = HashMap<Int, MutableList<NativeFaceObservationId>>()
        private val sums = HashMap<Int, DoubleArray>()
        private val handleOf = HashMap<NativeFaceObservationId, Int>()
        private val dirty = HashSet<Int>()
        private val anchors = HashMap<Int, String>()
        private var nextHandle = 0

        private val cannotLink: Map<NativeFaceObservationId, Set<NativeFaceObservationId>> =
            HashMap<NativeFaceObservationId, MutableSet<NativeFaceObservationId>>().also { m ->
                for (p in reviews.cannotLink) {
                    m.getOrPut(p.first) { HashSet() }.add(p.second)
                    m.getOrPut(p.second) { HashSet() }.add(p.first)
                }
            }

        fun isPlaced(face: NativeFaceObservationId) = face in handleOf

        fun containsCannotLinkPair(group: List<NativeFaceObservationId>): Boolean {
            val set = group.toHashSet()
            return group.any { f -> cannotLink[f]?.any { it in set } == true }
        }

        fun newCluster(group: List<NativeFaceObservationId>, dirty: Boolean): Int {
            val h = nextHandle++
            val sum = DoubleArray(dimension)
            for (f in group) {
                addInto(sum, faces.getValue(f).unit)
                handleOf[f] = h
            }
            members[h] = group.toMutableList()
            sums[h] = sum
            anchors[h] = group.minOf { it.localHex }
            if (dirty) this.dirty.add(h)
            return h
        }

        fun place(face: FaceEmbedding, policy: ClusterPolicy) {
            var best = -1
            var bestSim = Double.NEGATIVE_INFINITY
            var secondSim = Double.NEGATIVE_INFINITY
            for ((h, sum) in sums) {
                val sim = cosine(face.unit, sum)
                if (sim < policy.joinThreshold) continue
                if (vetoes(face.observation, h)) continue
                if (sim > bestSim || (sim == bestSim && anchorLess(h, best))) {
                    if (best >= 0) secondSim = maxOf(secondSim, bestSim)
                    best = h; bestSim = sim
                } else if (sim > secondSim) {
                    secondSim = sim
                }
            }
            val ambiguous = best >= 0 && bestSim - secondSim < policy.ambiguityMargin
            if (best < 0 || ambiguous) {
                newCluster(listOf(face.observation), dirty = true)
                return
            }
            members.getValue(best).add(face.observation)
            addInto(sums.getValue(best), face.unit)
            handleOf[face.observation] = best
            if (face.observation.localHex < anchors.getValue(best)) {
                anchors[best] = face.observation.localHex
            }
            dirty.add(best)
        }

        /** Union dirty clusters with their best partner until nothing changes. */
        fun mergeDirty(policy: ClusterPolicy) {
            while (dirty.isNotEmpty()) {
                val h = dirty.minWith(compareBy { anchorHex(it) })
                dirty.remove(h)
                val sum = sums.getValue(h)
                var partner = -1
                var partnerSim = Double.NEGATIVE_INFINITY
                var secondSim = Double.NEGATIVE_INFINITY
                for ((o, oSum) in sums) {
                    if (o == h) continue
                    val sim = cosineSums(sum, oSum)
                    if (sim < policy.mergeThreshold) continue
                    if (sim <= secondSim) continue
                    if (clustersVeto(h, o)) continue
                    if (sim > partnerSim || (sim == partnerSim && anchorLess(o, partner))) {
                        secondSim = maxOf(secondSim, partnerSim)
                        partner = o; partnerSim = sim
                    } else {
                        secondSim = sim
                    }
                }
                // Same ambiguity rule as joining: a cluster that resembles two
                // others about equally is left alone rather than bridging them.
                if (partner < 0 || partnerSim - secondSim < policy.ambiguityMargin) continue
                absorb(into = h, from = partner)
                dirty.remove(partner)
                dirty.add(h)
            }
        }

        private fun absorb(into: Int, from: Int) {
            val moved = members.remove(from)!!
            val movedSum = sums.remove(from)!!
            for (f in moved) handleOf[f] = into
            members.getValue(into).addAll(moved)
            val sum = sums.getValue(into)
            for (i in sum.indices) sum[i] += movedSum[i]
            val movedAnchor = anchors.remove(from)!!
            if (movedAnchor < anchors.getValue(into)) anchors[into] = movedAnchor
        }

        private fun vetoes(face: NativeFaceObservationId, h: Int): Boolean =
            cannotLink[face]?.any { handleOf[it] == h } == true

        private fun clustersVeto(a: Int, b: Int): Boolean {
            val small = if (members.getValue(a).size <= members.getValue(b).size) a else b
            val other = if (small == a) b else a
            return members.getValue(small).any { vetoes(it, other) }
        }

        private fun anchorHex(h: Int): String = anchors.getValue(h)
        private fun anchorLess(a: Int, b: Int): Boolean = b < 0 || anchorHex(a) < anchorHex(b)

        fun export(policy: ClusterPolicy): List<UnknownFaceCluster> {
            val people = HashMap<PersonKey, DoubleArray>()
            val confirmedOf = HashMap<NativeFaceObservationId, PersonKey>()
            for ((face, person) in reviews.confirmed) {
                val e = faces[face] ?: continue
                addInto(people.getOrPut(person) { DoubleArray(dimension) }, e.unit)
                confirmedOf[face] = person
            }
            val rejectedBy = HashMap<NativeFaceObservationId, MutableSet<PersonKey>>()
            for (r in reviews.rejectedPeople) {
                rejectedBy.getOrPut(r.observation) { HashSet() }.add(r.person)
            }
            val out = ArrayList<UnknownFaceCluster>(members.size)
            for ((h, group) in members) {
                val sorted = group.sortedBy { it.localHex }
                val rejected = HashSet<PersonKey>()
                for (f in sorted) {
                    rejectedBy[f]?.let(rejected::addAll)
                    cannotLink[f]?.forEach { o -> confirmedOf[o]?.let(rejected::add) }
                }
                out.add(UnknownFaceCluster(FaceClusterId(sorted.first()), sorted,
                    suggest(sums.getValue(h), people, rejected, policy)))
            }
            out.sortWith(compareByDescending<UnknownFaceCluster> { it.members.size }
                .thenBy { it.id.anchor.localHex })
            return out
        }

        private fun suggest(
            sum: DoubleArray,
            people: Map<PersonKey, DoubleArray>,
            rejected: Set<PersonKey>,
            policy: ClusterPolicy
        ): PersonSuggestion? {
            var best: PersonKey? = null
            var bestSim = Double.NEGATIVE_INFINITY
            var secondSim = Double.NEGATIVE_INFINITY
            for ((person, pSum) in people) {
                if (person in rejected) continue
                val sim = cosineSums(sum, pSum)
                // A zero-norm centroid (cancelling vectors) has no direction.
                if (!sim.isFinite()) continue
                val current = best
                if (current == null || sim > bestSim ||
                    (sim == bestSim && person.value < current.value)) {
                    secondSim = maxOf(secondSim, bestSim)
                    best = person; bestSim = sim
                } else if (sim > secondSim) {
                    secondSim = sim
                }
            }
            if (best == null || bestSim < policy.suggestThreshold) return null
            if (bestSim - secondSim < policy.suggestMargin) return null
            return PersonSuggestion(best, bestSim.toFloat())
        }
    }

    private fun addInto(sum: DoubleArray, unit: FloatArray) {
        for (i in sum.indices) sum[i] += unit[i]
    }

    private fun norm(v: DoubleArray): Double {
        var s = 0.0
        for (x in v) s += x * x
        return sqrt(s)
    }

    /** Cosine between a unit vector and the direction of a centroid sum. */
    private fun cosine(unit: FloatArray, sum: DoubleArray): Double {
        val n = norm(sum)
        if (n == 0.0) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        for (i in sum.indices) dot += unit[i] * sum[i]
        return dot / n
    }

    private fun cosineSums(a: DoubleArray, b: DoubleArray): Double {
        val na = norm(a)
        val nb = norm(b)
        if (na == 0.0 || nb == 0.0) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot / (na * nb)
    }
}
