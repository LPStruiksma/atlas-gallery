package nl.atlas.gallery.featurevisual.nls.retrieval

import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * Pure, streaming cosine top-K over the *existing* SigLIP 2 768-dimensional index.
 * The caller must obtain vectors from a single permission-approved, current epoch
 * and matching model version. This class cannot authorize media or revive stale
 * vectors: [stillAuthorized] must check grant, epoch AND model before, during,
 * and after iteration. A failed fence discards the entire partial result.
 *
 * [candidates] must be a lazy, caller-owned iterator over approved rows; do not
 * materialize the library before calling. The selector retains at most [limit]
 * candidates (plus the current row), never embeddings or a library-sized list.
 * The caller owns the vectors and must not mutate them during the operation.
 */
object SceneCandidateRanking {
    const val DIMENSION = 768
    const val MAX_TOP_K = 100

    data class Candidate(
        val mediaKey: String,
        val revision: String,
        val frameMs: Long?,
        val vector: FloatArray
    )

    data class Hit(
        val mediaKey: String,
        val revision: String,
        val frameMs: Long?,
        val cosine: Float
    )

    /** A cancellation or revoked/stale snapshot yields no partial hits. */
    sealed interface Result {
        data class Ranked(val hits: List<Hit>, val scanned: Int, val skippedInvalid: Int) : Result
        data object Aborted : Result
    }

    private val BEST_FIRST: Comparator<Hit> =
        compareByDescending<Hit> { it.cosine }
            .thenBy { it.mediaKey }
            .thenBy { it.frameMs ?: -1L }
            .thenBy { it.revision }
    private val WORST_FIRST: Comparator<Hit> = BEST_FIRST.reversed()

    /**
     * [query] is a normalized text embedding; malformed text vectors fail fast.
     * Candidate vectors can contain invalid/sparse rows, which are skipped.
     * No similarity floor or semantic suppression is applied.
     */
    fun rank(
        query: FloatArray,
        candidates: Iterator<Candidate>,
        limit: Int = MAX_TOP_K,
        stillAuthorized: () -> Boolean,
        isCancelled: () -> Boolean = { false }
    ): Result {
        require(limit in 1..MAX_TOP_K) { "top-K must be 1..$MAX_TOP_K" }
        require(query.size == DIMENSION && query.all { it.isFinite() }) {
            "Query must contain $DIMENSION finite values"
        }
        val queryNorm = squaredNorm(query)
        require(queryNorm.isFinite() && queryNorm > 0.0 &&
            kotlin.math.abs(queryNorm - 1.0) <= 0.01) {
            "Text query must be L2-normalized"
        }
        fun stopped(): Boolean = isCancelled() || !stillAuthorized() ||
            Thread.currentThread().isInterrupted

        if (stopped()) return Result.Aborted
        val heap = PriorityQueue<Hit>(limit + 1, WORST_FIRST)
        var scanned = 0
        var invalid = 0
        while (true) {
            if (stopped()) return Result.Aborted
            if (!candidates.hasNext()) break
            if (stopped()) return Result.Aborted
            val candidate = candidates.next()
            scanned++
            val vector = candidate.vector
            if (candidate.mediaKey.isBlank() || candidate.revision.isBlank() ||
                (candidate.frameMs != null && candidate.frameMs < 0L) ||
                vector.size != DIMENSION) {
                invalid++
                continue
            }
            var dot = 0.0
            var squared = 0.0
            var finite = true
            for (i in 0 until DIMENSION) {
                if ((i and 127) == 0 && stopped()) return Result.Aborted
                val value = vector[i]
                if (!value.isFinite()) { finite = false; break }
                dot += query[i].toDouble() * value
                squared += value.toDouble() * value
            }
            if (!finite || !squared.isFinite() || squared <= 0.0) {
                invalid++
                continue
            }
            // Double-precision accumulation prevents overflow and reduces
            // cancellation error; clamp minor rounding excursions to [-1, 1].
            val score = (dot / sqrt(queryNorm * squared)).coerceIn(-1.0, 1.0)
            if (!score.isFinite()) { invalid++; continue }
            val hit = Hit(candidate.mediaKey, candidate.revision, candidate.frameMs, score.toFloat())
            if (heap.size < limit) heap.add(hit)
            else if (BEST_FIRST.compare(hit, heap.peek()) < 0) {
                heap.poll()
                heap.add(hit)
            }
        }
        if (stopped()) return Result.Aborted
        return Result.Ranked(heap.sortedWith(BEST_FIRST), scanned, invalid)
    }

    private fun squaredNorm(vector: FloatArray): Double {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value
        return sum
    }
}
