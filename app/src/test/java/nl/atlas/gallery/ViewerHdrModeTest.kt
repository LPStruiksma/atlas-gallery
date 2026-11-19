package nl.atlas.gallery

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** BENCH-1 (#572): the focused-photo window colour mode decision, no device. */
class ViewerHdrModeTest {
    private val default = ActivityInfo.COLOR_MODE_DEFAULT

    @Test fun focusedGainmapPhotoInForegroundOnApi34IsHdr() {
        assertEquals(ActivityInfo.COLOR_MODE_HDR, viewerWindowColorMode(34, true, true, default))
        assertEquals(ActivityInfo.COLOR_MODE_HDR, viewerWindowColorMode(36, true, true, default))
    }

    @Test fun ordinarySdrPhotoKeepsThePriorMode() {
        assertEquals(default, viewerWindowColorMode(35, false, true, default))
    }

    @Test fun belowApi34NothingChanges() {
        assertEquals(default, viewerWindowColorMode(33, true, true, default))
        assertEquals(default, viewerWindowColorMode(29, true, true, default))
    }

    @Test fun backgroundActivityRestoresThePriorMode() {
        assertEquals(default, viewerWindowColorMode(35, true, false, default))
    }

    @Test fun priorWideGamutModeIsRestoredRatherThanForcedToDefault() {
        val wide = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        assertEquals(wide, viewerWindowColorMode(35, false, true, wide))
        assertEquals(ActivityInfo.COLOR_MODE_HDR, viewerWindowColorMode(35, true, true, wide))
    }
}
