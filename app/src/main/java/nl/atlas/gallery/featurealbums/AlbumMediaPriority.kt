package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord

/**
 * Deterministic presentation ranking for authorized catalogue rows.
 *
 * Newer capture date wins; equal-date distinct URIs have a stable tie-break.
 * For duplicate CURRENT-source rows describing the same URI+date, compare
 * provider content revisions and remaining metadata rather than relying on
 * enumeration order. This is NOT a permission, identity, or source-trust check.
 */
internal fun compareAlbumMediaPriority(a: MediaRecord, b: MediaRecord): Int =
    compareValues(a.takenAtMillis, b.takenAtMillis).takeIf { it != 0 }
        ?: a.uri.compareTo(b.uri).takeIf { it != 0 }
        ?: compareValues(a.generationModified, b.generationModified).takeIf { it != 0 }
        ?: compareValues(a.modifiedAtSeconds, b.modifiedAtSeconds).takeIf { it != 0 }
        ?: compareValues(a.providerVersion ?: "", b.providerVersion ?: "").takeIf { it != 0 }
        ?: compareValues(a.sizeBytes, b.sizeBytes).takeIf { it != 0 }
        ?: compareValues(a.widthPixels, b.widthPixels).takeIf { it != 0 }
        ?: compareValues(a.heightPixels, b.heightPixels).takeIf { it != 0 }
        ?: compareValues(a.orientationDegrees, b.orientationDegrees).takeIf { it != 0 }
        ?: compareValues(a.durationMillis, b.durationMillis).takeIf { it != 0 }
        ?: compareValues(a.isVideo, b.isVideo).takeIf { it != 0 }
        ?: compareValues(a.isFavorite, b.isFavorite).takeIf { it != 0 }
        ?: compareValues(a.favoriteKnown, b.favoriteKnown).takeIf { it != 0 }
        ?: a.dateSource.name.compareTo(b.dateSource.name).takeIf { it != 0 }
        ?: a.name.compareTo(b.name).takeIf { it != 0 }
        ?: compareValues(a.id, b.id)
