package nl.atlas.gallery.featureface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #61: no Android device, private media or real face model required. */
class ManualFaceScanGuardTest {
    @Test fun unpluggedRunAllowedAtThirtyPercentWhileForegroundAndCool() {
        assertNull(ManualFaceScanGuard(batteryPercent = 70, thermalStatus = 0,
            foreground = true).holdReason())
        assertNull(ManualFaceScanGuard(batteryPercent = 30, thermalStatus = 1,
            foreground = true).holdReason())
    }

    @Test fun lowBatteryStopsBeforeNextPhotoEvenWhenCharging() {
        assertEquals("Battery below 30%",
            ManualFaceScanGuard(29, 0, true).holdReason())
        assertEquals("Battery status unavailable",
            ManualFaceScanGuard(null, 0, true).holdReason())
    }

    @Test fun moderateThermalAndLeavingForegroundStopManualMode() {
        assertEquals("Waiting for phone to cool",
            ManualFaceScanGuard(80, 2, true).holdReason())
        assertEquals("Waiting for phone to cool",
            ManualFaceScanGuard(80, 3, true).holdReason())
        assertEquals("ATLAS is in the background",
            ManualFaceScanGuard(80, 0, false).holdReason())
        assertEquals("Thermal status unavailable",
            ManualFaceScanGuard(80, null, true).holdReason())
    }
}
