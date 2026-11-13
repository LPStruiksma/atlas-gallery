package nl.atlas.gallery.featuresearch.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featureface.durableFaceKeyOrNull
import nl.atlas.gallery.featureface.toFaceVisibleMedia
import nl.atlas.gallery.featurepersistence.IndexedMedia
import nl.atlas.gallery.featuresearch.face.FaceSearchPort
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder
import nl.atlas.gallery.featurevisual.onnx.SiglipModel
import nl.atlas.gallery.featurevisual.pose.OnnxPoseEstimator
import nl.atlas.gallery.featurevisual.search.VisualPoseInput
import nl.atlas.gallery.featurevisual.search.VisualVectorSource
import nl.atlas.gallery.featurevisual.store.VisualMedia

/**
 * VS-S2b (#468): the app's [FindSimilarPort]. The scope and every URI come
 * from the face-search port ([faces]), because the visual index catalogues
 * the same durable keys under the same grant (VS-S1c); [visualSource] hands
 * out the published visual index of a face scope (null while it is off).
 *
 * The SigLIP embedder (~100 MB) and the pose models open on first use, on
 * the worker thread, and close in [release] (the route's close). Pixels are
 * decoded from already-authorized URIs only and recycled after each call.
 */
class AppFindSimilarPort(
    context: Context,
    private val faces: FaceSearchPort,
    private val visualSource: (faceEpoch: String) -> VisualVectorSource?
) : FindSimilarPort {
    private val app = context.applicationContext
    private val lock = Any()
    private var embedder: SiglipImageEmbedder? = null
    private var pose: OnnxPoseEstimator? = null

    override val modelVersion: String get() = SiglipModel.MODEL_VERSION

    override fun liveScope(): String? = faces.liveEpoch()

    override fun scopeCurrent(scope: String): Boolean = faces.scopeCurrent(scope)

    override fun source(scope: String): VisualVectorSource? =
        if (!faces.scopeCurrent(scope)) null else visualSource(scope)

    override fun media(scope: String, record: MediaRecord): VisualMedia? {
        // Same projection as the catalogue publication (rememberPeopleDiscovery).
        val key = try { record.toFaceVisibleMedia(editorRevision = 0L).durableFaceKeyOrNull() }
            catch (_: IllegalArgumentException) { null }
            catch (_: IllegalStateException) { null } ?: return null
        val uri = faces.authorizedUri(scope, IndexedMedia(key.canonicalMediaKey, key.sourceRevision))
        return VisualMedia(key.canonicalMediaKey, key.sourceRevision).takeIf { uri == record.uri }
    }

    override fun authorizedUri(scope: String, media: VisualMedia): String? =
        faces.authorizedUri(scope, IndexedMedia(media.key, media.revision))

    override fun embed(reference: FindSimilarReference): FloatArray? {
        val bitmap = decode(reference.record.uri, reference.record.isVideo, reference.frameMs)
            ?: return null
        try {
            return synchronized(lock) { embedder ?: SiglipImageEmbedder.open(app).also { embedder = it } }
                .embed(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override fun referencePose(reference: FindSimilarReference): FloatArray? =
        describe(reference.record.uri, reference.record.isVideo, reference.frameMs)

    override fun candidatePose(media: VisualMedia, uri: String): FloatArray? =
        describe(uri, media.frameMs != null, media.frameMs)

    override fun release() {
        val open = synchronized(lock) {
            listOfNotNull<AutoCloseable>(embedder, pose).also { embedder = null; pose = null }
        }
        open.forEach { runCatching { it.close() } }
    }

    private fun describe(uri: String, video: Boolean, frameMs: Long?): FloatArray? {
        val bitmap = decode(uri, video, frameMs) ?: return null
        try {
            val estimator = synchronized(lock) {
                pose ?: OnnxPoseEstimator.open(app).also { pose = it }
            }
            return VisualPoseInput.describe(estimator, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** A fresh, upright bitmap of a photo or of a video frame; null when unreadable. */
    private fun decode(uri: String, video: Boolean, frameMs: Long?): Bitmap? {
        val parsed = Uri.parse(uri)
        if (!video) {
            return try {
                SiglipImageEmbedder.decodeOriented(ImageDecoder.createSource(app.contentResolver, parsed))
            } catch (revoked: SecurityException) { throw revoked }
            catch (_: Exception) { null }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(app, parsed)
            val frame = retriever.getFrameAtTime((frameMs ?: 0L) * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            if (frame.config == Bitmap.Config.ARGB_8888) frame
            else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
        } catch (revoked: SecurityException) { throw revoked }
        catch (_: Exception) { null }
        finally { runCatching { retriever.release() } }
    }

    companion object {
        /** A grid thumbnail of an authorized photo or video; null when unreadable. */
        fun thumbnail(context: Context, uri: String): Bitmap? = try {
            context.contentResolver.loadThumbnail(Uri.parse(uri), Size(256, 256), null)
        } catch (_: Exception) { null }
    }
}
