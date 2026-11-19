package nl.atlas.gallery.featurepeopleui

/**
 * Dependency-free bridge for Lane I until Lane P's featureface branch is
 * integrated. I passes FaceScanPhase.name and the persisted FaceScanSnapshot's
 * counters; no direct P dependency or Android/Compose state belongs here.
 *
 * An enum name is NOT detector capability or provenance. If any independent
 * evidence is missing, this adapter shows the existing truthful UNAVAILABLE
 * state. It never starts work or writes a face-review decision.
 */
data class NativeFaceScanDisplayEvidence(
    /** Exact Lane P FaceScanPhase.name value; unknown values are rejected. */
    val phaseName: String,
    val observedPermissionEpoch: String,
    val totalVisibleMedia: Int,
    val processed: Int,
    val unknownObservations: Int,
    /** Actual native locator/queue/index backend is installed and callable. */
    val nativeDetectorInstalled: Boolean,
    /** An explicit user opt-in/start operation is wired, not MediaStore refresh. */
    val realStartActionAvailable: Boolean = false
) {
    init {
        require(totalVisibleMedia >= 0 && processed in 0..totalVisibleMedia &&
            unknownObservations >= 0)
    }
}

/**
 * P's phase names are explicit. Future unknown phases FAIL CLOSED to
 * UNAVAILABLE rather than unexpectedly enabling a scanner or review action.
 */
fun presentNativeFaceScan(
    source: NativeFaceScanDisplayEvidence?,
    currentPermissionEpoch: String?
): FaceDiscoveryUiState {
    if (source == null || currentPermissionEpoch.isNullOrBlank() ||
        source.observedPermissionEpoch != currentPermissionEpoch ||
        !source.nativeDetectorInstalled
    ) return FaceDiscoveryUiState()

    val phase = when (source.phaseName) {
        "NOT_CONFIGURED" -> FaceDiscoveryPhase.UNAVAILABLE
        "NEVER_STARTED" ->
            if (source.realStartActionAvailable) FaceDiscoveryPhase.READY
            else FaceDiscoveryPhase.UNAVAILABLE
        "QUEUED" -> FaceDiscoveryPhase.QUEUED
        "RUNNING" -> FaceDiscoveryPhase.SCANNING
        "GROUPING" -> FaceDiscoveryPhase.GROUPING
        "PAUSED" -> FaceDiscoveryPhase.PAUSED
        "PERMISSION_REQUIRED" -> FaceDiscoveryPhase.PERMISSION_REQUIRED
        "STORAGE_LOW" -> FaceDiscoveryPhase.STORAGE_LOW
        "BATTERY_LOW" -> FaceDiscoveryPhase.BATTERY_LOW
        "CHARGER_REQUIRED" -> FaceDiscoveryPhase.WAITING_FOR_CHARGER
        "REVIEW_AVAILABLE" -> FaceDiscoveryPhase.REVIEW_AVAILABLE
        "COMPLETE" -> FaceDiscoveryPhase.FINISHED
        "ERROR" -> FaceDiscoveryPhase.FAILED
        else -> FaceDiscoveryPhase.UNAVAILABLE
    }
    if (phase == FaceDiscoveryPhase.UNAVAILABLE) return FaceDiscoveryUiState()
    return FaceDiscoveryUiState(
        phase = phase,
        processed = source.processed,
        eligible = source.totalVisibleMedia,
        unknownObservations = source.unknownObservations,
        observedPermissionEpoch = source.observedPermissionEpoch
        // Never show raw detector/provider failure exception strings in People.
    )
}
