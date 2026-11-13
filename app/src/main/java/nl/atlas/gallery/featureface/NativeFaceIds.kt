package nl.atlas.gallery.featureface

import java.util.UUID

/** Observation IDs belong only to ATLAS's private, versioned DETECTION index.
 * They are unrelated to person IDs and MUST NEVER be synthesized from V4 face_id.
 * On rescanning a revised image or different model, mint a NEW ID and invalidate
 * dependent review; on unchanged MediaStore/permission refresh, preserve existing ID.
 */
@JvmInline
value class NativeFaceObservationId(val localHex: String) {
    init { require(localHex.matches(Regex("[0-9a-f]{32}"))) }
}

object NativeFaceIds {
    fun createObservationId(): NativeFaceObservationId =
        NativeFaceObservationId(UUID.randomUUID().toString().replace("-", ""))
}

/** An imported, confirmed V4 source face is in a different namespace. No automatic
 * implicit cast, filename lookup or deterministic alias is allowed between the two.
 */
data class ConfirmedV4FaceRef(val importSha256: String, val v4FaceId: Long) {
    init {
        require(importSha256.matches(Regex("[0-9a-f]{64}")))
        require(v4FaceId > 0)
    }
}

/** U can show a candidate observation, but only X/I may implement an EXPLICIT,
 * human-reviewed attachment to a confirmed identity; this class is not an assignment.
 */
data class NativeFaceReviewReference(
    val id: NativeFaceObservationId,
    val media: FaceMediaKey,
    val detectorVersion: String
) {
    init { require(detectorVersion.isNotBlank()) }
}
