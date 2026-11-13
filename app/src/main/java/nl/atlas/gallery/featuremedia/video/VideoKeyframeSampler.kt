package nl.atlas.gallery.featuremedia.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.sqrt
import nl.atlas.gallery.featureface.FaceDecodeBudget

/** Metadata supplied from the current, permission-visible MediaStore inventory. */
data class VideoMediaRevision(val providerVersion: String, val generationModified: Long) {
    fun isUsable(): Boolean =
        providerVersion.isNotBlank() && providerVersion.length <= 256 &&
            '\u0000' !in providerVersion && generationModified > 0L
}

data class VideoKeyframe(val frameMs: Long, val bitmap: Bitmap)

/**
 * Owns every returned Bitmap. Use the batch in a .use { } block, or close it
 * after synchronous inference. Never cache its frames beyond the live grant.
 */
class VideoKeyframeBatch internal constructor(
    val revision: VideoMediaRevision,
    val frames: List<VideoKeyframe>
) : Closeable {
    override fun close() {
        for (frame in frames) if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }
}

enum class VideoSkipReason {
    ZERO_OR_INVALID_DURATION,
    INVALID_SOURCE,
    STALE_OR_REVOKED,
    UNREADABLE_VIDEO,
    NO_DECODABLE_FRAMES,
    DECODE_BUDGET_EXCEEDED
}

sealed interface VideoKeyframeResult {
    data class Sampled(val batch: VideoKeyframeBatch) : VideoKeyframeResult
    data class Skipped(val reason: VideoSkipReason) : VideoKeyframeResult
}

/**
 * Blocking, unwired MediaStore-only sampler. Call on an IO worker.
 *
 * The caller MUST provide a fresh authorization/revision check and an active
 * cancellation check. The source URI and revision are not permissions. No
 * background job, file path, network client or persistent index is opened.
 * Corrupt/DRM/non-video sources become typed skips; cancellation propagates.
 *
 * The existing 8 MiB face decode budget is used as an aggregate bitmap cap.
 * For long clips the per-frame target shrinks to retain coverage without
 * retaining 60 full-resolution previews in memory. A later indexer should
 * consume and close the batch promptly rather than cache the bitmaps.
 */
class VideoKeyframeSampler(
    private val context: Context,
    private val maxRetainedBitmapBytes: Long = FaceDecodeBudget.DEFAULT_BITMAP_BUDGET_BYTES
) {
    init {
        require(maxRetainedBitmapBytes in 1024L..(128L * 1024L * 1024L))
    }

    fun sample(
        uri: Uri,
        durationMs: Long,
        revision: VideoMediaRevision,
        isStillAuthorized: () -> Boolean,
        checkActive: () -> Unit
    ): VideoKeyframeResult {
        if (durationMs <= 0L || durationMs > Long.MAX_VALUE / 1000L) {
            return VideoKeyframeResult.Skipped(VideoSkipReason.ZERO_OR_INVALID_DURATION)
        }
        if (!isMediaStoreVideoUri(uri) || !revision.isUsable()) {
            return VideoKeyframeResult.Skipped(VideoSkipReason.INVALID_SOURCE)
        }
        val timestamps = VideoKeyframePlan.candidateTimes(durationMs)
        if (timestamps.isEmpty()) {
            return VideoKeyframeResult.Skipped(VideoSkipReason.ZERO_OR_INVALID_DURATION)
        }
        val kept = ArrayList<VideoKeyframe>(timestamps.size)
        var retainedBytes = 0L
        var transferred = false
        var retriever: MediaMetadataRetriever? = null
        try {
            fun assertCurrent() {
                checkActive()
                if (!isStillAuthorized()) throw RevokedVideoSource()
            }
            assertCurrent()
            retriever = MediaMetadataRetriever()
            // Only the caller's currently authorized content URI, never a path.
            retriever.setDataSource(context, uri)
            assertCurrent()
            val actualDuration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            if (actualDuration <= 0L || width <= 0 || height <= 0) {
                return VideoKeyframeResult.Skipped(VideoSkipReason.UNREADABLE_VIDEO)
            }

            // Reserve worst-case 8 bytes per pixel for every planned frame,
            // including 16-bit/channel output, to avoid a hidden 60x RSS spike.
            val worstCasePixels = maxRetainedBitmapBytes / timestamps.size / 8L
            val cappedEdge = min(
                VideoKeyframePlan.MAX_LONG_EDGE_PX,
                sqrt(worstCasePixels.toDouble()).toInt().coerceAtLeast(1)
            )
            val basePlan = FaceDecodeBudget.plan(
                width, height,
                maxLongEdge = VideoKeyframePlan.MAX_LONG_EDGE_PX,
                maxBitmapBytes = maxRetainedBitmapBytes,
                estimatedBytesPerPixel = 8
            )
            val targetEdge = min(cappedEdge,
                maxOf(basePlan.targetWidth, basePlan.targetHeight))
            val scale = min(1.0, targetEdge.toDouble() / maxOf(width, height))
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)

            var lastScene: List<Int>? = null
            for (timestamp in timestamps) {
                assertCurrent()
                val bitmap = retriever.getScaledFrameAtTime(
                    timestamp * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight
                ) ?: continue
                // A provider may ignore the requested size or return HDR. A
                // failed bound is a skip, not a successful oversized frame.
                val bytes = bitmap.allocationByteCount.toLong()
                if (bitmap.width <= 0 || bitmap.height <= 0 ||
                    maxOf(bitmap.width, bitmap.height) > targetEdge ||
                    bytes <= 0L || bytes > maxRetainedBitmapBytes - retainedBytes) {
                    bitmap.recycle()
                    return VideoKeyframeResult.Skipped(VideoSkipReason.DECODE_BUDGET_EXCEEDED)
                }
                try {
                    assertCurrent()
                    val histogram = lumaHistogram(bitmap)
                    if (VideoKeyframePlan.isSceneChange(lastScene, histogram)) {
                        kept.add(VideoKeyframe(timestamp, bitmap))
                        retainedBytes += bytes
                        lastScene = histogram
                    } else {
                        bitmap.recycle()
                    }
                } catch (failure: Throwable) {
                    if (!bitmap.isRecycled && kept.none { it.bitmap === bitmap }) {
                        bitmap.recycle()
                    }
                    throw failure
                }
            }
            assertCurrent()
            if (kept.isEmpty()) {
                return VideoKeyframeResult.Skipped(VideoSkipReason.NO_DECODABLE_FRAMES)
            }
            transferred = true
            return VideoKeyframeResult.Sampled(VideoKeyframeBatch(revision, kept.toList()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: RevokedVideoSource) {
            return VideoKeyframeResult.Skipped(VideoSkipReason.STALE_OR_REVOKED)
        } catch (_: SecurityException) {
            return VideoKeyframeResult.Skipped(VideoSkipReason.STALE_OR_REVOKED)
        } catch (_: IOException) {
            return VideoKeyframeResult.Skipped(VideoSkipReason.UNREADABLE_VIDEO)
        } catch (_: RuntimeException) {
            // MediaMetadataRetriever codecs throw for corrupt/DRM bitstreams.
            return VideoKeyframeResult.Skipped(VideoSkipReason.UNREADABLE_VIDEO)
        } finally {
            try {
                retriever?.release()
            } catch (_: RuntimeException) {
                // The decoder may already have failed; no success is inferred.
            }
            if (!transferred) {
                for (frame in kept) {
                    if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                }
            }
        }
    }

    private class RevokedVideoSource : Exception()

    private fun isMediaStoreVideoUri(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority != "media" ||
            uri.encodedQuery != null || uri.encodedFragment != null) return false
        val segments = uri.pathSegments
        return segments.size == 4 &&
            segments[0].isNotBlank() && segments[0] != "external" &&
            segments[0] != "unknown" &&
            segments[1] == "video" && segments[2] == "media" &&
            (segments[3].toLongOrNull() ?: 0L) > 0L
    }

    /** 16x16 grid avoids another bitmap allocation. No original pixels persist. */
    private fun lumaHistogram(bitmap: Bitmap): List<Int> {
        val histogram = IntArray(VideoKeyframePlan.LUMA_BINS)
        for (y in 0 until 16) {
            val sourceY = (y.toLong() * bitmap.height / 16L).toInt()
            for (x in 0 until 16) {
                val sourceX = (x.toLong() * bitmap.width / 16L).toInt()
                val color = bitmap.getPixel(sourceX, sourceY)
                val luminance = (77 * Color.red(color) +
                    150 * Color.green(color) + 29 * Color.blue(color)) shr 8
                histogram[luminance ushr 4]++
            }
        }
        return histogram.toList()
    }
}
