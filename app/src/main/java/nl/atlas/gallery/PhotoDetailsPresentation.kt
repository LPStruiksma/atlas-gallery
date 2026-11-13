package nl.atlas.gallery

import java.text.NumberFormat
import java.util.Locale

/** A human-readable, aggregate-only explanation of optional Android photo metadata. */
internal data class PhotoDetailRow(
    val label: String,
    val countText: String,
    val note: String
)

internal data class PhotoDetailsContent(
    val summary: String,
    val rows: List<PhotoDetailRow>,
    val skippedNote: String?
)

/**
 * The source counts come from FaceCatalogPublication, not a second MediaStore read.
 * A photo can occur in several missing-field rows; never add rows to estimate
 * unique photos with incomplete details. No raw provider keys, URIs or IDs enter
 * the visible text.
 */
internal fun photoDetailsContent(
    checked: Int,
    partial: Int,
    skipped: Int,
    missing: Map<String, Int>,
    locale: Locale = Locale.getDefault()
): PhotoDetailsContent {
    val count = NumberFormat.getIntegerInstance(locale)
    val observed = checked.coerceAtLeast(0)
    val summary = "Photos checked: ${count.format(observed)} · " +
        "missing some details: ${count.format(partial.coerceAtLeast(0))} · " +
        "skipped: ${count.format(skipped.coerceAtLeast(0))}"
    fun row(label: String, value: Int, note: String): PhotoDetailRow {
        val percent = if (observed > 0) value * 100.0 / observed else 0.0
        return PhotoDetailRow(label,
            "${count.format(value)} (${String.format(locale, "%.2f", percent)}%)", note)
    }

    val rest = missing.filterValues { it > 0 }.toMutableMap()
    val rows = ArrayList<PhotoDetailRow>()
    fun take(field: String, label: String, note: String) {
        val value = rest.remove(field) ?: return
        rows.add(row(label, value, note))
    }

    take("orientationDegrees", "Rotation not recorded",
        "Common and usually harmless. The image decoder reads rotation when a photo opens; " +
            "some grid shapes may be approximate.")
    take("sizeBytes", "File size unknown",
        "Sorting by file size may place these photos in the wrong order.")

    // Both missing for the same number of photos is common. The counts alone
    // cannot prove they are the SAME photos, so this is a paired indicator,
    // not a deduplicated count of unique photos.
    val width = rest["widthPixels"]
    val height = rest["heightPixels"]
    val layoutNote = "The original-proportions grid may use a fallback shape."
    if (width != null && height != null && width == height) {
        rest.remove("widthPixels")
        rest.remove("heightPixels")
        rows.add(row("Width/height unknown", width, layoutNote))
    } else {
        take("widthPixels", "Width unknown", layoutNote)
        take("heightPixels", "Height unknown", layoutNote)
    }

    take("modifiedAtSeconds", "Last changed date unknown",
        "ATLAS cannot use the change date to confirm this photo is unchanged.")
    take("generationModified", "Library change marker missing",
        "ATLAS uses a less precise change check when preparing People scans.")
    take("providerVersion", "Android library version missing",
        "ATLAS uses a less precise change check when preparing People scans.")
    take("editorRevision", "Edit version missing",
        "ATLAS cannot rely on this edit version when rechecking a photo.")

    // Future provider fields must not appear as raw developer identifiers.
    rest.toSortedMap().values.forEach { value ->
        rows.add(row("Other detail unavailable", value,
            "Other optional Android catalogue information is unavailable."))
    }
    return PhotoDetailsContent(
        summary = summary,
        rows = rows,
        skippedNote = if (skipped > 0)
            "Skipped photos could not be included in the People scan because " +
                "their library identity was incomplete. They remain in your gallery."
        else null
    )
}
