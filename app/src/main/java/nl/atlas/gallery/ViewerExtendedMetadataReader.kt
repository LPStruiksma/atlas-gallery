package nl.atlas.gallery

import android.content.Context
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Scoped to Details: metadata is loaded only after the sheet is shown and is
 * never logged, uploaded or placed in the thumbnail pipeline. A failed read
 * (including revoked permissions) returns null rather than displaying guessed
 * camera properties.
 */
internal fun interface ViewerMetadataReader {
    suspend fun read(context: Context, media: MediaRecord): ViewerExtendedMetadata?
}

private data class ViewerMetadataKey(
    val id: Long, val uri: String, val isVideo: Boolean, val modifiedSeconds: Long,
    val generationModified: Long
)

/** Small process-only LRU. Records without a modification stamp are not cached. */
private object ViewerMetadataCache {
    private const val LIMIT = 32
    private val values = object : LinkedHashMap<ViewerMetadataKey, ViewerExtendedMetadata>(
        LIMIT, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ViewerMetadataKey, ViewerExtendedMetadata>?
        ): Boolean = size > LIMIT
    }

    @Synchronized fun get(key: ViewerMetadataKey): ViewerExtendedMetadata? = values[key]
    @Synchronized fun put(key: ViewerMetadataKey, metadata: ViewerExtendedMetadata) {
        values[key] = metadata
    }
    @Synchronized fun clear() = values.clear()
}

/** Call when a full or selected-media grant changes. */
internal fun clearViewerMetadataCache() = ViewerMetadataCache.clear()

internal val PlatformViewerMetadataReader = ViewerMetadataReader { context, media ->
    withContext(Dispatchers.IO) {
        val uri = Uri.parse(media.uri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return@withContext null
        val key = if (media.modifiedAtSeconds > 0L) {
            ViewerMetadataKey(media.id, media.uri, media.isVideo,
                media.modifiedAtSeconds, media.generationModified)
        } else null
        if (key != null) {
            ViewerMetadataCache.get(key)?.let { return@withContext it }
        }
        val details = try {
            if (media.isVideo) readViewerVideoMetadata(context, uri)
            else context.contentResolver.openInputStream(uri)?.use(::readViewerExif)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // ContentResolver, EXIF and media extractors can all fail for a
            // stale, unsupported or permission-revoked content URI.
            null
        }
        if (key != null && details != null) ViewerMetadataCache.put(key, details)
        details
    }
}

internal val LocalViewerMetadataReader =
    androidx.compose.runtime.staticCompositionLocalOf { PlatformViewerMetadataReader }

/**
 * The EXIF timestamp is a local wall-clock when no timezone is embedded.
 * Avoid guessing UTC: many cameras have no EXIF offset at all.
 */
private fun exifDateMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).apply {
            isLenient = false
        }.parse(value)?.time
    }.getOrNull()
}

internal fun readViewerExif(stream: InputStream): ViewerExtendedMetadata {
    val exif = ExifInterface(stream)
    val gps = FloatArray(2)
    val hasGps = exif.getLatLong(gps)
    val flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
    fun double(tag: String): Double? = exif.getAttributeDouble(tag, Double.NaN)
        .takeIf { it.isFinite() && it > 0.0 }
    return ViewerExtendedMetadata(
        originalDateMillis = exifDateMillis(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
        cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE),
        cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL),
        // Platform ExifInterface has no LensModel tag; omit rather than invent one.
        iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, -1)
            .takeIf { it > 0 },
        focalMm = double(ExifInterface.TAG_FOCAL_LENGTH),
        focal35Mm = exif.getAttributeInt(
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1).takeIf { it > 0 },
        apertureF = double(ExifInterface.TAG_F_NUMBER),
        exposureSeconds = double(ExifInterface.TAG_EXPOSURE_TIME),
        flashFired = if (flash < 0) null else flash and 1 != 0,
        latitude = if (hasGps) gps[0].toDouble() else null,
        longitude = if (hasGps) gps[1].toDouble() else null
    )
}

/**
 * MediaMetadataRetriever knows duration; MediaExtractor exposes the VIDEO
 * track's actual codec and frame rate, rather than the MP4 container MIME.
 */
private fun readViewerVideoMetadata(context: Context, uri: Uri): ViewerExtendedMetadata {
    val retriever = MediaMetadataRetriever()
    var duration: Long? = null
    var fps: Double? = null
    try {
        retriever.setDataSource(context, uri)
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.takeIf { it > 0L }
        fps = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    } finally {
        retriever.release()
    }
    val extractor = MediaExtractor()
    var codec: String? = null
    try {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull()
                ?: continue
            if (!mime.startsWith("video/")) continue
            codec = when (mime.lowercase(Locale.ROOT)) {
                "video/avc" -> "H.264"
                "video/hevc" -> "H.265 (HEVC)"
                "video/av01" -> "AV1"
                "video/mp4v-es" -> "MPEG-4"
                "video/x-vnd.on2.vp9" -> "VP9"
                "video/x-vnd.on2.vp8" -> "VP8"
                else -> mime.substringAfter('/').uppercase(Locale.ROOT)
            }
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                fps = (runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    .getOrNull() ?: runCatching {
                        format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                    }.getOrNull())?.takeIf { it.isFinite() && it > 0.0 } ?: fps
            }
            break
        }
    } finally {
        extractor.release()
    }
    return ViewerExtendedMetadata(
        videoDurationMillis = duration, videoCodec = codec, videoFps = fps)
}
