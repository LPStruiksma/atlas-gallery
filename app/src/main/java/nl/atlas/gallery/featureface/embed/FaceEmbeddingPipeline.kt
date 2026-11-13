package nl.atlas.gallery.featureface.embed

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.floor
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.BoundedFaceFrame
import nl.atlas.gallery.featureface.FaceEmbeddedDetection
import nl.atlas.gallery.featureface.FaceEmbeddingReservation
import nl.atlas.gallery.featureface.FaceInferenceReservation
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.FacePixelBounds
import nl.atlas.gallery.featureface.FaceScanEmbeddingIndexPort
import nl.atlas.gallery.featureface.FaceScanIndexPort
import nl.atlas.gallery.featureface.FaceScanLease
import nl.atlas.gallery.featureface.NativeFaceIndexPort
import nl.atlas.gallery.featureface.OnDeviceFaceLocator
import nl.atlas.gallery.featureface.UnknownFaceBox
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedFaceLease
import nl.atlas.gallery.featurepersistence.IndexedMedia

/**
 * One synchronous, exclusive inference slot. YuNet and SFace see the SAME bounded,
 * EXIF-oriented bitmap. No pixels leave the process. The caller owns frame cleanup.
 *
 * Tests can inject a synthetic inference function; production must use [forOnnx].
 * The scan engine consumes the paired detections through [FaceEmbeddingReservation],
 * never the legacy box-only locator method.
 */
class FaceEmbeddingPipeline(
    private val detectorVersion: String,
    private val embedderVersion: String,
    private val infer: (BoundedFaceFrame) -> List<FaceEmbeddedDetection>
) : OnDeviceFaceLocator {
    private val slot = ReentrantLock()

    init {
        require(detectorVersion.isNotBlank() && embedderVersion.isNotBlank())
    }

    override fun locate(frame: BoundedFaceFrame): List<FacePixelBounds> =
        error("Embedding pipeline must use its exclusive detectAndEmbed reservation")

    override fun canStart(): Boolean = !slot.isLocked

    override fun tryReserve(): FaceInferenceReservation? {
        if (!slot.tryLock()) return null
        val closed = AtomicBoolean(false)
        return object : FaceEmbeddingReservation {
            override val detectorVersion: String get() = this@FaceEmbeddingPipeline.detectorVersion
            override val embedderVersion: String get() = this@FaceEmbeddingPipeline.embedderVersion

            override fun detectAndEmbed(frame: BoundedFaceFrame): List<FaceEmbeddedDetection> {
                check(!closed.get()) { "Inference reservation already closed" }
                return infer(frame).also { detections ->
                    require(detections.size <= 32) { "Face cap exceeded" }
                    detections.forEach { SFaceModel.l2Normalize(it.vector) }
                }
            }

            override fun locate(frame: BoundedFaceFrame): List<FacePixelBounds> =
                error("Cannot drop embeddings from an embedding reservation")

            override fun close() {
                if (closed.compareAndSet(false, true)) slot.unlock()
            }
        }
    }

    companion object {
        fun forOnnx(detector: OnnxFaceDetector, embedder: OnnxFaceEmbedder): FaceEmbeddingPipeline {
            require(embedder.embedderVersion == SFaceModel.EMBEDDER_VERSION)
            return FaceEmbeddingPipeline(detector.detectorVersion, embedder.embedderVersion) { frame ->
                val bitmap = (frame as? AndroidBitmapFaceFrame)?.bitmap
                    ?: throw IllegalArgumentException("SFace needs a private bounded bitmap")
                detector.detectFaces(bitmap).map { face ->
                    val embedding = embedder.embed(bitmap, face)
                    FaceEmbeddedDetection(FacePixelBounds(
                        floor(face.left.toDouble()).toInt(), floor(face.top.toDouble()).toInt(),
                        ceil(face.right.toDouble()).toInt(), ceil(face.bottom.toDouble()).toInt()
                    ), embedding.vector)
                }
            }
        }
    }
}

/**
 * In-place adapter for the existing FaceScanEngine contract. Its old box-only
 * completion is deliberately disabled: all-or-nothing persistence is mandatory.
 */
class PersistedFaceEmbeddingIndexPort(private val store: FaceIndexStore) : FaceScanEmbeddingIndexPort {
    private val native = NativeFaceIndexPort(store)

    override fun claim(permissionEpoch: String): FaceScanLease? = native.claim(permissionEpoch)
    override fun release(lease: FaceScanLease): Boolean = native.release(lease)
    override fun fail(lease: FaceScanLease): Boolean = native.fail(lease)
    override fun skip(lease: FaceScanLease, reason: String): Boolean = native.skip(lease, reason)

    override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>): Boolean =
        error("SFace embedding pipeline requires atomic vector completion")

    override fun completeEmbeddings(
        lease: FaceScanLease, observations: List<UnknownFaceBox>,
        vectors: List<FloatArray>, embedderVersion: String
    ): Boolean = completeEmbeddingFrames(lease, observations, vectors, embedderVersion,
        observations.map { null })

    /** VS-V1b: video faces carry their keyframe time into the same atomic completion. */
    override fun completeEmbeddingFrames(
        lease: FaceScanLease, observations: List<UnknownFaceBox>,
        vectors: List<FloatArray>, embedderVersion: String, frameMs: List<Long?>
    ): Boolean = store.completeEmbeddings(
        IndexedFaceLease(
            IndexedMedia(lease.media.canonicalMediaKey, lease.media.sourceRevision),
            lease.detectorVersion, lease.permissionEpoch, lease.opaqueToken
        ),
        observations.map { IndexedFaceBox(it.left, it.top, it.right, it.bottom) },
        vectors, embedderVersion, frameMs
    )
}
