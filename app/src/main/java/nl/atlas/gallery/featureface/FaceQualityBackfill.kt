package nl.atlas.gallery.featureface

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min

/** Only NULL-quality observations with an existing source-revision-matched vector are candidates. */
data class FaceQualityBackfillFace(
    val uid: String, val box: UnknownFaceBox, val frameMs: Long?
)
data class FaceQualityBackfillSource(
    val media: FaceMediaKey,
    val detectorVersion: String,
    val embedderVersion: String,
    val faces: List<FaceQualityBackfillFace>
) {
    init {
        require(detectorVersion.isNotBlank() && embedderVersion.isNotBlank())
        require(faces.isNotEmpty() && faces.size <= FaceDiscoveryContract.MAX_FACES_PER_IMAGE)
    }
}
data class FaceQualityScoredDetection(val bounds: FacePixelBounds, val quality: FaceQuality)
data class FaceQualityBackfillResult(
    /** Null: no candidate was attempted. A held run never advances its cursor. */
    val attemptedMediaKey: String? = null,
    val written: Int = 0,
    val held: Boolean = false
)

/**
 * COVER-0b: one source per bounded, charging/idle worker step; no second
 * scanner and no SFace call. Existing YuNet finds landmarks on a fresh
 * permission-checked decode, whose crop feeds only the quality scorer.
 * A face is written only if its old box strongly matches that detection
 * in the exact photo/video frame; ambiguous/unmatched faces remain NULL.
 *
 * Guard before every frame and after inference and before the DB transaction;
 * SQLite's epoch/revision/NULL predicates are the final authority.
 */
class FaceQualityBackfillRunner(
    private val epoch: String,
    private val next: (String?) -> FaceQualityBackfillSource?,
    private val store: (FaceQualityBackfillSource, Map<String, FaceQuality>) -> Int,
    private val reader: FaceFrameReader,
    private val scoreOnly: (BoundedFaceFrame) -> List<FaceQualityScoredDetection>?,
    private val canContinue: () -> Boolean
) {
    fun run(afterMediaKey: String?): FaceQualityBackfillResult {
        if (!canContinue()) return FaceQualityBackfillResult(held = true)
        val source = next(afterMediaKey) ?: return FaceQualityBackfillResult()
        if (!canContinue()) return FaceQualityBackfillResult(held = true)
        val scanned = HashMap<String, FaceQuality>()
        val syntheticLease = FaceScanLease(source.media, source.detectorVersion, epoch, "quality-backfill")
        try {
            reader.openFrames(syntheticLease, FaceDiscoveryContract.DEFAULT_DECODE_LONG_EDGE_PX).use { frames ->
                for (timed in frames.frames) {
                    if (!canContinue()) return FaceQualityBackfillResult(held = true)
                    val detections = scoreOnly(timed.frame)
                        ?: return FaceQualityBackfillResult(held = true)
                    if (!canContinue()) return FaceQualityBackfillResult(held = true)
                    val remaining = source.faces.filter { it.frameMs == timed.frameMs }
                    val used = HashSet<Int>()
                    for (face in remaining) {
                        if (!canContinue()) return FaceQualityBackfillResult(held = true)
                        val best = detections.indices.filter { it !in used }
                            .map { it to overlap(face.box, detections[it].bounds,
                                timed.frame.widthPixels, timed.frame.heightPixels) }
                            .maxByOrNull { it.second }
                        if (best != null && best.second >= MIN_MATCH_IOU) {
                            used += best.first
                            scanned[face.uid] = detections[best.first].quality
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: SecurityException) {
            // Current permission may have changed. No row is written.
            return FaceQualityBackfillResult(held = true)
        } catch (_: IOException) {
            // Unreadable source: leave its old observations unscored.
            return FaceQualityBackfillResult(source.media.canonicalMediaKey)
        } catch (_: FaceSourceSkipped) {
            return FaceQualityBackfillResult(source.media.canonicalMediaKey)
        } catch (_: RuntimeException) {
            // A corrupt frame or detector error is not a score of zero.
            return FaceQualityBackfillResult(source.media.canonicalMediaKey)
        }
        if (!canContinue()) return FaceQualityBackfillResult(held = true)
        val written = if (scanned.isEmpty()) 0 else store(source, scanned)
        return FaceQualityBackfillResult(source.media.canonicalMediaKey, written)
    }

    private fun overlap(old: UnknownFaceBox, detected: FacePixelBounds, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val l = detected.left.toFloat().coerceIn(0f, width.toFloat()) / width
        val t = detected.top.toFloat().coerceIn(0f, height.toFloat()) / height
        val r = detected.right.toFloat().coerceIn(0f, width.toFloat()) / width
        val b = detected.bottom.toFloat().coerceIn(0f, height.toFloat()) / height
        if (l >= r || t >= b) return 0f
        val intersection = max(0f, min(old.right, r) - max(old.left, l)) *
            max(0f, min(old.bottom, b) - max(old.top, t))
        val originalArea = (old.right - old.left) * (old.bottom - old.top)
        val detectedArea = (r - l) * (b - t)
        return intersection / (originalArea + detectedArea - intersection).coerceAtLeast(0.000001f)
    }

    companion object {
        /** Deliberately high: never transfer quality between adjacent faces. */
        const val MIN_MATCH_IOU = 0.70f
    }
}
