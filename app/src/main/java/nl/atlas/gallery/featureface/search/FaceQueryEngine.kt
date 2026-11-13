package nl.atlas.gallery.featureface.search

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.PersonKey

/**
 * VS-F1 (#468): the face query engine behind "search by face".
 *
 * Exact brute force over the int8 [FaceSearchCorpus] (no ANN library), then
 * identity reasoning over the human review facts:
 *
 * 1. Direct score: every face's best cosine to any query vector (fresh
 *    embeddings plus the stored vectors of picked seed faces), banded by
 *    [FaceQueryBands].
 * 2. Person resolution: the query is a confirmed person P when its seed faces
 *    are confirmed as exactly P, or, without a confirmed seed, when P's best
 *    face is a SAME_PERSON match and no other person's best face is within
 *    [FaceQueryBands.PERSON_MARGIN]. A seed face rejected for P blocks P.
 * 3. Expansion: P's other confirmed faces become SAME_PERSON (a human said
 *    so, across ages, light and angles the vectors may miss). Each published
 *    unknown cluster that holds a seed or SAME_PERSON face lifts its other
 *    members to at least PROBABLE; unknown people need no names.
 * 4. Vetoes (human facts beat similarity): a face with a cannot-link fact to
 *    an anchor (seed, P's confirmed faces, or without P the best direct
 *    match), a face rejected for P, and a face confirmed as another person
 *    are excluded as look-alikes. A cluster holding a face rejected for P
 *    gives no cluster lift.
 *
 * Results group faces by media. A video's faces carry their keyframe time
 * (VS-V1b), so a hit can open the matching moment. Photos and videos are
 * limited separately (VS-S2b): see [FaceQuery.videoMediaLimit]. Pure CPU, no
 * I/O, no writes; deterministic.
 */
object FaceQueryEngine {

    fun search(corpus: FaceSearchCorpus?, query: FaceQuery): FaceQueryResult {
        if (corpus == null) return FaceQueryResult.empty(FaceQueryStatus.UNAVAILABLE)
        if (query.modelVersion != corpus.modelVersion)
            return FaceQueryResult.empty(FaceQueryStatus.MODEL_MISMATCH)
        val n = corpus.faceCount
        // Seeds that left the index (rescan, revoke) are simply gone, not guessed.
        val seedRows = query.seedFaces.mapNotNull { corpus.rowByFace[it.localHex] }.sorted()
        val queries = query.embeddings + seedRows.map { corpus.unitVector(it) }
        if (n == 0 || queries.isEmpty()) return FaceQueryResult.empty(FaceQueryStatus.OK, n)

        val score = FloatArray(n) { -1f }
        for (q in queries) for (i in 0 until n) {
            val s = corpus.cosine(i, q)
            if (s > score[i]) score[i] = s
        }

        val person = resolvePerson(corpus, seedRows, score)
        val tier = arrayOfNulls<FaceMatchTier>(n)
        val reason = arrayOfNulls<FaceMatchReason>(n)
        for (i in 0 until n) {
            val s = score[i]
            when {
                s >= FaceQueryBands.SAME_PERSON -> { tier[i] = FaceMatchTier.SAME_PERSON; reason[i] = FaceMatchReason.DIRECT }
                s >= FaceQueryBands.PROBABLE -> { tier[i] = FaceMatchTier.PROBABLE; reason[i] = FaceMatchReason.DIRECT }
                s >= FaceQueryBands.LOOKALIKE_FLOOR -> { tier[i] = FaceMatchTier.LOOKALIKE_EXCLUDED; reason[i] = FaceMatchReason.DIRECT }
            }
        }
        val personRows = person?.let { corpus.confirmedFaces[it] }.orEmpty()
        for (i in personRows) { tier[i] = FaceMatchTier.SAME_PERSON; reason[i] = FaceMatchReason.CONFIRMED_PERSON }
        for (i in seedRows) { tier[i] = FaceMatchTier.SAME_PERSON; reason[i] = FaceMatchReason.SEED }

        // Veto facts first: a vetoed face is never a match and never lifts its cluster.
        val anchors = HashSet<String>()
        seedRows.forEach { anchors += corpus.faceIds[it] }
        personRows.forEach { anchors += corpus.faceIds[it] }
        if (person == null) bestRow(score)?.takeIf { score[it] >= FaceQueryBands.SAME_PERSON }
            ?.let { anchors += corpus.faceIds[it] }
        val seedSet = seedRows.toHashSet()
        fun vetoed(i: Int): Boolean {
            if (i in seedSet) return false
            val id = corpus.faceIds[i]
            val confirmedAs = corpus.reviews.confirmed[NativeFaceObservationId(id)]
            return (person != null && confirmedAs != null && confirmedAs != person) ||
                (person != null && (id to person) in corpus.rejected) ||
                corpus.cannotLink[id].orEmpty().any { it != id && it in anchors }
        }

        // Cluster lift, only from strong matches that survive the vetoes.
        if (corpus.clustersPublished) {
            val lifting = HashSet<String>()
            for (i in 0 until n) if (tier[i] == FaceMatchTier.SAME_PERSON && !vetoed(i))
                corpus.anchors[i]?.let { lifting += it }
            for (anchor in lifting.sorted()) {
                val members = corpus.clusterRows[anchor].orEmpty()
                if (person != null && members.any { (corpus.faceIds[it] to person) in corpus.rejected }) continue
                for (m in members) if (tier[m] == null || tier[m] == FaceMatchTier.LOOKALIKE_EXCLUDED) {
                    tier[m] = FaceMatchTier.PROBABLE; reason[m] = FaceMatchReason.CLUSTER
                }
            }
        }

        // Vetoes: human facts exclude a face whatever its similarity or lift.
        for (i in 0 until n) {
            val t = tier[i] ?: continue
            if (t != FaceMatchTier.LOOKALIKE_EXCLUDED && vetoed(i)) tier[i] = FaceMatchTier.LOOKALIKE_EXCLUDED
        }

        return collect(corpus, score, tier, reason, person, query.mediaLimit, query.videoMediaLimit)
    }

    private fun bestRow(score: FloatArray): Int? {
        var best = -1
        for (i in score.indices) if (best < 0 || score[i] > score[best]) best = i
        return best.takeIf { it >= 0 }
    }

    /**
     * A person the seeds contradict is never the query: a seed rejected for
     * that person, or a seed with a cannot-link fact to any of that person's
     * confirmed faces (for example a cluster split, then the other half named).
     */
    private fun excludedPeople(corpus: FaceSearchCorpus, seedRows: List<Int>): Set<PersonKey> {
        if (seedRows.isEmpty()) return emptySet()
        val seedIds = seedRows.mapTo(HashSet()) { corpus.faceIds[it] }
        val out = HashSet<PersonKey>()
        for ((face, person) in corpus.rejected) if (face in seedIds) out += person
        for (seed in seedIds) for (other in corpus.cannotLink[seed].orEmpty())
            corpus.reviews.confirmed[NativeFaceObservationId(other)]?.let { out += it }
        return out
    }

    private fun resolvePerson(corpus: FaceSearchCorpus, seedRows: List<Int>, score: FloatArray): PersonKey? {
        val excluded = excludedPeople(corpus, seedRows)
        val seedPeople = seedRows.mapNotNullTo(HashSet()) {
            corpus.reviews.confirmed[NativeFaceObservationId(corpus.faceIds[it])]
        }
        val candidate: PersonKey = when {
            seedPeople.size > 1 -> return null // self-contradictory query: no expansion
            seedPeople.size == 1 -> seedPeople.single()
            else -> {
                val ranked = corpus.confirmedFaces.entries
                    .filter { it.key !in excluded }
                    .map { (p, rows) -> p to rows.maxOf { score[it] } }
                    .sortedWith(compareBy({ -it.second }, { it.first.value }))
                val top = ranked.firstOrNull()?.takeIf { it.second >= FaceQueryBands.SAME_PERSON }
                    ?: return null
                val second = ranked.getOrNull(1)?.second ?: -1f
                top.first.takeIf { top.second - second >= FaceQueryBands.PERSON_MARGIN } ?: return null
            }
        }
        return candidate.takeIf { it !in excluded }
    }

    private val tierOrder = compareBy<FaceMatch>({ it.tier.ordinal }, { -it.similarity }, { it.face.localHex })

    private fun collect(
        corpus: FaceSearchCorpus, score: FloatArray, tier: Array<FaceMatchTier?>,
        reason: Array<FaceMatchReason?>, person: PersonKey?, limit: Int, videoLimit: Int
    ): FaceQueryResult {
        val byMedia = HashMap<Int, MutableList<FaceMatch>>()
        val lookalikes = ArrayList<FaceMatch>()
        var lookalikeCount = 0
        for (i in tier.indices) {
            val t = tier[i] ?: continue
            val match = FaceMatch(NativeFaceObservationId(corpus.faceIds[i]), corpus.mediaOf(i),
                corpus.boxes[i], score[i], t, reason[i]!!, corpus.frameMsOf(i))
            if (t == FaceMatchTier.LOOKALIKE_EXCLUDED) { lookalikeCount++; lookalikes += match }
            else byMedia.getOrPut(corpus.mediaIndex[i]) { ArrayList() }.add(match)
        }
        val hits = byMedia.values.map { faces ->
            faces.sortWith(tierOrder)
            FaceMediaHit(faces[0].media, faces[0].tier, faces.maxOf { it.similarity }, faces.toList())
        }.sortedWith(compareBy<FaceMediaHit>({ it.tier.ordinal }, { -it.bestSimilarity }, { it.media.key }))
        lookalikes.sortWith(compareBy<FaceMatch>({ -it.similarity }, { it.face.localHex }))
        // Separate budgets (#478 follow-up): a video is one hit at its best
        // moment, and videos never take the photos' places, or the reverse.
        var photos = 0
        var videos = 0
        val kept = ArrayList<FaceMediaHit>(minOf(hits.size, limit + videoLimit))
        for (hit in hits) {
            if (hit.isVideo) { if (videos++ < videoLimit) kept += hit }
            else if (photos++ < limit) kept += hit
        }
        return FaceQueryResult(FaceQueryStatus.OK, kept,
            lookalikes.take(FaceQueryResult.MAX_LOOKALIKES), lookalikeCount, person,
            corpus.faceCount, photos > limit, videos > videoLimit)
    }
}
