package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Wave-2 unit D2-3 (#402): conservative, on-device face clustering.
 *
 * Everything in this package is pure Kotlin with no Android, storage, network
 * or model dependency. It never sees pixels, file paths or V4 face IDs: only a
 * native observation ID plus the embedding vector D2-2 computed on the device.
 * The caller persists results; nothing here leaves the process.
 */

/**
 * One face embedding from a single model version. The vector is copied and
 * L2-normalised on construction, so later caller mutation cannot change it.
 *
 * #508: the unit vector is kept as int8 with one scale per vector (about
 * 150 bytes at 128-d instead of 528), so a 400k-photo library's faces fit in
 * a phone heap. The rounding error is at most half a step of max|x|/127 per
 * component (cosine error well under 0.01, far below the thresholds' spacing
 * and the model's own int8 noise). Every reader, the reference clusterer in
 * the tests included, sees the same decoded values, so clustering stays
 * deterministic.
 */
class FaceEmbedding(
    val observation: NativeFaceObservationId,
    val modelVersion: String,
    vector: FloatArray,
    /**
     * #580: how much more crowded this face's neighbourhood is than a typical
     * face's, 0 for a typical one (see FaceMetric). Only [ClusterLinkage.MEAN_PAIR]
     * reads it.
     */
    val crowd: Float = 0f,
    /**
     * #580: opaque key of the photo or video frame the face was found in, 0 if
     * unknown. Two faces with the same non-zero key are two people in one
     * picture; a policy with [ClusterPolicy.sameFrameVeto] never groups them.
     */
    val frame: Long = 0L
) {
    private val quantized: ByteArray
    private val scale: Float

    init {
        require(modelVersion.isNotBlank() && modelVersion.length <= 256 &&
            '\u0000' !in modelVersion) { "Invalid embedding model version" }
        require(vector.size in 1..MAX_DIMENSION) { "Embedding dimension out of range" }
        require(crowd.isFinite() && crowd >= 0f && crowd <= 2f) { "Invalid crowd value" }
        var sumSquares = 0.0
        for (v in vector) {
            require(v.isFinite()) { "Embedding contains a non-finite value" }
            sumSquares += v.toDouble() * v
        }
        val norm = sqrt(sumSquares)
        require(norm > MIN_NORM) { "Embedding has no direction (zero vector)" }
        val unit = FloatArray(vector.size) { (vector[it] / norm).toFloat() }
        var max = 0f
        for (u in unit) max = maxOf(max, abs(u))
        scale = max / 127f
        quantized = ByteArray(unit.size) { (unit[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }
    }

    /** A fresh copy of the (decoded) unit vector. */
    internal val unit: FloatArray get() = FloatArray(quantized.size).also(::unitInto)

    /** Decodes the unit vector into [out] without allocating. */
    internal fun unitInto(out: FloatArray) {
        for (i in quantized.indices) out[i] = quantized[i] * scale
    }

    val dimension: Int get() = quantized.size

    companion object {
        const val MAX_DIMENSION = 1024
        private const val MIN_NORM = 1e-6
    }
}

/** Opaque key of a human-confirmed person; this package never invents one. */
@JvmInline
value class PersonKey(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 256 && '\u0000' !in value) {
            "Invalid person key"
        }
    }
}

/**
 * A cluster is named by its smallest member observation ID. The ID is a pure
 * function of membership, so it is deterministic and needs no stored counter;
 * it changes when membership changes, which invalidates stale UI actions.
 */
@JvmInline
value class FaceClusterId(val anchor: NativeFaceObservationId)

/** Unordered pair of observations a human said are NOT the same person. */
class FacePair private constructor(
    val first: NativeFaceObservationId,
    val second: NativeFaceObservationId
) {
    override fun equals(other: Any?): Boolean =
        other is FacePair && other.first == first && other.second == second
    override fun hashCode(): Int = 31 * first.hashCode() + second.hashCode()
    override fun toString(): String = "FacePair(${first.localHex}, ${second.localHex})"

    companion object {
        fun of(a: NativeFaceObservationId, b: NativeFaceObservationId): FacePair {
            require(a != b) { "A face cannot be split from itself" }
            return if (a.localHex < b.localHex) FacePair(a, b) else FacePair(b, a)
        }
    }
}

/** A human said this observation is NOT [person] (a rejected suggestion). */
data class RejectedPerson(val observation: NativeFaceObservationId, val person: PersonKey)

/**
 * Human review facts, the only authority over identities. Clustering reads
 * them and never writes them:
 * - [confirmed] faces are owned by their person and never enter an unknown
 *   cluster; an unknown face never becomes a confirmed one on its own
 *   (#580: [ClusterPolicy.personMatchFloor] may show it under a person as a
 *   [PersonMatch], which is not a fact and never an example).
 * - [cannotLink] pairs (splits, "not the same person") veto any cluster or
 *   merge that would put both faces together.
 * - [rejectedPeople] suppresses a person suggestion for a cluster that holds
 *   that observation.
 * - [sameUnknown] pairs (#580: a "yes" to "same person?" for two unnamed
 *   groups) keep the clusters of both faces together, whatever their score.
 *   A [cannotLink] fact between any two faces of those clusters wins: "not
 *   the same person" is never overridden, also not by another human answer.
 */
data class ClusterReviewFacts(
    val confirmed: Map<NativeFaceObservationId, PersonKey> = emptyMap(),
    val cannotLink: Set<FacePair> = emptySet(),
    val rejectedPeople: Set<RejectedPerson> = emptySet(),
    val sameUnknown: Set<FacePair> = emptySet()
) {
    init {
        require(confirmed.size <= MAX_FACTS && cannotLink.size <= MAX_FACTS &&
            rejectedPeople.size <= MAX_FACTS && sameUnknown.size <= MAX_FACTS) {
            "Review fact limit exceeded"
        }
    }

    companion object {
        const val MAX_FACTS = 500_000
        val NONE = ClusterReviewFacts()
    }
}

/**
 * Cosine thresholds. All similarities are between L2-normalised vectors
 * (cluster centroids are re-normalised means).
 *
 * - [joinThreshold]: a face joins a cluster only at or above this similarity
 *   to the cluster centroid.
 * - [ambiguityMargin]: if a second cluster is within this margin of the best,
 *   the face is left on its own instead of guessing.
 * - [mergeThreshold]: two unknown clusters are unioned only at or above this
 *   centroid similarity (stricter than joining, to limit chaining).
 * - [suggestThreshold] / [suggestMargin]: when an unknown cluster resembles
 *   exactly one confirmed person, offer that person as a suggestion for human
 *   review. A suggestion is never an assignment.
 * - [attachMinSize] / [attachThreshold] (#579): after each update,
 *   [FaceClusterer.consolidate] moves every face of a cluster smaller than
 *   [attachMinSize] into the most similar cluster of at least that size, if
 *   the face reaches [attachThreshold] against that cluster's centroid. A
 *   centroid of several faces is a de-noised identity, so this cut can sit
 *   below [joinThreshold], which a lone face must reach against another lone
 *   face. [attachMinSize] 0 turns consolidation off.
 * - [linkage] (#580): how a face is scored against a cluster and a cluster
 *   against a cluster; every threshold above is read on that score.
 * - [crowdWeight]: with [ClusterLinkage.MEAN_PAIR], how strongly the two
 *   sides' mean [FaceEmbedding.crowd] is subtracted from the score.
 * - [sameFrameVeto]: never put two faces with the same non-zero
 *   [FaceEmbedding.frame] in one cluster (two people in one picture).
 * - [groupMinSize] / [groupThreshold]: with [ClusterLinkage.MEAN_PAIR], the
 *   cut for a face joining a cluster of at least [groupMinSize] faces, and
 *   for unioning two such clusters, in place of [joinThreshold] and
 *   [mergeThreshold]. 0 turns it off.
 * - [metric]: applied to every stored vector before grouping (see
 *   [embedding]); null groups the raw vectors.
 * - [personMatchFloor] (#580): with [ClusterLinkage.MEAN_PAIR], an unnamed
 *   face whose mean score against a named person's confirmed faces reaches
 *   this is shown under that person without a question
 *   ([FaceClusterState.matched]) and stays out of the unknown clusters.
 *   0 turns it off: then only a human answer adds a face to a person.
 */
data class ClusterPolicy(
    val joinThreshold: Float,
    val ambiguityMargin: Float,
    val mergeThreshold: Float,
    val suggestThreshold: Float,
    val suggestMargin: Float,
    val attachThreshold: Float = 1f,
    val attachMinSize: Int = 0,
    val linkage: ClusterLinkage = ClusterLinkage.CENTROID,
    val crowdWeight: Float = 0f,
    val sameFrameVeto: Boolean = false,
    val groupThreshold: Float = 1f,
    val groupMinSize: Int = 0,
    val metric: FaceMetric? = null,
    val personMatchFloor: Float = 0f
) {
    init {
        val all = listOf(joinThreshold, ambiguityMargin, mergeThreshold,
            suggestThreshold, suggestMargin, attachThreshold)
        require(all.all { it.isFinite() }) { "Policy values must be finite" }
        require(joinThreshold in 0f..1f && mergeThreshold in 0f..1f &&
            suggestThreshold in 0f..1f) { "Thresholds must be in [0, 1]" }
        require(ambiguityMargin in 0f..1f && suggestMargin in 0f..1f) {
            "Margins must be in [0, 1]"
        }
        require(mergeThreshold >= joinThreshold) {
            "Cluster merges must be at least as strict as joins"
        }
        require(attachThreshold in 0f..1f) { "Thresholds must be in [0, 1]" }
        require(crowdWeight.isFinite() && crowdWeight in 0f..4f) { "Crowd weight must be in [0, 4]" }
        require(groupThreshold.isFinite() && groupThreshold in 0f..1f) { "Thresholds must be in [0, 1]" }
        require(groupMinSize == 0 || groupMinSize in 2..MAX_ATTACH_MIN_SIZE) {
            "Group size must be 0 (off) or 2..$MAX_ATTACH_MIN_SIZE"
        }
        require(linkage == ClusterLinkage.MEAN_PAIR ||
            (crowdWeight == 0f && groupMinSize == 0)) {
            "Crowd weight and group threshold need MEAN_PAIR linkage"
        }
        require(personMatchFloor.isFinite() && personMatchFloor in 0f..1f) {
            "Thresholds must be in [0, 1]"
        }
        require(personMatchFloor == 0f || linkage == ClusterLinkage.MEAN_PAIR) {
            "Matching named people needs MEAN_PAIR linkage"
        }
        require(linkage == ClusterLinkage.CENTROID || attachMinSize == 0) {
            "MEAN_PAIR uses groupThreshold, not the centroid attach pass"
        }
        require(attachMinSize == 0 || attachMinSize in 2..MAX_ATTACH_MIN_SIZE) {
            "Attach size must be 0 (off) or 2..$MAX_ATTACH_MIN_SIZE"
        }
    }

    /**
     * Identifies the grouping rules. A store that published membership under
     * another fingerprint regroups its stored faces (no rescan), so a policy
     * change reaches existing libraries. Suggestion values are left out: they
     * do not change membership.
     */
    val fingerprint: String get() =
        "j=$joinThreshold;a=$ambiguityMargin;m=$mergeThreshold;" +
            "t=$attachThreshold;n=$attachMinSize" +
            if (linkage == ClusterLinkage.CENTROID && !sameFrameVeto && metric == null) ""
            else ";l=$linkage;c=$crowdWeight;f=$sameFrameVeto;g=$groupThreshold;k=$groupMinSize" +
                (metric?.let { ";x=${it.version}" } ?: "") +
                (if (personMatchFloor > 0f) ";p=$personMatchFloor" else "")

    /**
     * The embedding this policy groups, from a stored raw vector: through
     * [metric] when the policy has one, with [frame] kept only when
     * [sameFrameVeto] reads it.
     */
    fun embedding(
        observation: NativeFaceObservationId, modelVersion: String, raw: FloatArray, frame: Long = 0L
    ): FaceEmbedding {
        val keptFrame = if (sameFrameVeto) frame else 0L
        val m = metric ?: return FaceEmbedding(observation, modelVersion, raw, frame = keptFrame)
        val face = m.apply(raw)
        return FaceEmbedding(observation, modelVersion, face.vector, face.crowd, keptFrame)
    }

    companion object {
        /**
         * Starting point for SFace (128-d, `face_recognition_sface_2021dec`).
         * OpenCV's published same-identity cosine cut-off for SFace is 0.363;
         * these values sit well above it on purpose (fewer wrong merges, more
         * clusters for a human to merge). They are NOT tuned on labelled real
         * faces yet; the tests only prove behaviour on synthetic vectors.
         */
        val SFACE_CONSERVATIVE = ClusterPolicy(
            joinThreshold = 0.45f,
            ambiguityMargin = 0.05f,
            mergeThreshold = 0.50f,
            suggestThreshold = 0.50f,
            suggestMargin = 0.08f
        )

        /**
         * #579: the policy People runs on. SFACE_CONSERVATIVE split one real
         * person into hundreds of one-face "people" on a phone library: a
         * lone face rarely reaches 0.45 against another lone face of the same
         * person (gallery photos vary in pose, age, light and size), and the
         * ambiguity margin turned every face that resembled two fragments of
         * the same person into yet another fragment.
         *
         * - No ambiguity margin: a face joins its best cluster, a cluster
         *   merges with its best partner. Cannot-link facts still veto both.
         * - Join 0.38 between a face and a centroid: just above OpenCV's
         *   published same-identity cut-off (0.363) for two lone faces.
         * - Merge 0.42 between two centroids: two de-noised identities of one
         *   person score far higher; different people far lower.
         * - Attach 0.32 to a cluster of 3 or more faces: a centroid of three
         *   or more faces sits closer to the identity than any one face, so
         *   a weaker (small, blurred, turned) face of that person still
         *   reaches it while a stranger's face stays near 0.1.
         *
         * Picked on gallery-like synthetic identities (FaceClustererGroupingTest).
         *
         * NOT the default: on real SFace int8 vectors of strangers (Open Images,
         * PR #581 finding by the #580 seat) stranger pairs reach 0.38 in 0.29%
         * of pairs and SFace vectors share a common direction, so attach 0.32
         * snowballed 1,340 of 2,404 strangers into one group. Kept for tests
         * and as a starting point once the phone's nn_cos histogram is in.
         */
        val SFACE_GALLERY = ClusterPolicy(
            joinThreshold = 0.38f,
            ambiguityMargin = 0f,
            mergeThreshold = 0.42f,
            suggestThreshold = 0.50f,
            suggestMargin = 0.08f,
            attachThreshold = 0.32f,
            attachMinSize = 3
        )

        /**
         * #580: measured on real faces (docs/people/PEOPLE_BENCH.md), for
         * vectors that went through [FaceMetric.SFACE]. On 48 real photo
         * libraries pooled into one (14,182 faces), a reference model judged
         * 0.4% of the faces in groups of 3+ to be in the wrong group, against
         * 38% under [SFACE_CONSERVATIVE] on raw vectors, and 47 of the 58
         * recurring people had 80% or more of their faces in one group.
         *
         * - Mean-pair linkage: a face joins the group whose members it
         *   resembles on average, so a mixed group cannot grow by resembling
         *   everyone a little.
         * - 0.45 for two lone faces or small groups; 0.35 against or between
         *   groups of 3+, where the mean over several faces is steadier.
         * - Crowd weight 1: faces in crowded regions (babies above all) need
         *   that much more.
         * - Two faces in one picture are never grouped automatically.
         * - An unnamed face at 0.45 or more against a named person's
         *   confirmed faces (3 or more, and no second person that also fits)
         *   is shown under that person ([FaceClusterState.matched]): the
         *   owner's choice of 2026-11-12, measured in PEOPLE_BENCH.md
         *   ("Adding to a named person without asking").
         */
        val SFACE_MEASURED: ClusterPolicy by lazy { ClusterPolicy(
            joinThreshold = 0.45f,
            ambiguityMargin = 0f,
            mergeThreshold = 0.45f,
            suggestThreshold = 0.50f,
            suggestMargin = 0.08f,
            linkage = ClusterLinkage.MEAN_PAIR,
            crowdWeight = 1f,
            sameFrameVeto = true,
            groupThreshold = 0.35f,
            groupMinSize = 3,
            metric = FaceMetric.SFACE,
            personMatchFloor = 0.45f
        ) }

        /**
         * The policy the face index publishes with (#580). A phone that
         * grouped under [SFACE_CONSERVATIVE] regroups its stored faces once
         * on the next publication, without a rescan: the fingerprint differs.
         * Change it only with numbers from real faces (tools/people_bench,
         * the phone's ATLAS_FACE line): a false merge across hundreds of
         * photos is worse than fragments.
         */
        val DEFAULT: ClusterPolicy get() = SFACE_MEASURED

        const val MAX_ATTACH_MIN_SIZE = 1_000
    }
}

/** How a face is scored against a cluster, and a cluster against a cluster. */
enum class ClusterLinkage {
    /** Cosine between re-normalised centroids (the pre-#580 rule). */
    CENTROID,
    /**
     * Mean cosine over all cross pairs, minus [ClusterPolicy.crowdWeight] times
     * the mean [FaceEmbedding.crowd] of the two sides. A mixed group has a
     * short centroid, so unlike [CENTROID] it scores low against everything.
     */
    MEAN_PAIR
}

/** Offered to a human for one unknown cluster; never applied automatically. */
data class PersonSuggestion(val person: PersonKey, val similarity: Float)

/** An unknown-identity cluster, members sorted by observation ID. */
data class UnknownFaceCluster(
    val id: FaceClusterId,
    val members: List<NativeFaceObservationId>,
    val suggestion: PersonSuggestion?
)
