package nl.atlas.gallery.featuresearch.face

import android.graphics.Bitmap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import nl.atlas.gallery.FaceCropGeometry
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.BoundedFaceFrame
import nl.atlas.gallery.featureface.FaceDecodeBudget
import nl.atlas.gallery.featureface.FaceDiscoveryContract
import nl.atlas.gallery.featureface.FaceEmbeddingReservation
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceGeometry
import nl.atlas.gallery.featureface.FaceScanLease
import nl.atlas.gallery.featureface.OnDeviceFaceLocator
import nl.atlas.gallery.featureface.UnknownFaceBox
import nl.atlas.gallery.featurepersistence.IndexedFaceBox

/**
 * VS-F2a: query capture ONLY. The host passes the SAME detector/embedder
 * [inference] and authorized bounded [reader] used by the People scan. Never
 * instantiate a model here, persist vectors, or start a second inference path.
 *
 * [capture] always decodes, detects, embeds and crops on IO, with the pipeline's
 * exclusive reservation acquired BEFORE any image allocation. Results are
 * fenced both on publication and on selection against the live source grant.
 * Multiple concurrent requests cannot publish an older selection.
 */
class QueryFaceCapture(
    private val reader: FaceFrameReader,
    private val inference: OnDeviceFaceLocator,
    private val sourceCurrent: (QueryFaceSource) -> Boolean,
    private val crop: (BoundedFaceFrame, UnknownFaceBox) -> Bitmap? = ::queryFaceCrop,
    /**
     * How long a capture waits for the shared reservation before it shows
     * Busy. A background scan holds it per photo and gives it up between
     * photos and at the end of its bounded batch (8 photos / 2 s), and a
     * just-replaced capture finishes its own photo; neither is worth a
     * "Try again" for the user.
     */
    private val busyWaitMs: Long = BUSY_WAIT_MS
) {
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow<QueryFaceCaptureState>(QueryFaceCaptureState.Idle)
    val state: StateFlow<QueryFaceCaptureState> get() = mutableState

    suspend fun capture(source: QueryFaceSource) {
        val request = generation.incrementAndGet()
        mutableState.value = QueryFaceCaptureState.Loading(source)
        val outcome = try {
            val deadline = System.nanoTime() + busyWaitMs * 1_000_000L
            var result = withContext(Dispatchers.IO) { captureOnWorker(source) }
            // Never a second detector: wait (cancellably) for the one we share.
            while (result is QueryFaceCaptureState.Busy && generation.get() == request &&
                System.nanoTime() < deadline) {
                delay(BUSY_POLL_MS)
                result = withContext(Dispatchers.IO) { captureOnWorker(source) }
            }
            result
        } catch (cancelled: CancellationException) {
            if (generation.get() == request) mutableState.value = QueryFaceCaptureState.Idle
            throw cancelled
        } catch (_: SecurityException) {
            QueryFaceCaptureState.Unavailable(source)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (generation.get() == request) mutableState.value = QueryFaceCaptureState.Failure(source)
            return
        } catch (_: Exception) {
            // Do not turn corrupt images, broken models or invalid vectors into "no faces".
            QueryFaceCaptureState.Failure(source)
        }
        if (generation.get() == request) {
            mutableState.value = if (permitted(source)) outcome
                else QueryFaceCaptureState.Unavailable(source)
        }
    }

    fun choose(index: Int): QueryFace? {
        val ready = mutableState.value as? QueryFaceCaptureState.Ready ?: return null
        if (!permitted(ready.source)) {
            generation.incrementAndGet()
            mutableState.value = QueryFaceCaptureState.Unavailable(ready.source)
            return null
        }
        return ready.faces.getOrNull(index)?.query
    }

    /** A closed picker cannot publish a late IO result. */
    fun clear() {
        generation.incrementAndGet()
        mutableState.value = QueryFaceCaptureState.Idle
    }

    private fun permitted(source: QueryFaceSource): Boolean =
        try { sourceCurrent(source) } catch (_: SecurityException) { false }

    private fun captureOnWorker(source: QueryFaceSource): QueryFaceCaptureState {
        if (!permitted(source)) return QueryFaceCaptureState.Unavailable(source)
        // In particular, do NOT create a second detector or embedder when busy.
        val reservation = inference.tryReserve() ?: return QueryFaceCaptureState.Busy(source)
        reservation.use { acquired ->
            val embedding = acquired as? FaceEmbeddingReservation
                ?: return QueryFaceCaptureState.Failure(source)
            val lease = FaceScanLease(source.media, embedding.detectorVersion,
                source.permissionEpoch, "query-face-ephemeral")
            reader.open(lease, FaceDecodeBudget.DEFAULT_LONG_EDGE).use { frame ->
                require(frame.widthPixels > 0 && frame.heightPixels > 0 &&
                    maxOf(frame.widthPixels, frame.heightPixels) <= FaceDecodeBudget.DEFAULT_LONG_EDGE) {
                    "Query decoder broke the face decode budget"
                }
                if (!permitted(source)) return QueryFaceCaptureState.Unavailable(source)
                val detected = embedding.detectAndEmbed(frame)
                require(detected.size <= FaceDiscoveryContract.MAX_FACES_PER_IMAGE)
                val faces = detected.map { detection ->
                    val box = FaceGeometry.normalize(frame.widthPixels, frame.heightPixels,
                        listOf(detection.bounds)).single()
                    QueryFaceChoice(
                        QueryFace(detection.vector, embedding.embedderVersion, box),
                        crop(frame, box)
                    )
                }.sortedWith(compareBy<QueryFaceChoice>(
                    { it.query.sourceBox.top }, { it.query.sourceBox.left },
                    { it.query.sourceBox.bottom }, { it.query.sourceBox.right }))
                if (!permitted(source)) return QueryFaceCaptureState.Unavailable(source)
                return if (faces.isEmpty()) QueryFaceCaptureState.Empty(source)
                    else QueryFaceCaptureState.Ready(source, faces)
            }
        }
    }
}

/** Longer than one bounded scan batch (2 s budget plus its last photo). */
internal const val BUSY_WAIT_MS = 4_000L
private const val BUSY_POLL_MS = 50L

/**
 * Reuse the review UI's EXIF-oriented, padded face-crop geometry, but crop the
 * SAME <=960px decoded frame already used for YuNet/SFace. No second decode.
 * Limit each in-memory preview to 192px and at most 32 faces per capture.
 */
internal fun queryFaceCrop(frame: BoundedFaceFrame, box: UnknownFaceBox): Bitmap? {
    val bitmap = (frame as? AndroidBitmapFaceFrame)?.bitmap ?: return null
    val plan = FaceCropGeometry.plan(bitmap.width, bitmap.height,
        IndexedFaceBox(box.left, box.top, box.right, box.bottom),
        targetEdge = maxOf(bitmap.width, bitmap.height),
        maxDecodeEdge = maxOf(bitmap.width, bitmap.height)) ?: return null
    val region = Bitmap.createBitmap(bitmap, plan.left, plan.top,
        plan.right - plan.left, plan.bottom - plan.top)
    // createBitmap can return its source for full-frame crops. Keep UI pixels
    // independent of the owned frame, which will be recycled on close.
    val crop = if (region === bitmap) region.copy(Bitmap.Config.ARGB_8888, false) else region
    val edge = FaceCropGeometry.TARGET_EDGE
    val scale = minOf(1f, edge.toFloat() / maxOf(crop.width, crop.height))
    if (scale == 1f) return crop
    val preview = try {
        Bitmap.createScaledBitmap(crop,
            (crop.width * scale).roundToInt().coerceAtLeast(1),
            (crop.height * scale).roundToInt().coerceAtLeast(1), true)
    } finally {
        crop.recycle()
    }
    return preview
}
