package nl.atlas.gallery.featurepeopleui

/** Each page comes from a single committed, permission-visible X FaceIndexStore snapshot. */
const val MAX_NATIVE_REVIEW_PAGE = 50

/**
 * A bounded, immutable face-detection review page. There are no identity
 * suggestions or V4 People assignments in this data structure. Do not pass
 * MediaStore or V4 rows from an uncommitted, inaccessible source.
 */
private fun freezeReviewPage(
    source: List<DetectedFaceReviewCandidate>
): List<DetectedFaceReviewCandidate> {
    require(source.size <= MAX_NATIVE_REVIEW_PAGE)
    // A caller must not mutate a page after validation, nor can an exposed
    // MutableList cast change the already checked UID/epoch membership.
    return java.util.Collections.unmodifiableList(ArrayList(source))
}

class NativeFaceReviewQueue(
    val permissionEpoch: String,
    val sourceGeneration: String,
    candidates: List<DetectedFaceReviewCandidate>,
    val totalVisibleObservations: Int,
    /** Page token remains private to I/X and is never written to logs/semantics. */
    val hasMore: Boolean
) {
    val candidates: List<DetectedFaceReviewCandidate> = freezeReviewPage(candidates)

    init {
        require(permissionEpoch.isNotBlank() && sourceGeneration.isNotBlank())
        require(this.candidates.size <= MAX_NATIVE_REVIEW_PAGE)
        require(totalVisibleObservations >= this.candidates.size)
        require(this.candidates.all { it.key.validFor(permissionEpoch) })
        require(this.candidates.map { it.key.nativeFaceUid }.toSet().size == this.candidates.size) {
            "Duplicate native face observation UID in review page"
        }
    }
}

/**
 * Live authorization provided by Lane I's latest *committed* photo grant AND
 * X's reviewed observation-index generation. This is NOT a Person identity
 * or a stored token. It must advance for every replacement/regrant/rescan.
 */
data class NativeReviewScope(
    val permissionEpoch: String,
    val sourceGeneration: String
) {
    init { require(permissionEpoch.isNotBlank() && sourceGeneration.isNotBlank()) }
}

/**
 * One coherent action-time read from Lane I's current state holder.
 * The page is compared by reference: two pages from the SAME detector/grant
 * still cannot reuse each other's old buttons or pagination cursors.
 */
data class NativeReviewActionSnapshot(
    val scope: NativeReviewScope,
    val page: NativeFaceReviewQueue?
)

/** Epoch AND current source-generation admission; a stale page returns null. */
fun currentNativeFaceReviewQueueOrNull(
    queue: NativeFaceReviewQueue?,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?
): NativeFaceReviewQueue? = queue?.takeIf {
    !currentPermissionEpoch.isNullOrBlank() &&
        !currentSourceGeneration.isNullOrBlank() &&
        it.permissionEpoch == currentPermissionEpoch &&
        it.sourceGeneration == currentSourceGeneration
}

/** Reject attempts to review a UID no longer included in the CURRENT page. */
fun currentNativeReviewCommandOrNull(
    queue: NativeFaceReviewQueue?,
    key: DetectedFaceReviewKey,
    decision: DetectionReviewChoice,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?
): NativeFaceReviewCommand? {
    val current = currentNativeFaceReviewQueueOrNull(
        queue, currentPermissionEpoch, currentSourceGeneration
    ) ?: return null
    if (current.candidates.none { it.key == key }) return null
    return nativeReviewCommandOrNull(key, decision, currentPermissionEpoch)
}

/**
 * Event-time gate for a Compose callback captured *before* a regrant/rescan.
 * UI-render state is NOT the current scope. The caller must read latest live
 * state at the instant of interaction, not close over earlier Compose values.
 * The X SQLite write must separately enforce active epoch and exact face_uid.
 */
fun currentNativeReviewCommandWithLiveScopeOrNull(
    queue: NativeFaceReviewQueue?,
    key: DetectedFaceReviewKey,
    decision: DetectionReviewChoice,
    renderedPermissionEpoch: String?,
    renderedSourceGeneration: String?,
    liveStateAtAction: NativeReviewActionSnapshot?
): NativeFaceReviewCommand? {
    if (liveStateAtAction == null || queue == null ||
        liveStateAtAction.page !== queue ||
        liveStateAtAction.scope.permissionEpoch != renderedPermissionEpoch ||
        liveStateAtAction.scope.sourceGeneration != renderedSourceGeneration
    ) return null
    return currentNativeReviewCommandOrNull(
        queue, key, decision, renderedPermissionEpoch, renderedSourceGeneration
    )
}

/** Read-only pagination is equally scope sensitive; never load old pages. */
fun nativeReviewNextPageAllowed(
    queue: NativeFaceReviewQueue?,
    renderedPermissionEpoch: String?,
    renderedSourceGeneration: String?,
    liveStateAtAction: NativeReviewActionSnapshot?
): Boolean =
    queue != null && liveStateAtAction != null &&
        liveStateAtAction.page === queue &&
        liveStateAtAction.scope.permissionEpoch == renderedPermissionEpoch &&
        liveStateAtAction.scope.sourceGeneration == renderedSourceGeneration &&
        currentNativeFaceReviewQueueOrNull(
            queue, renderedPermissionEpoch, renderedSourceGeneration
        )?.hasMore == true
