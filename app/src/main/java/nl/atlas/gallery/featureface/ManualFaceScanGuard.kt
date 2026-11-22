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

/**
 * A remembered manual-scan wait is only shown while the live guard still
 * holds. A "Waiting for phone to cool" from an earlier Scan now must clear
 * once the phone is cool again, rather than sticking until the next tap.
 * Returns the live reason (which may differ from the remembered one) or null.
 */
internal fun currentManualWait(remembered: String?, live: ManualFaceScanGuard): String? =
    if (remembered == null) null else live.holdReason()
