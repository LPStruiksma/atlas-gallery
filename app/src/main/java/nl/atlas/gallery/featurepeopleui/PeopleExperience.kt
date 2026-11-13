package nl.atlas.gallery.featurepeopleui

/**
 * State published by a REAL on-device face-index owner (Lane P/I).
 * Never infer discovery availability from media count or imported V4 profiles.
 * All counters refer to accessible material in the CURRENT permission epoch.
 */
enum class FaceDiscoveryPhase {
    UNAVAILABLE, READY, QUEUED, SCANNING, PAUSED, FINISHED, FAILED,
    PERMISSION_REQUIRED, BATTERY_LOW, STORAGE_LOW, REVIEW_AVAILABLE
}

data class FaceDiscoveryUiState(
    val phase: FaceDiscoveryPhase = FaceDiscoveryPhase.UNAVAILABLE,
    val processed: Int = 0,
    val eligible: Int = 0,
    val unknownObservations: Int = 0,
    val errorMessage: String? = null,
    /** Authoritative grant epoch from Lane I; never reuse across selection changes. */
    val observedPermissionEpoch: String? = null
) {
    init {
        require(processed >= 0 && eligible >= 0 && processed <= eligible)
        require(unknownObservations >= 0)
    }

    val progress: Float? get() = if (eligible > 0) processed.toFloat() / eligible else null
    val isBusy: Boolean get() = phase == FaceDiscoveryPhase.QUEUED ||
        phase == FaceDiscoveryPhase.SCANNING

    /** Revocation/reselection must immediately hide stale scanner status/counts. */
    fun restrictedToGrant(currentPermissionEpoch: String?): FaceDiscoveryUiState =
        if (currentPermissionEpoch != null &&
            observedPermissionEpoch == currentPermissionEpoch) this
        else FaceDiscoveryUiState()
}

data class PeopleProfileUi(
    val personId: Long,
    val name: String,
    val accessiblePhotos: Int,
    val confirmedPhotoReferences: Int,
    /** Only a permission-checked, MediaStore URI may be passed, never a raw V4 path. */
    val coverUri: String? = null,
    /** A crop is allowed only after the optional sidecar trust gate succeeds. */
    val verifiedPortrait: PortraitCropBinding? = null
) {
    init {
        require(accessiblePhotos >= 0 && confirmedPhotoReferences >= accessiblePhotos)
    }
}

data class PeopleHomeUiState(
    val discovery: FaceDiscoveryUiState = FaceDiscoveryUiState(),
    val profiles: List<PeopleProfileUi> = emptyList(),
    val v4SyncSummary: String? = null,
    val importedConfirmedLabels: Boolean = profiles.isNotEmpty()
)

/**
 * Exactly mirrors Lane P's UNKNOWN observation review journal, not a cross-photo
 * identity decision. "Same person" is deliberately impossible in this API.
 * UNREVIEWED restores a previous reversible skip/not-a-face decision.
 */
enum class DetectionReviewChoice { UNREVIEWED, LATER, NOT_A_FACE }

/**
 * Every observation decision is scoped to one revision of one visible media
 * item, one detector and one Android grant. Face ordinal alone is NEVER an ID.
 * Mirrors Lane P's UnknownFaceObservation; there is no synthetic person_id.
 */
data class DetectedFaceReviewKey(
    /** X's random installation-local 32-lowercase-hex face_uid. NEVER a V4 face_id. */
    val nativeFaceUid: String,
    val canonicalMediaKey: String,
    val sourceRevision: String,
    val detectorVersion: String,
    val permissionEpoch: String,
    val ordinal: Int
) {
    init {
        require(nativeFaceUid.length == 32 && nativeFaceUid.all {
            it in '0'..'9' || it in 'a'..'f'
        }) { "Native face observation UID must be a 32-character lowercase hex token" }
        require(listOf(canonicalMediaKey, sourceRevision, detectorVersion,
            permissionEpoch).all { it.isNotBlank() })
        require(ordinal in 0 until 32) // Lane P FaceDiscoveryContract.MAX_FACES_PER_IMAGE
    }

    fun validFor(currentPermissionEpoch: String?): Boolean =
        !currentPermissionEpoch.isNullOrBlank() && permissionEpoch == currentPermissionEpoch
}

/**
 * Exact persistence command: the ONLY native review target is X's stable UID,
 * paired with the current permission grant. The DB, not the UI, must verify
 * that the UID still refers to a processed observation in this active grant.
 */
data class NativeFaceReviewCommand(
    val permissionEpoch: String,
    val nativeFaceUid: String,
    val decision: DetectionReviewChoice
)

/** Prevent old/regranted observation buttons from even constructing a write. */
fun nativeReviewCommandOrNull(
    key: DetectedFaceReviewKey,
    decision: DetectionReviewChoice,
    currentPermissionEpoch: String?
): NativeFaceReviewCommand? = if (key.validFor(currentPermissionEpoch)) {
    NativeFaceReviewCommand(key.permissionEpoch, key.nativeFaceUid, decision)
} else null

/** UI title and description are not an identity match score or recognized name. */
data class DetectedFaceReviewCandidate(
    val key: DetectedFaceReviewKey,
    val title: String,
    val explanation: String,
    val decision: DetectionReviewChoice = DetectionReviewChoice.UNREVIEWED
)

data class PeopleActionAvailability(
    val mayStartScan: Boolean = false,
    val mayPauseScan: Boolean = false,
    val mayResumeScan: Boolean = false,
    val mayOpenReview: Boolean = false
)

/** Copy for an accessible screen; no pretend "indexing" button without an action. */
fun discoveryHeadline(state: FaceDiscoveryUiState): String = when (state.phase) {
    FaceDiscoveryPhase.UNAVAILABLE -> "Face discovery isn't in this build yet"
    FaceDiscoveryPhase.READY -> "Ready to discover faces on this device"
    FaceDiscoveryPhase.QUEUED -> "Face discovery is queued"
    FaceDiscoveryPhase.SCANNING -> "Looking for faces on this device"
    FaceDiscoveryPhase.PAUSED -> "Face discovery is paused"
    FaceDiscoveryPhase.FINISHED -> "Face discovery finished"
    FaceDiscoveryPhase.FAILED -> "Face discovery needs attention"
    FaceDiscoveryPhase.PERMISSION_REQUIRED -> "Photo access needs an update"
    FaceDiscoveryPhase.BATTERY_LOW -> "Face discovery is waiting for power"
    FaceDiscoveryPhase.STORAGE_LOW -> "Face discovery is waiting for storage"
    FaceDiscoveryPhase.REVIEW_AVAILABLE -> "Unknown faces ready for review"
}

fun discoveryExplanation(state: FaceDiscoveryUiState): String = when (state.phase) {
    FaceDiscoveryPhase.UNAVAILABLE ->
        "Your photo library can refresh, but that does not scan faces. For now, People shows only labels you already reviewed and exported from V4."
    FaceDiscoveryPhase.READY ->
        "Detection finds faces, not names. Any new faces will remain unknown until you review them."
    FaceDiscoveryPhase.QUEUED ->
        "The on-device scan will begin when the actual scanner's requirements are met."
    FaceDiscoveryPhase.SCANNING, FaceDiscoveryPhase.PAUSED ->
        "Detection is local and resumable. An unknown face is never automatically assigned to a person."
    FaceDiscoveryPhase.FINISHED ->
        "Detected faces are not identified people. Review available unknown observations separately."
    FaceDiscoveryPhase.FAILED ->
        state.errorMessage?.take(240) ?: "No original photo was changed. You can review the scanner status."
    FaceDiscoveryPhase.PERMISSION_REQUIRED ->
        "Your photo access has changed. No old face observations will be shown until the permitted catalogue is checked again."
    FaceDiscoveryPhase.BATTERY_LOW ->
        "Scanning is suspended to respect battery limits. No photo or confirmed label was changed."
    FaceDiscoveryPhase.STORAGE_LOW ->
        "Scanning is suspended because the device reports low storage. ATLAS will not make room by deleting photos."
    FaceDiscoveryPhase.REVIEW_AVAILABLE ->
        "These are face locations, not identified people. You can mark a detection as not a face or postpone it."
}
