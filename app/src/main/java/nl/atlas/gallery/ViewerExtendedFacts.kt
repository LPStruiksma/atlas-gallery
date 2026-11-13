package nl.atlas.gallery

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Camera/video fields read only after Details opens. This value object contains
 * no URI, user-media bytes or persistent storage key and is safe to format on
 * the UI thread. Null denotes an absent or inaccessible metadata attribute.
 */
internal data class ViewerExtendedMetadata(
    val originalDateMillis: Long? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lens: String? = null,
    val iso: Int? = null,
    val focalMm: Double? = null,
    val focal35Mm: Int? = null,
    val apertureF: Double? = null,
    val exposureSeconds: Double? = null,
    val flashFired: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val videoDurationMillis: Long? = null,
    val videoCodec: String? = null,
    val videoFps: Double? = null
)

internal data class ViewerFactRow(val label: String, val value: String)

internal fun viewerCaptureDateMillis(
    facts: ViewerDetailsFacts, metadata: ViewerExtendedMetadata?
): Long? = metadata?.originalDateMillis?.takeIf { it > 0L } ?: facts.takenAtMillis

internal fun viewerMegapixels(
    dimensions: ViewerDimensions?, locale: Locale = Locale.getDefault()
): String? = dimensions?.takeIf { it.width > 0 && it.height > 0 }?.let {
    String.format(locale, "%.1f MP", it.width.toLong() * it.height / 1_000_000.0)
}

private fun Double.usable(): Boolean = isFinite() && this > 0.0

internal fun viewerCameraRows(
    metadata: ViewerExtendedMetadata?, locale: Locale = Locale.getDefault()
): List<ViewerFactRow> = buildList {
    if (metadata == null) return@buildList
    val make = metadata.cameraMake?.trim().orEmpty()
    val model = metadata.cameraModel?.trim().orEmpty()
    val camera = when {
        model.isBlank() -> make
        make.isBlank() || model.startsWith(make, ignoreCase = true) -> model
        else -> "$make $model"
    }
    if (camera.isNotBlank()) add(ViewerFactRow("Camera", camera))
    metadata.lens?.trim()?.takeIf { it.isNotEmpty() }?.let {
        add(ViewerFactRow("Lens", it))
    }
    metadata.iso?.takeIf { it > 0 }?.let {
        add(ViewerFactRow("ISO", it.toString()))
    }
    metadata.focalMm?.takeIf { it.usable() }?.let { focal ->
        val equivalent = metadata.focal35Mm?.takeIf { it > 0 }
            ?.let { " · $it mm equiv." }.orEmpty()
        add(ViewerFactRow("Focal length",
            String.format(locale, "%.1f mm", focal) + equivalent))
    }
    metadata.apertureF?.takeIf { it.usable() }?.let {
        add(ViewerFactRow("Aperture", String.format(locale, "f/%.1f", it)))
    }
    metadata.exposureSeconds?.takeIf { it.usable() }?.let { seconds ->
        val label = if (seconds < 1.0 && 1.0 / seconds <= Int.MAX_VALUE) {
            "1/${(1.0 / seconds).roundToInt()} s"
        } else if (seconds % 1.0 == 0.0) {
            String.format(locale, "%.0f s", seconds)
        } else String.format(locale, "%.1f s", seconds)
        add(ViewerFactRow("Exposure", label))
    }
    metadata.flashFired?.let {
        add(ViewerFactRow("Flash", if (it) "Fired" else "Off"))
    }
    val lat = metadata.latitude
    val lon = metadata.longitude
    if (lat != null && lon != null &&
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    ) {
        add(ViewerFactRow("Location", String.format(locale, "%.5f, %.5f", lat, lon)))
    }
}

internal fun viewerVideoRows(
    metadata: ViewerExtendedMetadata?, fallbackDurationMillis: Long,
    locale: Locale = Locale.getDefault()
): List<ViewerFactRow> = buildList {
    val duration = metadata?.videoDurationMillis?.takeIf { it > 0L }
        ?: fallbackDurationMillis
    viewerDuration(duration)?.let { add(ViewerFactRow("Duration", it)) }
    metadata?.videoCodec?.trim()?.takeIf { it.isNotEmpty() }?.let {
        add(ViewerFactRow("Codec", it))
    }
    metadata?.videoFps?.takeIf { it.isFinite() && it > 0.0 }?.let {
        add(ViewerFactRow("Frame rate", String.format(locale, "%.2f fps", it)))
    }
}
