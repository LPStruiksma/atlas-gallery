package nl.atlas.gallery

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-29 (#330): the viewer's video page lifecycle as a pure frame
 * simulation (no Media3, no Android). Timings mirror GalleryViewer: a page
 * settles 180 ms after it is focused and still; Media3's first rendered frame
 * arrives a synthetic [FIRST_FRAME_MS] after the player is mounted.
 */
class ViewerVideoLifecycleTest {
    private companion object {
        const val FRAME_MS = 16L
        const val SETTLE_MS = 180L
        const val SWIPE_ANIMATION_MS = 300L
        const val FIRST_FRAME_MS = 150L // synthetic, not a device measurement
    }

    @Test fun leavingForegroundPausesAndReturningNeverAutoStartsVideo() {
        for (event in Lifecycle.Event.entries) {
            val expected = event == Lifecycle.Event.ON_PAUSE ||
                event == Lifecycle.Event.ON_STOP
            assertEquals("Wrong background playback handling for $event",
                expected, viewerVideoMustPause(event))
        }
    }

    @Test fun posterStaysUntilFirstFrameAndOnlyTheFocusedSettledPagePlays() {
        assertEquals(ViewerVideoLayers(player = false, poster = true),
            viewerVideoLayers(focused = false, rapidlyChanging = false, settled = true, firstFrameRendered = false))
        assertEquals(ViewerVideoLayers(player = false, poster = true),
            viewerVideoLayers(focused = true, rapidlyChanging = true, settled = true, firstFrameRendered = true))
        assertEquals(ViewerVideoLayers(player = true, poster = true),
            viewerVideoLayers(focused = true, rapidlyChanging = false, settled = true, firstFrameRendered = false))
        assertEquals(ViewerVideoLayers(player = true, poster = false),
            viewerVideoLayers(focused = true, rapidlyChanging = false, settled = true, firstFrameRendered = true))
    }

    @Test fun tenSwipesPrepareOnePlayerPerSettledPageAndNeverShowBlack() {
        val after = simulate(swipeEveryMs = 700L, swipes = 10, newRule = true)
        val before = simulate(swipeEveryMs = 700L, swipes = 10, newRule = false)
        println("PERF-29 10 swipes @700 ms (synthetic first frame ${FIRST_FRAME_MS} ms): " +
            "before prepares=${before.prepares} black frames=${before.blackFrames}; " +
            "after prepares=${after.prepares} black frames=${after.blackFrames}")
        for (run in listOf(before, after)) {
            assertEquals("start page + 10 swipes", 11, run.prepares)
            assertEquals(run.prepares, run.releases)
            assertTrue("never two players", run.maxConcurrentPlayers <= 1)
        }
        assertEquals(0, after.blackFrames)
        assertTrue(before.blackFrames > 0)
    }

    @Test fun flingBurstPreparesNothingUntilTheLastPageSettles() {
        val run = simulate(swipeEveryMs = 100L, swipes = 10, newRule = true)
        assertEquals("only the final page", 1, run.prepares)
        assertEquals(0, run.blackFrames)
    }

    private data class Run(
        val prepares: Int, val releases: Int, val maxConcurrentPlayers: Int, val blackFrames: Int
    )

    private fun simulate(swipeEveryMs: Long, swipes: Int, newRule: Boolean): Run {
        val pages = swipes + 1
        val endMs = swipeEveryMs * swipes + 2_000L
        var current = 0
        var lastSwipeAt = Long.MIN_VALUE / 2
        val focusedStillSince = LongArray(pages) { -1L }
        val mountedAt = LongArray(pages) { -1L }
        var prepares = 0; var releases = 0; var maxConcurrent = 0; var black = 0
        var t = 0L
        while (t <= endMs) {
            if (current < swipes && t >= (current + 1) * swipeEveryMs) {
                current++
                lastSwipeAt = t
            }
            val rapidlyChanging = t - lastSwipeAt < SWIPE_ANIMATION_MS
            var concurrent = 0
            for (page in 0 until pages) {
                val focused = page == current
                if (!focused || rapidlyChanging) focusedStillSince[page] = -1L
                else if (focusedStillSince[page] < 0) focusedStillSince[page] = t
                val settled = focusedStillSince[page] >= 0 && t - focusedStillSince[page] >= SETTLE_MS
                val firstFrame = mountedAt[page] >= 0 && t - mountedAt[page] >= FIRST_FRAME_MS
                val layers = viewerVideoLayers(focused, rapidlyChanging, settled, firstFrame)
                if (layers.player && mountedAt[page] < 0) { mountedAt[page] = t; prepares++ }
                if (!layers.player && mountedAt[page] >= 0) { mountedAt[page] = -1L; releases++ }
                if (mountedAt[page] >= 0) concurrent++
                if (focused) {
                    val playerFrameVisible = mountedAt[page] >= 0 && t - mountedAt[page] >= FIRST_FRAME_MS
                    val posterVisible = if (newRule) layers.poster else !layers.player
                    if (!posterVisible && !playerFrameVisible) black++
                }
            }
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            t += FRAME_MS
        }
        // Leaving the viewer releases the last player.
        for (page in 0 until pages) if (mountedAt[page] >= 0) releases++
        return Run(prepares, releases, maxConcurrent, black)
    }
}
