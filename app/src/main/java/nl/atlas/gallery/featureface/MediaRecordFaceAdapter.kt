package nl.atlas.gallery.featureface

import nl.atlas.gallery.MediaRecord

/** Direct handoff from Lane M's authorized, version-aware media catalogue.
 *
 * I MUST call this only for rows from a completed, grant-valid MediaStore
 * snapshot, and provide its current editor revision instead of relying on
 * zero after an in-app edit. This does not check Android photo permission.
 *
 * FaceCatalogPublisher catalogues photos and (VS-V1b) videos, rejects
 * incomplete optional image provenance with explicit unknown revision markers,
 * leaves out a video with incomplete provenance, and invalidates its transaction
 * if the grant/provider drifts.
 */
fun MediaRecord.toFaceVisibleMedia(editorRevision: Long): FaceVisibleMedia =
    FaceVisibleMedia(
        uri = uri,
        volumeName = volumeName,
        isVideo = isVideo,
        id = id,
        providerVersion = providerVersion,
        generationModified = generationModified,
        sizeBytes = sizeBytes,
        modifiedAtSeconds = modifiedAtSeconds,
        editorRevision = editorRevision,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        orientationDegrees = orientationDegrees,
        durationMs = if (isVideo) durationMillis.coerceAtLeast(0L) else 0L,
        takenAtMillis = takenAtMillis
    )
