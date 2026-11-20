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

/** BENCH-1 (#572) review: which facts make the focused photo HDR. */
class ViewerFocusedGainmapTest {
    private val uri = "content://media/external/images/media/572"
    private val proven = mapOf(viewerHdrFactKey(uri, 0L) to true)

    @Test fun provenGainmapPhotoWithoutScrubIsHdr() {
        assertEquals(true, viewerFocusedHasGainmap(uri, false, 0L, false, proven))
    }

    @Test fun filmstripScrubPreviewForcesSdr() {
        assertEquals(false, viewerFocusedHasGainmap(uri, false, 0L, true, proven))
    }

    @Test fun editorReturnRevisionMustProveItsGainmapAgain() {
        val revision = viewerHdrRevision(mustReload = true, editorRevision = 3L)
        assertEquals(3L, revision)
        // Before the fresh decode: no fact for revision 3, so SDR.
        assertEquals(false, viewerFocusedHasGainmap(uri, false, revision, false, proven))
        // Edited to SDR: the fresh decode records false.
        val edited = proven + (viewerHdrFactKey(uri, revision) to false)
        assertEquals(false, viewerFocusedHasGainmap(uri, false, revision, false, edited))
        assertEquals(0L, viewerHdrRevision(mustReload = false, editorRevision = 3L))
    }

    @Test fun failedDecodeRecordedFalseIsSdr() {
        val failed = mapOf(viewerHdrFactKey(uri, 0L) to false)
        assertEquals(false, viewerFocusedHasGainmap(uri, false, 0L, false, failed))
    }

    @Test fun videoAndMissingFocusAreSdr() {
        assertEquals(false, viewerFocusedHasGainmap(uri, true, 0L, false, proven))
        assertEquals(false, viewerFocusedHasGainmap(null, false, 0L, false, proven))
    }
}
