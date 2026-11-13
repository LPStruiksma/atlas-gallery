package nl.atlas.gallery.featurepeopleui

/**
 * The identity/provenance subset of P's AuthorizedUnknownFace. Lane I maps
 * ONLY the accepted P FaceUnknownInboxBridge rows to these values on IO.
 * Never construct these from unverified MediaStore names, V4 People or a
 * raw detector result. URI and face box deliberately do not enter U state.
 */
data class AuthorizedUnknownReviewEvidence(
    val nativeFaceUid: String,
    val canonicalMediaKey: String,
    val sourceRevision: String,
    val detectorVersion: String,
    val ordinal: Int
)

/**
 * P's FaceMediaSourceKey publishes opaque lowercase SHA-256 digests with
 * versioned prefixes. This is defensive shape validation, NOT authorization:
 * P/I must still check the exact live source revision and Android grant.
 * Reject a path/URI here even if an incorrect integrator bypassed P.
 */
private val pFaceMediaDigest = Regex("atlas-face-media-v1:[0-9a-f]{64}")
private val pFaceRevisionDigest = Regex("atlas-face-rev-v1:[0-9a-f]{64}")

/**
 * Fail-closed view projection over the SAME published grant and X review
 * count as P's authorized inbox page. All parameters must come from one
 * current, committed IO-worker snapshot; no Compose-held epoch or cached
 * number is authoritative. The page cursor and rendering URIs stay with I.
 *
 * Null means "unavailable", NOT an empty inbox; I must not display a success
 * or a zero-unknown count when an epoch, count or source changed.
 *
 * The current sourceGeneration is a distinct I/X index-generation token,
 * never a model version or just the Android permission epoch. Increment
 * it on every index replacement and regrant.
 */
fun authorizedUnknownReviewQueueOrNull(
    pagePermissionEpoch: String,
    pageSourceGeneration: String,
    evidence: List<AuthorizedUnknownReviewEvidence>,
    actionableUnknownCount: Int?,
    hasMore: Boolean,
    currentScope: NativeReviewScope?
): NativeFaceReviewQueue? {
    if (currentScope == null ||
        pagePermissionEpoch != currentScope.permissionEpoch ||
        pageSourceGeneration != currentScope.sourceGeneration ||
        pagePermissionEpoch.isBlank() || pageSourceGeneration.isBlank() ||
        actionableUnknownCount == null || actionableUnknownCount < 0 ||
        evidence.size > MAX_NATIVE_REVIEW_PAGE ||
        actionableUnknownCount < evidence.size ||
        (hasMore && evidence.isEmpty())
    ) return null

    // Do not publish a partial page after any failed row. P already verifies
    // URI syntax, source revision, model and keyset ordering; U independently
    // verifies the review-key shape before allowing any button to be shown.
    val candidates = try {
        val seenImageSlots = HashSet<Pair<String, Int>>(evidence.size)
        evidence.map {
            require(seenImageSlots.add(it.canonicalMediaKey to it.ordinal)) {
                "Duplicate image/face ordinal in review page"
            }
            require(pFaceMediaDigest.matches(it.canonicalMediaKey) &&
                pFaceRevisionDigest.matches(it.sourceRevision) &&
                it.detectorVersion.isNotBlank() &&
                it.detectorVersion.length <= 256 &&
                '\u0000' !in it.detectorVersion) {
                "Unknown review requires P's exact opaque media and revision digests"
            }
            DetectedFaceReviewCandidate(
                key = DetectedFaceReviewKey(
                    nativeFaceUid = it.nativeFaceUid,
                    canonicalMediaKey = it.canonicalMediaKey,
                    sourceRevision = it.sourceRevision,
                    detectorVersion = it.detectorVersion,
                    permissionEpoch = pagePermissionEpoch,
                    ordinal = it.ordinal
                ),
                title = "Unknown face",
                explanation = "Detected in your photo; no person has been identified."
            )
        }
    } catch (_: IllegalArgumentException) {
        return null
    }

    return try {
        NativeFaceReviewQueue(
            permissionEpoch = pagePermissionEpoch,
            sourceGeneration = pageSourceGeneration,
            candidates = candidates,
            totalVisibleObservations = actionableUnknownCount,
            hasMore = hasMore
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}
