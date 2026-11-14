package nl.atlas.gallery.featureface

/**
 * Foreground-only manual face scanning capability (#61).
 * Missing battery or thermal readings fail closed rather than claiming safety.
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
