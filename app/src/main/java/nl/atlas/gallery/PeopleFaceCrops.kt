package nl.atlas.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featurepersistence.IndexedFaceBox

/**
 * Decode target and crop rectangle for one review face crop (#438), in the
 * decoder's output pixels. [decodeWidth] x [decodeHeight] is the whole image,
 * downsampled; the crop rectangle lies inside it.
 */
internal data class FaceCropPlan(
    val decodeWidth: Int, val decodeHeight: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int
)

/**
 * Pure geometry for review face crops.
 *
 * Orientation: a stored box is normalized (0..1) against the frame the face
 * scanner saw, and the scanner decodes with [ImageDecoder], which applies the
 * EXIF orientation and reports the oriented size. The crop decoder uses the
 * same ImageDecoder, so [plan] takes that oriented size and the box needs no
 * rotation. Never pass MediaStore's stored (unrotated) width/height here.
 */
internal object FaceCropGeometry {
    /** Margin on each side, as a fraction of the box's own width/height. */
    const val MARGIN = 0.25f
    /** Long edge of a crop, in pixels: ~3x density for a 56 dp preview. */
    const val TARGET_EDGE = 192
    /** Largest whole-image decode, so a tiny face in a huge photo stays cheap. */
    const val MAX_DECODE_EDGE = 1536

    fun plan(
        orientedWidth: Int,
        orientedHeight: Int,
        box: IndexedFaceBox,
        margin: Float = MARGIN,
        targetEdge: Int = TARGET_EDGE,
        maxDecodeEdge: Int = MAX_DECODE_EDGE
    ): FaceCropPlan? {
        if (orientedWidth <= 0 || orientedHeight <= 0) return null
        require(margin >= 0f && targetEdge > 0 && maxDecodeEdge > 0)
        val mx = (box.right - box.left) * margin
        val my = (box.bottom - box.top) * margin
        val l = (box.left - mx).coerceAtLeast(0f)
        val t = (box.top - my).coerceAtLeast(0f)
        val r = (box.right + mx).coerceAtMost(1f)
        val b = (box.bottom + my).coerceAtMost(1f)
        val cropLongEdge = max((r - l) * orientedWidth, (b - t) * orientedHeight)
        val scale = minOf(1f, targetEdge / cropLongEdge,
            maxDecodeEdge.toFloat() / max(orientedWidth, orientedHeight))
        val dw = max(1, (orientedWidth * scale).roundToInt())
        val dh = max(1, (orientedHeight * scale).roundToInt())
        val left = floor(l * dw).toInt().coerceIn(0, dw - 1)
        val top = floor(t * dh).toInt().coerceIn(0, dh - 1)
        val right = ceil(r * dw).toInt().coerceIn(left + 1, dw)
        val bottom = ceil(b * dh).toInt().coerceIn(top + 1, dh)
        return FaceCropPlan(dw, dh, left, top, right, bottom)
    }

    /**
     * VS-V1c (#468): a video face's box is normalized against the keyframe
     * the scanner sampled at frame_ms (rotation already applied by
     * MediaMetadataRetriever). [frameRequest] is the bounded size to ask the
     * retriever for; [frameCrop] is the crop rectangle in the frame actually
     * returned, whose size may differ (the retriever keeps aspect ratio).
     */
    fun frameRequest(orientedWidth: Int, orientedHeight: Int, box: IndexedFaceBox): FaceCropPlan? =
        plan(orientedWidth, orientedHeight, box)

    fun frameCrop(frameWidth: Int, frameHeight: Int, box: IndexedFaceBox): FaceCropPlan? =
        plan(frameWidth, frameHeight, box, targetEdge = Int.MAX_VALUE,
            maxDecodeEdge = Int.MAX_VALUE)

    /** Video frame size after its rotation metadata (0/90/180/270). */
    fun orientedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (((rotationDegrees % 360) + 360) % 180 == 90) height to width else width to height
}

/** A cached crop belongs to one epoch, one observation and one source revision. */
internal data class FaceCropKey(
    val epoch: String, val faceUid: String, val mediaKey: String, val revision: String
)

/**
 * Small in-memory LRU of review crops. Never on disk. Any key of another epoch
 * empties it first, so a crop never outlives the permission scope it was
 * decoded under; [clear] runs on revoke.
 */
internal class FaceCropCache<V : Any>(private val maxEntries: Int = 32) {
    init { require(maxEntries > 0) }
    private var epoch: String? = null
    private val entries = object : LinkedHashMap<FaceCropKey, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FaceCropKey, V>?) =
            size > maxEntries
    }

    @Synchronized fun get(key: FaceCropKey): V? { fence(key.epoch); return entries[key] }
    @Synchronized fun put(key: FaceCropKey, value: V) { fence(key.epoch); entries[key] = value }
    @Synchronized fun clear() { entries.clear(); epoch = null }
    val size: Int @Synchronized get() = entries.size

    private fun fence(next: String) {
        if (epoch != next) { entries.clear(); epoch = next }
    }
}

/** Decodes one face crop from an already authorized URI; off main only. */
internal fun interface FaceCropDecoder {
    fun decode(authorizedUri: String, box: IndexedFaceBox): Bitmap?

    /**
     * VS-V1c (#468): the same, for a face at [frameMs] in a video (null: a
     * photo). A decoder without video support fails closed to the neutral
     * icon for every video face.
     */
    fun decodeAt(authorizedUri: String, box: IndexedFaceBox, frameMs: Long?): Bitmap? =
        if (frameMs == null) decode(authorizedUri, box) else null
}

/**
 * The app's crop decoder: a downsampled ImageDecoder decode of the
 * MediaStore URI with a crop, so only the scaled image and the crop are in
 * memory. Never a file path, never a disk cache.
 */
internal class MediaStoreFaceCropDecoder(private val resolver: ContentResolver) : FaceCropDecoder {
    override fun decode(authorizedUri: String, box: IndexedFaceBox): Bitmap? =
        decodeAt(authorizedUri, box, null)

    override fun decodeAt(authorizedUri: String, box: IndexedFaceBox, frameMs: Long?): Bitmap? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Face crop decode must never run on main" }
        val uri = Uri.parse(authorizedUri)
        if (uri.scheme != "content" || uri.authority != "media") return null
        val video = uri.pathSegments.getOrNull(1) == "video"
        // A photo never has a frame time and a video always needs one.
        if (video != (frameMs != null)) return null
        if (frameMs != null) return try {
            decodeVideoFrame(uri, box, frameMs)
        } catch (stopped: java.util.concurrent.CancellationException) {
            throw stopped
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // Corrupt, DRM or vanished video, or a codec refusal: the neutral icon.
            null
        }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val plan = FaceCropGeometry.plan(info.size.width, info.size.height, box)
                ?: throw IOException("Photo has no usable size")
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(plan.decodeWidth, plan.decodeHeight)
            decoder.crop = Rect(plan.left, plan.top, plan.right, plan.bottom)
        }
    }

    /**
     * VS-V1c (#468): one bounded frame at [frameMs], read the way the VS-V1a
     * keyframe sampler read it (OPTION_CLOSEST_SYNC at the same time, so the
     * same sync frame the face was found in), scaled down by the retriever to
     * at most [FaceCropGeometry.MAX_DECODE_EDGE], then cropped. Through the
     * authorized content URI's descriptor only, never a path; nothing is
     * cached on disk. Any decode failure is null: the neutral icon.
     */
    private fun decodeVideoFrame(uri: Uri, box: IndexedFaceBox, frameMs: Long): Bitmap? {
        if (frameMs < 0L || frameMs > Long.MAX_VALUE / 1000L) return null
        val retriever = MediaMetadataRetriever()
        try {
            // The caller may close the descriptor as soon as setDataSource returns.
            resolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) }
                ?: return null
            fun meta(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val (width, height) = FaceCropGeometry.orientedSize(
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            val request = FaceCropGeometry.frameRequest(width, height, box) ?: return null
            val frame = retriever.getScaledFrameAtTime(frameMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                request.decodeWidth, request.decodeHeight) ?: return null
            try {
                // A provider may ignore the requested size: never keep an oversized frame.
                if (frame.width <= 0 || frame.height <= 0 ||
                    maxOf(frame.width, frame.height) > FaceCropGeometry.MAX_DECODE_EDGE) return null
                val crop = FaceCropGeometry.frameCrop(frame.width, frame.height, box) ?: return null
                val cut = Bitmap.createBitmap(frame, crop.left, crop.top,
                    crop.right - crop.left, crop.bottom - crop.top)
                val longEdge = maxOf(cut.width, cut.height)
                if (longEdge <= FaceCropGeometry.TARGET_EDGE) {
                    return if (cut === frame) cut.copy(Bitmap.Config.ARGB_8888, false) else cut
                }
                val scale = FaceCropGeometry.TARGET_EDGE.toFloat() / longEdge
                val scaled = Bitmap.createScaledBitmap(cut,
                    max(1, (cut.width * scale).roundToInt()),
                    max(1, (cut.height * scale).roundToInt()), true)
                if (cut !== frame && cut !== scaled) cut.recycle()
                return scaled
            } finally {
                frame.recycle()
            }
        } finally {
            try { retriever.release() } catch (_: IOException) { } catch (_: RuntimeException) { }
        }
    }
}

/**
 * A review face crop, or the neutral icon while it loads or when the source
 * is outside the live grant. Rereads on every host change, so a revoke or a
 * new epoch drops the image.
 */
@Composable
internal fun FaceCropPreview(
    host: PeopleDiscoveryHost,
    epoch: String?,
    nativeFaceUid: String,
    unavailableDescription: String,
    /** Review cards use a small rounded crop; People home fills its circle. */
    imageModifier: Modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
    fallback: (@Composable () -> Unit)? = null
) {
    val version by host.changes.collectAsState()
    val crop by produceState<ImageBitmap?>(null, epoch, nativeFaceUid, version) {
        value = withContext(Dispatchers.IO) { host.faceCrop(epoch, nativeFaceUid) }
            ?.asImageBitmap()
    }
    val image = crop
    if (image != null) {
        Image(image, "Face crop", imageModifier.testTag("atlas-face-crop"),
            contentScale = ContentScale.Crop)
    } else if (fallback != null) {
        fallback()
    } else {
        Icon(Icons.Filled.PhotoLibrary, unavailableDescription,
            Modifier.testTag("atlas-face-crop-unavailable"))
    }
}

/**
 * True once an open native person album has nothing left to show: the run's
 * opt-in is gone (revoke, failure) or the live reviewed snapshot no longer
 * names [person] (Undo, unname, merge away, all faces gone). A null snapshot
 * alone (loading, a pending refresh, mid-publication) keeps the album open.
 */
internal fun nativePersonAlbumGone(
    optedIn: Boolean,
    people: nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState?,
    person: nl.atlas.gallery.featurepeople.cluster.PersonKey
): Boolean = !optedIn || (people != null && person !in people.names)
