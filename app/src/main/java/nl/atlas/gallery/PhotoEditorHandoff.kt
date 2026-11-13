package nl.atlas.gallery

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A public Android intent handoff, NOT a private Samsung editor API.
 * Samsung's actual Gallery package can change and may decline ACTION_EDIT.
 * Never guess unexported component names, use reflection or copy a photo.
 */
internal object PhotoEditorHandoff {
    private const val SAMSUNG_GALLERY = "com.sec.android.gallery3d"

    enum class Result { SAMSUNG_EDITOR, SAMSUNG_GALLERY, SYSTEM_EDITOR, UNAVAILABLE }

    fun eligible(media: MediaRecord): Boolean {
        val uri = Uri.parse(media.uri)
        return !media.isVideo && uri.scheme == "content" && uri.authority == "media"
    }

    fun makeIntent(uri: Uri, action: String, samsungOnly: Boolean): Intent {
        require(uri.scheme == "content" && uri.authority == "media") {
            "Only the selected MediaStore image may be handed to an editor"
        }
        require(action == Intent.ACTION_EDIT || action == Intent.ACTION_VIEW)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (action == Intent.ACTION_EDIT) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        return Intent(action).apply {
            // MediaStore-backed content URIs, not file:// paths or full album access.
            setDataAndType(uri, "image/*")
            clipData = ClipData.newRawUri("Selected ATLAS photo", uri)
            addFlags(flags)
            if (samsungOnly) setPackage(SAMSUNG_GALLERY)
        }
    }

    fun launch(context: Context, media: MediaRecord): Result {
        if (!eligible(media)) return Result.UNAVAILABLE
        val uri = Uri.parse(media.uri)
        val routes = listOf(
            Triple(Intent.ACTION_EDIT, true, Result.SAMSUNG_EDITOR),
            Triple(Intent.ACTION_VIEW, true, Result.SAMSUNG_GALLERY),
            Triple(Intent.ACTION_EDIT, false, Result.SYSTEM_EDITOR)
        )
        for ((action, samsungOnly, outcome) in routes) {
            try {
                context.startActivity(makeIntent(uri, action, samsungOnly))
                return outcome
            } catch (_: ActivityNotFoundException) {
                // Samsung may expose only a viewer, not an external editor.
            } catch (_: SecurityException) {
                // Respect per-image access and provider permission decisions.
            }
        }
        return Result.UNAVAILABLE
    }
}
