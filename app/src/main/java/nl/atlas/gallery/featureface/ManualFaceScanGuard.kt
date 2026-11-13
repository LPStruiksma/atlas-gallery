package nl.atlas.gallery.featureface

/**
 * Manual scanning is an ephemeral foreground privilege, never a background
 * WorkManager constraint override. Fail closed if either sensor is unavailable.
 * Android 29+ thermal status MODERATE is 2, but this policy is JVM-testable.
 */
internal data class ManualFaceScanGuard(
    val batteryPercent: Int?,
    val thermalStatus: Int?,
    val foreground: Boolean
) {
    fun holdReason(): String? = when {
        !foreground -> "ATLAS is in the background"
        batteryPercent == null -> "Battery status unavailable"
        batteryPercent < 30 -> "Battery below 30%"
        thermalStatus == null -> "Thermal status unavailable"
        thermalStatus >= 2 -> "Waiting for phone to cool"
        else -> null
    }
}
