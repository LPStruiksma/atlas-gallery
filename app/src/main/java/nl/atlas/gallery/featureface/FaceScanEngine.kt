package nl.atlas.gallery.featureface

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException

/** Pure orchestration only. Every method is BLOCKING and must run on a dedicated IO worker,
 * never Compose/main. Platform decoder, bundled on-device detector and private DB are injected.
 * Nothing in this file has permission to infer a person identity or modify the V4 People DB.
 */
data class FaceScanLease(
    val media: FaceMediaKey,
    val detectorVersion: String,
    val permissionEpoch: String,
    val opaqueToken: String
) {
    init { require(detectorVersion.isNotBlank() && permissionEpoch.isNotBlank() && opaqueToken.isNotBlank()) }
}

/** Oriented, downsampled image. Implementations MUST release bitmap/decoder resources on close. */
interface BoundedFaceFrame : Closeable {
    val widthPixels: Int
    val heightPixels: Int
}

/** Implementor must stream/decode only currently permitted MediaStore content, max long edge. */
fun interface FaceFrameReader {
    @Throws(FaceScanIoFailure::class)
    fun open(lease: FaceScanLease, maxLongEdgePixels: Int): BoundedFaceFrame

    /**
     * VS-V1b (#468): every frame one scan of this source needs. A photo is
     * one still frame; a video reader returns its bounded keyframes, or
     * throws [FaceSourceSkipped] for a corrupt, DRM or undecodable video.
     */
    @Throws(FaceScanIoFailure::class, FaceSourceSkipped::class)
    fun openFrames(lease: FaceScanLease, maxLongEdgePixels: Int): FaceSourceFrames =
        FaceSourceFrames.still(open(lease, maxLongEdgePixels))
}

/** One decoded frame of a source: [frameMs] is null for a photo, the keyframe time for a video. */
class TimedFaceFrame(val frameMs: Long?, val frame: BoundedFaceFrame) {
    init { require(frameMs == null || frameMs >= 0) { "Negative keyframe time" } }
}

/**
 * The frames of ONE source for ONE scan, in time order. Closing it closes
 * every frame, also when an earlier close fails; the scanner closes it
 * before any completion is persisted.
 */
class FaceSourceFrames(val frames: List<TimedFaceFrame>) : Closeable {
    init {
        require(frames.isNotEmpty() && frames.size <= MAX_FRAMES) { "Unbounded source frames" }
        require(frames.size == 1 || frames.all { it.frameMs != null }) {
            "A multi-frame source must time every frame"
        }
        require(frames.zipWithNext().all { (a, b) -> a.frameMs!! < b.frameMs!! }) {
            "Keyframes must be strictly time ordered"
        }
    }

    override fun close() {
        var failure: Throwable? = null
        for (timed in frames) {
            try { timed.frame.close() } catch (t: Throwable) {
                if (failure == null) failure = t else failure.addSuppressed(t)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        /** VideoKeyframePlan.MAX_FRAMES_PER_VIDEO; also the batch-policy unit cap. */
        const val MAX_FRAMES = 60
        fun still(frame: BoundedFaceFrame) = FaceSourceFrames(listOf(TimedFaceFrame(null, frame)))
    }
}

/**
 * A typed, terminal skip of one source (corrupt or DRM video, no decodable
 * keyframe, unknown duration). It is neither "0 faces" nor a retry: the
 * index records [reason] and keeps the source out of the queue until its
 * revision changes.
 */
class FaceSourceSkipped(val reason: String) : Exception(reason) {
    init { require(reason.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "Invalid skip reason" } }
}

/** A detector reservation protects decode-to-inference capacity, not identity. */
/** An exclusive admission slot claimed before decoding any image bytes.
 * Close must be idempotent; a started async Task holds the slot until its
 * completion callback, even if the worker gives up waiting or is cancelled.
 */
interface FaceInferenceReservation : Closeable {
    fun locate(frame: BoundedFaceFrame): List<FacePixelBounds>
}

/** A detector's box and SFace vector remain paired through geometry sorting. */
class FaceEmbeddedDetection(val bounds: FacePixelBounds, vector: FloatArray) {
    private val stored = vector.copyOf()
    val vector: FloatArray get() = stored.copyOf()
}

/** A reservation performs YuNet + SFace synchronously while holding one inference slot. */
interface FaceEmbeddingReservation : FaceInferenceReservation {
    val detectorVersion: String
    val embedderVersion: String
    fun detectAndEmbed(frame: BoundedFaceFrame): List<FaceEmbeddedDetection>
}

/** An index that requires embeddings cannot silently fall back to box-only completion. */
interface FaceScanEmbeddingIndexPort : FaceScanIndexPort {
    fun completeEmbeddings(
        lease: FaceScanLease, observations: List<UnknownFaceBox>,
        vectors: List<FloatArray>, embedderVersion: String
    ): Boolean

    /**
     * VS-V1b: [frameMs] gives each observation's video position (null for a
     * photo). A port that cannot store positions refuses video results.
     */
    fun completeEmbeddingFrames(
        lease: FaceScanLease, observations: List<UnknownFaceBox>,
        vectors: List<FloatArray>, embedderVersion: String, frameMs: List<Long?>
    ): Boolean {
        check(frameMs.all { it == null }) { "This face index cannot store video positions" }
        return completeEmbeddings(lease, observations, vectors, embedderVersion)
    }
}

fun interface OnDeviceFaceLocator {
    @Throws(FaceScanIoFailure::class)
    fun locate(frame: BoundedFaceFrame): List<FacePixelBounds>

    /** Informational only. Never use this as the sole admission guard. */
    fun canStart(): Boolean = true

    /** Atomic reservation for async implementations. Legacy synchronous test
     * implementations get a compatible best-effort reservation by default.
     */
    fun tryReserve(): FaceInferenceReservation? {
        if (!canStart()) return null
        return object : FaceInferenceReservation {
            override fun locate(frame: BoundedFaceFrame) = this@OnDeviceFaceLocator.locate(frame)
            override fun close() = Unit
        }
    }
}

/** Only X/I may supply the app-private durable implementation. The caller must match the
 * original epoch, revision, detector and opaque lease inside a single transaction. */
interface FaceScanIndexPort {
    fun claim(permissionEpoch: String): FaceScanLease?
    fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>): Boolean
    fun release(lease: FaceScanLease): Boolean // pause/revocation does NOT spend retry budget
    fun fail(lease: FaceScanLease): Boolean // bounded retry; no false completed scan

    /** VS-V1b: terminal typed skip. A port without skip storage spends a bounded retry. */
    fun skip(lease: FaceScanLease, reason: String): Boolean = fail(lease)

    /** VS-V1b: box completion with per-observation video positions (null for a photo). */
    fun completeFrames(
        lease: FaceScanLease, observations: List<UnknownFaceBox>, frameMs: List<Long?>
    ): Boolean {
        check(frameMs.all { it == null }) { "This face index cannot store video positions" }
        return complete(lease, observations)
    }
}

class FaceScanIoFailure(message: String) : Exception(message)

data class FacePixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

object FaceGeometry {
    /** Fail closed rather than silently dropping a 33rd face or persisting bad geometry.
     * Deterministic top/left ordering makes ordinals stable when a detector returns another order,
     * but NO ordinal should be used to identify a person across source/model changes.
     */
    fun normalize(width: Int, height: Int, rectangles: List<FacePixelBounds>): List<UnknownFaceBox> {
        require(width > 0 && height > 0) { "invalid oriented frame" }
        require(rectangles.size <= FaceDiscoveryContract.MAX_FACES_PER_IMAGE) { "face cap exceeded" }
        return rectangles.map { box ->
            val left = box.left.coerceIn(0, width)
            val right = box.right.coerceIn(0, width)
            val top = box.top.coerceIn(0, height)
            val bottom = box.bottom.coerceIn(0, height)
            require(left < right && top < bottom) { "off-frame or degenerate face" }
            UnknownFaceBox(left.toFloat() / width, top.toFloat() / height,
                right.toFloat() / width, bottom.toFloat() / height)
        }.sortedWith(compareBy<UnknownFaceBox>({ it.top }, { it.left }, { it.bottom }, { it.right }))
    }
}

enum class FaceScanStep {
    HELD, NO_PENDING, INDEXED, RELEASED_STALE, RETRYABLE_FAILURE, DEFERRED, MODEL_UNAVAILABLE,
    /** VS-V1b: a typed, terminal skip (corrupt/DRM video), recorded by the index. */
    SKIPPED
}

/** [frames]: frames decoded for this step (a video's keyframes), for the batch budget. */
data class FaceScanStepResult(
    val step: FaceScanStep, val observations: Int = 0, val frames: Int = 0
) {
    init {
        require(observations in 0..FaceDiscoveryContract.MAX_FACES_PER_IMAGE)
        require(frames in 0..FaceSourceFrames.MAX_FRAMES)
    }
}

/** One-photo budget, no continuous loop; WorkManager or user-controlled job may invoke again.
 * The private durable index is authoritative for progress and resume; NEVER hold image bytes here.
 */
private data class ScannedFaces(
    val boxes: List<UnknownFaceBox>,
    val vectors: List<FloatArray> = emptyList(),
    val embedderVersion: String? = null,
    /** Per box: null for a photo, the keyframe time for a video. */
    val frameMs: List<Long?> = boxes.map { null }
)

/** One face of one frame, before the per-source cap and ordering. */
private class FrameFace(val box: UnknownFaceBox, val vector: FloatArray?, val frameMs: Long?) {
    val area: Float get() = (box.right - box.left) * (box.bottom - box.top)
}

/**
 * VS-V1b: a source keeps at most MAX_FACES_PER_IMAGE observations, so review
 * ordinals and step results keep their bound. A photo over the cap already
 * failed closed in [FaceGeometry.normalize]. A video, whose keyframes are a
 * sample anyway, keeps its largest faces (the most recognisable), ties by
 * time; the kept faces are ordered by time, then geometry, deterministically.
 */
private object SourceFaces {
    private val geometry = compareBy<FrameFace>(
        { it.frameMs ?: -1L }, { it.box.top }, { it.box.left }, { it.box.bottom }, { it.box.right })

    fun select(faces: List<FrameFace>): List<FrameFace> {
        val cap = FaceDiscoveryContract.MAX_FACES_PER_IMAGE
        val kept = if (faces.size <= cap) faces
            else faces.sortedWith(compareByDescending<FrameFace> { it.area }.then(geometry)).take(cap)
        return kept.sortedWith(geometry)
    }
}

class FaceScanEngine(
    private val scopeEpoch: () -> String?,
    private val currentConditions: () -> FaceScanConditions,
    private val sourceRevisionCurrent: (FaceMediaKey) -> Boolean,
    private val index: FaceScanIndexPort,
    private val reader: FaceFrameReader,
    private val locator: OnDeviceFaceLocator,
    private val maxLongEdgePixels: Int = FaceDiscoveryContract.DEFAULT_DECODE_LONG_EDGE_PX
) {
    init { require(maxLongEdgePixels in 480..2048) }

    fun scanOne(expectedEpoch: String): FaceScanStepResult {
        if (expectedEpoch.isBlank() || !ready(expectedEpoch)) return FaceScanStepResult(FaceScanStep.HELD)
        // Atomic admission MUST precede SQLite claim and any image allocation;
        // an advisory canStart check alone races with concurrent workers.
        val reservation = try {
            locator.tryReserve()
        } catch (_: FaceInferenceStalled) {
            // A hung native Task may still retain its bitmap. Stop the worker
            // rather than polling indefinitely or spending image retry budget.
            return FaceScanStepResult(FaceScanStep.MODEL_UNAVAILABLE)
        } ?: return FaceScanStepResult(FaceScanStep.DEFERRED)
        try {
            return scanReserved(expectedEpoch, reservation)
        } finally {
            reservation.close()
        }
    }

    private fun scanReserved(
        expectedEpoch: String, reservation: FaceInferenceReservation
    ): FaceScanStepResult {
        val lease = index.claim(expectedEpoch) ?: return FaceScanStepResult(FaceScanStep.NO_PENDING)
        // Never open a content URI when the original lease is already stale.
        if (lease.permissionEpoch != expectedEpoch || !leaseCurrent(expectedEpoch, lease)) {
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.RELEASED_STALE)
        }
        // Closing the decoded frames must happen BEFORE marking a source processed.
        // A close() failure must never produce a success state with leaking resources.
        var decodedFrames = 0
        val found: ScannedFaces? = try {
            reader.openFrames(lease, maxLongEdgePixels).use { source ->
                decodedFrames = source.frames.size
                val embedding = reservation as? FaceEmbeddingReservation
                if (embedding != null) {
                    check(index is FaceScanEmbeddingIndexPort) {
                        "Embedding inference needs atomic embedding persistence"
                    }
                    check(embedding.detectorVersion == lease.detectorVersion) {
                        "YuNet detector version differs from catalog lease"
                    }
                } else {
                    check(index !is FaceScanEmbeddingIndexPort) {
                        "Embedding index requires an embedding-aware inference reservation"
                    }
                }
                val faces = ArrayList<FrameFace>()
                var current = true
                for (timed in source.frames) {
                    val frame = timed.frame
                    require(frame.widthPixels > 0 && frame.heightPixels > 0 &&
                        maxOf(frame.widthPixels, frame.heightPixels) <= maxLongEdgePixels) {
                        "decoder violated bounded-frame contract"
                    }
                    // Before EVERY frame: grant, source revision, opt-in, pause,
                    // battery and charging guards. A video stops mid-way when any
                    // of them changes. The surrounding catch releases on
                    // cancellation; do not call leaseCurrent here.
                    if (!stillCurrent(expectedEpoch, lease.media)) { current = false; break }
                    if (embedding != null) {
                        embedding.detectAndEmbed(frame).mapTo(faces) { detection ->
                            FrameFace(FaceGeometry.normalize(frame.widthPixels, frame.heightPixels,
                                listOf(detection.bounds)).single(), detection.vector, timed.frameMs)
                        }
                    } else {
                        FaceGeometry.normalize(frame.widthPixels, frame.heightPixels,
                            reservation.locate(frame)).mapTo(faces) {
                            FrameFace(it, null, timed.frameMs)
                        }
                    }
                }
                if (!current) null else {
                    val kept = SourceFaces.select(faces)
                    check(kept.size <= FaceDiscoveryContract.MAX_FACES_PER_IMAGE)
                    if (embedding != null) ScannedFaces(kept.map { it.box },
                        kept.map { it.vector!! }, embedding.embedderVersion, kept.map { it.frameMs })
                    else ScannedFaces(kept.map { it.box }, frameMs = kept.map { it.frameMs })
                }
            }
        } catch (cancelled: CancellationException) {
            index.release(lease)
            throw cancelled // honor WorkManager/coroutine cancellation after cleanup
        } catch (interrupted: InterruptedException) {
            index.release(lease)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: SecurityException) {
            // A MediaStore provider may revoke permission before our observer notices.
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.RELEASED_STALE)
        } catch (skipped: FaceSourceSkipped) {
            return handleSkip(expectedEpoch, lease, skipped.reason)
        } catch (_: FaceDispatchNotStarted) {
            // Detector was closed between atomic admission and process().
            // No Task started; release the photo lease without spending retries.
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.DEFERRED)
        } catch (_: FaceInferenceBusy) {
            // A previous asynchronous detector still holds its frame. This is
            // backpressure, not a corrupt photo or exhausted inference retry.
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.DEFERRED)
        } catch (_: FaceInferenceStalled) {
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.MODEL_UNAVAILABLE)
        } catch (_: FaceInferencePending) {
            // Timeout is NOT detector failure: the Task may still be using pixels.
            // Wait for callback/backoff; do not spend one of three photo retries.
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.DEFERRED)
        } catch (_: FaceScanIoFailure) {
            return handleDecodeFailure(expectedEpoch, lease)
        } catch (_: IOException) {
            return handleDecodeFailure(expectedEpoch, lease)
        } catch (_: RuntimeException) {
            // Detector internal failures and corrupt/oversized face boxes are NOT "0 faces".
            return handleDecodeFailure(expectedEpoch, lease)
        }
        if (found == null || !leaseCurrent(expectedEpoch, lease)) {
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.RELEASED_STALE)
        }
        // Durable transaction is the final authority on epoch, revision, model and lease.
        val completed = if (found.embedderVersion != null) {
            (index as FaceScanEmbeddingIndexPort).completeEmbeddingFrames(
                lease, found.boxes, found.vectors, found.embedderVersion, found.frameMs)
        } else index.completeFrames(lease, found.boxes, found.frameMs)
        return if (completed) FaceScanStepResult(FaceScanStep.INDEXED, found.boxes.size, decodedFrames)
        else FaceScanStepResult(FaceScanStep.RELEASED_STALE)
    }

    private fun handleSkip(epoch: String, lease: FaceScanLease, reason: String): FaceScanStepResult {
        if (!leaseCurrent(epoch, lease)) {
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.RELEASED_STALE)
        }
        return if (index.skip(lease, reason)) FaceScanStepResult(FaceScanStep.SKIPPED)
        else FaceScanStepResult(FaceScanStep.RELEASED_STALE)
    }

    private fun handleDecodeFailure(epoch: String, lease: FaceScanLease): FaceScanStepResult {
        if (!leaseCurrent(epoch, lease)) {
            index.release(lease)
            return FaceScanStepResult(FaceScanStep.RELEASED_STALE)
        }
        // X may reject the failure transaction after a regrant, source edit,
        // model switch or a superseding worker. Do not report a photo retry
        // when no failure was durably admitted for this exact lease.
        return if (index.fail(lease)) FaceScanStepResult(FaceScanStep.RETRYABLE_FAILURE)
        else FaceScanStepResult(FaceScanStep.RELEASED_STALE)
    }

    /** Preserve coroutine/worker cancellation even when it occurs during metadata checking,
     * including BEFORE the frame has been opened. No scan lease may be stranded.
     */
    private fun leaseCurrent(epoch: String, lease: FaceScanLease): Boolean = try {
        stillCurrent(epoch, lease.media)
    } catch (cancelled: CancellationException) {
        index.release(lease)
        throw cancelled
    } catch (interrupted: InterruptedException) {
        index.release(lease)
        Thread.currentThread().interrupt()
        throw interrupted
    }

    // MediaStore permission or metadata reads can fail with a stale grant *before* the
    // app's observer updates its epoch. Fail closed and let the caller refresh access.
    private fun stillCurrent(epoch: String, media: FaceMediaKey): Boolean = try {
        ready(epoch) && sourceRevisionCurrent(media)
    } catch (_: SecurityException) { false
    } catch (_: IOException) { false
    } catch (_: FaceScanIoFailure) { false }

    private fun ready(epoch: String): Boolean = scopeEpoch() == epoch && currentConditions().canRun()
}
