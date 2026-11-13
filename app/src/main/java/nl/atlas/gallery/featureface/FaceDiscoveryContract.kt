package nl.atlas.gallery.featureface

/** Data-only, zero-dependency handshake for P (detection), U (review) and I (integration).
 * No identity inference and no V4 PeopleRepository writes occur in this module.
 */
object FaceDiscoveryContract {
    const val SCHEMA_VERSION = 1
    const val MAX_FACES_PER_IMAGE = 32
    const val MAX_CATALOG_BATCH = 1024
    const val DEFAULT_DECODE_LONG_EDGE_PX = 960
    const val MAX_IN_FLIGHT_DECODE = 1
}

enum class FaceScanPhase {
    NOT_CONFIGURED, NEVER_STARTED, QUEUED, RUNNING, PAUSED,
    PERMISSION_REQUIRED, STORAGE_LOW, BATTERY_LOW, REVIEW_AVAILABLE, COMPLETE, ERROR
}

/** Latest *fully committed* MediaStore permission-visible snapshot, never a raw path. */
data class FaceScanSnapshot(
    val permissionEpoch: String,
    val totalVisibleMedia: Int,
    val pending: Int,
    val processing: Int,
    val processed: Int,
    val failed: Int,
    val unknownObservations: Int,
    val scanVersion: String,
    val pausedByUser: Boolean
) {
    init {
        require(permissionEpoch.isNotBlank() && scanVersion.isNotBlank())
        require(listOf(totalVisibleMedia, pending, processing, processed, failed, unknownObservations)
            .all { it >= 0 })
        require(pending + processing + processed + failed <= totalVisibleMedia)
    }
}

data class FaceScanConditions(
    val optedIn: Boolean,
    val photosPermissionGranted: Boolean,
    val permissionSnapshotCurrent: Boolean,
    val batteryNotLow: Boolean,
    val storageNotLow: Boolean,
    val charging: Boolean,
    val pausedByUser: Boolean,
    val backgroundMaintenance: Boolean
) {
    /** Only manually requested scans may run unplugged. Never scan without active scope. */
    fun canRun(): Boolean = optedIn && photosPermissionGranted && permissionSnapshotCurrent &&
        batteryNotLow && storageNotLow && !pausedByUser && (!backgroundMaintenance || charging)

    fun holdReason(): FaceScanPhase? = when {
        !photosPermissionGranted || !permissionSnapshotCurrent -> FaceScanPhase.PERMISSION_REQUIRED
        !optedIn || pausedByUser -> FaceScanPhase.PAUSED
        !storageNotLow -> FaceScanPhase.STORAGE_LOW
        !batteryNotLow || (backgroundMaintenance && !charging) -> FaceScanPhase.BATTERY_LOW
        else -> null
    }
}

/** Ratios relative to the decoded, EXIF-oriented display frame (not original raw pixels). */
data class UnknownFaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }
}

/** Source identity/revision supplied by Lane M. Never derive from `_ID` alone. */
data class FaceMediaKey(val canonicalMediaKey: String, val sourceRevision: String) {
    init { require(canonicalMediaKey.isNotBlank() && sourceRevision.isNotBlank()) }
}

/** A detected face is unknown; tracking IDs within photos are not cross-photo identity IDs. */
data class UnknownFaceObservation(
    val media: FaceMediaKey,
    val ordinal: Int,
    val box: UnknownFaceBox,
    val detectorVersion: String,
    val permissionEpoch: String
) {
    init {
        require(ordinal in 0 until FaceDiscoveryContract.MAX_FACES_PER_IMAGE)
        require(detectorVersion.isNotBlank() && permissionEpoch.isNotBlank())
    }
}

/** Review of an observation only; these choices are NOT cross-photo person recognition. */
enum class UnknownFaceReviewDecision { UNREVIEWED, LATER, NOT_A_FACE }

data class UnknownFaceReviewRecord(
    val observation: UnknownFaceObservation,
    val decision: UnknownFaceReviewDecision
)
