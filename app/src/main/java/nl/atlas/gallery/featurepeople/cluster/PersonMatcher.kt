package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId

/**
 * #580: the named person an unnamed face is shown under without a question,
 * and the crowd-corrected mean cosine to that person's confirmed faces.
 * Engine-made and never a review fact: a human "not this person" ends it.
 */
data class PersonMatch(val person: PersonKey, val mean: Float)

/**
 * #580: which unnamed faces are, by the measured rule, a named person
 * ([ClusterPolicy.personMatchFloor]; the rule and its numbers are described
 * at nl.atlas.gallery.featurepeople.identity.NamedPersonMatches).
 *
 * Only confirmed faces are examples, so the result is a pure function of the
 * faces and the review facts: [all] computes it from nothing, and [extend]
 * gives the same result for a state that only gained unnamed faces, by
 * scoring just those.
 */
internal object PersonMatcher {
    const val MIN_EXAMPLES = 3

    private class People(
        val keys: List<PersonKey>,
        val indexOf: Map<PersonKey, Int>,
        val sums: FloatArray,
        val crowd: FloatArray,
        val counts: IntArray,
        val frames: List<Set<Long>>
    )

    private class Tentative(val face: NativeFaceObservationId, val person: PersonKey,
                            val mean: Float, val frame: Long)

    private fun people(
        faces: Map<NativeFaceObservationId, FaceEmbedding>, reviews: ClusterReviewFacts, dim: Int
    ): People? {
        val membersOf = HashMap<PersonKey, ArrayList<NativeFaceObservationId>>()
        for ((face, person) in reviews.confirmed) {
            if (face in faces) membersOf.getOrPut(person) { ArrayList() }.add(face)
        }
        val keys = membersOf.filterValues { it.size >= MIN_EXAMPLES }.keys.sortedBy { it.value }
        if (keys.isEmpty()) return null
        val p = keys.size
        val indexOf = HashMap<PersonKey, Int>(p * 2)
        val sums = FloatArray(p * dim)
        val crowd = FloatArray(p)
        val counts = IntArray(p)
        val frames = ArrayList<Set<Long>>(p)
        val scratch = FloatArray(dim)
        for ((k, person) in keys.withIndex()) {
            indexOf[person] = k
            val seen = HashSet<Long>()
            // A fixed order: float sums must not depend on the map's order.
            val members = membersOf.getValue(person).sortedBy { it.localHex }
            val base = k * dim
            for (m in members) {
                val face = faces.getValue(m)
                face.unitInto(scratch)
                for (d in 0 until dim) sums[base + d] += scratch[d]
                crowd[k] += face.crowd
                if (face.frame != 0L) seen += face.frame
            }
            crowd[k] /= members.size
            counts[k] = members.size
            frames += seen
        }
        return People(keys, indexOf, sums, crowd, counts, frames)
    }

    /** Per face: the one person it fits, before the one-face-per-picture rule. */
    private fun score(
        people: People, faces: Map<NativeFaceObservationId, FaceEmbedding>,
        candidates: Iterable<NativeFaceObservationId>, reviews: ClusterReviewFacts,
        dim: Int, w: Float, floor: Float
    ): List<Tentative> {
        val barred = HashMap<NativeFaceObservationId, HashSet<Int>>()
        for (r in reviews.rejectedPeople) {
            val k = people.indexOf[r.person] ?: continue
            barred.getOrPut(r.observation) { HashSet() }.add(k)
        }
        for (pair in reviews.cannotLink) {
            reviews.confirmed[pair.first]?.let(people.indexOf::get)?.let {
                barred.getOrPut(pair.second) { HashSet() }.add(it)
            }
            reviews.confirmed[pair.second]?.let(people.indexOf::get)?.let {
                barred.getOrPut(pair.first) { HashSet() }.add(it)
            }
        }
        val p = people.keys.size
        val unit = FloatArray(dim)
        val out = ArrayList<Tentative>()
        for (id in candidates) {
            if (id in reviews.confirmed) continue
            val face = faces[id] ?: continue
            face.unitInto(unit)
            var best = -1
            var bestMean = Float.NEGATIVE_INFINITY
            var reaching = 0
            for (k in 0 until p) {
                var dot = 0f
                val base = k * dim
                for (d in 0 until dim) dot += unit[d] * people.sums[base + d]
                val mean = dot / people.counts[k] - w * (face.crowd + people.crowd[k]) / 2f
                if (mean < floor) continue
                // A person a human answer bars still counts as "also fits".
                reaching++
                if (mean > bestMean) { bestMean = mean; best = k }
            }
            if (reaching != 1) continue
            if (barred[id]?.contains(best) == true) continue
            if (face.frame != 0L && face.frame in people.frames[best]) continue
            out += Tentative(id, people.keys[best], bestMean, face.frame)
        }
        return out
    }

    /** Of several faces of one picture that fit one person, only the best stands. */
    private fun resolve(tentative: List<Tentative>): Map<NativeFaceObservationId, PersonMatch> {
        val out = HashMap<NativeFaceObservationId, PersonMatch>(tentative.size * 2)
        val perFrame = HashMap<Pair<Long, PersonKey>, Tentative>()
        for (t in tentative) {
            if (t.frame == 0L) { out[t.face] = PersonMatch(t.person, t.mean); continue }
            val key = t.frame to t.person
            val had = perFrame[key]
            if (had == null || t.mean > had.mean ||
                (t.mean == had.mean && t.face.localHex < had.face.localHex)) perFrame[key] = t
        }
        for (t in perFrame.values) out[t.face] = PersonMatch(t.person, t.mean)
        return out
    }

    fun all(
        faces: Map<NativeFaceObservationId, FaceEmbedding>, reviews: ClusterReviewFacts,
        dim: Int, policy: ClusterPolicy, floor: Float = policy.personMatchFloor
    ): Map<NativeFaceObservationId, PersonMatch> {
        if (floor <= 0f || policy.linkage != ClusterLinkage.MEAN_PAIR) return emptyMap()
        val people = people(faces, reviews, dim) ?: return emptyMap()
        return resolve(score(people, faces, faces.keys, reviews, dim, policy.crowdWeight, floor))
    }

    /**
     * [old] is [all] of the same faces without [added], under the same review
     * facts; none of [added] is confirmed and no face of [old] left. A face
     * that lost its picture to a better one earlier can only return when that
     * one leaves, which the caller handles by calling [all].
     */
    fun extend(
        old: Map<NativeFaceObservationId, PersonMatch>,
        faces: Map<NativeFaceObservationId, FaceEmbedding>,
        added: Collection<NativeFaceObservationId>, reviews: ClusterReviewFacts,
        dim: Int, policy: ClusterPolicy
    ): Map<NativeFaceObservationId, PersonMatch> {
        val floor = policy.personMatchFloor
        if (floor <= 0f || policy.linkage != ClusterLinkage.MEAN_PAIR) return emptyMap()
        if (added.isEmpty()) return old
        val people = people(faces, reviews, dim) ?: return emptyMap()
        val fresh = score(people, faces, added, reviews, dim, policy.crowdWeight, floor)
        if (fresh.isEmpty()) return old
        val tentative = ArrayList<Tentative>(old.size + fresh.size)
        for ((face, match) in old) {
            tentative += Tentative(face, match.person, match.mean, faces.getValue(face).frame)
        }
        tentative += fresh
        return resolve(tentative)
    }
}
