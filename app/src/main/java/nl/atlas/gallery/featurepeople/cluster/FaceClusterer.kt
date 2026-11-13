package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import kotlin.math.sqrt

/**
 * Immutable clustering snapshot for ONE embedding model version. Embeddings
 * from different models are not comparable, so a model change means a new,
 * empty state (D2-2 re-embeds and mints new observations).
 *
 * The state holds every face vector it has been given (confirmed and
 * unknown), so later updates can re-place faces after a review change. At
 * 128 dimensions that is about 512 bytes per face.
 */
class FaceClusterState internal constructor(
    val modelVersion: String,
    val dimension: Int,
    val policy: ClusterPolicy,
    internal val faces: Map<NativeFaceObservationId, FaceEmbedding>,
    /** Unknown clusters, largest first, then by cluster ID. */
    val unknownClusters: List<UnknownFaceCluster>,
    val reviews: ClusterReviewFacts,
    /**
     * #580: unnamed faces shown under a named person without a question
     * ([ClusterPolicy.personMatchFloor]). They are in no unknown cluster and
     * are not confirmed: only [reviews] holds what a human said. Recomputed
     * by every update from the confirmed faces alone.
     */
    val matched: Map<NativeFaceObservationId, PersonMatch> = emptyMap()
) {
    /** Built on first use: a publication never needs it (#508 heap). */
    private val clusterByFace: Map<NativeFaceObservationId, FaceClusterId> by lazy {
        HashMap<NativeFaceObservationId, FaceClusterId>().also { map ->
            for (cluster in unknownClusters) for (m in cluster.members) map[m] = cluster.id
        }
    }

    val faceCount: Int get() = faces.size

    /** The unknown cluster holding [observation], or null if confirmed, matched or absent. */
    fun clusterOf(observation: NativeFaceObservationId): FaceClusterId? =
        clusterByFace[observation]

    operator fun contains(observation: NativeFaceObservationId): Boolean =
        observation in faces

    companion object {
        /**
         * Bounds memory and runaway callers: 400k faces of a whole library
         * (#508), about 60 MB of int8 vectors at 128-d plus their IDs.
         */
        const val MAX_FACES = 400_000

        fun empty(modelVersion: String, dimension: Int, policy: ClusterPolicy): FaceClusterState {
            require(modelVersion.isNotBlank() && modelVersion.length <= 256 &&
                '\u0000' !in modelVersion) { "Invalid embedding model version" }
            require(dimension in 1..FaceEmbedding.MAX_DIMENSION) { "Invalid dimension" }
            return FaceClusterState(modelVersion, dimension, policy, emptyMap(),
                emptyList(), ClusterReviewFacts.NONE)
        }
    }
}

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
 * #580: [ClusterPolicy.linkage] picks the score those thresholds read.
 * [ClusterLinkage.MEAN_PAIR] scores a face by its mean cosine to a cluster's
 * members (and two clusters by the mean over all cross pairs) minus the crowd
 * term, so a cluster that holds different people scores low against everyone
 * instead of resembling everyone; [ClusterPolicy.sameFrameVeto] keeps two
 * faces of one picture apart like a cannot-link fact does.
 *
 * Results are deterministic for the same state and inputs, whatever the order
 * of [update]'s collections. Splitting one batch into several updates may
 * group borderline faces differently; it never violates a review fact.
 *
 * Pure CPU work, no I/O; call it on a background dispatcher.
 */
object FaceClusterer {

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

        // #580: unnamed faces that are a named person by the measured rule
        // stay out of the unknown clusters. The examples are the confirmed
        // faces only, so while those, the human vetoes and the matched faces
        // themselves stay as they were, only the added faces need a score.
        val matched = when {
            state.policy.personMatchFloor <= 0f -> emptyMap()
            reviews === state.reviews &&
                removeSet.none { it in reviews.confirmed || it in state.matched } &&
                add.none { it.observation in reviews.confirmed } ->
                PersonMatcher.extend(state.matched, faces, add.map { it.observation },
                    reviews, state.dimension, state.policy)
            else -> PersonMatcher.all(faces, reviews, state.dimension, state.policy)
        }

        val work = Work(state.dimension, faces, reviews, state.policy)

        // Carry surviving unknown clusters over, applying removals and reviews.
        for (cluster in state.unknownClusters) {
            val kept = cluster.members.filter {
                it in faces && it !in reviews.confirmed && it !in matched
            }
            if (kept.isEmpty()) continue
            if (work.containsCannotLinkPair(kept)) continue // dissolved: re-placed below
            work.newCluster(kept, dirty = kept.size != cluster.members.size)
        }

        // Everything unknown and not yet in a cluster: new faces, dissolved
        // clusters, and faces whose confirmation was undone.
        val toPlace = faces.keys
            .filter { it !in reviews.confirmed && it !in matched && !work.isPlaced(it) }
            .sortedBy { it.localHex }
        for (face in toPlace) work.place(faces.getValue(face), state.policy)
        // #580 human "same person" answers, in a fixed order, before the
        // automatic unions (which then see the joined clusters).
        if (reviews.sameUnknown.isNotEmpty()) {
            for (pair in reviews.sameUnknown.sortedWith(
                compareBy<FacePair> { it.first.localHex }.thenBy { it.second.localHex })) {
                work.link(pair.first, pair.second)
            }
        }
        work.mergeDirty(state.policy)

        return FaceClusterState(state.modelVersion, state.dimension, state.policy,
            faces, work.export(state.policy), reviews, matched)
    }

    /**
     * #580: the named person each unnamed face of [state] matches at [floor],
     * computed from nothing (the confirmed faces are the only examples).
     * With [ClusterPolicy.personMatchFloor] as [floor] this equals
     * [FaceClusterState.matched].
     */
    fun personMatches(state: FaceClusterState, floor: Float): Map<NativeFaceObservationId, PersonMatch> =
        PersonMatcher.all(state.faces, state.reviews, state.dimension, state.policy, floor)

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

    /**
     * #579 second pass, run after [update] by cluster publication:
     * 1. Every face of a cluster smaller than [ClusterPolicy.attachMinSize]
     *    moves to the most similar cluster of at least that size when it
     *    reaches [ClusterPolicy.attachThreshold] against that cluster's
     *    centroid (as it was before this pass, so the result does not depend
     *    on visiting order) and no cannot-link fact vetoes it.
     * 2. Clusters of at least that size are unioned, most similar pair first,
     *    while their current centroids reach [ClusterPolicy.mergeThreshold]
     *    and no cannot-link fact vetoes the union. Re-scoring the current
     *    centroids before every union stops a chain of pairs from pulling two
     *    different people together.
     *
     * Confirmed faces are never in an unknown cluster, so they are never
     * moved, and an unknown face never joins a confirmed person. Faces that
     * fit nowhere stay in their small cluster. Deterministic for the same
     * state. Returns [state] itself when nothing moves or the pass is off.
     */
    fun consolidate(state: FaceClusterState): FaceClusterState {
        val policy = state.policy
        val minSize = policy.attachMinSize
        if (minSize == 0 || state.unknownClusters.size < 2) return state
        val dim = state.dimension
        val clusters = state.unknownClusters // largest first, then anchor
        val bigCount = clusters.indexOfFirst { it.members.size < minSize }
            .let { if (it < 0) clusters.size else it }
        if (bigCount == 0) return state

        val cannotLink = HashMap<NativeFaceObservationId, MutableList<NativeFaceObservationId>>()
        for (p in state.reviews.cannotLink) {
            cannotLink.getOrPut(p.first) { ArrayList(2) }.add(p.second)
            cannotLink.getOrPut(p.second) { ArrayList(2) }.add(p.first)
        }
        // Group index of every unknown face; small clusters keep their own index.
        val groupOf = HashMap<NativeFaceObservationId, Int>(state.faceCount * 2)
        val groups = ArrayList<MutableList<NativeFaceObservationId>>(clusters.size)
        for ((i, c) in clusters.withIndex()) {
            groups.add(c.members.toMutableList())
            for (m in c.members) groupOf[m] = i
        }
        val sums = Array(bigCount) { DoubleArray(dim) }
        val scratch = FloatArray(dim)
        for (i in 0 until bigCount) for (m in clusters[i].members) {
            state.faces.getValue(m).unitInto(scratch)
            val s = sums[i]
            for (d in 0 until dim) s[d] += scratch[d]
        }
        // Frozen unit centroids for step 1.
        val dirs = FloatArray(bigCount * dim)
        for (i in 0 until bigCount) {
            val n = norm(sums[i])
            if (n == 0.0) continue
            for (d in 0 until dim) dirs[i * dim + d] = (sums[i][d] / n).toFloat()
        }
        fun vetoed(face: NativeFaceObservationId, group: Int): Boolean =
            cannotLink[face]?.any { groupOf[it] == group } == true

        var moved = false
        val cut = policy.attachThreshold
        for (i in bigCount until clusters.size) {
            for (face in clusters[i].members) {
                state.faces.getValue(face).unitInto(scratch)
                var best = -1
                var bestSim = Float.NEGATIVE_INFINITY
                for (b in 0 until bigCount) {
                    val base = b * dim
                    var dot = 0f
                    for (d in 0 until dim) dot += scratch[d] * dirs[base + d]
                    // Strictly greater: ties keep the earlier (larger, then lower anchor) cluster.
                    if (dot > bestSim) { bestSim = dot; best = b }
                }
                if (best < 0 || bestSim < cut) continue
                if (vetoed(face, best)) continue
                groups[i].remove(face)
                groups[best].add(face)
                groupOf[face] = best
                val s = sums[best]
                for (d in 0 until dim) s[d] += scratch[d]
                moved = true
            }
        }

        // Step 2: union big clusters, best pair first, re-scored live.
        val parent = IntArray(bigCount) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        data class Pair(val a: Int, val b: Int, val sim: Double)
        val pairs = ArrayList<Pair>()
        val norms = DoubleArray(bigCount) { norm(sums[it]) }
        for (a in 0 until bigCount) for (b in a + 1 until bigCount) {
            val sim = cosine(sums[a], norms[a], sums[b], norms[b])
            if (sim >= policy.mergeThreshold) pairs.add(Pair(a, b, sim))
        }
        pairs.sortWith(compareByDescending<Pair> { it.sim }.thenBy { it.a }.thenBy { it.b })
        for (p in pairs) {
            val ra = find(p.a)
            val rb = find(p.b)
            if (ra == rb) continue
            val sim = cosine(sums[ra], norm(sums[ra]), sums[rb], norm(sums[rb]))
            if (sim < policy.mergeThreshold) continue
            val (small, other) = if (groups[ra].size <= groups[rb].size) ra to rb else rb to ra
            if (groups[small].any { vetoed(it, other) }) continue
            for (m in groups[small]) groupOf[m] = other
            groups[other].addAll(groups[small])
            groups[small].clear()
            for (d in 0 until dim) sums[other][d] += sums[small][d]
            parent[small] = other
            moved = true
        }
        if (!moved) return state
        return restore(state.modelVersion, policy, state.faces.values,
            groups.filter { it.isNotEmpty() }, state.reviews)
    }

    private fun cosine(a: DoubleArray, na: Double, b: DoubleArray, nb: Double): Double {
        if (na == 0.0 || nb == 0.0) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot / (na * nb)
    }

    /**
     * Cluster bookkeeping in dense slots so the hot loops scan flat arrays.
     *
     * Speed-ups over #409, all exact (they give the same states as #409):
     * - Each cluster's centroid norm is cached and refreshed only when the
     *   cluster changes, instead of being recomputed for every comparison.
     * - A float copy of each unit centroid feeds a cheap pre-filter. A pair
     *   whose float cosine is below a threshold by more than [FILTER_SLACK]
     *   is certainly below it in double too, so it is skipped; every pair
     *   that passes is scored with #409's double arithmetic.
     * - Scans are remembered. A slot's "scan" compares its centroid with every
     *   live slot; the near pairs (possible unions) are recorded on both
     *   sides. A new singleton's placement scan doubles as its first scan.
     *   When a dirty cluster is unioned later, a pair is re-tested only if one
     *   side changed since the other scanned it. Clusters changed since their
     *   last scan are kept in change order ([pendHead]) so they are found
     *   without walking every slot.
     * - Dirty clusters are kept in anchor order in a sorted map.
     * - #508: a one-face cluster keeps no double sum; its sum is its face's
     *   unit vector, read exactly as the sum would hold it (0.0 + u[i]). A
     *   double sum is allocated when a second face joins. Most clusters of a
     *   large library are such singletons (strangers), which saved about
     *   1 KB per stranger.
     */
    private class Work(
        private val dimension: Int,
        private val faces: Map<NativeFaceObservationId, FaceEmbedding>,
        private val reviews: ClusterReviewFacts,
        private val policy: ClusterPolicy
    ) {
        private val meanPair = policy.linkage == ClusterLinkage.MEAN_PAIR
        private val crowdWeight = policy.crowdWeight.toDouble()
        private val frameVeto = policy.sameFrameVeto
        private var capacity = 16
        /** Sum of the members' crowd values (MEAN_PAIR only). */
        private var crowdSums = DoubleArray(capacity)
        /**
         * Frame keys of the members, only for [ClusterPolicy.sameFrameVeto] and
         * only for a cluster of two or more faces: a singleton's key is its
         * face's (#508: most clusters of a large library are singletons).
         */
        private var frames = arrayOfNulls<HashSet<Long>>(capacity)
        /** Sum of a cluster with two or more faces; null for a singleton. */
        private var sums = arrayOfNulls<DoubleArray>(capacity)
        /** The face of a singleton cluster; null for a larger one. */
        private var single = arrayOfNulls<FaceEmbedding>(capacity)
        private val decoded = FloatArray(dimension)
        private val faceUnit = FloatArray(dimension)
        private var dirs = FloatArray(capacity * dimension)
        private var norms = DoubleArray(capacity)
        private var members = arrayOfNulls<MutableList<NativeFaceObservationId>>(capacity)
        private var anchors = arrayOfNulls<String>(capacity)
        /** Logical time of the slot's last change; (slot, version) names one centroid. */
        private var version = LongArray(capacity)
        /** Logical time of the slot's last scan, valid only while >= its version. */
        private var scannedAt = LongArray(capacity)
        /** Near slots found by scans of or against this slot: (slot, version) pairs. */
        private var near = arrayOfNulls<LongList>(capacity)
        private var dirtyKey = arrayOfNulls<String>(capacity)
        private var seen = IntArray(capacity)
        private var seenStamp = 0
        /** Doubly linked list of live slots not scanned since their last change. */
        private var pendPrev = IntArray(capacity)
        private var pendNext = IntArray(capacity)
        private var pending = BooleanArray(capacity)
        private var pendHead = -1
        private var pendTail = -1
        private val freeSlots = IntList()
        private var live = IntArray(capacity)
        private var liveAt = IntArray(capacity)
        private var liveCount = 0
        private var slotCount = 0
        private var clock = 0L

        private val handleOf = HashMap<NativeFaceObservationId, Int>()
        private val dirty = java.util.TreeMap<String, Int>()
        private val probe = FloatArray(dimension)
        private val nearScratch = IntList()

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

        private fun allocate(): Int {
            if (freeSlots.size > 0) return freeSlots.pop()
            if (slotCount == capacity) grow()
            return slotCount++
        }

        private fun grow() {
            val c = capacity * 2
            crowdSums = crowdSums.copyOf(c)
            frames = frames.copyOf(c)
            sums = sums.copyOf(c)
            single = single.copyOf(c)
            dirs = dirs.copyOf(c * dimension)
            norms = norms.copyOf(c)
            members = members.copyOf(c)
            anchors = anchors.copyOf(c)
            version = version.copyOf(c)
            scannedAt = scannedAt.copyOf(c)
            near = near.copyOf(c)
            dirtyKey = dirtyKey.copyOf(c)
            seen = seen.copyOf(c)
            pendPrev = pendPrev.copyOf(c)
            pendNext = pendNext.copyOf(c)
            pending = pending.copyOf(c)
            live = live.copyOf(c)
            liveAt = liveAt.copyOf(c)
            capacity = c
        }

        fun newCluster(group: List<NativeFaceObservationId>, dirty: Boolean): Int {
            val h = allocate()
            if (group.size == 1) {
                single[h] = faces.getValue(group[0])
                sums[h] = null
            } else {
                val sum = DoubleArray(dimension)
                for (f in group) {
                    faces.getValue(f).unitInto(decoded)
                    for (i in 0 until dimension) sum[i] += decoded[i]
                }
                single[h] = null
                sums[h] = sum
            }
            for (f in group) handleOf[f] = h
            var crowd = 0.0
            for (f in group) crowd += faces.getValue(f).crowd
            crowdSums[h] = crowd
            frames[h] = if (!frameVeto || group.size == 1) null else HashSet<Long>().also { set ->
                for (f in group) faces.getValue(f).frame.let { if (it != 0L) set.add(it) }
            }
            members[h] = group.toMutableList()
            anchors[h] = group.minOf { it.localHex }
            dirtyKey[h] = null
            live[liveCount] = h
            liveAt[h] = liveCount
            liveCount++
            changed(h)
            if (dirty) markDirty(h)
            return h
        }

        /** Refreshes the cached norm and float direction after a change. */
        private fun changed(h: Int) {
            val base = h * dimension
            val sum = sums[h]
            var s = 0.0
            if (sum != null) {
                for (i in 0 until dimension) { val x = sum[i]; s += x * x }
            } else {
                single[h]!!.unitInto(decoded)
                for (i in 0 until dimension) { val x = decoded[i].toDouble(); s += x * x }
            }
            val n = sqrt(s)
            norms[h] = n
            if (sum != null) {
                for (i in 0 until dimension) {
                    dirs[base + i] = if (n == 0.0) 0f else (sum[i] / n).toFloat()
                }
            } else {
                for (i in 0 until dimension) {
                    dirs[base + i] = if (n == 0.0) 0f else (decoded[i].toDouble() / n).toFloat()
                }
            }
            version[h] = ++clock
            scannedAt[h] = -1
            near[h]?.clear()
            unpend(h)
            pend(h)
        }

        private fun release(h: Int) {
            val at = liveAt[h]
            val last = live[--liveCount]
            live[at] = last
            liveAt[last] = at
            members[h] = null
            frames[h] = null
            anchors[h] = null
            near[h] = null
            sums[h] = null
            single[h] = null
            version[h] = ++clock
            scannedAt[h] = -1
            unpend(h)
            freeSlots.add(h)
        }

        private fun pend(h: Int) {
            pending[h] = true
            pendPrev[h] = pendTail
            pendNext[h] = -1
            if (pendTail >= 0) pendNext[pendTail] = h else pendHead = h
            pendTail = h
        }

        private fun unpend(h: Int) {
            if (!pending[h]) return
            pending[h] = false
            val p = pendPrev[h]
            val n = pendNext[h]
            if (p >= 0) pendNext[p] = n else pendHead = n
            if (n >= 0) pendPrev[n] = p else pendTail = p
        }

        private fun markDirty(h: Int) {
            val key = dirtyKey[h]
            if (key != null) dirty.remove(key)
            val a = anchors[h]!!
            dirty[a] = h
            dirtyKey[h] = a
        }

        private fun clearDirty(h: Int) {
            val key = dirtyKey[h] ?: return
            dirty.remove(key)
            dirtyKey[h] = null
        }

        /** Float cosine between [probe] and slot [o]'s unit centroid. */
        private fun approx(o: Int): Float {
            val base = o * dimension
            var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
            var i = 0
            val end4 = dimension - 3
            while (i < end4) {
                a0 += probe[i] * dirs[base + i]
                a1 += probe[i + 1] * dirs[base + i + 1]
                a2 += probe[i + 2] * dirs[base + i + 2]
                a3 += probe[i + 3] * dirs[base + i + 3]
                i += 4
            }
            while (i < dimension) { a0 += probe[i] * dirs[base + i]; i++ }
            return (a0 + a1) + (a2 + a3)
        }

        /** Slot [h]'s sum as #409 holds it: a double sum, or a singleton's unit. */
        private fun sumOf(h: Int, out: DoubleArray): DoubleArray {
            sums[h]?.let { return it }
            single[h]!!.unitInto(decoded)
            for (i in 0 until dimension) out[i] = decoded[i].toDouble()
            return out
        }
        private val sumScratchA = DoubleArray(dimension)
        private val sumScratchB = DoubleArray(dimension)

        /**
         * A unit face vector against slot [o]: #409's cosine to the centroid
         * sum, or under [ClusterLinkage.MEAN_PAIR] the mean cosine to the
         * members minus the crowd term. The mean is `dot / count`; since
         * `|sum| <= count` it never exceeds the cosine when that is positive,
         * and the crowd term only lowers it, so the float pre-filter on the
         * centroid cosine stays a safe upper bound for both linkages.
         */
        private fun exactFace(unit: FloatArray, o: Int, crowd: Float = 0f): Double {
            val n = norms[o]
            if (n == 0.0) return Double.NEGATIVE_INFINITY
            val sum = sumOf(o, sumScratchA)
            var dot = 0.0
            for (i in 0 until dimension) dot += unit[i] * sum[i]
            if (!meanPair) return dot / n
            val count = members[o]!!.size
            return dot / count - crowdWeight * (crowd + crowdSums[o] / count) / 2
        }

        /** Two slots: #409's cosine between the centroid sums, or the MEAN_PAIR score (see [exactFace]). */
        private fun exactPair(a: Int, b: Int): Double {
            val na = norms[a]
            val nb = norms[b]
            if (na == 0.0 || nb == 0.0) return Double.NEGATIVE_INFINITY
            val sa = sumOf(a, sumScratchA)
            val sb = sumOf(b, sumScratchB)
            var dot = 0.0
            for (i in 0 until dimension) dot += sa[i] * sb[i]
            if (!meanPair) return dot / (na * nb)
            val ca = members[a]!!.size
            val cb = members[b]!!.size
            return dot / (ca.toDouble() * cb) -
                crowdWeight * (crowdSums[a] / ca + crowdSums[b] / cb) / 2
        }

        /** The cut a face must reach against slot [o]: the group cut once [o] is big enough. */
        private fun joinCutFor(o: Int, policy: ClusterPolicy): Float =
            if (policy.groupMinSize > 0 && members[o]!!.size >= policy.groupMinSize) policy.groupThreshold
            else policy.joinThreshold

        /** The cut for unioning [a] and [b]: the group cut only when both are big enough. */
        private fun mergeCutFor(a: Int, b: Int, policy: ClusterPolicy): Float =
            if (policy.groupMinSize > 0 && members[a]!!.size >= policy.groupMinSize &&
                members[b]!!.size >= policy.groupMinSize) minOf(policy.groupThreshold, policy.mergeThreshold)
            else policy.mergeThreshold

        /** Slot [h]'s own double sum, allocated from its singleton face if needed. */
        private fun ownSum(h: Int): DoubleArray {
            sums[h]?.let { return it }
            val sum = DoubleArray(dimension)
            single[h]!!.unitInto(decoded)
            for (i in 0 until dimension) sum[i] += decoded[i]
            sums[h] = sum
            single[h] = null
            return sum
        }

        fun place(face: FaceEmbedding, policy: ClusterPolicy) {
            val unit = faceUnit
            face.unitInto(unit)
            unit.copyInto(probe)
            val lowJoin = if (policy.groupMinSize > 0) minOf(policy.joinThreshold, policy.groupThreshold)
                else policy.joinThreshold
            val lowMerge = if (policy.groupMinSize > 0) minOf(policy.mergeThreshold, policy.groupThreshold)
                else policy.mergeThreshold
            val joinCut = lowJoin - FILTER_SLACK
            val mergeCut = lowMerge - FILTER_SLACK
            var best = -1
            var bestSim = Double.NEGATIVE_INFINITY
            var secondSim = Double.NEGATIVE_INFINITY
            for (k in 0 until liveCount) {
                val h = live[k]
                val a = approx(h)
                if (a >= mergeCut) nearScratch.add(h)
                if (a < joinCut) continue
                val sim = exactFace(unit, h, face.crowd)
                if (sim < joinCutFor(h, policy)) continue
                if (vetoes(face.observation, h)) continue
                if (frameVeto && face.frame != 0L && hasFrame(h, face.frame)) continue
                if (sim > bestSim || (sim == bestSim && anchorLess(h, best))) {
                    if (best >= 0) secondSim = maxOf(secondSim, bestSim)
                    best = h; bestSim = sim
                } else if (sim > secondSim) {
                    secondSim = sim
                }
            }
            val ambiguous = best >= 0 && bestSim - secondSim < policy.ambiguityMargin
            if (best < 0 || ambiguous) {
                // The new singleton's centroid is this face (up to float
                // rounding, far inside FILTER_SLACK), so this scan is also its
                // union scan against every slot alive now.
                val h = newCluster(listOf(face.observation), dirty = true)
                finishScan(h)
                return
            }
            nearScratch.clear()
            members[best]!!.add(face.observation)
            crowdSums[best] += face.crowd
            if (frameVeto) ownFrames(best).let { if (face.frame != 0L) it.add(face.frame) }
            val sum = ownSum(best)
            for (i in 0 until dimension) sum[i] += unit[i]
            handleOf[face.observation] = best
            if (face.observation.localHex < anchors[best]!!) anchors[best] = face.observation.localHex
            changed(best)
            markDirty(best)
        }

        /**
         * Slot [h] has just been compared with every other live slot, and
         * [nearScratch] holds the near ones: mark it scanned and record each
         * near pair on both sides.
         */
        private fun finishScan(h: Int) {
            scannedAt[h] = clock
            unpend(h)
            val mine = near[h] ?: LongList().also { near[h] = it }
            val vh = version[h]
            for (j in 0 until nearScratch.size) {
                val o = nearScratch[j]
                if (o == h) continue
                mine.add(o.toLong()); mine.add(version[o])
                val theirs = near[o] ?: LongList().also { near[o] = it }
                theirs.add(h.toLong()); theirs.add(vh)
            }
            nearScratch.clear()
        }

        /**
         * Collects into [nearScratch] every slot that might reach [cut] against
         * slot [h], each once. If [h] is unchanged since its last scan, only
         * its recorded near pairs and the slots changed since that scan need
         * checking; any other slot either was in that scan or has scanned
         * [h] since, and recorded the pair if it was near.
         */
        private fun candidates(h: Int, cut: Float): Boolean {
            val hBase = h * dimension
            for (i in 0 until dimension) probe[i] = dirs[hBase + i]
            val since = scannedAt[h]
            if (since < 0 || since < version[h]) {
                for (k in 0 until liveCount) {
                    val o = live[k]
                    if (o != h && approx(o) >= cut) nearScratch.add(o)
                }
                return true // a fresh scan, to be recorded
            }
            val stamp = ++seenStamp
            seen[h] = stamp
            val list = near[h]
            if (list != null) {
                var j = 0
                while (j < list.size) {
                    val o = list[j].toInt()
                    if (members[o] != null && version[o] == list[j + 1] && seen[o] != stamp) {
                        seen[o] = stamp
                        nearScratch.add(o)
                    }
                    j += 2
                }
            }
            var o = pendTail
            while (o >= 0 && version[o] > since) {
                if (seen[o] != stamp) {
                    seen[o] = stamp
                    if (approx(o) >= cut) nearScratch.add(o)
                }
                o = pendPrev[o]
            }
            return false
        }

        /** Union dirty clusters with their best partner until nothing changes. */
        fun mergeDirty(policy: ClusterPolicy) {
            val mergeCut = (if (policy.groupMinSize > 0) minOf(policy.mergeThreshold, policy.groupThreshold)
                else policy.mergeThreshold) - FILTER_SLACK
            while (dirty.isNotEmpty()) {
                val h = dirty.pollFirstEntry()!!.value
                dirtyKey[h] = null
                val fresh = candidates(h, mergeCut)
                var partner = -1
                var partnerSim = Double.NEGATIVE_INFINITY
                var secondSim = Double.NEGATIVE_INFINITY
                for (j in 0 until nearScratch.size) {
                    val o = nearScratch[j]
                    val sim = exactPair(h, o)
                    if (sim < mergeCutFor(h, o, policy)) continue
                    if (sim <= secondSim) continue
                    if (clustersVeto(h, o)) continue
                    if (sim > partnerSim || (sim == partnerSim && anchorLess(o, partner))) {
                        secondSim = maxOf(secondSim, partnerSim)
                        partner = o; partnerSim = sim
                    } else {
                        secondSim = sim
                    }
                }
                if (fresh) finishScan(h) else nearScratch.clear()
                // Same ambiguity rule as joining: a cluster that resembles two
                // others about equally is left alone rather than bridging them.
                if (partner < 0 || partnerSim - secondSim < policy.ambiguityMargin) continue
                absorb(into = h, from = partner)
                markDirty(h)
            }
        }

        private fun absorb(into: Int, from: Int) {
            clearDirty(from)
            val moved = members[from]!!
            for (f in moved) handleOf[f] = into
            members[into]!!.addAll(moved)
            crowdSums[into] += crowdSums[from]
            if (frameVeto) {
                val target = ownFrames(into)
                val set = frames[from]
                if (set != null) target.addAll(set)
                else single[from]!!.frame.let { if (it != 0L) target.add(it) }
            }
            val target = ownSum(into)
            val source = sumOf(from, sumScratchA)
            for (i in 0 until dimension) target[i] += source[i]
            val movedAnchor = anchors[from]!!
            if (movedAnchor < anchors[into]!!) anchors[into] = movedAnchor
            release(from)
            changed(into)
        }

        /**
         * A human said [a] and [b] are one person: union their clusters,
         * unless a "not the same person" fact stands between any two of
         * their faces. Scores and the same-picture rule do not apply to a
         * human answer. Faces that are confirmed or gone are skipped.
         */
        fun link(a: NativeFaceObservationId, b: NativeFaceObservationId) {
            val ha = handleOf[a] ?: return
            val hb = handleOf[b] ?: return
            if (ha == hb) return
            // "Not the same person" and two faces in one picture both beat a
            // stored yes: it is replayed on every publication, and a face
            // scanned later may put the two groups in the same picture.
            if (clustersVeto(ha, hb)) return
            val small = if (members[ha]!!.size <= members[hb]!!.size) ha else hb
            val other = if (small == ha) hb else ha
            absorb(into = other, from = small)
            markDirty(other)
        }

        private fun vetoes(face: NativeFaceObservationId, h: Int): Boolean =
            cannotLink[face]?.any { handleOf[it] == h } == true

        private fun clustersVeto(a: Int, b: Int): Boolean {
            val small = if (members[a]!!.size <= members[b]!!.size) a else b
            val other = if (small == a) b else a
            if (frameVeto) {
                val set = frames[small]
                if (set == null) {
                    val key = single[small]!!.frame
                    if (key != 0L && hasFrame(other, key)) return true
                } else if (set.any { hasFrame(other, it) }) return true
            }
            return members[small]!!.any { vetoes(it, other) }
        }

        private fun hasFrame(h: Int, key: Long): Boolean =
            frames[h]?.contains(key) ?: (single[h]!!.frame == key)

        /** Slot [h]'s own frame set, allocated from its singleton face if needed (before [ownSum]). */
        private fun ownFrames(h: Int): HashSet<Long> {
            frames[h]?.let { return it }
            val set = HashSet<Long>()
            single[h]?.frame?.let { if (it != 0L) set.add(it) }
            frames[h] = set
            return set
        }

        private fun anchorLess(a: Int, b: Int): Boolean = b < 0 || anchors[a]!! < anchors[b]!!

        fun export(policy: ClusterPolicy): List<UnknownFaceCluster> {
            val people = HashMap<PersonKey, DoubleArray>()
            val confirmedOf = HashMap<NativeFaceObservationId, PersonKey>()
            for ((face, person) in reviews.confirmed) {
                val e = faces[face] ?: continue
                addInto(people.getOrPut(person) { DoubleArray(dimension) }, e.unit)
                confirmedOf[face] = person
            }
            val personKeys = people.keys.toTypedArray()
            val personSums = Array(personKeys.size) { people.getValue(personKeys[it]) }
            val personNorms = DoubleArray(personKeys.size) { norm(personSums[it]) }
            val rejectedBy = HashMap<NativeFaceObservationId, MutableSet<PersonKey>>()
            for (r in reviews.rejectedPeople) {
                rejectedBy.getOrPut(r.observation) { HashSet() }.add(r.person)
            }
            val out = ArrayList<UnknownFaceCluster>(liveCount)
            for (k in 0 until liveCount) {
                val h = live[k]
                val sorted = members[h]!!.sortedBy { it.localHex }
                val rejected = HashSet<PersonKey>()
                for (f in sorted) {
                    rejectedBy[f]?.let(rejected::addAll)
                    cannotLink[f]?.forEach { o -> confirmedOf[o]?.let(rejected::add) }
                }
                out.add(UnknownFaceCluster(FaceClusterId(sorted.first()), sorted,
                    suggest(h, personKeys, personSums, personNorms, rejected, policy)))
            }
            out.sortWith(compareByDescending<UnknownFaceCluster> { it.members.size }
                .thenBy { it.id.anchor.localHex })
            return out
        }

        private fun suggest(
            h: Int,
            personKeys: Array<PersonKey>,
            personSums: Array<DoubleArray>,
            personNorms: DoubleArray,
            rejected: Set<PersonKey>,
            policy: ClusterPolicy
        ): PersonSuggestion? {
            val na = norms[h]
            val sum = sumOf(h, sumScratchA)
            var best: PersonKey? = null
            var bestSim = Double.NEGATIVE_INFINITY
            var secondSim = Double.NEGATIVE_INFINITY
            for (p in personKeys.indices) {
                val person = personKeys[p]
                if (person in rejected) continue
                val nb = personNorms[p]
                val sim = if (na == 0.0 || nb == 0.0) {
                    Double.NEGATIVE_INFINITY
                } else {
                    val pSum = personSums[p]
                    var dot = 0.0
                    for (i in 0 until dimension) dot += sum[i] * pSum[i]
                    dot / (na * nb)
                }
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

    /**
     * How far a float cosine may sit below a threshold and still be scored
     * exactly. Float error on a dot of unit vectors is about dim * 6e-8
     * (under 1e-4 at the 1,024-d maximum), so 1e-3 is a wide safety margin.
     */
    private const val FILTER_SLACK = 1e-3f

    private fun addInto(sum: DoubleArray, unit: FloatArray) {
        for (i in sum.indices) sum[i] += unit[i]
    }

    private fun norm(v: DoubleArray): Double {
        var s = 0.0
        for (x in v) s += x * x
        return sqrt(s)
    }
}

private class IntList {
    private var a = IntArray(16)
    var size = 0
        private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun pop(): Int = a[--size]
    fun clear() { size = 0 }
}

private class LongList {
    private var a = LongArray(8)
    var size = 0
        private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
}
