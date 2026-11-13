package nl.atlas.gallery

import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState

/**
 * #46: presentation only. All counts came from the grant-checked scanner
 * status; never infer a scan from an opt-in, a nonzero count or a stored row.
 */
internal fun peopleStatusLine(state: FaceDiscoveryUiState): String? {
    val label = when (state.phase) {
        FaceDiscoveryPhase.UNAVAILABLE, FaceDiscoveryPhase.READY -> null
        FaceDiscoveryPhase.QUEUED -> "Starting"
        FaceDiscoveryPhase.SCANNING -> "Finding people"
        FaceDiscoveryPhase.GROUPING -> "Grouping people"
        FaceDiscoveryPhase.PAUSED -> "Paused"
        FaceDiscoveryPhase.FINISHED, FaceDiscoveryPhase.REVIEW_AVAILABLE -> "Finished"
        FaceDiscoveryPhase.PERMISSION_REQUIRED -> "Permission required"
        FaceDiscoveryPhase.BATTERY_LOW -> "Battery low"
        FaceDiscoveryPhase.WAITING_FOR_CHARGER -> "Waiting for charger"
        FaceDiscoveryPhase.STORAGE_LOW -> "Storage low"
        FaceDiscoveryPhase.FAILED -> "Needs attention"
    } ?: return null
    val showCount = state.eligible > 0 && state.phase !in setOf(
        FaceDiscoveryPhase.PERMISSION_REQUIRED, FaceDiscoveryPhase.FAILED)
    return label + if (showCount) " · ${state.processed} of ${state.eligible}" else ""
}

/** The saved opt-in survives scope restoration; it never authorizes reading photos. */
internal fun faceScanSettingsValue(
    phase: FaceDiscoveryPhase?, loading: Boolean, savedOptIn: Boolean,
    photoAccess: Boolean = true
): String {
    if (!photoAccess && savedOptIn) return "Permission required"
    if (loading) return "Restoring…"
    return when (phase) {
        FaceDiscoveryPhase.SCANNING -> "Finding people · Pause"
        FaceDiscoveryPhase.GROUPING -> "Grouping people"
        FaceDiscoveryPhase.QUEUED -> "Starting"
        FaceDiscoveryPhase.PAUSED -> "Paused · Resume"
        FaceDiscoveryPhase.PERMISSION_REQUIRED -> "Permission required"
        FaceDiscoveryPhase.BATTERY_LOW -> "Battery low"
        FaceDiscoveryPhase.WAITING_FOR_CHARGER -> "Waiting for charger"
        FaceDiscoveryPhase.STORAGE_LOW -> "Storage low"
        FaceDiscoveryPhase.FAILED -> "Needs attention"
        FaceDiscoveryPhase.READY -> "Not started"
        FaceDiscoveryPhase.FINISHED, FaceDiscoveryPhase.REVIEW_AVAILABLE -> "Finished"
        FaceDiscoveryPhase.UNAVAILABLE, null -> if (savedOptIn) "Restoring…" else "Unavailable"
    }
}

/**
 * Visual indexing is a separate queue in the same worker. Do not claim it is
 * finding people or actively indexing before its own done counter advances.
 */
internal fun visualIndexStatusText(
    enabled: Boolean, status: VisualIndexStatus?, phase: FaceDiscoveryPhase?,
    loading: Boolean, photoAccess: Boolean = true
): String {
    if (!enabled) return "Off"
    if (!photoAccess || phase == FaceDiscoveryPhase.PERMISSION_REQUIRED) return "Permission required"
    if (status?.failure != null || phase == FaceDiscoveryPhase.FAILED) return "Needs attention"
    if (loading || phase == null || phase == FaceDiscoveryPhase.UNAVAILABLE) return "Restoring…"
    when (phase) {
        FaceDiscoveryPhase.PAUSED -> return "Paused"
        FaceDiscoveryPhase.WAITING_FOR_CHARGER -> return "Waiting for charger"
        FaceDiscoveryPhase.BATTERY_LOW -> return "Battery low"
        FaceDiscoveryPhase.STORAGE_LOW -> return "Storage low"
        FaceDiscoveryPhase.GROUPING -> return "Waiting for scan"
        else -> Unit
    }
    val done = status?.done
    val total = status?.total
    if (done != null && total != null) {
        if (done >= total) return "Finished"
        if (done > 0) return "Indexing photos"
    }
    return "Starting"
}
