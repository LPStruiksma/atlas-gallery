package nl.atlas.gallery.featurepeople.identity

import nl.atlas.gallery.featurepeople.cluster.ClusterLinkage
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster

/**
 * #580 People Intelligence, step "observation -> hypothesis -> confirmed".
 *
 * Grouping (FaceClusterer) only joins what it is sure of, so one person can
 * end as two or three groups: a child at two ages, with and without glasses,
 * a profile set. A hypothesis is the engine saying "these two groups may be
 * one person" without acting on it. Only a human answer turns it into a fact:
 * yes joins the groups for good, no is stored as "not the same person" and
 * the pair is never asked again. Unknown stays a valid outcome: a pair that
 * no layer supports is simply not listed.
 *
 * Pure Kotlin, on the device, on vectors already in memory. Nothing here
 * reads pixels, names or files, and nothing leaves the process.
 */

/** The kinds of evidence the People goal names. Only [FACE] is measured today. */
enum class EvidenceLayer { FACE, BODY, ATTIRE, CONTEXT }

/**
 * What one layer found for one pair of groups. [support] is that layer's own
 * calibrated score (for [EvidenceLayer.FACE]: crowd-corrected cosine). A
 * [veto] means the layer knows the two cannot be one person (two faces in one
 * picture); a veto always wins, whatever the other layers say.
 */
data class EvidenceReading(val layer: EvidenceLayer, val support: Float, val veto: Boolean = false)

/** The two groups a layer is asked about: their faces, as the clusterer holds them. */
class GroupPair internal constructor(
    val first: UnknownFaceCluster,
    val second: UnknownFaceCluster
)

/**
 * Plug-in point for the later layers (body, attire, context). A source may
 * only veto a pair or add support to one the face layer already proposed: it
 * can change the order of the questions, never create one on its own, and
 * never join anything. So a new layer cannot cause a false merge.
 */
fun interface EvidenceSource {
    fun read(pair: GroupPair): EvidenceReading?
}

/**
 * "Are these the same person?" for two unnamed groups, [first] the larger.
 * - [meanPair]: mean corrected cosine over all cross pairs (what grouping uses).
 * - [bestMatch]: for each face of the smaller group its best match in the
 *   other, then the median: high when the groups overlap in some poses.
 */
data class SamePersonHypothesis(
    val first: FaceClusterId,
    val second: FaceClusterId,
    val firstFaces: Int,
    val secondFaces: Int,
    val meanPair: Float,
    val bestMatch: Float,
    val evidence: List<EvidenceReading>
) {
    /** Order of asking: strongest first. Extra layers add at most [EXTRA_WEIGHT] each. */
    val strength: Float get() = maxOf(meanPair + (BEST_MATCH_FLOOR - MEAN_PAIR_FLOOR), bestMatch) +
        evidence.filter { it.layer != EvidenceLayer.FACE }
            .sumOf { (it.support.coerceIn(0f, 1f) * EXTRA_WEIGHT).toDouble() }.toFloat()

    companion object {
        /**
         * Measured on 48 real photo libraries (docs/people/PEOPLE_BENCH.md):
         * at these floors about two in three questions are the same person
         * and about one in ten is clearly someone else; lower floors ask
         * mostly about strangers.
         */
        const val MEAN_PAIR_FLOOR = 0.30f
        const val BEST_MATCH_FLOOR = 0.40f
        const val EXTRA_WEIGHT = 0.05f
    }
}

/**
 * "Is this unnamed [group] also [person]?" Scores as in [SamePersonHypothesis],
 * with the person's confirmed faces as the larger side. Only ever a question.
 */
data class PersonHypothesis(
    val person: PersonKey,
    val group: FaceClusterId,
    val personFaces: Int,
    val groupFaces: Int,
    val meanPair: Float,
    val bestMatch: Float
) {
    val strength: Float get() = maxOf(meanPair + (SamePersonHypothesis.BEST_MATCH_FLOOR -
        SamePersonHypothesis.MEAN_PAIR_FLOOR), bestMatch)
}

object SamePersonHypotheses {
    /** A question needs one group of at least this many faces... */
    const val MIN_FIRST = 3
    /** ...and another of at least this many: lone faces are mostly strangers. */
    const val MIN_SECOND = 2
    /** Largest groups considered; bounds the work on a whole library. */
    const val MAX_GROUPS = 2_000
    /** Faces per group read for [SamePersonHypothesis.bestMatch] (even stride). */
    const val MAX_SAMPLE = 48
    /** Pairs under this mean are skipped before the per-face pass. */
    const val PREFILTER = 0.15f
    const val DEFAULT_LIMIT = 50

    /**
     * Deterministic for one state. Empty unless the state was grouped with
     * mean-pair linkage: the floors are calibrated for those vectors only.
     * Never lists a pair with a "not the same person" fact between any two of
     * its faces, or with two faces from one picture.
     */
    fun of(
        state: FaceClusterState,
        sources: List<EvidenceSource> = emptyList(),
        limit: Int = DEFAULT_LIMIT
    ): List<SamePersonHypothesis> {
        require(limit in 0..10_000) { "Invalid limit" }
        val policy = state.policy
        if (policy.linkage != ClusterLinkage.MEAN_PAIR || limit == 0) return emptyList()
        // unknownClusters is largest first, then by id: a stable prefix.
        val groups = state.unknownClusters.asSequence()
            .filter { it.members.size >= MIN_SECOND }.take(MAX_GROUPS).toList()
        if (groups.size < 2 || groups[0].members.size < MIN_FIRST) return emptyList()
        val dim = state.dimension
        val g = groups.size
        val sums = FloatArray(g * dim)
        val crowd = FloatArray(g)
        val frames = arrayOfNulls<HashSet<Long>>(g)
        val indexOf = HashMap<FaceClusterId, Int>(g * 2)
        val scratch = FloatArray(dim)
        for ((k, group) in groups.withIndex()) {
            indexOf[group.id] = k
            val seen = HashSet<Long>()
            for (m in group.members) {
                val face = state.faces.getValue(m)
                face.unitInto(scratch)
                val base = k * dim
                for (d in 0 until dim) sums[base + d] += scratch[d]
                crowd[k] += face.crowd
                if (face.frame != 0L) seen += face.frame
            }
            crowd[k] /= group.members.size
            frames[k] = seen
        }
        // Human "not the same person" facts, as pairs of group indexes.
        val vetoed = HashSet<Long>()
        for (pair in state.reviews.cannotLink) {
            val a = state.clusterOf(pair.first)?.let(indexOf::get) ?: continue
            val b = state.clusterOf(pair.second)?.let(indexOf::get) ?: continue
            if (a != b) vetoed += key(minOf(a, b), maxOf(a, b))
        }
        val w = policy.crowdWeight
        val out = ArrayList<SamePersonHypothesis>()
        val samples = arrayOfNulls<Sample>(g)
        for (a in 0 until g) {
            val na = groups[a].members.size
            if (na < MIN_FIRST) break
            for (b in a + 1 until g) {
                if (key(a, b) in vetoed) continue
                val nb = groups[b].members.size
                var dot = 0f
                val pa = a * dim
                val pb = b * dim
                for (d in 0 until dim) dot += sums[pa + d] * sums[pb + d]
                val mean = dot / (na.toFloat() * nb) - w * (crowd[a] + crowd[b]) / 2f
                if (mean < PREFILTER) continue
                if (frames[a]!!.any { it in frames[b]!! }) continue
                val sa = samples[a] ?: Sample(state, groups[a].members, dim).also { samples[a] = it }
                val sb = samples[b] ?: Sample(state, groups[b].members, dim).also { samples[b] = it }
                val best = bestMatch(sb, sa, dim, w)
                if (mean < SamePersonHypothesis.MEAN_PAIR_FLOOR &&
                    best < SamePersonHypothesis.BEST_MATCH_FLOOR) continue
                val evidence = ArrayList<EvidenceReading>(1 + sources.size)
                evidence += EvidenceReading(EvidenceLayer.FACE, maxOf(mean, best))
                var blocked = false
                if (sources.isNotEmpty()) {
                    val pair = GroupPair(groups[a], groups[b])
                    for (source in sources) {
                        val reading = source.read(pair) ?: continue
                        require(reading.layer != EvidenceLayer.FACE && reading.support.isFinite()) {
                            "An extra source cannot speak for the face layer"
                        }
                        if (reading.veto) { blocked = true; break }
                        evidence += reading
                    }
                }
                if (blocked) continue
                out += SamePersonHypothesis(groups[a].id, groups[b].id, na, nb, mean, best, evidence)
            }
        }
        out.sortWith(compareByDescending<SamePersonHypothesis> { it.strength }
            .thenByDescending { it.firstFaces + it.secondFaces }
            .thenBy { it.first.anchor.localHex }.thenBy { it.second.anchor.localHex })
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    private fun key(a: Int, b: Int): Long = a.toLong() shl 32 or b.toLong()

    /**
     * "Is this group also [PersonHypothesis.person]?" for unnamed groups
     * against named people. Only a human confirms a face for a named
     * person; a later photo grouping is not sure about
     * ([nl.atlas.gallery.featurepeople.cluster.ClusterPolicy.personMatchFloor])
     * waits in an unnamed group until this question is answered.
     *
     * The same scores and floors as [of], with the person's confirmed faces
     * as the larger group. A lone face is listed only when it reaches the
     * grouping rule itself ([nl.atlas.gallery.featurepeople.cluster.ClusterPolicy.groupThreshold]
     * against a person of at least groupMinSize faces, else joinThreshold):
     * the face that would have joined the group had it not been named.
     *
     * Never lists a group with a face the user took out of that person or
     * refused for them, with a "not the same person" fact against one of the
     * person's faces, or with a face from a picture the person is already in.
     * [people] null asks for every named person; the work grows with unnamed
     * groups times people, so pass the people on screen.
     */
    fun forPeople(
        state: FaceClusterState,
        people: Set<PersonKey>? = null,
        limit: Int = DEFAULT_LIMIT
    ): List<PersonHypothesis> {
        require(limit in 0..10_000) { "Invalid limit" }
        val policy = state.policy
        if (policy.linkage != ClusterLinkage.MEAN_PAIR || limit == 0) return emptyList()
        val membersOf = HashMap<PersonKey, ArrayList<NativeFaceObservationId>>()
        for ((face, person) in state.reviews.confirmed) {
            if (face !in state || (people != null && person !in people)) continue
            membersOf.getOrPut(person) { ArrayList() }.add(face)
        }
        val keys = membersOf.filterValues { it.size >= MIN_FIRST }.keys.sortedBy { it.value }
        if (keys.isEmpty() || state.unknownClusters.isEmpty()) return emptyList()
        val dim = state.dimension
        val p = keys.size
        val indexOf = HashMap<PersonKey, Int>(p * 2)
        val faces = ArrayList<List<NativeFaceObservationId>>(p)
        val sums = FloatArray(p * dim)
        val crowd = FloatArray(p)
        val frames = ArrayList<HashSet<Long>>(p)
        val scratch = FloatArray(dim)
        for ((k, person) in keys.withIndex()) {
            indexOf[person] = k
            val members = membersOf.getValue(person).sortedBy { it.localHex }
            faces += members
            val seen = HashSet<Long>()
            for (m in members) {
                val face = state.faces.getValue(m)
                face.unitInto(scratch)
                val base = k * dim
                for (d in 0 until dim) sums[base + d] += scratch[d]
                crowd[k] += face.crowd
                if (face.frame != 0L) seen += face.frame
            }
            crowd[k] /= members.size
            frames += seen
        }
        // Per unknown face: the people a human answer keeps it away from.
        val barred = HashMap<NativeFaceObservationId, HashSet<Int>>()
        for (r in state.reviews.rejectedPeople) {
            val k = indexOf[r.person] ?: continue
            barred.getOrPut(r.observation) { HashSet() }.add(k)
        }
        for (pair in state.reviews.cannotLink) {
            state.reviews.confirmed[pair.first]?.let(indexOf::get)?.let {
                barred.getOrPut(pair.second) { HashSet() }.add(it)
            }
            state.reviews.confirmed[pair.second]?.let(indexOf::get)?.let {
                barred.getOrPut(pair.first) { HashSet() }.add(it)
            }
        }
        val w = policy.crowdWeight
        val out = ArrayList<PersonHypothesis>()
        val samples = arrayOfNulls<Sample>(p)
        val sum = FloatArray(dim)
        val no = HashSet<Int>()
        val seen = HashSet<Long>()
        for (group in state.unknownClusters) {
            val n = group.members.size
            sum.fill(0f)
            no.clear()
            seen.clear()
            var groupCrowd = 0f
            for (m in group.members) {
                val face = state.faces.getValue(m)
                face.unitInto(scratch)
                for (d in 0 until dim) sum[d] += scratch[d]
                groupCrowd += face.crowd
                if (face.frame != 0L) seen += face.frame
                barred[m]?.let(no::addAll)
            }
            groupCrowd /= n
            var own: Sample? = null
            for (k in 0 until p) {
                if (k in no) continue
                val np = faces[k].size
                var dot = 0f
                val base = k * dim
                for (d in 0 until dim) dot += sum[d] * sums[base + d]
                val mean = dot / (n.toFloat() * np) - w * (groupCrowd + crowd[k]) / 2f
                if (mean < PREFILTER) continue
                if (seen.any { it in frames[k] }) continue
                if (n < MIN_SECOND) {
                    val rule = if (policy.groupMinSize in 1..np) policy.groupThreshold
                        else policy.joinThreshold
                    if (mean < rule) continue
                }
                val sp = samples[k] ?: Sample(state, faces[k], dim).also { samples[k] = it }
                val sg = own ?: Sample(state, group.members, dim).also { own = it }
                val best = bestMatch(sg, sp, dim, w)
                if (n >= MIN_SECOND && mean < SamePersonHypothesis.MEAN_PAIR_FLOOR &&
                    best < SamePersonHypothesis.BEST_MATCH_FLOOR) continue
                out += PersonHypothesis(keys[k], group.id, np, n, mean, best)
            }
        }
        out.sortWith(compareByDescending<PersonHypothesis> { it.strength }
            .thenByDescending { it.groupFaces }
            .thenBy { it.person.value }.thenBy { it.group.anchor.localHex })
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /** Up to [MAX_SAMPLE] unit vectors of one group, evenly spread over its sorted members. */
    private class Sample(state: FaceClusterState, members: List<NativeFaceObservationId>, dim: Int) {
        val count = minOf(members.size, MAX_SAMPLE)
        val units = FloatArray(count * dim)
        val crowd = FloatArray(count)
        init {
            val scratch = FloatArray(dim)
            for (i in 0 until count) {
                val face: FaceEmbedding = state.faces.getValue(
                    members[(i.toLong() * members.size / count).toInt()])
                face.unitInto(scratch)
                scratch.copyInto(units, i * dim)
                crowd[i] = face.crowd
            }
        }
    }

    /** Median over [small]'s faces of their best corrected cosine in [large]. */
    private fun bestMatch(small: Sample, large: Sample, dim: Int, w: Float): Float {
        val best = FloatArray(small.count)
        for (i in 0 until small.count) {
            var top = Float.NEGATIVE_INFINITY
            val pi = i * dim
            for (j in 0 until large.count) {
                var dot = 0f
                val pj = j * dim
                for (d in 0 until dim) dot += small.units[pi + d] * large.units[pj + d]
                val s = dot - w * (small.crowd[i] + large.crowd[j]) / 2f
                if (s > top) top = s
            }
            best[i] = top
        }
        best.sort()
        val n = best.size
        return if (n % 2 == 1) best[n / 2] else (best[n / 2 - 1] + best[n / 2]) / 2f
    }
}
