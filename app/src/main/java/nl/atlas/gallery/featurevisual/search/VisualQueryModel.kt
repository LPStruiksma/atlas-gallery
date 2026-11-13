package nl.atlas.gallery.featurevisual.search

import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor

/**
 * VS-S2a (#468): data model of the semantic "find similar" query. Pure Kotlin,
 * no Android, storage, network or model runtime. Only opaque media keys,
 * revisions, keyframe times and on-device SigLIP 2 vectors; never pixels or paths.
 */

/**
 * Cosine bands between L2-normalised SigLIP 2 B/16 image vectors
 * (`siglip2_b16_224.int8`).
 *
 * Calibrated in VS-S2c on the VS-S0 benchmark set (1,397 Open Images action
 * photos, 975,106 pairs, shipped int8 model, stored through the int8 codec);
 * method and numbers in docs/visual-search/VS_S2C_CALIBRATION.md, re-run with
 * tools/visual_search_bench/calibrate_bands.py. "Same-class precision" is the
 * share of pairs at or above a cosine that share a human-verified label
 * (base rate 3.5%). Keep every threshold here so that re-tuning is one diff.
 *
 * - [STRONG]: at or above, a hit is shown as "very similar" (same-class
 *   precision 0.81 at and above it; target 0.80).
 * - [FLOOR]: [FLOOR, STRONG) is "related"; below [FLOOR] a vector is dropped
 *   (precision 0.53 at and above it; target 0.50).
 * - [NEAR_DUPLICATE]: at or above, a vector is the reference again (a copy,
 *   an edit, a burst neighbour): counted, never shown as a "similar" result.
 *   Keeps 94.6% of synthetic edits out of the results while hiding 8 of the
 *   975,106 pairs of distinct photos.
 * - [QUANTIZATION_TOLERANCE]: worst cosine error measured on synthetic vectors
 *   for the stored int8 codec (see VisualQueryEngineTest); bands are far wider.
 */
object VisualQueryBands {
    const val STRONG = 0.84f
    const val FLOOR = 0.74f
    const val NEAR_DUPLICATE = 0.95f
    const val QUANTIZATION_TOLERANCE = 0.01f

    init {
        check(FLOOR < STRONG && STRONG < NEAR_DUPLICATE && NEAR_DUPLICATE <= 1f)
    }

    /** Band of a semantic cosine, or null when it is below [FLOOR]. */
    fun bandOf(semantic: Float): VisualMatchBand? = when {
        semantic >= STRONG -> VisualMatchBand.STRONG
        semantic >= FLOOR -> VisualMatchBand.RELATED
        else -> null
    }
}

/** Ordered best first. A band always comes from the semantic cosine, never from pose. */
enum class VisualMatchBand { STRONG, RELATED }

/** Read-only, version-fenced pages of the active semantic index. */
interface VisualVectorSource {
    /** The embedder version every returned vector was produced by. */
    val modelVersion: String

    /**
     * Keyset page after [after] in (media key, frame) order, at most [limit]
     * rows. Empty when the index is revoked, superseded or of another version.
     */
    fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector>

    /** The stored unit vector of exactly [media] (same revision), or null. */
    fun lookup(media: VisualMedia): FloatArray?

    /**
     * True while the snapshot [page] reads from is still the published one.
     * Asked after the last page of a non-empty scan (VS-S2c): false means a
     * short or empty page may have come from a superseded snapshot. Only an
     * epoch-less source (in-memory, tests) may keep this default;
     * [StoreVisualVectorSource] overrides it.
     */
    fun isCurrent(): Boolean = true
}

/**
 * The reference to search from. [media] is set when the reference is a library
 * item: its stored vector is reused when indexed at the same revision, and the
 * item itself is excluded from the results. [embed] computes a fresh vector
 * (SigLIP 2, for a picked or not-yet-indexed image); it runs only when needed,
 * on the caller's worker thread.
 */
class VisualReference(
    val media: VisualMedia? = null,
    val embed: (() -> FloatArray?)? = null
) {
    init {
        require(media != null || embed != null) { "A reference needs media or an embedder" }
    }
}

/**
 * One query. [modelVersion] is the version of [VisualReference.embed]'s
 * embedder and must equal the index's, else the query fails closed with
 * [VisualQueryStatus.MODEL_MISMATCH].
 */
class VisualQuery(
    val modelVersion: String,
    val reference: VisualReference,
    val limit: Int = DEFAULT_LIMIT
) {
    init {
        require(modelVersion.isNotBlank() && modelVersion.length <= 256 &&
            '\u0000' !in modelVersion) { "Invalid query model version" }
        require(limit in 1..MAX_LIMIT) { "Limit must be 1..$MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 5_000
    }
}

/**
 * One ranked photo or video moment. [semantic] is its cosine to the reference
 * and decides [band]; [score] is what it is ranked by (semantic, or the pose
 * re-rank score when pose emphasis is on); [pose] is its pose similarity when
 * computed.
 *
 * VS-V2: a video is ONE hit, its best-scoring moment ([media] carries that
 * keyframe's frame_ms); [secondary] holds up to [MAX_SECONDARY_MOMENTS] of its
 * next-best moments above the floor, best first, never ranked on their own.
 */
data class VisualHit(
    val media: VisualMedia,
    val semantic: Float,
    val band: VisualMatchBand,
    val score: Float = semantic,
    val pose: Float? = null,
    val secondary: List<VisualHit> = emptyList()
) {
    /** Where a video hit should start playing (VS-S2b seeks there); null for a photo. */
    val frameMs: Long? get() = media.frameMs
    val isVideoMoment: Boolean get() = media.frameMs != null

    companion object {
        const val MAX_SECONDARY_MOMENTS = 3
    }
}

/** A moment's position as the results grid shows it: m:ss, or h:mm:ss from an hour. */
fun formatMomentTime(frameMs: Long): String {
    require(frameMs >= 0L)
    val total = frameMs / 1000L
    val hours = total / 3600L
    val minutes = total % 3600L / 60L
    val seconds = total % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
}

enum class VisualQueryStatus {
    OK,
    /** The index has no vectors yet for this version, or the reference cannot be embedded. */
    NOT_INDEXED,
    /** Query and index come from different embedder versions or dimensions. */
    MODEL_MISMATCH,
    /** The grant was lost during the query: nothing is shown. */
    REVOKED,
    /**
     * The index snapshot was superseded (a rescan published a new one) while
     * the query paged through it: the read is incomplete, so nothing is shown.
     * The host may query again on the new snapshot.
     */
    STALE
}

/** Where pose emphasis stands for the shown order. */
enum class VisualPoseState {
    /** Semantic order. */
    OFF,
    /** The top [nl.atlas.gallery.featurevisual.pose.PoseRerank.TOP_K] are pose re-ranked. */
    APPLIED,
    /** The reference has no body: semantic order kept, with a hint. */
    NO_BODY,
    /** The pose model could not run: semantic order kept. */
    UNAVAILABLE
}

/**
 * Ranked hits, best first: by [VisualHit.score], then media key, then frame;
 * one per photo or video (VS-V2). [nearDuplicates] were dropped as copies of
 * the reference; [scanned] counts vectors compared (every moment counts);
 * [truncated] means more than [VisualQuery.limit] items passed the floor.
 */
data class VisualQueryResult(
    val status: VisualQueryStatus,
    val hits: List<VisualHit>,
    val nearDuplicates: Int,
    val scanned: Int,
    val truncated: Boolean,
    /** True when the reference's stored vector was used instead of a fresh embedding. */
    val reusedStoredVector: Boolean,
    val pose: VisualPoseState = VisualPoseState.OFF
) {
    companion object {
        fun empty(status: VisualQueryStatus, scanned: Int = 0) =
            VisualQueryResult(status, emptyList(), 0, scanned, false, false)
    }
}

/** What the results screen shows; [Results] with no hits is the empty state. */
sealed interface VisualSearchUiState {
    data object Loading : VisualSearchUiState
    data object NotIndexed : VisualSearchUiState
    data object VersionMismatch : VisualSearchUiState
    data object Revoked : VisualSearchUiState
    data class Results(val result: VisualQueryResult) : VisualSearchUiState

    companion object {
        fun of(result: VisualQueryResult): VisualSearchUiState = when (result.status) {
            VisualQueryStatus.OK -> Results(result)
            VisualQueryStatus.NOT_INDEXED -> NotIndexed
            VisualQueryStatus.MODEL_MISMATCH -> VersionMismatch
            VisualQueryStatus.REVOKED -> Revoked
            // The index is being republished: same "try again" message as indexing.
            VisualQueryStatus.STALE -> NotIndexed
        }
    }
}
