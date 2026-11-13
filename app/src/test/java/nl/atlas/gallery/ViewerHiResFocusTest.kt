package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Pure regression against stale retained-pager readiness: no media decode. */
class ViewerHiResFocusTest {
    @Test fun noLargeDecodeUntilFocusedAndSettled() {
        assertFalse(shouldRenderLargePhoto(false, false, false))
        assertFalse(shouldRenderLargePhoto(true, false, false))
        assertFalse(shouldRenderLargePhoto(false, true, false))
        assertFalse(shouldRenderLargePhoto(false, true, true))
        assertFalse(shouldRenderLargePhoto(true, false, true))
        assertTrue(shouldRenderLargePhoto(true, true, true))
    }

    @Test fun selectedVideoNeedsItsOwnSettledFocusBeforePlayerAllocation() {
        assertFalse(shouldStartViewerVideoPlayer(false, false, true))
        assertFalse(shouldStartViewerVideoPlayer(true, true, true))
        assertFalse(shouldStartViewerVideoPlayer(true, false, false))
        assertTrue(shouldStartViewerVideoPlayer(true, false, true))
        // Rapid tap/fling sequences cannot start a player while moving.
        repeat(10_000) { frame ->
            val focused = frame % 17 == 0
            val rapidlyChanging = frame % 3 != 0
            if (!focused || rapidlyChanging) {
                assertFalse(shouldStartViewerVideoPlayer(
                    focused, rapidlyChanging, true))
            }
        }
    }

    @Test fun largeDecodeNotReenabledByAnOldPageReadyFlagDuringScrub() {
        var priorPageSettled = true
        // A previously zoomed page may remain composed in beyond-viewport
        // pages, but must never get heavyweight image work while not focused.
        repeat(10_000) { i ->
            val focused = i % 97 == 0
            val scrubbing = i % 5 != 0
            if (!focused || scrubbing) {
                assertFalse(shouldRenderLargePhoto(focused, !scrubbing, priorPageSettled))
            }
            priorPageSettled = true
        }
    }
}
