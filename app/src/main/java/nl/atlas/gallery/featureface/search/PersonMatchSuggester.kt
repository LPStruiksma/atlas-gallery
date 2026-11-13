package nl.atlas.gallery.featureface.search

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import kotlin.math.sqrt

/**
 * #571 (goal #509 P11): "Same person?" suggestions. Pure Kotlin, no Android,
 * storage or model runtime; reads, never writes, review facts.
 *
 * Inputs for ONE review generation: the published unknown clusters and every
 * confirmed person's faces, as int8 unit vectors with one scale each
 * ([FaceEmbedding], #508/#518), plus the human review facts. Built by
 * FaceIndexStore.personMatchInputs from the materialised rows only (no
 * cluster rebuild), or by tests from synthetic vectors.
 */
class PersonMatchInputs private constructor(
    /** The review generation the rows were read under (StoredPeopleReview.generation). */
    val generation: String,
    val modelVersion: String,
    /** Unknown clusters by anchor; members in face-uid order. */
    val clusters: Map<FaceClusterId, List<FaceEmbedding>>,
    /** Confirmed faces per person; faces in face-uid order. */
    val confirmed: Map<PersonKey, List<FaceEmbedding>>,
    val reviews: ClusterReviewFacts
) {
    val faceCount: Int get() = clusters.values.sumOf { it.size } + confirmed.values.sumOf { it.size }

    /**
     * Streaming builder. A face confirmed as a person is that person's, even
     * if a stale membership row still names a cluster: confirmed faces never
     * belong to an unknown cluster.
     */
    class Builder(
        private val generation: String,
        private val modelVersion: String,
        private val reviews: ClusterReviewFacts
    ) {
        private val clusters = HashMap<String, MutableList<FaceEmbedding>>()
        private val confirmed = HashMap<PersonKey, MutableList<FaceEmbedding>>()
        private val seen = HashSet<String>()

        init {
            require(generation.isNotBlank() && generation.length <= 256) { "Invalid generation" }
            require(modelVersion.isNotBlank() && modelVersion.length <= 256 &&
                '\u0000' !in modelVersion) { "Invalid model version" }
        }

        fun add(face: NativeFaceObservationId, vector: FloatArray,
                clusterAnchor: NativeFaceObservationId?, person: PersonKey?): Builder {
            require(clusterAnchor != null || person != null) {
                "A suggestion input face is either clustered or confirmed"
            }
            require(seen.size < MAX_FACES) { "Suggestion input limit $MAX_FACES reached" }
            require(seen.add(face.localHex)) { "Duplicate face in suggestion inputs" }
            val embedding = FaceEmbedding(face, modelVersion, vector)
            if (person != null) confirmed.getOrPut(person) { ArrayList() } += embedding
            else clusters.getOrPut(clusterAnchor!!.localHex) { ArrayList() } += embedding
            return this
        }

        fun build(): PersonMatchInputs {
            val byUid = compareBy<FaceEmbedding> { it.observation.localHex }
            val c = clusters.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { (anchor, faces) ->
                FaceClusterId(NativeFaceObservationId(anchor)) to faces.sortedWith(byUid)
            }
            val p = confirmed.entries.sortedBy { it.key.value }.associateTo(LinkedHashMap()) {
                it.key to it.value.sortedWith(byUid)
            }
            return PersonMatchInputs(generation, modelVersion, c, p, reviews)
        }
    }

    companion object {
        /** Same ceiling as the search corpus (FaceSearchCorpus.MAX_FACES). */
        const val MAX_FACES = FaceSearchCorpus.MAX_FACES
    }
}

/** Ordered best first; both are shown, PROBABLE as "maybe". */
enum class PersonMatchTier { SAME_PERSON, PROBABLE }

/**
 * One unnamed cluster suggested for [person].
 * - [score] = mean of [centroidSimilarity] (cluster centroid to the person's
 *   confirmed-face centroid) and [topKMean] (mean of the [PersonMatchSuggester.TOP_K]
 *   best cosines of the cluster centroid to the person's confirmed faces).
 * - [ambiguous]: another person scores within [FaceQueryBands.PERSON_MARGIN]
 *   of this one for the same cluster; the UI should not pre-select it.
 */
data class PersonMatchSuggestion(
    val person: PersonKey,
    val cluster: FaceClusterId,
    val faceCount: Int,
    val score: Float,
    val centroidSimilarity: Float,
    val topKMean: Float,
    val tier: PersonMatchTier,
    val ambiguous: Boolean
)

/**
 * Per named person, at most [PersonMatchSuggester.MAX_PER_PERSON] suggestions
 * in a stable order: SAME_PERSON before PROBABLE, then score (high first),
 * then cluster size (large first), then cluster anchor. [truncated] holds the
 * people who had more candidates than the limit. [bandsCalibrated] is false
 * until the VS benchmark replaces [FaceQueryBands] with measured numbers.
 */
class PersonMatchSuggestions(
    val generation: String,
    val byPerson: Map<PersonKey, List<PersonMatchSuggestion>>,
    val truncated: Set<PersonKey>,
    val bandsCalibrated: Boolean = false
)

/**
 * Ranks unknown clusters against named people. Exact brute force, two passes:
 *
 * 1. Centroid gate: every cluster centroid against every person centroid.
 *    Pairs below [FaceQueryBands.LOOKALIKE_FLOOR] are dropped here, so the
 *    expensive pass only sees plausible pairs.
 * 2. Top-k: the cluster centroid against up to [MAX_EXEMPLARS] of the
 *    person's confirmed faces (an even stride over face-uid order, so large
 *    people stay bounded and the choice is deterministic).
 *
 * A pair is suggested only at [score] >= [FaceQueryBands.PROBABLE].
 *
 * Human facts always win, and none is ever re-asked:
 * - a cluster with any member rejected for a person is never suggested for
 *   that person;
 * - a cluster with any member cannot-linked to one of the person's confirmed
 *   faces is never suggested for that person.
 *
 * The bands are the uncalibrated [FaceQueryBands] (OpenCV anchor and the
 * conservative clustering policy); see its calibration note.
 */
object PersonMatchSuggester {
    const val MAX_PER_PERSON = 50
    const val TOP_K = 5
    const val MAX_EXEMPLARS = 64

    fun suggest(inputs: PersonMatchInputs, perPersonLimit: Int = MAX_PER_PERSON): PersonMatchSuggestions {
        require(perPersonLimit in 1..MAX_PER_PERSON) { "Per-person limit must be 1..$MAX_PER_PERSON" }
        if (inputs.confirmed.isEmpty() || inputs.clusters.isEmpty())
            return PersonMatchSuggestions(inputs.generation, emptyMap(), emptySet())

        val people = inputs.confirmed.keys.toList()
        val dim = (inputs.confirmed.values.first().first()).dimension
        val scratch = FloatArray(dim)

        // People: centroid plus packed exemplars.
        val personCentroids = Array(people.size) { p ->
            centroid(inputs.confirmed.getValue(people[p]), dim, scratch)
        }
        val exemplars = Array(people.size) { p -> packExemplars(inputs.confirmed.getValue(people[p]), dim, scratch) }
        val personIndex = HashMap<PersonKey, Int>().also { m -> people.forEachIndexed { i, k -> m[k] = i } }

        // Facts as lookups: face -> people it must never be suggested for.
        val ownerOf = HashMap<String, Int>()
        for ((person, faces) in inputs.confirmed) {
            val i = personIndex.getValue(person)
            for (f in faces) ownerOf[f.observation.localHex] = i
        }
        val blockedByFace = HashMap<String, MutableSet<Int>>()
        for (pair in inputs.reviews.cannotLink) {
            val a = pair.first.localHex
            val b = pair.second.localHex
            ownerOf[b]?.let { blockedByFace.getOrPut(a) { HashSet() } += it }
            ownerOf[a]?.let { blockedByFace.getOrPut(b) { HashSet() } += it }
        }
        for (r in inputs.reviews.rejectedPeople) {
            val i = personIndex[r.person] ?: continue
            blockedByFace.getOrPut(r.observation.localHex) { HashSet() } += i
        }

        val candidates = Array(people.size) { ArrayList<PersonMatchSuggestion>() }
        val scores = FloatArray(people.size)
        val centroidSims = FloatArray(people.size)
        val topKs = FloatArray(people.size)
        val passed = BooleanArray(people.size)
        val top = FloatArray(TOP_K)

        for ((cluster, members) in inputs.clusters) {
            if (members.isEmpty()) continue
            val c = centroid(members, dim, scratch)
            var blocked: Set<Int>? = null
            for (m in members) {
                val b = blockedByFace[m.observation.localHex] ?: continue
                blocked = if (blocked == null) b else blocked + b
            }
            var best = -2f
            for (p in people.indices) {
                passed[p] = false
                if (blocked != null && p in blocked) continue
                val cs = dot(c, personCentroids[p])
                if (cs < FaceQueryBands.LOOKALIKE_FLOOR) continue
                val tk = topKMean(c, exemplars[p], dim, top)
                val score = (cs + tk) / 2f
                if (score < FaceQueryBands.PROBABLE) continue
                passed[p] = true; scores[p] = score; centroidSims[p] = cs; topKs[p] = tk
                if (score > best) best = score
            }
            if (best < -1f) continue
            for (p in people.indices) {
                if (!passed[p]) continue
                var ambiguous = false
                for (q in people.indices) if (q != p && passed[q] &&
                    scores[q] >= scores[p] - FaceQueryBands.PERSON_MARGIN) { ambiguous = true; break }
                candidates[p] += PersonMatchSuggestion(people[p], cluster, members.size, scores[p],
                    centroidSims[p], topKs[p],
                    if (scores[p] >= FaceQueryBands.SAME_PERSON) PersonMatchTier.SAME_PERSON
                    else PersonMatchTier.PROBABLE, ambiguous)
            }
        }

        val order = compareBy<PersonMatchSuggestion> { it.tier.ordinal }
            .thenByDescending { it.score }
            .thenByDescending { it.faceCount }
            .thenBy { it.cluster.anchor.localHex }
        val out = LinkedHashMap<PersonKey, List<PersonMatchSuggestion>>()
        val truncated = LinkedHashSet<PersonKey>()
        for (p in people.indices) {
            val list = candidates[p]
            if (list.isEmpty()) continue
            list.sortWith(order)
            if (list.size > perPersonLimit) truncated += people[p]
            out[people[p]] = list.take(perPersonLimit)
        }
        return PersonMatchSuggestions(inputs.generation, out, truncated)
    }

    /** Re-normalised mean of the decoded unit vectors. */
    private fun centroid(faces: List<FaceEmbedding>, dim: Int, scratch: FloatArray): FloatArray {
        val sum = FloatArray(dim)
        for (f in faces) {
            require(f.dimension == dim) { "Mixed embedding dimensions" }
            f.unitInto(scratch)
            for (k in 0 until dim) sum[k] += scratch[k]
        }
        return normalize(sum)
    }

    /** Up to [MAX_EXEMPLARS] unit vectors, an even stride over [faces], packed row-major. */
    private fun packExemplars(faces: List<FaceEmbedding>, dim: Int, scratch: FloatArray): FloatArray {
        val n = minOf(faces.size, MAX_EXEMPLARS)
        val out = FloatArray(n * dim)
        for (i in 0 until n) {
            val f = faces[(i.toLong() * faces.size / n).toInt()]
            require(f.dimension == dim) { "Mixed embedding dimensions" }
            f.unitInto(scratch)
            val unit = normalize(scratch.copyOf())
            unit.copyInto(out, i * dim)
        }
        return out
    }

    /** Mean of the min(TOP_K, n) best cosines of [c] to the packed rows. */
    private fun topKMean(c: FloatArray, rows: FloatArray, dim: Int, top: FloatArray): Float {
        val n = rows.size / dim
        val k = minOf(TOP_K, n)
        var filled = 0
        for (r in 0 until n) {
            val base = r * dim
            var d = 0f
            for (j in 0 until dim) d += c[j] * rows[base + j]
            // Keep top[0 until filled] sorted descending.
            if (filled < k) {
                var i = filled++
                while (i > 0 && top[i - 1] < d) { top[i] = top[i - 1]; i-- }
                top[i] = d
            } else if (d > top[k - 1]) {
                var i = k - 1
                while (i > 0 && top[i - 1] < d) { top[i] = top[i - 1]; i-- }
                top[i] = d
            }
        }
        var s = 0f
        for (i in 0 until k) s += top[i]
        return s / k
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (k in a.indices) d += a[k] * b[k]
        return d
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sq = 0.0
        for (x in v) sq += x.toDouble() * x
        val inv = if (sq > 0.0) (1.0 / sqrt(sq)).toFloat() else 0f
        for (k in v.indices) v[k] *= inv
        return v
    }
}
