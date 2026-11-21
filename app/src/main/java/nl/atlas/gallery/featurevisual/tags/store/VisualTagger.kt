package nl.atlas.gallery.featurevisual.tags.store

import nl.atlas.gallery.featurevisual.VisualScanIndexPort
import nl.atlas.gallery.featurevisual.store.VisualIndexLease
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualMomentsWrite
import nl.atlas.gallery.featurevisual.store.VisualVectorCodec
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import nl.atlas.gallery.featurevisual.store.VisualVectorWrite
import nl.atlas.gallery.featurevisual.tags.TagHit
import nl.atlas.gallery.featurevisual.tags.TagPolicy
import nl.atlas.gallery.featurevisual.tags.TagScorer

/**
 * TAGS-1 (#732): one row of the visual index, keyed like it: a photo (frame
 * [IMAGE_FRAME]) or one video keyframe moment (frame_ms >= 0).
 */
data class TagRowKey(val mediaKey: String, val frameMs: Long) {
    companion object {
        /** The visual index's photo frame (`VisualIndexStore.IMAGE_FRAME`). */
        const val IMAGE_FRAME = -1L
        fun of(media: VisualMedia) = TagRowKey(media.key, media.frameMs ?: IMAGE_FRAME)
    }
}

/** What scoring one stored vector produced; [hits] may be empty (scored, no tag cleared). */
data class TaggedRow(val key: TagRowKey, val revision: String, val hits: List<TagHit>)

/** A tag of one media item: its best margin over the item's rows (a video: its moments). */
data class StoredTag(val id: String, val margin: Float)

/** Where [VisualTagStorePort.mediaForTag] looks. */
sealed interface TagScope {
    /** Every tagged item. The caller still resolves each key through the live grant. */
    data object Library : TagScope
    /** Only these media keys (a screen's scope, e.g. one album). */
    data class Media(val keys: Set<String>) : TagScope
}

/** Where the backfill sweep is: after (key, frame), or done at [doneAtMs] (wall clock). */
data class TagSweepState(val after: TagRowKey?, val doneAtMs: Long?)

data class TagStoreCounts(val rows: Int, val hits: Int, val taggedMedia: Int)

/**
 * App-private tag rows (TAGS-1). [VisualTagStore] on the device, an in-memory
 * fake in JVM tests. Keys and order are the visual index's: (media key, frame)
 * in SQLite BINARY order.
 *
 * Privacy: rows are derived, on-device data like the vectors they come from.
 * A reader must resolve every returned media key through the CURRENT Android
 * grant before showing it (TAGS-2); this store is not authority.
 */
interface VisualTagStorePort {
    /**
     * Makes [fence] (`TagMatrix.fence`: image model | text model | vocabulary)
     * current. A different stored fence deletes every row and resets the
     * sweep first; true when that happened.
     */
    fun useFence(fence: String): Boolean
    fun sweepState(): TagSweepState
    /** Stored revisions of rows with key in (after, last]. */
    fun revisionsIn(after: TagRowKey?, last: TagRowKey): Map<TagRowKey, String>
    /**
     * One transaction: delete rows with key in (after, last] that are not in
     * [present] (their vectors are gone), upsert [rows], move the sweep to [last].
     */
    fun applySweepPage(after: TagRowKey?, last: TagRowKey, present: Set<TagRowKey>, rows: List<TaggedRow>)
    /** Rows with key after [after] (all rows when null). */
    fun rowsAfter(after: TagRowKey?): Int
    /** End of the vectors: delete rows after [after], mark the sweep done at [nowMs]. */
    fun finishSweep(after: TagRowKey?, nowMs: Long)
    /** Start the next sweep from the first key. */
    fun restartSweep()
    /** Incremental: for each key in [replaceMedia] delete all its rows first, then upsert [rows]. */
    fun putRows(rows: List<TaggedRow>, replaceMedia: Set<String> = emptySet())
    /** Tags of one item, best margin first; [revision] non-null ignores rows of another revision. */
    fun tagsFor(mediaKey: String, revision: String? = null): List<StoredTag>
    /** Media keys with [tagId], best margin first. */
    fun mediaForTag(tagId: String, scope: TagScope = TagScope.Library, limit: Int = 500): List<String>
    fun counts(): TagStoreCounts
    /** The visual index was deleted, or its model no longer matches the tags. */
    fun deleteAll()
}

/** One sweep step: at most [maxPages] pages of [pageSize] vectors or [maxElapsedMs]. */
data class TagSweepBudget(
    val pageSize: Int = 256,
    val maxPages: Int = 8,
    val maxElapsedMs: Long = 1_500L,
    /** A finished sweep starts again after this long (wall clock), to catch drift. */
    val resweepAfterMs: Long = 24L * 60 * 60 * 1000,
) {
    init {
        require(pageSize in 1..512) // VisualIndexStore.loadActiveVectors' largest page
        require(maxPages >= 1 && maxElapsedMs > 0 && resweepAfterMs >= 0)
    }
}

enum class TagSweepStop {
    /** The index's vectors are from another model than the tag matrix: no tags. */
    UNSUPPORTED_MODEL,
    /** The last sweep finished recently; nothing to do. */
    IDLE,
    /** This step reached the end of the vectors. */
    FINISHED,
    /** Pages were done and more remain. */
    MORE,
    /** The host guard (thermal, power, grant, stop) ended the step. */
    GUARD,
}

data class TagSweepResult(
    val stop: TagSweepStop,
    /** Vectors read from the index in this step. */
    val examined: Int = 0,
    /** Of those, rows (re)scored because they were new, changed or untagged. */
    val scored: Int = 0,
    /** Stale rows the sweep removed. */
    val pruned: Int = 0,
) {
    /** The host should come back soon. */
    val moreWork: Boolean get() = stop == TagSweepStop.MORE || stop == TagSweepStop.GUARD
}

/**
 * TAGS-1 (#732): scores visual index vectors into tags with the TAGS-0 matrix
 * and thresholds ([TagScorer], [TagPolicy]: on-by-default tags only, at most 5).
 * Never decodes an image: [tagPublished] takes the vectors the scan just
 * wrote, [sweepStep] reads stored ones. Both see the same int8 round trip, so
 * a re-scan reproduces the same rows.
 */
class VisualTagger(
    val scorer: TagScorer,
    private val store: VisualTagStorePort,
    private val policy: TagPolicy = TagPolicy(),
    private val budget: TagSweepBudget = TagSweepBudget(),
    private val clockElapsedMs: () -> Long,
    private val wallClockMs: () -> Long,
) {
    val fence: String get() = scorer.matrix.fence

    private fun score(vector: FloatArray, model: String): List<TagHit> = scorer.score(vector, model, policy)

    /**
     * Incremental update after the visual index committed [writes] (photos)
     * and [videos] (all moments of each video, replacing its earlier ones).
     * Vectors of another model are ignored. Returns the rows written.
     */
    fun tagPublished(writes: List<VisualVectorWrite>, videos: List<VisualMomentsWrite>): Int {
        if (writes.none { scorer.accepts(it.lease.modelVersion) } &&
            videos.none { scorer.accepts(it.lease.modelVersion) }) return 0
        store.useFence(fence)
        val rows = ArrayList<TaggedRow>()
        for (write in writes) {
            val lease = write.lease
            if (!scorer.accepts(lease.modelVersion)) continue
            rows += TaggedRow(TagRowKey.of(lease.media), lease.media.revision,
                score(asStored(write.vector), lease.modelVersion))
        }
        val replaced = HashSet<String>()
        for (video in videos) {
            val lease = video.lease
            if (!scorer.accepts(lease.modelVersion)) continue
            replaced += lease.media.key
            for (moment in video.moments) {
                rows += TaggedRow(TagRowKey(lease.media.key, moment.frameMs), lease.media.revision,
                    score(asStored(moment.vector), lease.modelVersion))
            }
        }
        store.putRows(rows, replaced)
        return rows.size
    }

    /**
     * One bounded backfill/reconcile step over the active index ([read]:
     * `VisualIndexStore.loadActiveVectors` for the current epoch and
     * [modelVersion], which already enforces the grant's epoch). Rows whose
     * stored revision matches are skipped without scoring; new or changed rows
     * are scored; rows whose vectors are gone are pruned. [canContinue] is
     * checked before every page.
     */
    fun sweepStep(
        epoch: String, modelVersion: String,
        read: (after: VisualVectorCursor?, limit: Int) -> List<VisualIndexedVector>,
        canContinue: () -> Boolean,
    ): TagSweepResult {
        if (!scorer.accepts(modelVersion)) {
            // Vector version changed under the tags: they no longer describe the vectors.
            store.deleteAll()
            return TagSweepResult(TagSweepStop.UNSUPPORTED_MODEL)
        }
        store.useFence(fence)
        var state = store.sweepState()
        state.doneAtMs?.let { done ->
            val age = wallClockMs() - done
            if (age in 0 until budget.resweepAfterMs) return TagSweepResult(TagSweepStop.IDLE)
            store.restartSweep()
            state = TagSweepState(null, null)
        }
        val start = clockElapsedMs()
        var after = state.after
        var examined = 0
        var scored = 0
        var pruned = 0
        for (page in 0 until budget.maxPages) {
            if (page > 0 && clockElapsedMs() - start >= budget.maxElapsedMs) {
                return TagSweepResult(TagSweepStop.MORE, examined, scored, pruned)
            }
            if (!canContinue()) return TagSweepResult(TagSweepStop.GUARD, examined, scored, pruned)
            val cursor = after?.let {
                VisualVectorCursor(epoch, modelVersion, it.mediaKey,
                    it.frameMs.takeUnless { f -> f == TagRowKey.IMAGE_FRAME })
            }
            val vectors = read(cursor, budget.pageSize)
            if (vectors.isEmpty()) {
                pruned += store.rowsAfter(after)
                store.finishSweep(after, wallClockMs())
                return TagSweepResult(TagSweepStop.FINISHED, examined, scored, pruned)
            }
            val last = TagRowKey.of(vectors.last().media)
            val known = store.revisionsIn(after, last)
            val present = HashSet<TagRowKey>(vectors.size * 2)
            val rows = ArrayList<TaggedRow>()
            for (v in vectors) {
                if (v.modelVersion != modelVersion) continue
                val key = TagRowKey.of(v.media)
                present += key
                if (known[key] == v.media.revision) continue
                rows += TaggedRow(key, v.media.revision, score(v.vector, modelVersion))
            }
            pruned += known.keys.count { it !in present }
            store.applySweepPage(after, last, present, rows)
            examined += vectors.size
            scored += rows.size
            after = last
            if (vectors.size < budget.pageSize) {
                // A short page is the end: finish now instead of one more empty read.
                pruned += store.rowsAfter(after)
                store.finishSweep(after, wallClockMs())
                return TagSweepResult(TagSweepStop.FINISHED, examined, scored, pruned)
            }
        }
        return TagSweepResult(TagSweepStop.MORE, examined, scored, pruned)
    }

    /** Drops every tag row (the visual index was deleted). */
    fun deleteAll() = store.deleteAll()

    /** The bytes the store keeps: int8 round trip, as `loadActiveVectors` returns them. */
    private fun asStored(vector: FloatArray): FloatArray =
        VisualVectorCodec.encode(vector).let { VisualVectorCodec.decode(it.bytes, it.scale, vector.size) }

}

/**
 * The one scan hook: a [VisualScanIndexPort] that, after the index ACCEPTED a
 * batch, hands its vectors to [onPublished]. A refused batch (stale lease)
 * tags nothing. A tagging failure never fails the index write: the sweep
 * reconciles it later.
 */
class TaggingIndexPort(
    private val delegate: VisualScanIndexPort,
    private val onPublished: (List<VisualVectorWrite>, List<VisualMomentsWrite>) -> Unit,
) : VisualScanIndexPort by delegate {
    override fun completeBatch(writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>): Boolean =
        delegate.completeBatch(writes, skipped).also { if (it) publish(writes, emptyList()) }

    override fun completeBatch(
        writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>, videos: List<VisualMomentsWrite>,
    ): Boolean = delegate.completeBatch(writes, skipped, videos).also { if (it) publish(writes, videos) }

    private fun publish(writes: List<VisualVectorWrite>, videos: List<VisualMomentsWrite>) {
        if (writes.isEmpty() && videos.isEmpty()) return
        try {
            onPublished(writes, videos)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: RuntimeException) {
            // SQLite or asset failure: the index write stands; the sweep retags.
        }
    }
}
