package nl.atlas.gallery.featureidentity.curate

import kotlin.math.abs

/** Pure input record: callers adapt local People/index rows to this shape. */
data class ReferenceCandidate(
    val id: String,
    val captureTimeMillis: Long?,
    val similarityToProfileCentre: Float,
    val landmarks: List<Pair<Float, Float>>,
    val quality: Float? = null,
    val belowSizeFloor: Boolean? = null,
    /** Mean local face luminance, normalised to 0..1 when available. */
    val lighting: Float? = null
) {
    init {
        require(id.isNotBlank())
        require(similarityToProfileCentre.isFinite() && similarityToProfileCentre in -1f..1f)
        require(quality == null || quality.isFinite() && quality in 0f..1f)
        require(lighting == null || lighting.isFinite() && lighting in 0f..1f)
    }
}

data class ReferenceCurationOptions(
    val limit: Int,
    val similarityFloor: Float = 0.45f,
    val capturedFromMillis: Long? = null,
    val capturedUntilMillis: Long? = null,
    val recencyHalfLifeMillis: Long = 365L * 24 * 60 * 60 * 1000,
    /** Fixed by the caller, never read from a clock, preserving reproducibility. */
    val referenceTimeMillis: Long? = null
) {
    init {
        require(limit >= 0)
        require(similarityFloor.isFinite() && similarityFloor in -1f..1f)
        require(capturedFromMillis == null || capturedUntilMillis == null || capturedFromMillis <= capturedUntilMillis)
        require(recencyHalfLifeMillis > 0)
    }
}

enum class ReferenceRejection { BELOW_SIMILARITY_FLOOR, OUTSIDE_DATE_WINDOW, INVALID_LANDMARKS, SIZE_FLOOR }
data class RejectedReference(val id: String, val reason: ReferenceRejection)
data class ReferenceCuration(val selected: List<ReferenceCandidate>, val outliers: List<RejectedReference>)

/** Offline selector. It has no store, Android, network, UI or clock dependency and performs no writes. */
object ReferenceCurator {
    fun curate(candidates: List<ReferenceCandidate>, options: ReferenceCurationOptions): ReferenceCuration {
        val rejected = ArrayList<RejectedReference>()
        val eligible = candidates.mapNotNull { candidate ->
            val reason = rejection(candidate, options)
            if (reason != null) {
                rejected += RejectedReference(candidate.id, reason)
                null
            } else candidate to FacePoseEstimate.fromYuNet(candidate.landmarks)!!
        }
        val remaining = eligible.toMutableList()
        val selected = ArrayList<Pair<ReferenceCandidate, FacePoseEstimate>>()
        while (selected.size < options.limit && remaining.isNotEmpty()) {
            val ranked = compareBy<Pair<ReferenceCandidate, FacePoseEstimate>>(
                { score(it, selected, options) },
                { it.first.similarityToProfileCentre },
                { it.first.quality ?: 0.5f },
                { it.first.captureTimeMillis ?: Long.MIN_VALUE }
            ).thenComparator { a, b ->
                // maxWith chooses the lexically smallest id, independent of input order.
                -a.first.id.compareTo(b.first.id)
            }
            val best = remaining.maxWithOrNull(ranked)!!
            selected += best
            remaining.remove(best)
        }
        return ReferenceCuration(selected.map { it.first }, rejected.sortedWith(compareBy({ it.id }, { it.reason.ordinal })))
    }

    private fun rejection(c: ReferenceCandidate, o: ReferenceCurationOptions): ReferenceRejection? = when {
        c.similarityToProfileCentre < o.similarityFloor -> ReferenceRejection.BELOW_SIMILARITY_FLOOR
        c.captureTimeMillis != null && o.capturedFromMillis != null && c.captureTimeMillis < o.capturedFromMillis -> ReferenceRejection.OUTSIDE_DATE_WINDOW
        c.captureTimeMillis != null && o.capturedUntilMillis != null && c.captureTimeMillis > o.capturedUntilMillis -> ReferenceRejection.OUTSIDE_DATE_WINDOW
        FacePoseEstimate.fromYuNet(c.landmarks) == null -> ReferenceRejection.INVALID_LANDMARKS
        c.belowSizeFloor == true -> ReferenceRejection.SIZE_FLOOR
        else -> null
    }

    private fun score(c: Pair<ReferenceCandidate, FacePoseEstimate>, chosen: List<Pair<ReferenceCandidate, FacePoseEstimate>>, o: ReferenceCurationOptions): Float {
        val candidate = c.first
        val pose = c.second
        val quality = candidate.quality ?: 0.5f
        val recency = if (candidate.captureTimeMillis == null || o.referenceTimeMillis == null) 0.5f else {
            val age = (o.referenceTimeMillis - candidate.captureTimeMillis).coerceAtLeast(0)
            (1.0 / (1.0 + age.toDouble() / o.recencyHalfLifeMillis)).toFloat()
        }
        val diversity = if (chosen.isEmpty()) 1f else chosen.minOf { old ->
            var distance = abs(pose.yaw - old.second.yaw) * 0.45f
            if (pose.expressionBand != old.second.expressionBand) distance += 0.25f
            val a = candidate.lighting
            val b = old.first.lighting
            if (a != null && b != null) distance += abs(a - b) * 0.30f
            distance.coerceIn(0f, 1f)
        }
        return candidate.similarityToProfileCentre * 0.40f + quality * 0.30f + diversity * 0.20f + recency * 0.10f
    }
}
