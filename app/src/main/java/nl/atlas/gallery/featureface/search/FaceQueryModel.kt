package nl.atlas.gallery.featureface.search

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * VS-F1 (#468): data model of "search by face". Pure Kotlin, no Android,
 * storage, network or model runtime. Only native observation IDs, media keys,
 * boxes and on-device SFace vectors; never pixels, paths or V4 face IDs.
 */

/**
 * Cosine bands between L2-normalised SFace vectors (`face_recognition_sface_2021dec`).
 *
 * Calibration status: anchored on OpenCV Zoo's published same-identity cut-off
 * ([SFaceModel.OPENCV_SAME_IDENTITY_COSINE] = 0.363) and on ATLAS's own
 * conservative clustering policy (merge/suggest at 0.50). NOT yet tuned on a
 * labelled face set; the VS benchmark unit replaces these numbers with
 * measured ones. Keep every threshold here so that tuning is one diff.
 *
 * - [SAME_PERSON]: at or above, a direct match is shown as the same person.
 *   Equal to ClusterPolicy.SFACE_CONSERVATIVE.mergeThreshold on purpose: a
 *   pair this close would also be merged by People clustering.
 * - [PROBABLE]: the OpenCV cut-off. [PROBABLE, SAME_PERSON) is shown, but
 *   marked "probably the same person".
 * - [LOOKALIKE_FLOOR]: [LOOKALIKE_FLOOR, PROBABLE) is a look-alike: counted
 *   and returned separately, never shown as a result.
 * - [PERSON_MARGIN]: a query resolves to a confirmed person only when no other
 *   confirmed person's best face is within this margin (same rule as the
 *   clustering suggestion margin, ClusterPolicy.suggestMargin is 0.08; this is
 *   looser because every candidate already passed [SAME_PERSON]).
 * - [QUANTIZATION_TOLERANCE]: worst error measured on synthetic vectors for the
 *   int8 corpus (see FaceQueryEngineTest); bands are far wider than this.
 */
object FaceQueryBands {
    const val SAME_PERSON = 0.50f
    const val PROBABLE = SFaceModel.OPENCV_SAME_IDENTITY_COSINE
    const val LOOKALIKE_FLOOR = 0.28f
    const val PERSON_MARGIN = 0.05f
    const val QUANTIZATION_TOLERANCE = 0.01f

    init {
        check(LOOKALIKE_FLOOR < PROBABLE && PROBABLE < SAME_PERSON && SAME_PERSON <= 1f)
    }
}

/** Ordered best first. LOOKALIKE_EXCLUDED is never part of the ranked hits. */
enum class FaceMatchTier { SAME_PERSON, PROBABLE, LOOKALIKE_EXCLUDED }

/** Why a face is in the result; the strongest reason wins. */
enum class FaceMatchReason {
    /** The face the user picked as the query. */
    SEED,
    /** A human confirmed this face as the person the query resolved to. */
    CONFIRMED_PERSON,
    /** Its own similarity to the query. */
    DIRECT,
    /** Shares a published unknown cluster with a same-person match. */
    CLUSTER
}

/**
 * One query. [embeddings] are fresh SFace vectors (for example from a picked
 * reference image, VS-F2a); [seedFaces] are indexed faces the user selected.
 * Both may be given. [modelVersion] must equal the corpus model, else the
 * query fails closed with [FaceQueryStatus.MODEL_MISMATCH].
 *
 * Photos and videos have separate result budgets (VS-S2b, #478 follow-up):
 * [mediaLimit] bounds the photos and [videoMediaLimit] the videos (each video
 * counted once, at its best moment), so a library with many matching video
 * keyframes can never push matching photos out of the ranked list. 0 videos
 * means a caller that shows photos only.
 */
class FaceQuery(
    val modelVersion: String,
    embeddings: List<FloatArray> = emptyList(),
    val seedFaces: Set<NativeFaceObservationId> = emptySet(),
    val mediaLimit: Int = DEFAULT_MEDIA_LIMIT,
    val videoMediaLimit: Int = DEFAULT_VIDEO_MEDIA_LIMIT
) {
    /** Unit vectors, copied: caller mutation cannot change the query. */
    val embeddings: List<FloatArray> = embeddings.map { SFaceModel.l2Normalize(it) }

    init {
        require(modelVersion.isNotBlank() && modelVersion.length <= 256 &&
            '\u0000' !in modelVersion) { "Invalid query model version" }
        require(this.embeddings.isNotEmpty() || seedFaces.isNotEmpty()) {
            "A face query needs an embedding or a seed face"
        }
        require(this.embeddings.size + seedFaces.size <= MAX_QUERY_FACES) {
            "At most $MAX_QUERY_FACES query faces"
        }
        require(mediaLimit in 1..MAX_MEDIA_LIMIT) { "Media limit must be 1..$MAX_MEDIA_LIMIT" }
        require(videoMediaLimit in 0..MAX_MEDIA_LIMIT) {
            "Video media limit must be 0..$MAX_MEDIA_LIMIT"
        }
    }

    companion object {
        const val MAX_QUERY_FACES = 16
        const val DEFAULT_MEDIA_LIMIT = 5_000
        /** Videos' own share; a video is one result however many moments match. */
        const val DEFAULT_VIDEO_MEDIA_LIMIT = 500
        const val MAX_MEDIA_LIMIT = 200_000
    }
}

/**
 * One face in a result; [similarity] is its own best cosine to the query.
 * [frameMs] (VS-V1b) is the face's keyframe time in a video, null in a photo.
 */
data class FaceMatch(
    val face: NativeFaceObservationId,
    val media: IndexedMedia,
    val box: IndexedFaceBox,
    val similarity: Float,
    val tier: FaceMatchTier,
    val reason: FaceMatchReason,
    val frameMs: Long? = null
)

/** One photo or video in the ranked list, with the faces that matched in it. */
data class FaceMediaHit(
    val media: IndexedMedia,
    val tier: FaceMatchTier,
    val bestSimilarity: Float,
    val faces: List<FaceMatch>
) {
    /** The video moment of the best-ranked matching face; null for a photo. */
    val bestFrameMs: Long? get() = faces.firstOrNull()?.frameMs

    /** Every distinct video moment with a matching face, in time order. */
    val momentsMs: List<Long> get() = faces.mapNotNull { it.frameMs }.distinct().sorted()

    /** A video: its faces carry keyframe times (VS-V1b); a photo's never do. */
    val isVideo: Boolean get() = faces.any { it.frameMs != null }
}

enum class FaceQueryStatus {
    OK,
    /** Revoked, superseded or never-published index: nothing was read. */
    UNAVAILABLE,
    /** Query vectors from another embedder version: not comparable, no results. */
    MODEL_MISMATCH
}

/**
 * Ranked media: SAME_PERSON before PROBABLE, then by best similarity, then by
 * media key. [lookalikes] are faces that resemble the query but were excluded
 * (low band, a cannot-link or rejected fact, or confirmed as someone else).
 * [hits] holds at most [FaceQuery.mediaLimit] photos plus at most
 * [FaceQuery.videoMediaLimit] videos, in one ranked order.
 */
class FaceQueryResult(
    val status: FaceQueryStatus,
    val hits: List<FaceMediaHit>,
    /** The strongest excluded faces, at most [MAX_LOOKALIKES], best first. */
    val lookalikes: List<FaceMatch>,
    /** Every excluded face, also those beyond [MAX_LOOKALIKES]. */
    val lookalikeCount: Int,
    val resolvedPerson: PersonKey?,
    val scannedFaces: Int,
    /** More photos matched than [FaceQuery.mediaLimit]. */
    val truncated: Boolean,
    /** More videos matched than [FaceQuery.videoMediaLimit]. */
    val videosTruncated: Boolean = false
) {
    companion object {
        const val MAX_LOOKALIKES = 500
        fun empty(status: FaceQueryStatus, scanned: Int = 0) =
            FaceQueryResult(status, emptyList(), emptyList(), 0, null, scanned, false)
    }
}

/**
 * Immutable, in-memory search corpus for ONE embedder version: every indexed
 * face's int8-quantised unit vector (128 bytes per face, about 38 MB for
 * 300k faces), its media and box, its published unknown-cluster anchor, and
 * the human review facts. Built by FaceIndexStore.faceSearchCorpus under one
 * epoch-gated read, or by tests from synthetic vectors.
 *
 * Quantisation: q = round(127 * u) per component of the unit vector u, and the
 * cosine to a float unit query x is (q . x) / |q|. Exact brute force; no ANN.
 */
class FaceSearchCorpus private constructor(
    val modelVersion: String,
    internal val size: Int,
    internal val faceIds: Array<String>,
    internal val vectors: ByteArray,
    internal val inverseNorms: FloatArray,
    internal val mediaIndex: IntArray,
    internal val media: List<IndexedMedia>,
    internal val boxes: Array<IndexedFaceBox>,
    internal val anchors: Array<String?>,
    /** Keyframe time per row; [NO_FRAME] for a photo face (VS-V1b). */
    internal val frames: LongArray,
    /** False while cluster publication is pending: no cluster expansion then. */
    val clustersPublished: Boolean,
    val reviews: ClusterReviewFacts
) {
    val faceCount: Int get() = size

    internal val rowByFace: HashMap<String, Int> =
        HashMap<String, Int>(maxOf(16, size * 2)).also { m -> for (i in 0 until size) m[faceIds[i]] = i }

    internal val confirmedFaces: Map<PersonKey, List<Int>> by lazy {
        val out = HashMap<PersonKey, MutableList<Int>>()
        for ((face, person) in reviews.confirmed) {
            val row = rowByFace[face.localHex] ?: continue
            out.getOrPut(person) { ArrayList() }.add(row)
        }
        out
    }

    internal val cannotLink: Map<String, Set<String>> by lazy {
        val out = HashMap<String, MutableSet<String>>()
        for (pair: FacePair in reviews.cannotLink) {
            out.getOrPut(pair.first.localHex) { HashSet() }.add(pair.second.localHex)
            out.getOrPut(pair.second.localHex) { HashSet() }.add(pair.first.localHex)
        }
        out
    }

    internal val rejected: Set<Pair<String, PersonKey>> by lazy {
        reviews.rejectedPeople.mapTo(HashSet()) { it.observation.localHex to it.person }
    }

    internal val clusterRows: Map<String, List<Int>> by lazy {
        val out = HashMap<String, MutableList<Int>>()
        if (clustersPublished) for (i in 0 until size) {
            val a = anchors[i] ?: continue
            out.getOrPut(a) { ArrayList() }.add(i)
        }
        out
    }

    /** Dequantised unit vector of row [row]; used for seed faces. */
    internal fun unitVector(row: Int): FloatArray {
        val base = row * DIMENSION
        val inv = inverseNorms[row]
        return FloatArray(DIMENSION) { vectors[base + it] * inv }
    }

    /** Cosine of row [row] to the unit float vector [query]. */
    internal fun cosine(row: Int, query: FloatArray): Float {
        val base = row * DIMENSION
        var dot = 0f
        for (k in 0 until DIMENSION) dot += vectors[base + k] * query[k]
        return dot * inverseNorms[row]
    }

    /**
     * Streaming builder: rows go straight into the packed arrays, so a 300k
     * corpus never holds 300k FloatArray objects at once.
     */
    class Builder(private val modelVersion: String, expected: Int = 1024) {
        private var n = 0
        private var ids = arrayOfNulls<String>(maxOf(1, expected))
        private var vecs = ByteArray(maxOf(1, expected) * DIMENSION)
        private var invNorms = FloatArray(maxOf(1, expected))
        private var mediaIdx = IntArray(maxOf(1, expected))
        private var boxes = arrayOfNulls<IndexedFaceBox>(maxOf(1, expected))
        private var anchors = arrayOfNulls<String>(maxOf(1, expected))
        private var frames = LongArray(maxOf(1, expected))
        private val mediaRows = ArrayList<IndexedMedia>()
        private val mediaByKey = HashMap<String, Int>()
        private val seen = HashSet<String>()
        private var built = false

        init {
            require(modelVersion.isNotBlank() && modelVersion.length <= 256 &&
                '\u0000' !in modelVersion) { "Invalid corpus model version" }
            require(expected in 0..MAX_FACES) { "Expected size out of range" }
        }

        private fun grow() {
            val cap = minOf(MAX_FACES, ids.size * 2)
            ids = ids.copyOf(cap); vecs = vecs.copyOf(cap * DIMENSION)
            invNorms = invNorms.copyOf(cap); mediaIdx = mediaIdx.copyOf(cap)
            boxes = boxes.copyOf(cap); anchors = anchors.copyOf(cap)
            frames = frames.copyOf(cap)
        }

        fun add(face: NativeFaceObservationId, media: IndexedMedia, box: IndexedFaceBox,
                clusterAnchor: NativeFaceObservationId?, vector: FloatArray,
                frameMs: Long? = null): Builder {
            check(!built) { "Corpus builder already finalized" }
            require(n < MAX_FACES) { "Face search corpus limit $MAX_FACES reached" }
            require(frameMs == null || frameMs >= 0) { "Negative video frame time" }
            require(seen.add(face.localHex)) { "Duplicate face in corpus" }
            val mi = mediaByKey[media.key]?.also {
                require(mediaRows[it] == media) { "One media key with two revisions" }
            } ?: mediaRows.size.also { mediaRows += media; mediaByKey[media.key] = it }
            if (n == ids.size) grow()
            val unit = SFaceModel.l2Normalize(vector)
            val base = n * DIMENSION
            var sq = 0.0
            for (k in 0 until DIMENSION) {
                val q = (unit[k] * 127f).roundToInt().coerceIn(-127, 127)
                vecs[base + k] = q.toByte()
                sq += (q * q).toDouble()
            }
            require(sq > 0.0) { "Vector quantises to zero" }
            invNorms[n] = (1.0 / sqrt(sq)).toFloat()
            ids[n] = face.localHex; mediaIdx[n] = mi; boxes[n] = box
            anchors[n] = clusterAnchor?.localHex
            frames[n] = frameMs ?: NO_FRAME
            n++
            return this
        }

        fun build(reviews: ClusterReviewFacts, clustersPublished: Boolean): FaceSearchCorpus {
            check(!built) { "Corpus builder already finalized" }
            built = true
            // The SQLite corpus path counted rows within its read transaction,
            // so these arrays already have the exact final size. Reuse them
            // instead of doubling peak vector memory during the final copy.
            // A finalized builder may not add rows: the corpus stays immutable.
            @Suppress("UNCHECKED_CAST")
            val completeIds = (if (n == ids.size) ids else ids.copyOf(n)) as Array<String>
            @Suppress("UNCHECKED_CAST")
            val completeBoxes = (if (n == boxes.size) boxes else boxes.copyOf(n))
                as Array<IndexedFaceBox>
            return FaceSearchCorpus(modelVersion, n, completeIds,
                if (n == ids.size) vecs else vecs.copyOf(n * DIMENSION),
                if (n == ids.size) invNorms else invNorms.copyOf(n),
                if (n == ids.size) mediaIdx else mediaIdx.copyOf(n),
                mediaRows.toList(), completeBoxes,
                if (n == ids.size) anchors else anchors.copyOf(n),
                if (n == ids.size) frames else frames.copyOf(n),
                clustersPublished, reviews)
        }
    }

    internal fun mediaOf(row: Int): IndexedMedia = media[mediaIndex[row]]

    /** The keyframe time of row [row] in a video; null for a photo face. */
    internal fun frameMsOf(row: Int): Long? = frames[row].takeIf { it != NO_FRAME }

    companion object {
        private const val NO_FRAME = -1L
        const val DIMENSION = SFaceModel.DIMENSION
        /** Above the 150k-photo target with several faces per photo; ~64 MB of vectors. */
        const val MAX_FACES = 500_000
    }
}
