package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** #509: the big photo is locked to the filmstrip centre and never jumps on release. */
class ViewerScrubOverlayTest {
    @Test fun whileScrubbingTheOverlayAlwaysShowsTheLiveCentre() {
        // Pager still on 10; the strip centre has raced ahead to 57.
        assertEquals(57, viewerScrubOverlayIndex(57, 40, 10, pagerMoving = false))
        assertEquals(3, viewerScrubOverlayIndex(3, null, 10, pagerMoving = true))
    }

    @Test fun afterReleaseTheTargetIsHeldUntilThePagerHasLandedOnIt() {
        assertEquals(57, viewerScrubOverlayIndex(null, 57, 10, pagerMoving = false))
        assertEquals(57, viewerScrubOverlayIndex(null, 57, 57, pagerMoving = true))
        assertNull(viewerScrubOverlayIndex(null, 57, 57, pagerMoving = false))
    }

    @Test fun noScrubNoOverlay() {
        assertNull(viewerScrubOverlayIndex(null, null, 4, pagerMoving = true))
        assertNull(viewerScrubOverlayIndex(null, null, 4, pagerMoving = false))
    }

    @Test fun swipeUpOpensDetailsAndSwipeDownClosesOnlyWhenUnzoomed() {
        assertEquals(ViewerVerticalSwipe.DETAILS, viewerVerticalSwipe(-200f, 150f, 1f))
        assertEquals(ViewerVerticalSwipe.CLOSE, viewerVerticalSwipe(220f, 150f, 1f))
        assertEquals(ViewerVerticalSwipe.NONE, viewerVerticalSwipe(-90f, 150f, 1f))
        assertEquals(ViewerVerticalSwipe.NONE, viewerVerticalSwipe(-400f, 150f, 2.3f))
        assertEquals(ViewerVerticalSwipe.NONE, viewerVerticalSwipe(Float.NaN, 150f, 1f))
    }
    @Test fun downwardExitGateRejectsDiagonalEarlyAndReversedGestures() {
        assertEquals(false, viewerMayStartDownExit(120f, 100f, 40L, false, 12f))
        assertEquals(false, viewerMayStartDownExit(120f, 40f, 20L, false, 12f))
        assertEquals(false, viewerMayStartDownExit(120f, 40f, 40L, true, 12f))
        assertEquals(false, viewerMayStartDownExit(100f, 40f, 40L, false, 100f))
        assertEquals(true, viewerMayStartDownExit(120f, 40f, 40L, false, 12f))
    }

    @Test fun downExitClockIsMeasuredFromPointerDownNotDragRecognition() {
        // With down at t=100 and slop crossed at t=119, a move at t=125
        // already qualifies. Starting the clock at slop crossing would fail.
        val downMillis = 100L
        val slopRecognitionMillis = 119L
        val releaseMoveMillis = 125L
        assertEquals(true, viewerMayStartDownExit(30f, 0f,
            releaseMoveMillis - downMillis, false, 12f))
        assertEquals(false, viewerMayStartDownExit(30f, 0f,
            releaseMoveMillis - slopRecognitionMillis, false, 12f))
    }

    @Test fun earlyUpwardStepCancelsDownExitEvenWithPositiveNetTravel() {
        val slop = 12f
        var cancelled = false
        cancelled = viewerEarlyUpwardReversal(cancelled, 90f, slop)
        assertEquals(false, cancelled)
        cancelled = viewerEarlyUpwardReversal(cancelled, -5f, slop)
        assertEquals(true, cancelled)
        assertEquals(false, viewerMayStartDownExit(85f, 0f, 40L, cancelled, slop))
        assertEquals(true, viewerEarlyUpwardReversal(cancelled, 100f, slop))
    }

    @Test fun verticalThenDiagonalReleaseMustSpringBackInsteadOfClosing() {
        val elapsedMs = 45L
        val slop = 12f
        // Eligible after the first vertical segment.
        assertEquals(true, viewerMayStartDownExit(90f, 0f, elapsedMs, false, slop))
        // Later sideways travel breaks V22's whole-gesture dy/dx > 1.4.
        val totalY = 180f
        val totalX = 150f
        val gateAtRelease = viewerMayStartDownExit(
            totalY, totalX, elapsedMs, false, slop)
        assertEquals(false, gateAtRelease)
        assertEquals(ViewerVerticalSwipe.NONE, viewerVerticalRelease(
            totalY, 1_800f, 150f, 1_200f, 1f, gateAtRelease))
    }

    @Test fun velocityCommitsEvenWhenTravelIsBelowTheDistanceThreshold() {
        assertEquals(ViewerVerticalSwipe.CLOSE,
            viewerVerticalRelease(28f, 1_700f, 150f, 1_200f, 1f, true))
        assertEquals(ViewerVerticalSwipe.DETAILS,
            viewerVerticalRelease(-28f, -1_700f, 150f, 1_200f, 1f, true))
        assertEquals(ViewerVerticalSwipe.NONE,
            viewerVerticalRelease(28f, 500f, 150f, 1_200f, 1f, true))
        assertEquals(ViewerVerticalSwipe.NONE,
            viewerVerticalRelease(220f, 2_000f, 150f, 1_200f, 2f, true))
        assertEquals(ViewerVerticalSwipe.NONE,
            viewerVerticalRelease(220f, 2_000f, 150f, 1_200f, 1f, false))
    }

    @Test fun scrubHapticResetsForEachRunIncludingAfterPagerMoves() {
        var last: Int? = null
        fun tick(current: Int?): Boolean {
            if (current == null) { last = null; return false }
            val shouldTick = viewerShouldTickScrub(last, current)
            last = current
            return shouldTick
        }
        assertEquals(false, tick(10))
        assertEquals(true, tick(11))
        assertEquals(false, tick(null))
        assertEquals(false, tick(42))
        assertEquals(true, tick(43))
    }

    @Test fun heldScrubTargetWinsForDetailsActions() {
        assertEquals(21, viewerVisibleActionIndex(21, 10, 64))
        assertEquals(10, viewerVisibleActionIndex(null, 10, 64))
        assertEquals(63, viewerVisibleActionIndex(200, 10, 64))
    }


    @Test
    fun favouriteAndTrashOnlyForMediaStoreItems() {
        assertTrue(viewerCanChangeInMediaStore("content://media/external/images/media/42", 34))
        assertTrue(viewerCanChangeInMediaStore("content://media/external/images/media/42", 30))
        // createFavoriteRequest/createTrashRequest are API 30+; minSdk is 29.
        assertFalse(viewerCanChangeInMediaStore("content://media/external/images/media/42", 29))
        assertFalse(viewerCanChangeInMediaStore("content://com.android.providers.media.documents/document/image%3A42", 34))
        assertFalse(viewerCanChangeInMediaStore("file:///sdcard/DCIM/a.jpg", 34))
        assertFalse(viewerCanChangeInMediaStore("atlas-fixture://1", 34))
    }
}
