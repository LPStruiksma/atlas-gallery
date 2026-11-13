package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/**
 * Only synthetic album-relative indexes, no Android media or photo access.
 * These are request semantics, not emulator frame-time assertions.
 */
class ViewerJumpIntentTest {
    @Test fun latestTapCarriesUniqueSequenceEvenWhenTheSameTileIsPressedAgain() {
        var request: ViewerJumpIntent? = null
        val clicks = listOf(10, 11, 8, 11, 11, 3, 63)
        val observed = ArrayList<ViewerJumpIntent>()
        clicks.forEach { index ->
            request = nextViewerJumpIntent(request, index, 64)
            observed.add(requireNotNull(request))
        }
        assertEquals(clicks, observed.map { it.index })
        assertEquals((1L..clicks.size.toLong()).toList(),
            observed.map { it.sequence })
        assertEquals(63, request?.index)
        // Structurally equal index alone is insufficient to represent a new
        // click; distinct sequence makes cancellation/reprocessing possible.
        assertNotEquals(observed[3], observed[4])
    }

    @Test fun aLargeAlbumJumpNeverRequiresWalkingIntermediateIndices() {
        val first = nextViewerJumpIntent(null, 149_999, 150_000)
        val back = nextViewerJumpIntent(first, 0, 150_000)
        assertEquals(ViewerJumpIntent(149_999, 1L), first)
        assertEquals(ViewerJumpIntent(0, 2L), back)
    }

    @Test fun endingOldFlingNeverOverridesNewerTapOrCoarseSeek() {
        val newestTap = nextViewerJumpIntent(null, 37, 150_000)
        assertTrue(mayCommitFinishedStripGesture(
            wasScrubbing = true, coarseSeeking = false,
            controlledScroll = false, newerExplicitIntent = null))
        assertFalse(mayCommitFinishedStripGesture(
            wasScrubbing = true, coarseSeeking = false,
            controlledScroll = false, newerExplicitIntent = newestTap))
        assertFalse(mayCommitFinishedStripGesture(
            wasScrubbing = true, coarseSeeking = true,
            controlledScroll = false, newerExplicitIntent = null))
        assertFalse(mayCommitFinishedStripGesture(
            wasScrubbing = true, coarseSeeking = false,
            controlledScroll = true, newerExplicitIntent = null))
        assertFalse(mayCommitFinishedStripGesture(
            wasScrubbing = false, coarseSeeking = false,
            controlledScroll = false, newerExplicitIntent = null))
    }

    @Test fun outOfCurrentAlbumTapFailsClosedWithoutCreatingRequest() {
        for ((index, size) in listOf(-1 to 8, 8 to 8, 0 to 0, 5 to -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                nextViewerJumpIntent(null, index, size)
            }
        }
    }
}
