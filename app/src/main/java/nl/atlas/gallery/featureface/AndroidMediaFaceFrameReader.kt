package nl.atlas.gallery.featureface

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import nl.atlas.gallery.featuremedia.video.VideoKeyframeResult
import nl.atlas.gallery.featuremedia.video.VideoKeyframeSampler
import nl.atlas.gallery.featuremedia.video.VideoMediaRevision
import nl.atlas.gallery.featuremedia.video.VideoSkipReason

/**
 * An app-private, short-lived frame. Never put this Bitmap in a disk cache,
 * log its contents, share it with a network client or expose it to People IDs.
 * ML Kit must finish consuming the bitmap BEFORE the caller closes the frame.
 */
class AndroidBitmapFaceFrame internal constructor(private val decoded: Bitmap) : BoundedFaceFrame {
    private val lock = Any()
    private var closed = false
    private var outstandingDetectors = 0
    override val widthPixels: Int get() = decoded.width
    override val heightPixels: Int get() = decoded.height
    val bitmap: Bitmap get() = synchronized(lock) {
        check(!closed && !decoded.isRecycled) { "Face frame already released" }
        decoded
    }

    /**
     * Protect asynchronous ML Kit processing from a race with worker cancellation.
     * Acquire BEFORE process(bitmap); register a completion listener that closes
     * this token for every success/error/cancel outcome. On process() setup failure,
     * close the token in the caller's catch/finally. Never re-open a closed frame.
     * This lease holds pixels only; it does NOT grant any media access or identity.
     */
    fun retainForAsyncInference(): Closeable {
        synchronized(lock) {
            check(!closed && !decoded.isRecycled) { "Face frame already released" }
            outstandingDetectors++
        }
        val released = AtomicBoolean(false)
        return Closeable {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    check(outstandingDetectors > 0) { "Face inference lease underflow" }
                    outstandingDetectors--
                    recycleIfUnused()
                }
            }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        recycleIfUnused()
    }

    private fun recycleIfUnused() {
        // Called with lock held. The frame is logically closed at once; delayed
        // detector completion alone releases its last bitmap reference.
        if (closed && outstandingDetectors == 0 && !decoded.isRecycled) decoded.recycle()
    }
}

/**
 * Real MediaStore decoder adapter: no new dependency or additional permission.
 * The integrator supplies a *live* stable-media-key -> MediaStore content URI
 * resolver. It must reject missing/changed/revoked media before this callback.
 *
 * MUST run off main; do not invoke from a Compose callback. This adapter never
 * queries a filesystem path, reads V4, uses an Internet client or schedules jobs.
 * ImageDecoder honors supported EXIF orientation on decode; verify pixel-box
 * orientation on emulator fixtures before allowing review portraits.
 */
/**
 * VS-V1b (#468): what a video scan needs from the live grant. [source] is the
 * directory's gated key+revision lookup (FaceAuthorizedMediaDirectory
 * .authorizedSource): it throws SecurityException once the video is no
 * longer visible at that revision. [sampler] is VS-V1a's bounded sampler,
 * whose aggregate bitmap cap is the face decode budget.
 */
class AuthorizedVideoFrames(
    val sampler: VideoKeyframeSampler,
    val source: (FaceMediaKey) -> FaceVisibleMedia
)

class AndroidMediaFaceFrameReader(
    private val contentResolver: ContentResolver,
    private val authorizedMediaUri: (FaceMediaKey) -> Uri,
    private val maxEstimatedBitmapBytes: Long = FaceDecodeBudget.DEFAULT_BITMAP_BUDGET_BYTES,
    /** Null: this reader is photo-only and skips a video with VIDEO_UNSUPPORTED. */
    private val videos: AuthorizedVideoFrames? = null
) : FaceFrameReader {
    init {
        require(maxEstimatedBitmapBytes in 1024L..(128L * 1024 * 1024))
    }

    override fun open(lease: FaceScanLease, maxLongEdgePixels: Int): BoundedFaceFrame {
        val uri = authorizedUri(lease, maxLongEdgePixels)
        require(!isVideoUri(uri)) { "A video is scanned through its keyframes, not one frame" }
        return decodePhoto(uri, maxLongEdgePixels)
    }

    /**
     * A photo is one still frame. A video becomes its bounded VS-V1a
     * keyframes: a revoked or changed source is a SecurityException (stale,
     * no retry spent), any other sampler skip is a typed [FaceSourceSkipped].
     */
    override fun openFrames(lease: FaceScanLease, maxLongEdgePixels: Int): FaceSourceFrames {
        val uri = authorizedUri(lease, maxLongEdgePixels)
        if (!isVideoUri(uri)) return FaceSourceFrames.still(decodePhoto(uri, maxLongEdgePixels))
        val video = videos ?: throw FaceSourceSkipped(VIDEO_UNSUPPORTED)
        val row = video.source(lease.media)
        if (!row.isVideo || Uri.parse(row.uri) != uri) {
            throw SecurityException("Authorized video source changed")
        }
        val sampled = video.sampler.sample(
            uri, row.durationMs,
            VideoMediaRevision(row.providerVersion.orEmpty(), row.generationModified),
            isStillAuthorized = {
                try { video.source(lease.media).uri == row.uri }
                catch (_: SecurityException) { false }
            },
            checkActive = {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
            }
        )
        val batch = when (sampled) {
            is VideoKeyframeResult.Skipped ->
                if (sampled.reason == VideoSkipReason.STALE_OR_REVOKED) {
                    throw SecurityException("Video source revoked while sampling")
                } else throw FaceSourceSkipped(sampled.reason.name)
            is VideoKeyframeResult.Sampled -> sampled.batch
        }
        try {
            if (batch.frames.any { maxOf(it.bitmap.width, it.bitmap.height) > maxLongEdgePixels }) {
                throw FaceSourceSkipped(VideoSkipReason.DECODE_BUDGET_EXCEEDED.name)
            }
            // Each frame now owns its bitmap; closing the set recycles them all.
            return FaceSourceFrames(batch.frames.map {
                TimedFaceFrame(it.frameMs, AndroidBitmapFaceFrame(it.bitmap))
            })
        } catch (failure: Throwable) {
            batch.close()
            throw failure
        }
    }

    private fun authorizedUri(lease: FaceScanLease, maxLongEdgePixels: Int): Uri {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Face decode must never run on main" }
        require(maxLongEdgePixels in 480..2048)
        val uri = authorizedMediaUri(lease.media)
        require(uri.scheme == "content" && uri.authority == "media") {
            "Only currently authorized MediaStore URIs are supported"
        }
        return uri
    }

    private fun isVideoUri(uri: Uri): Boolean = uri.pathSegments.getOrNull(1) == "video"

    private fun decodePhoto(uri: Uri, maxLongEdgePixels: Int): BoundedFaceFrame {
        // No file:// fallback, no opening originals by absolute path.
        val source = ImageDecoder.createSource(contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val plan = FaceDecodeBudget.plan(
                info.size.width, info.size.height,
                maxLongEdge = maxLongEdgePixels,
                maxBitmapBytes = maxEstimatedBitmapBytes,
                estimatedBytesPerPixel = 8 // worst case for high-precision output
            )
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(plan.targetWidth, plan.targetHeight)
        }
        // setTargetSize is not a hard peak-RSS limit. Validate actual decoded
        // allocation, discard nonconforming results, and test true RSS on Android.
        if (bitmap.width !in 1..maxLongEdgePixels ||
            bitmap.height !in 1..maxLongEdgePixels ||
            bitmap.allocationByteCount.toLong() > maxEstimatedBitmapBytes) {
            bitmap.recycle()
            throw IOException("Decoded face frame exceeds the local budget")
        }
        return AndroidBitmapFaceFrame(bitmap)
    }

    companion object {
        /** A video reached a reader built without [AuthorizedVideoFrames]. */
        const val VIDEO_UNSUPPORTED = "VIDEO_UNSUPPORTED"
    }
}
