package nl.atlas.gallery

import java.util.Locale

/** Derived once for a selected, immutable catalogue item; no IO or photo access. */
internal data class ViewerDetailsFacts(
    val filename: String,
    val kind: String,
    val album: String,
    val relativeFolder: String?,
    val fileSize: String?,
    val duration: String?,
    val takenAtMillis: Long?,
    val modifiedAtMillis: Long?
)

internal fun viewerDetailsFacts(media: MediaRecord, locale: Locale = Locale.getDefault()): ViewerDetailsFacts =
    ViewerDetailsFacts(
        filename = media.name.ifBlank { "Unnamed item" },
        kind = if (media.isVideo) "Video" else "Photo",
        album = media.album,
        // Never display a reconstructed absolutePath; MediaStore paths may be
        // redacted/stale and are not valid storage-permission evidence.
        relativeFolder = media.relativePath.trim('/').takeIf { it.isNotBlank() },
        fileSize = media.sizeBytes.takeIf { it > 0L }?.let { viewerFileSize(it, locale) },
        duration = if (media.isVideo) viewerDuration(media.durationMillis) else null,
        takenAtMillis = media.takenAtMillis.takeIf { it > 0L },
        // MediaStore.DATE_MODIFIED is seconds; an invalid provider value
        // must not overflow into a plausible but incorrect calendar date.
        modifiedAtMillis = media.modifiedAtSeconds.takeIf {
            it > 0L && it <= Long.MAX_VALUE / 1000L
        }?.times(1000L)
    )

internal fun viewerFileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 0L) return "Unknown"
    if (bytes < 1_000L) return "$bytes B"
    val value = bytes.toDouble()
    val labels = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
    var scaled = value
    var index = -1
    do {
        scaled /= 1000.0
        index++
    } while (scaled >= 1000.0 && index < labels.lastIndex)
    return String.format(locale, "%.1f %s", scaled, labels[index])
}

internal fun viewerDuration(durationMillis: Long): String? {
    if (durationMillis <= 0L) return null
    val totalSeconds = durationMillis / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}
