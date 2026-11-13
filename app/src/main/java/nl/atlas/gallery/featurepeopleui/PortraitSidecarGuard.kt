package nl.atlas.gallery.featurepeopleui

/**
 * V4's JSONL v1/v2 metadata is OPTIONAL and may never establish identity.
 * This pure contract deliberately does not read files, photos or a database.
 * Lane X/I must verify the sealed file, unchanged five-column CSV membership,
 * permission-granted MediaStore object and original orientation before calling.
 */
data class ReviewedFaceKey(
    val imagePath: String,
    val faceId: Long,
    val personId: Long
)

data class NormalizedFaceBox(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double
) {
    fun valid(): Boolean = listOf(left, top, width, height).all { it.isFinite() } &&
        left >= 0.0 && top >= 0.0 && width > 0.0 && height > 0.0 &&
        left + width <= 1.0 + 1e-9 && top + height <= 1.0 + 1e-9
}

/** Metadata from a strictly verified sidecar: never sufficient on its own. */
data class V4FaceSidecarHint(
    val schema: String,
    val key: ReviewedFaceKey,
    val reviewState: String,
    val normalizedBox: NormalizedFaceBox?,
    val indexedWidth: Int?,
    val indexedHeight: Int?,
    val indexedSizeBytes: Long?,
    val indexedMtimeNanos: Long?,
    val manualCover: Boolean
)

/**
 * Caller-supplied independent current-media proof. Never manufacture this
 * from V4 path/id alone. The exact native media identity is the URI, not V4 id.
 *
 * nativeMtimeNanos is intentionally nullable: current MediaRecord has only
 * second-granularity mtime. In that case we refuse to bind portrait geometry.
 */
data class CurrentMediaPortraitEvidence(
    val mediaUri: String,
    val authorizedAbsolutePath: String,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val nativeMtimeNanos: Long?,
    val permissionEpoch: String,
    val verifiedPermissionEpoch: String,
    val sourceOrientationMatched: Boolean,
    val authorizedNow: Boolean
)

data class PortraitCropBinding(
    val mediaUri: String,
    val crop: NormalizedFaceBox,
    val verifiedPermissionEpoch: String,
    val chosenManually: Boolean
)

/**
 * Trust-gate only. The importer supplies the authoritative CSV tuple set;
 * a V4 sidecar's own 'confirmed' text is never accepted as a confirmation.
 */
fun bindVerifiedPortrait(
    hint: V4FaceSidecarHint,
    confirmedCsvKeys: Set<ReviewedFaceKey>,
    evidence: CurrentMediaPortraitEvidence,
    portraitPadFraction: Double = 0.5
): PortraitCropBinding? {
    if (hint.schema != "atlas.v4.face_metadata.v2") return null
    if (hint.key !in confirmedCsvKeys || hint.reviewState != "confirmed") return null
    val box = hint.normalizedBox?.takeIf { it.valid() } ?: return null
    if (!evidence.authorizedNow ||
        evidence.permissionEpoch.isBlank() ||
        evidence.permissionEpoch != evidence.verifiedPermissionEpoch ||
        !evidence.sourceOrientationMatched ||
        !evidence.mediaUri.startsWith("content://media/") ||
        hint.key.imagePath != evidence.authorizedAbsolutePath
    ) return null
    val iw = hint.indexedWidth ?: return null
    val ih = hint.indexedHeight ?: return null
    val isize = hint.indexedSizeBytes ?: return null
    val imtime = hint.indexedMtimeNanos ?: return null
    if (iw <= 0 || ih <= 0 || isize < 0 || imtime < 0) return null
    if (iw != evidence.width || ih != evidence.height ||
        isize != evidence.sizeBytes || imtime != evidence.nativeMtimeNanos
    ) return null
    if (!portraitPadFraction.isFinite() || portraitPadFraction !in 0.0..2.0) return null
    return PortraitCropBinding(
        evidence.mediaUri, expandedPortraitRect(box, portraitPadFraction),
        evidence.verifiedPermissionEpoch, hint.manualCover
    )
}

/** Original balanced face-centered crop with edge shifting, not shrinking. */
fun expandedPortraitRect(box: NormalizedFaceBox, pad: Double): NormalizedFaceBox {
    require(box.valid() && pad.isFinite() && pad in 0.0..2.0)
    val width = (box.width * (1.0 + 2.0 * pad)).coerceAtMost(1.0)
    val height = (box.height * (1.0 + 2.0 * pad)).coerceAtMost(1.0)
    val left = (box.left + box.width / 2.0 - width / 2.0).coerceIn(0.0, 1.0 - width)
    val top = (box.top + box.height / 2.0 - height / 2.0).coerceIn(0.0, 1.0 - height)
    return NormalizedFaceBox(left, top, width, height)
}

/**
 * True/Unknown detected-face totals from the sidecar are observations of
 * one indexed V4 snapshot, not proof that no undetected person is present.
 * 'Only these reviewed people' must be labeled accordingly.
 */
data class IndexedFaceCount(
    val totalIndexedFaces: Int?,
    val verified: Boolean,
    val sidecarEpoch: String,
    /** I must revalidate this against the currently authorized inventory. */
    val permissionEpoch: String? = null,
    /** M's provider-version + volume/type + generation + editor invalidation key. */
    val mediaRevision: String? = null,
    /** URI of the independently revalidated MediaStore object. */
    val mediaUri: String? = null
) {
    /**
     * Untrusted V4 totals MUST NOT leak through a URI-only map after regrant,
     * editing or a provider-version reset. No binding information = Unknown.
     */
    fun knownTotalFor(
        currentMediaUri: String?, currentPermissionEpoch: String?,
        currentMediaRevision: String?
    ): Int? {
        if (currentMediaUri.isNullOrBlank() || mediaUri != currentMediaUri ||
            currentPermissionEpoch.isNullOrBlank() || currentMediaRevision.isNullOrBlank() ||
            permissionEpoch != currentPermissionEpoch || mediaRevision != currentMediaRevision ||
            !verified || sidecarEpoch.isBlank()
        ) return null
        return totalIndexedFaces?.takeIf { it >= 0 }
    }
}
