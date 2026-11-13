package nl.atlas.gallery.featurevisual

import android.graphics.Bitmap
import java.io.IOException
import nl.atlas.gallery.featurevisual.store.VisualIndexLease
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualMoment
import nl.atlas.gallery.featurevisual.store.VisualMomentsWrite
import nl.atlas.gallery.featurevisual.store.VisualVectorWrite

/**
 * Minimal transactional seam: the People worker will supply only media that
 * its CURRENT permission snapshot authorises. This stage does not open media,
 * create workers, mint epochs, or bypass the worker's decode/thermal budget.
 */
interface VisualScanIndexPort {
    fun claimAuthorized(
        epoch: String, modelVersion: String, authorized: List<VisualMedia>, maxItems: Int
    ): List<VisualIndexLease>
    /**
     * All writes and skips succeed together or none do. False means a stale
     * lease. A skipped item gets no vector and is not claimed again until its
     * revision or the model changes (#480).
     */
    fun completeBatch(
        writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease> = emptyList()
    ): Boolean
    /**
     * VS-V2: as above, plus [videos], each a video's keyframe moments that
     * replace all of its earlier moments in the same transaction. A port that
     * cannot store moments refuses (false), which holds the step.
     */
    fun completeBatch(
        writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>,
        videos: List<VisualMomentsWrite>
    ): Boolean = if (videos.isEmpty()) completeBatch(writes, skipped) else false
    fun releaseBatch(leases: List<VisualIndexLease>): Int
    /**
     * #482 follow-up: count one unexpected failure ([VisualEmbedOutcome.Failed])
     * of the still-leased item, durably and on its own, and return its count
     * for this revision and model. 0 means the lease is stale (nothing counted).
     * A port that does not count returns 0, so the step only holds.
     */
    fun recordFailure(lease: VisualIndexLease): Int = 0
}

/**
 * At most [maxItems] items or [maxElapsedMs] per step. The time budget is
 * cooperative (#480): it is checked before every item, so a step overruns it
 * by at most ONE decode + embedding. The decode is subsampled (both sides
 * >= 896 px) and SigLIP B/16 runs at a fixed 224 px input, which keeps that
 * single item bounded; the item cap bounds the rest.
 */
data class VisualScanBudget(val maxItems: Int = 8, val maxElapsedMs: Long = 2_000L) {
    init {
        require(maxItems in 1..8)
        require(maxElapsedMs > 0)
    }
}

/**
 * VS-V2: a video's own budget, separate from the photos' (as VS-V1c did for
 * faces), so videos never block photos:
 * - catalogue: at most [MAX_CATALOG_VIDEOS] videos are published, in
 *   inventory order; later ones are deferred, never a reason to fail photos;
 * - step: a step runs photos when its window has any; only a window without
 *   photos runs ONE video ([VisualStepPlan]);
 * - video: VS-V1a's bounded keyframes (at most 60, scene changes only), of
 *   which at most [maxMoments], spread evenly, are embedded. The guard is
 *   checked before every keyframe; once [maxElapsedMs] is spent, the moments
 *   embedded so far are the video's index (a slow device indexes fewer
 *   moments instead of retrying the video for ever).
 */
data class VisualVideoBudget(val maxMoments: Int = 12, val maxElapsedMs: Long = 20_000L) {
    init {
        require(maxMoments in 1..MAX_MOMENTS)
        require(maxElapsedMs > 0)
    }

    /** At most [maxMoments] of [frames] (ordered by time), evenly spread, first kept. */
    fun <T> pick(frames: List<T>): List<T> {
        if (frames.size <= maxMoments) return frames
        // Index i * (n - 1) / (k - 1): first and last kept, strictly increasing.
        if (maxMoments == 1) return listOf(frames.first())
        val last = frames.size - 1
        return List(maxMoments) { i -> frames[(i.toLong() * last / (maxMoments - 1)).toInt()] }
    }

    companion object {
        /** VideoKeyframePlan.MAX_FRAMES_PER_VIDEO: the store's per-video cap too. */
        const val MAX_MOMENTS = 60
        const val MAX_CATALOG_VIDEOS = 10_000
    }
}

/**
 * VS-V2: which candidates one step may claim. Photos first: when the window
 * holds any authorised photo, the step claims photos only; otherwise it claims
 * the first video alone. So a video costs at most one step, and only when no
 * photo in the window is waiting.
 */
object VisualStepPlan {
    fun <T> select(authorized: List<T>, isVideo: (T) -> Boolean): List<T> {
        val photos = authorized.filterNot(isVideo)
        if (photos.isNotEmpty()) return photos
        return authorized.take(1)
    }
}

/** One decoded keyframe of a video, at [frameMs]; [image] is owned by the sampler's caller. */
class VisualKeyframe<out B>(val frameMs: Long, val image: B)

/** What sampling one authorised video produced, before embedding. */
sealed interface VisualVideoFrames<out B> {
    /** Keyframes in time order; the caller recycles every image. */
    class Sampled<B>(val frames: List<VisualKeyframe<B>>) : VisualVideoFrames<B>
    /** Corrupt, DRM, zero-length or otherwise undecodable: a typed skip. */
    data class Skipped(val reason: String) : VisualVideoFrames<Nothing>
    /**
     * Refused although still authorised (a provider SecurityException the grant
     * does not explain): a counted [VisualEmbedOutcome.Failed], so it becomes
     * a skip after [VisualScanStage.MAX_ITEM_FAILURES] tries instead of
     * holding the video lane for ever (review #486 note 3).
     */
    data class Failed(val reason: String) : VisualVideoFrames<Nothing>
}

/**
 * #482 follow-up (b): finds the next window of queued candidates with at least
 * one authorised item, from [cursor] on. A window with none moves past it, so
 * items further in key order are reached; the end wraps to the start once; at
 * most [maxWindows] windows are read. [read] returns the queued window strictly
 * after its argument (null: the epoch is gone); [resolve] says whether an item
 * is an authorised video (true), photo (false) or not authorised (null).
 * Returns null when [read] does, else the window (empty: nothing found) and the
 * cursor the next step starts from.
 */
object VisualWindowScan {
    class Result(val window: List<Pair<VisualMedia, Boolean>>, val cursor: VisualMedia?)

    fun next(
        cursor: VisualMedia?, maxWindows: Int,
        read: (after: VisualMedia?) -> List<VisualMedia>?,
        resolve: (VisualMedia) -> Boolean?
    ): Result? {
        require(maxWindows >= 1)
        var after = cursor
        var wrapped = after == null
        repeat(maxWindows) {
            val queued = read(after) ?: return null
            if (queued.isEmpty()) {
                if (wrapped) return Result(emptyList(), null)
                after = null
                wrapped = true
                return@repeat
            }
            val authorized = queued.mapNotNull { media -> resolve(media)?.let { media to it } }
            if (authorized.isNotEmpty()) return Result(authorized, after)
            after = queued.last()
        }
        return Result(emptyList(), after)
    }
}

/** What reading and embedding one authorised item produced. */
sealed interface VisualEmbedOutcome {
    class Vector(val vector: FloatArray) : VisualEmbedOutcome
    /** VS-V2: a video's embedded keyframes, each at its frame_ms (at least one). */
    class Moments(val moments: List<VisualMoment>) : VisualEmbedOutcome
    /**
     * The item itself cannot be read (corrupt, undecodable, unsupported):
     * a typed per-item skip, never a reason to hold the page (#480).
     */
    data class Unreadable(val reason: String) : VisualEmbedOutcome
    /**
     * Not the item's fault (grant or scope gone, embedder closed): publish
     * nothing from this step and release every lease for a later run.
     */
    data object Hold : VisualEmbedOutcome
    /**
     * #482 follow-up: an unexpected failure on this item (a runtime error
     * such as OrtException, or a NaN or wrong-size output). The step holds
     * and the item's failure is counted; after [VisualScanStage.MAX_ITEM_FAILURES]
     * it becomes a recorded skip, like [Unreadable], instead of being retried
     * for ever.
     */
    data class Failed(val reason: String) : VisualEmbedOutcome
}

/** Why a step ended. */
enum class VisualScanStop {
    /** No authorised queued item could be claimed. */
    NOTHING_CLAIMED,
    /** Every claimed item was indexed or skipped. */
    COMPLETED,
    /** The time budget or a host guard (thermal, power, grant, stop) ended it early. */
    BUDGET_OR_GUARD,
    /** An item asked to hold, or the store refused a stale lease. */
    HELD
}

data class VisualScanResult(
    val claimed: Int,
    val indexed: Int,
    val held: Boolean,
    val skipped: Int = 0,
    val stop: VisualScanStop = if (claimed == 0) VisualScanStop.NOTHING_CLAIMED
        else if (held) VisualScanStop.HELD else VisualScanStop.COMPLETED
)

/**
 * A single, synchronous, bounded batch step; run on the existing background
 * worker thread. All embeddings and skips in a step are published atomically.
 * A HOLD outcome aborts the step instead of committing a partial permission
 * snapshot; an unreadable item is a recorded skip that lets the items after it
 * proceed. Every unused or failed lease is released, but only if still
 * epoch-current.
 */
class VisualScanStage(
    private val index: VisualScanIndexPort,
    val modelVersion: String,
    val dimension: Int,
    private val embedAuthorized: (VisualMedia) -> VisualEmbedOutcome,
    private val clockElapsedMs: () -> Long
) {
    init {
        require(modelVersion.isNotBlank() && modelVersion.length <= 256 && '\u0000' !in modelVersion)
        require(dimension in 1..4096)
    }

    /**
     * Production seam: [decode] creates a fresh Bitmap owned by this stage, or
     * returns null / throws [IOException] for an unreadable item. It throws
     * [SecurityException] when the item is no longer authorised, which holds.
     * The embedder stays owned by the caller (one per scan run, #474).
     */
    constructor(
        index: VisualScanIndexPort,
        embedder: ImageEmbedder,
        decode: (VisualMedia) -> Bitmap?,
        clockElapsedMs: () -> Long
    ) : this(
        index, embedder.modelVersion, embedder.dimension,
        { media -> decodeAndEmbed(media, decode, embedder::embed) },
        clockElapsedMs
    )

    companion object {
        /** Tries of one item (same revision and model) before it is skipped. */
        const val MAX_ITEM_FAILURES = 3

        /**
         * Maps decode and embed failures to outcomes. Decoder failures and an
         * input the embedder rejects ([IllegalArgumentException]) are the
         * item's; a revoked grant or a closed embedder ([IllegalStateException])
         * is not. Any other exception from the embedder (OrtException) is a
         * counted [VisualEmbedOutcome.Failed]. Interrupts and errors propagate.
         */
        fun <B> decodeAndEmbedWith(
            media: VisualMedia,
            decode: (VisualMedia) -> B?,
            embed: (B) -> FloatArray,
            recycle: (B) -> Unit
        ): VisualEmbedOutcome {
            val image = try {
                decode(media) ?: return VisualEmbedOutcome.Unreadable("decoder returned no image")
            } catch (_: SecurityException) {
                return VisualEmbedOutcome.Hold
            } catch (e: IOException) {
                return VisualEmbedOutcome.Unreadable(e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                return VisualEmbedOutcome.Unreadable(e.javaClass.simpleName)
            } catch (_: OutOfMemoryError) {
                return VisualEmbedOutcome.Unreadable("OutOfMemoryError")
            }
            return try {
                VisualEmbedOutcome.Vector(embed(image))
            } catch (e: IllegalArgumentException) {
                VisualEmbedOutcome.Unreadable(e.javaClass.simpleName)
            } catch (_: IllegalStateException) {
                VisualEmbedOutcome.Hold
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                VisualEmbedOutcome.Failed(e.javaClass.simpleName)
            } finally {
                recycle(image)
            }
        }

        /**
         * VS-V2: samples one authorised video ([sample]: VS-V1a keyframes, or a
         * typed skip; [SecurityException] when the grant is gone), embeds at
         * most [budget] moments of it and recycles every keyframe. Mapping as
         * [decodeAndEmbedWith]: a keyframe the embedder rejects is left out (a
         * video with none left is Unreadable); a closed embedder or a failed
         * guard holds; any other embedder exception is a counted Failed.
         */
        fun <B> embedVideoWith(
            media: VisualMedia,
            sample: (VisualMedia) -> VisualVideoFrames<B>,
            embed: (B) -> FloatArray,
            recycle: (B) -> Unit,
            budget: VisualVideoBudget,
            clockElapsedMs: () -> Long,
            canContinue: () -> Boolean
        ): VisualEmbedOutcome {
            val start = clockElapsedMs()
            val sampled = try {
                sample(media)
            } catch (_: SecurityException) {
                return VisualEmbedOutcome.Hold
            } catch (e: IOException) {
                return VisualEmbedOutcome.Unreadable(e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                return VisualEmbedOutcome.Unreadable(e.javaClass.simpleName)
            } catch (_: OutOfMemoryError) {
                return VisualEmbedOutcome.Unreadable("OutOfMemoryError")
            }
            val frames = when (sampled) {
                is VisualVideoFrames.Skipped -> return VisualEmbedOutcome.Unreadable(sampled.reason)
                is VisualVideoFrames.Failed -> return VisualEmbedOutcome.Failed(sampled.reason)
                is VisualVideoFrames.Sampled -> sampled.frames
            }
            try {
                if (frames.isEmpty()) return VisualEmbedOutcome.Unreadable("NO_DECODABLE_FRAMES")
                if (frames.size > VisualVideoBudget.MAX_MOMENTS ||
                    frames.zipWithNext().any { (a, b) -> a.frameMs >= b.frameMs } ||
                    frames.first().frameMs < 0L) {
                    return VisualEmbedOutcome.Unreadable("INVALID_KEYFRAMES")
                }
                val moments = ArrayList<VisualMoment>(budget.maxMoments)
                for (frame in budget.pick(frames)) {
                    if (moments.isNotEmpty() && clockElapsedMs() - start >= budget.maxElapsedMs) break
                    if (!canContinue()) return VisualEmbedOutcome.Hold
                    val vector = try {
                        embed(frame.image)
                    } catch (_: IllegalArgumentException) {
                        continue
                    } catch (_: IllegalStateException) {
                        return VisualEmbedOutcome.Hold
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        return VisualEmbedOutcome.Failed(e.javaClass.simpleName)
                    }
                    moments += VisualMoment(frame.frameMs, vector)
                }
                return if (moments.isEmpty()) VisualEmbedOutcome.Unreadable("NO_EMBEDDABLE_FRAMES")
                    else VisualEmbedOutcome.Moments(moments)
            } finally {
                for (frame in frames) recycle(frame.image)
            }
        }

        private fun decodeAndEmbed(
            media: VisualMedia, decode: (VisualMedia) -> Bitmap?, embed: (Bitmap) -> FloatArray
        ): VisualEmbedOutcome = decodeAndEmbedWith(media, decode, embed) { it.recycle() }
    }

    /**
     * [authorized] must have been resolved under the currently checked grant,
     * in the same worker session. At most 1024 candidates are considered; the
     * store may claim only matching queued revisions and model versions.
     * [canContinue] is the host's per-item guard (thermal, power, grant,
     * worker stop); it is checked before every item, like the time budget.
     */
    fun runBatch(
        epoch: String, authorized: List<VisualMedia>, budget: VisualScanBudget = VisualScanBudget(),
        canContinue: () -> Boolean = { true }
    ): VisualScanResult {
        require(authorized.size <= 1024) { "Unbounded authorized candidate page" }
        val start = clockElapsedMs()
        val leases = index.claimAuthorized(epoch, modelVersion, authorized, budget.maxItems)
        if (leases.isEmpty()) return VisualScanResult(0, 0, held = false)
        val writes = ArrayList<VisualVectorWrite>(leases.size)
        val videos = ArrayList<VisualMomentsWrite>()
        val skipped = ArrayList<VisualIndexLease>()
        var stoppedEarly = false
        fun valid(vector: FloatArray) = vector.size == dimension && vector.all { it.isFinite() }
        try {
            for (lease in leases) {
                if (clockElapsedMs() - start >= budget.maxElapsedMs || !canContinue()) {
                    stoppedEarly = true
                    break
                }
                check(lease.modelVersion == modelVersion && lease.dimension == dimension) {
                    "Store and embedder model mismatch"
                }
                var outcome = embedAuthorized(lease.media)
                // A NaN or wrong-size output is this item's failure, not the step's.
                if (outcome is VisualEmbedOutcome.Vector && !valid(outcome.vector) ||
                    outcome is VisualEmbedOutcome.Moments &&
                    (outcome.moments.isEmpty() || !outcome.moments.all { valid(it.vector) })) {
                    outcome = VisualEmbedOutcome.Failed("Invalid image embedding")
                }
                when (outcome) {
                    is VisualEmbedOutcome.Vector -> writes += VisualVectorWrite(lease, outcome.vector)
                    is VisualEmbedOutcome.Moments ->
                        videos += VisualMomentsWrite(lease, outcome.moments)
                    is VisualEmbedOutcome.Unreadable -> skipped += lease
                    VisualEmbedOutcome.Hold ->
                        return VisualScanResult(leases.size, 0, held = true,
                            stop = VisualScanStop.HELD)
                    is VisualEmbedOutcome.Failed -> {
                        // Counted on its own, then the whole step holds as before
                        // (nothing of it is published). The last allowed try
                        // turns the item into a recorded skip instead.
                        val tries = index.recordFailure(lease)
                        if (tries < MAX_ITEM_FAILURES) {
                            return VisualScanResult(leases.size, 0, held = true,
                                stop = VisualScanStop.HELD)
                        }
                        skipped += lease
                    }
                }
            }
            if (writes.isEmpty() && videos.isEmpty() && skipped.isEmpty()) {
                return VisualScanResult(leases.size, 0, held = true,
                    stop = VisualScanStop.BUDGET_OR_GUARD)
            }
            val accepted = if (videos.isEmpty()) index.completeBatch(writes, skipped)
                else index.completeBatch(writes, skipped, videos)
            if (!accepted) {
                return VisualScanResult(leases.size, 0, held = true, stop = VisualScanStop.HELD)
            }
            val indexed = writes.size + videos.size
            return VisualScanResult(leases.size, indexed, held = stoppedEarly,
                skipped = skipped.size,
                stop = if (stoppedEarly) VisualScanStop.BUDGET_OR_GUARD else VisualScanStop.COMPLETED)
        } finally {
            // CompleteBatch consumed matching leases; releasing them again does
            // nothing. A revoked epoch cannot be mutated by this stale step.
            index.releaseBatch(leases)
        }
    }
}
