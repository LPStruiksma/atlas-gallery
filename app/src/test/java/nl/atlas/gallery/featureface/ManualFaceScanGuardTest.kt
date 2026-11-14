package nl.atlas.gallery.featureface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic conditions only: no media or face model. */
class ManualFaceScanGuardTest {
    @Test fun foregroundUnpluggedAtThirtyIsAllowed() {
        assertNull(ManualFaceScanGuard(80, 0, true).holdReason())
        assertNull(ManualFaceScanGuard(30, 1, true).holdReason())
    }

    @Test fun batteryAndThermalThresholdsStopBeforeNextPhoto() {
        assertEquals("Battery below 30%", ManualFaceScanGuard(29, 0, true).holdReason())
        assertEquals("Waiting for phone to cool", ManualFaceScanGuard(60, 2, true).holdReason())
        assertEquals("Waiting for phone to cool", ManualFaceScanGuard(60, 4, true).holdReason())
    }

    @Test fun missingSensorOrBackgroundFailsClosed() {
        assertEquals("ATLAS is in the background", ManualFaceScanGuard(90, 0, false).holdReason())
        assertEquals("Battery status unavailable", ManualFaceScanGuard(null, 0, true).holdReason())
        assertEquals("Thermal status unavailable", ManualFaceScanGuard(90, null, true).holdReason())
    }
}
