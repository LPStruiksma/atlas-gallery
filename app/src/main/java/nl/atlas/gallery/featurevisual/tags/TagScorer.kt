package nl.atlas.gallery.featurevisual.tags

import nl.atlas.gallery.featurevisual.store.VisualVectorCodec
import kotlin.math.exp
import kotlin.math.sqrt

/** A tag that cleared its threshold; [margin] = cosine − threshold, the ranking key. */
data class TagHit(val id: String, val cosine: Float, val margin: Float)

/**
 * How many tags an item keeps. Tags that are off by default (too few licensed
 * labels to measure, or below the precision/recall gate; see
 * `docs/visual-search/TAGS_0.md`) stay off unless a caller opts in.
 */
data class TagPolicy(
    val maxTags: Int = 5,
    val includeOffByDefault: Boolean = false,
    val minMargin: Float = 0f,
) {
    init {
        require(maxTags >= 1) { "maxTags must be positive" }
        require(minMargin.isFinite() && minMargin >= 0f) { "Invalid minMargin" }
    }
}

/** The stored vector belongs to another index model: the tag matrix's thresholds do not apply to it. */
class TagModelMismatchException(expected: String, actual: String) :
    IllegalStateException("Tag matrix is for $expected, item is $actual")

/**
 * Pure scorer: one dot product per tag against a vector the visual index
 * already stored. Thread-safe and allocation-light (one score array per call).
 */
class TagScorer(val matrix: TagMatrix) {
    private val thresholds = FloatArray(matrix.tags.size) { matrix.tags[it].threshold }
    private val defaultOn = BooleanArray(matrix.tags.size) { matrix.tags[it].defaultOn }

    fun accepts(modelVersion: String): Boolean = modelVersion == matrix.imageModelVersion

    /** Raw cosines for every tag, in matrix order. [vector] need not be normalised. */
    fun cosines(vector: FloatArray, modelVersion: String): FloatArray {
        fence(modelVersion)
        val d = matrix.dimension
        require(vector.size == d) { "Wrong visual dimension" }
        var squared = 0.0
        for (v in vector) squared += v.toDouble() * v
        require(squared.isFinite() && squared > 0.0) { "Invalid visual vector" }
        val inverse = (1.0 / sqrt(squared)).toFloat()
        val rows = matrix.vectors
        return FloatArray(thresholds.size) { t ->
            var dot = 0f
            val base = t * d
            for (i in 0 until d) dot += rows[base + i] * vector[i]
            dot * inverse
        }
    }

    /** Top tags for one item, best margin first. */
    fun score(vector: FloatArray, modelVersion: String, policy: TagPolicy = TagPolicy()): List<TagHit> {
        val cos = cosines(vector, modelVersion)
        val hits = ArrayList<TagHit>()
        for (t in cos.indices) {
            if (!defaultOn[t] && !policy.includeOffByDefault) continue
            val margin = cos[t] - thresholds[t]
            if (margin >= policy.minMargin) hits += TagHit(matrix.tags[t].id, cos[t], margin)
        }
        hits.sortWith(compareByDescending<TagHit> { it.margin }.thenBy { it.id })
        return if (hits.size > policy.maxTags) hits.subList(0, policy.maxTags).toList() else hits
    }

    /** Same, straight from the store's int8 row (`VisualIndexStore` / `VisualVectorCodec`). */
    fun scoreStored(
        bytes: ByteArray, scale: Float, modelVersion: String, policy: TagPolicy = TagPolicy(),
    ): List<TagHit> = score(VisualVectorCodec.decode(bytes, scale, matrix.dimension), modelVersion, policy)

    private fun fence(modelVersion: String) {
        if (!accepts(modelVersion)) throw TagModelMismatchException(matrix.imageModelVersion, modelVersion)
    }

    companion object {
        /** SigLIP 2 B/16 224 logit parameters (NLS_1_TEXT_ASSET.md), for showing a probability. */
        const val LOGIT_T = 112.669f
        const val LOGIT_B = -16.771725f

        fun probability(cosine: Float): Float = (1.0 / (1.0 + exp(-(LOGIT_T * cosine + LOGIT_B).toDouble()))).toFloat()
    }
}

/**
 * Work for tagging a whole library once: items × tags × dimension
 * multiply-adds and the matrix's resident size. See TAGS_0.md for a measured
 * host rate; the device rate is TAGS-1's to measure.
 */
data class TagCostEstimate(val items: Long, val tags: Int, val dimension: Int) {
    val multiplyAdds: Long get() = items * tags * dimension
    val matrixBytesFloat: Long get() = tags.toLong() * dimension * 4
    val matrixBytesInt8: Long get() = tags.toLong() * (dimension + 9)

    /** Seconds at a sustained rate, in multiply-adds per second. */
    fun secondsAt(multiplyAddsPerSecond: Double): Double {
        require(multiplyAddsPerSecond > 0.0)
        return multiplyAdds / multiplyAddsPerSecond
    }
}
