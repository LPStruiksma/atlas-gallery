package nl.atlas.gallery.featureface

/**
 * One row from Lane M's already committed and permission-visible MediaStore
 * catalogue. Merely constructing this value does not confer a media grant.
 * I must revalidate the source provider generation and Android permission
 * both before native scanning and before resolving any content URI.
 *
 * The native index persists only the opaque FaceMediaSourceKey digest, never
 * this URI, an original photo, a person name or a biometric embedding.
 */
data class FaceVisibleMedia(
    val uri: String,
    val volumeName: String,
    val isVideo: Boolean,
    val id: Long,
    val providerVersion: String?,
    val generationModified: Long,
    val sizeBytes: Long,
    val modifiedAtSeconds: Long,
    val editorRevision: Long,
    val widthPixels: Int,
    val heightPixels: Int,
    val orientationDegrees: Int,
    /** VS-V1b: MediaStore duration of a video row; 0 when unknown, unused for photos. */
    val durationMs: Long = 0L,
    /**
     * #508: MediaStore capture time (ms; 0 when unknown). Orders the face
     * scan newest first and picks the newest photos of a library above the
     * photo cap. Not part of the durable face key.
     */
    val takenAtMillis: Long = 0L
)
