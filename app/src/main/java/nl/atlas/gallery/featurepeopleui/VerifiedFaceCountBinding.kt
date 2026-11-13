package nl.atlas.gallery.featurepeopleui

/**
 * A strictly verified v2 image record from the optional V4 sidecar.
 * The producer verifies canonical JSONL, complete SHA-256 and semantic
 * assigned<=detected counts BEFORE creating this object. An unkeyed SHA
 * detects damage only; it does not authenticate a malicious source.
 */
data class V4ImageCountHint(
    val schema: String,
    val imagePath: String,
    val countStatus: String,
    val totalIndexedFaces: Int?,
    val eligibleAssignedFaceRows: Int,
    val indexedWidth: Int?,
    val indexedHeight: Int?,
    val indexedSizeBytes: Long?,
    val indexedMtimeNanos: Long?,
    val verifiedSidecarSeal: String
)

/**
 * Link a known V4 count to the CURRENT, permission-granted native media
 * record; counts without an authoritative confirmed CSV membership do not
 * become People search facets.
 *
 * The current MediaRecord offers seconds only. Until M/I independently
 * establish subsecond original mtime and orientation, this function returns
 * null. It is intentionally safe to leave "Only these indexed faces" disabled.
 */
fun bindVerifiedImageFaceCount(
    hint: V4ImageCountHint,
    confirmedCsvImagePaths: Set<String>,
    evidence: CurrentMediaPortraitEvidence,
    currentMediaRevision: String?
): IndexedFaceCount? {
    if (hint.schema != "atlas.v4.face_metadata.v2" ||
        hint.countStatus != "verified" ||
        hint.verifiedSidecarSeal.length != 64 ||
        !hint.verifiedSidecarSeal.all { it in '0'..'9' || it in 'a'..'f' } ||
        hint.imagePath !in confirmedCsvImagePaths ||
        hint.imagePath != evidence.authorizedAbsolutePath ||
        !evidence.authorizedNow ||
        evidence.permissionEpoch.isBlank() ||
        evidence.permissionEpoch != evidence.verifiedPermissionEpoch ||
        !evidence.sourceOrientationMatched ||
        !evidence.mediaUri.startsWith("content://media/") ||
        currentMediaRevision.isNullOrBlank()
    ) return null

    val total = hint.totalIndexedFaces ?: return null
    if (total < 0 || hint.eligibleAssignedFaceRows < 0 ||
        hint.eligibleAssignedFaceRows > total) return null

    val indexedWidth = hint.indexedWidth ?: return null
    val indexedHeight = hint.indexedHeight ?: return null
    val indexedSize = hint.indexedSizeBytes ?: return null
    val indexedTime = hint.indexedMtimeNanos ?: return null
    if (indexedWidth <= 0 || indexedHeight <= 0 ||
        indexedSize < 0 || indexedTime < 0) return null
    if (indexedWidth != evidence.width || indexedHeight != evidence.height ||
        indexedSize != evidence.sizeBytes ||
        indexedTime != evidence.nativeMtimeNanos) return null

    return IndexedFaceCount(
        totalIndexedFaces = total,
        verified = true,
        sidecarEpoch = hint.verifiedSidecarSeal,
        permissionEpoch = evidence.permissionEpoch,
        mediaRevision = currentMediaRevision,
        mediaUri = evidence.mediaUri
    )
}
