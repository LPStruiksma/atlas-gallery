package nl.atlas.gallery.featurevisual.search

import android.graphics.Bitmap
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder
import nl.atlas.gallery.featurevisual.pose.OnnxPoseEstimator
import nl.atlas.gallery.featurevisual.pose.PoseDescriptor
import nl.atlas.gallery.featurevisual.pose.PoseRerank
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor

/**
 * VS-S2a (#468): semantic "find similar". An exact brute-force cosine of the
 * reference's SigLIP 2 vector against every active, version-matched vector of
 * the index, streamed page by page (never 150k × 768 floats at once), keeping
 * only the best [VisualQuery.limit] above [VisualQueryBands.FLOOR]. No ANN.
 *
 * [stillGranted] is the host's Android grant check. It runs before the query,
 * between pages and after the last page; any false discards everything read
 * and returns [VisualQueryStatus.REVOKED]. Blocking: call from a worker thread.
 *
 * VS-S2c: a superseded snapshot reads as an empty page, which alone would end
 * the scan early with partial hits. So once anything was read, the engine asks
 * [VisualVectorSource.isCurrent] after the last page and returns
 * [VisualQueryStatus.STALE] (nothing shown) when the snapshot it paged through
 * is no longer the published one. A source is bound to one epoch, so the host
 * re-queries with a source for the new epoch; the engine never mixes epochs.
 *
 * VS-V2: video moments are scored like photos, then collapsed per video to its
 * best moment ([collapseMoments]); a video takes one result slot.
 */
class VisualQueryEngine(
    private val source: VisualVectorSource,
    private val stillGranted: () -> Boolean = { true }
) {
    fun search(query: VisualQuery): VisualQueryResult {
        if (!stillGranted()) return VisualQueryResult.empty(VisualQueryStatus.REVOKED)
        if (query.modelVersion != source.modelVersion) {
            return VisualQueryResult.empty(VisualQueryStatus.MODEL_MISMATCH)
        }
        val reference = query.reference
        val stored = reference.media?.let(source::lookup)
        val raw = stored ?: reference.embed?.invoke()
            ?: return VisualQueryResult.empty(VisualQueryStatus.NOT_INDEXED)
        val probe = unit(raw)
        val referenceKey = reference.media

        val best = PriorityQueue<VisualHit>(query.limit + 1, WORST_FIRST)
        var passed = 0
        var nearDuplicates = 0
        var scanned = 0
        var after: VisualVectorCursor? = null
        // VS-V2: a video's moments arrive together (key order); collect them
        // and rank the video once, by its best moment.
        val moments = ArrayList<VisualHit>()
        fun offer(hit: VisualHit) {
            passed++
            best += hit
            if (best.size > query.limit) best.poll()
        }
        fun flushMoments() {
            if (moments.isEmpty()) return
            offer(collapseMoments(moments))
            moments.clear()
        }
        while (true) {
            if (!stillGranted()) return VisualQueryResult.empty(VisualQueryStatus.REVOKED)
            val page = source.page(after, PAGE_SIZE)
            check(page.size <= PAGE_SIZE) { "Oversized visual vector page" }
            for (row in page) {
                if (row.modelVersion != source.modelVersion || row.vector.size != probe.size) {
                    return VisualQueryResult.empty(VisualQueryStatus.MODEL_MISMATCH, scanned)
                }
                check(after == null || isAfter(row.cursor, after)) { "Visual pages out of order" }
                after = row.cursor
                scanned++
                check(scanned <= VisualIndexStore.MAX_MEDIA) { "Visual index larger than its cap" }
                if (referenceKey != null && row.media.key == referenceKey.key &&
                    row.media.frameMs == referenceKey.frameMs) continue
                val cosine = dot(probe, row.vector)
                if (cosine >= VisualQueryBands.NEAR_DUPLICATE) { nearDuplicates++; continue }
                val band = VisualQueryBands.bandOf(cosine) ?: continue
                val hit = VisualHit(row.media, cosine, band)
                if (moments.isNotEmpty() && moments[0].media.key != row.media.key) flushMoments()
                if (row.media.frameMs != null) moments += hit else offer(hit)
            }
            if (page.size < PAGE_SIZE) break
        }
        flushMoments()
        if (!stillGranted()) return VisualQueryResult.empty(VisualQueryStatus.REVOKED)
        if (scanned == 0) return VisualQueryResult.empty(VisualQueryStatus.NOT_INDEXED)
        if (!source.isCurrent()) return VisualQueryResult.empty(VisualQueryStatus.STALE, scanned)
        val hits = best.sortedWith(BEST_FIRST)
        return VisualQueryResult(VisualQueryStatus.OK, hits, nearDuplicates, scanned,
            truncated = passed > query.limit, reusedStoredVector = stored != null)
    }

    /**
     * The "emphasise pose" control over a semantic [result]: describes the
     * reference ([referencePose]) and the semantic top [PoseRerank.TOP_K]
     * ([candidatePose]), then applies [PoseRerank]. Bitmaps must be downscaled
     * first ([VisualPoseInput]). A reference without a body keeps the semantic
     * order ([VisualPoseState.NO_BODY]); a failing pose model keeps it too
     * ([VisualPoseState.UNAVAILABLE]); a candidate that fails counts as no body.
     */
    fun emphasisePose(
        result: VisualQueryResult,
        referencePose: () -> FloatArray?,
        candidatePose: (VisualMedia) -> FloatArray?
    ): VisualQueryResult {
        if (result.status != VisualQueryStatus.OK) return result
        require(result.pose == VisualPoseState.OFF) { "Pose emphasis needs the semantic result" }
        if (result.hits.isEmpty()) return result.copy(pose = VisualPoseState.APPLIED)
        val query = try {
            referencePose()
        } catch (e: Exception) {
            return result.copy(pose = VisualPoseState.UNAVAILABLE)
        } ?: return result.copy(pose = VisualPoseState.NO_BODY)
        val descriptors = arrayOfNulls<FloatArray>(result.hits.size)
        for (i in 0 until minOf(PoseRerank.TOP_K, result.hits.size)) {
            if (!stillGranted()) return VisualQueryResult.empty(VisualQueryStatus.REVOKED)
            descriptors[i] = try { candidatePose(result.hits[i].media) } catch (e: Exception) { null }
        }
        if (!stillGranted()) return VisualQueryResult.empty(VisualQueryStatus.REVOKED)
        val candidates = result.hits.mapIndexed { i, hit ->
            PoseRerank.Candidate(i.toLong(), hit.semantic, descriptors[i])
        }
        val ranked = PoseRerank.rerank(query, candidates)
        val hits = ranked.map { r ->
            val i = r.id.toInt()
            val pose = descriptors[i]?.let { PoseDescriptor.similarity(query, it) }
            result.hits[i].copy(score = r.score, pose = pose)
        }
        return result.copy(hits = hits, pose = VisualPoseState.APPLIED)
    }

    companion object {
        /** VisualIndexStore.loadActiveVectors' largest page. */
        const val PAGE_SIZE = 512

        /** Higher score first; ties by media key, then image before keyframes, then frame. */
        val BEST_FIRST: Comparator<VisualHit> = compareByDescending<VisualHit> { it.score }
            .thenBy { it.media.key }.thenBy { it.media.frameMs ?: -1L }
        private val WORST_FIRST: Comparator<VisualHit> = BEST_FIRST.reversed()

        /**
         * VS-V2: one video's surviving moments as one hit: the best moment,
         * with up to [VisualHit.MAX_SECONDARY_MOMENTS] next-best as secondary.
         */
        internal fun collapseMoments(moments: List<VisualHit>): VisualHit {
            require(moments.isNotEmpty() && moments.all { it.media.key == moments[0].media.key })
            val ranked = moments.sortedWith(BEST_FIRST)
            return ranked[0].copy(secondary = ranked.subList(1,
                minOf(ranked.size, 1 + VisualHit.MAX_SECONDARY_MOMENTS)).toList())
        }

        internal fun unit(vector: FloatArray): FloatArray {
            require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Invalid reference vector" }
            var squared = 0.0
            for (value in vector) squared += value.toDouble() * value
            require(squared > 0.0 && squared.isFinite()) { "Zero reference vector" }
            val inverse = (1.0 / sqrt(squared)).toFloat()
            return FloatArray(vector.size) { vector[it] * inverse }
        }

        internal fun dot(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (k in a.indices) sum += a[k] * b[k]
            return sum
        }

        private fun isAfter(next: VisualVectorCursor, previous: VisualVectorCursor): Boolean {
            val byKey = next.mediaKey.compareTo(previous.mediaKey)
            return byKey > 0 || (byKey == 0 && (next.frameMs ?: -1L) > (previous.frameMs ?: -1L))
        }
    }
}

/**
 * [VisualVectorSource] over [VisualIndexStore]'s read API for one published
 * [epoch] and embedder [modelVersion]. [lookup] is a one-row keyset page that
 * starts just before [VisualMedia]'s (key, frame) position, so it needs no new
 * store API; it returns the vector only when key, frame and revision all match.
 *
 * [isCurrent] probes the first row of [epoch]: the store answers nothing for a
 * superseded or revoked epoch, and within one published epoch processed rows
 * are only added, never removed, so an empty probe after a non-empty scan means
 * the snapshot was replaced.
 */
class StoreVisualVectorSource(
    private val store: VisualIndexStore,
    private val epoch: String,
    override val modelVersion: String
) : VisualVectorSource {
    override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> =
        store.loadActiveVectors(epoch, modelVersion, after, limit)

    override fun lookup(media: VisualMedia): FloatArray? {
        // The store keeps an image at frame -1 and a keyframe at its frame_ms (>= 0).
        val before = (media.frameMs ?: -1L) - 1L
        val row = store.loadActiveVectors(epoch, modelVersion,
            VisualVectorCursor(epoch, modelVersion, media.key, before), 1).firstOrNull()
        return row?.takeIf { it.media == media }?.vector
    }

    override fun isCurrent(): Boolean =
        store.loadActiveVectors(epoch, modelVersion, null, 1).isNotEmpty()
}

/** Android inputs for a query: embedding and pose description of bitmaps. */
object VisualPoseInput {
    /**
     * Longest side handed to the pose estimator. The person detector sees
     * 224 px and the landmark crop 256 px of the body box, so this keeps a body
     * covering a third of the frame near crop resolution, at about 2 MB of
     * ARGB instead of 48 MB for a 12 MP photo (the #477 review note).
     */
    const val MAX_SIDE = 768

    /** Target size for [width] × [height]: unchanged when already small enough. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Bad bitmap size" }
        val longest = max(width, height)
        if (longest <= MAX_SIDE) return width to height
        val scale = MAX_SIDE.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Pose descriptor of an upright [bitmap], downscaled first. The caller owns [bitmap]. */
    fun describe(estimator: OnnxPoseEstimator, bitmap: Bitmap): FloatArray? {
        val (w, h) = targetSize(bitmap.width, bitmap.height)
        val small = if (w == bitmap.width && h == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            return estimator.describe(small)
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    /**
     * A reference for a library item or a picked image: reuses the stored
     * vector when indexed, else decodes ([decode], a fresh bitmap this call
     * recycles) and embeds it with [embedder].
     */
    fun reference(
        media: VisualMedia?, embedder: SiglipImageEmbedder, decode: () -> Bitmap?
    ): VisualReference = VisualReference(media) {
        decode()?.let { bitmap ->
            try { embedder.embed(bitmap) } finally { bitmap.recycle() }
        }
    }
}
