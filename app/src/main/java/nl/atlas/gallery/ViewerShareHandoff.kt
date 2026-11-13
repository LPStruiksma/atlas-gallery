package nl.atlas.gallery

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Read-only Android Sharesheet handoff for the *one selected* MediaStore item.
 * The receiving app gets a temporary URI read grant, never a filesystem path,
 * album-wide access, a copy, write access, or a private Samsung API.
 */
internal object ViewerShareHandoff {
    enum class Result { STARTED, NOT_SHAREABLE, UNAVAILABLE }

    fun eligible(media: MediaRecord): Boolean {
        val uri = Uri.parse(media.uri)
        val path = uri.pathSegments
        // GalleryRepository uses Images/Video.getContentUri(volume) plus a
        // numeric MediaStore row ID. Never share a whole collection URI.
        return uri.scheme == "content" && uri.authority == "media" &&
            path.size == 4 && path[0].isNotBlank() &&
            path[1] == (if (media.isVideo) "video" else "images") &&
            path[2] == "media" && (path[3].toLongOrNull() ?: 0L) > 0L
    }

    fun makeSendIntent(media: MediaRecord): Intent? {
        if (!eligible(media)) return null
        val uri = Uri.parse(media.uri)
        return Intent(Intent.ACTION_SEND).apply {
            type = if (media.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Selected ATLAS media", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun launch(context: Context, media: MediaRecord): Result {
        val sendIntent = makeSendIntent(media) ?: return Result.NOT_SHAREABLE
        val chooser = Intent.createChooser(
            sendIntent, if (media.isVideo) "Share video" else "Share photo"
        ).apply {
            // Retain the grant on the chooser wrapper as well as ACTION_SEND.
            clipData = sendIntent.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(chooser)
            Result.STARTED
        } catch (_: ActivityNotFoundException) {
            Result.UNAVAILABLE
        } catch (_: SecurityException) {
            // Permission may have been revoked after the viewer opened.
            Result.UNAVAILABLE
        }
    }
}
