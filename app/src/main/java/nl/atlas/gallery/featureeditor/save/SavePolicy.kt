package nl.atlas.gallery.featureeditor.save

import nl.atlas.gallery.featureaiedit.save.AiEditImageFormat
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe

/** Save writes over the photo; Save as copy writes a new item next to it. */
internal enum class SaveMode { IN_PLACE, COPY }

/** What happened to a motion photo's embedded clip. */
internal enum class ClipOutcome {
    /** The source has no clip. */
    NONE,
    /** Geometry-only edit: the clip is re-attached unchanged and described for the new file. */
    KEPT,
    /** A colour, filter, overlay or patch edit changed the still; the clip would no longer match it. */
    DROPPED_EDITED,
    /** A clip format this path cannot rewrite safely (Samsung trailer, padded or misplaced clip). */
    DROPPED_UNSUPPORTED,
    /** The output format cannot carry a clip (PNG, WebP), or an HDR directory was unrecognised. */
    DROPPED_FORMAT,
}

/** What happened to an Ultra HDR gainmap. */
internal enum class HdrOutcome { NONE, KEPT, DROPPED_FORMAT }

/**
 * Output format per source MIME (EDIT-0 record, `docs/editor/PARITY.md` media rows):
 * never transcode silently. A source the platform cannot re-encode in its own format (HEIF/HEIC,
 * AVIF, anything else) is saved only as a JPEG **copy**; Save over it returns
 * [EditorSaveResult.CopyRequired] so the editor offers "Save as copy (JPEG)" explicitly.
 */
internal data class OutputFormat(val format: AiEditImageFormat, val inPlace: Boolean) {
    companion object {
        fun forSource(mime: String?, sdkInt: Int): OutputFormat = when (mime?.lowercase()) {
            "image/jpeg", "image/jpg" -> OutputFormat(AiEditImageFormat.JPEG, inPlace = true)
            "image/png" -> OutputFormat(AiEditImageFormat.PNG, inPlace = true)
            // Lossless WebP encoding exists from Android 11.
            "image/webp" -> if (sdkInt >= 30) OutputFormat(AiEditImageFormat.WEBP, inPlace = true)
                else OutputFormat(AiEditImageFormat.JPEG, inPlace = false)
            else -> OutputFormat(AiEditImageFormat.JPEG, inPlace = false)
        }
    }
}

internal object SavePolicy {
    /**
     * True when every step only moves pixels (rotate, flip, straighten, perspective, crop): the
     * still still shows the same moment the clip shows, so a motion photo keeps its clip.
     */
    fun isGeometryOnly(recipe: EditRecipe): Boolean = recipe.operations.all {
        it is EditOperation.Rotate90 || it is EditOperation.Flip || it is EditOperation.Straighten ||
            it is EditOperation.Perspective || it is EditOperation.Crop || it is EditOperation.ResetGeometry
    }

    /** `<stem>_edited.<ext>`, then `<stem>_edited_2.<ext>`, ... (case-insensitive). */
    fun copyName(sourceName: String, extension: String, existing: Set<String>): String {
        val stem = sourceName.substringBeforeLast('.', sourceName).ifBlank { "image" }
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        var n = 1
        while (true) {
            val name = if (n == 1) "${stem}_edited.$extension" else "${stem}_edited_$n.$extension"
            if (name.lowercase() !in taken) return name
            n++
        }
    }

    const val PENDING_PREFIX = "atlas-edit-pending-"

    fun pendingName(token: String, extension: String) = "$PENDING_PREFIX$token.$extension"
}
