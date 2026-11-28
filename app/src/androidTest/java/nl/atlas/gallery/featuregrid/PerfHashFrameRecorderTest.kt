package nl.atlas.gallery.featuregrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PERF-HASH (#592) review finding 2: the measurement plumbing stops cleanly.
 * About a second of real frames, no media, no UI.
 */
@RunWith(AndroidJUnit4::class)
class PerfHashFrameRecorderTest {
    @Test fun intervalRecorderStopsEvenWhenTheBodyThrows() {
        val recorder = FrameIntervalRecorder()
        var thrown = false
        try {
            recorder.start()
            awaitFrames(10)
            throw IllegalStateException("measured body failed")
        } catch (e: IllegalStateException) {
            thrown = true
        } finally {
            recorder.close()
        }
        assertTrue(thrown)
        val afterClose = recorder.frames()
        assertTrue("recorded $afterClose frames before close", afterClose >= 10)
        awaitFrames(15)
        assertEquals("frames recorded after close", afterClose, recorder.frames())
    }

    @Test fun intervalRecorderReturnsIntervalsAndCanRestart() {
        val recorder = FrameIntervalRecorder()
        recorder.start()
        awaitFrames(12)
        val first = recorder.stop()
        assertTrue("intervals ${first.size}", first.size >= 10 && first.all { it > 0 })
        recorder.start()
        awaitFrames(6)
        val second = recorder.stop()
        assertTrue("restart intervals ${second.size}", second.size in 4..(first.size))
    }

    @Test fun coverageSamplerSeparatesBlankCoveredAndEpisodes() {
        val sampler = TileCoverageSampler()
        sampler.start()
        val ms = 1_000_000L
        onMain {
            // Frame 1: tile 1 blank and uncovered, tile 2 blank but covered, tile 3 shows pixels.
            assertTrue(sampler.beginFrame(16 * ms))
            sampler.tile(1, blank = true, covered = false)
            sampler.tile(2, blank = true, covered = true)
            sampler.tile(3, blank = false, covered = false)
            sampler.endFrame()
            // A second draw in the same frame is not counted twice.
            assertFalse(sampler.beginFrame(16 * ms))
            // Frame 2: tile 1 fills in, tile 2 still covered, tile 3 scrolled away.
            assertTrue(sampler.beginFrame(32 * ms))
            sampler.tile(1, blank = false, covered = false)
            sampler.tile(2, blank = true, covered = true)
            sampler.endFrame()
        }
        val s = sampler.stop()
        assertEquals(2, s.frames)
        assertEquals(5L, s.tileFrames)
        assertEquals(3L, s.blankTileFrames)
        assertEquals(2L, s.coveredTileFrames)
        assertEquals(1L, s.uncoveredTileFrames)
        assertEquals(2, s.framesWithBlank)
        assertEquals(1, s.framesWithUncovered)
        // Tile 1 blank and uncovered for 16 ms; tile 2 blank from 16 ms to the last frame.
        assertEquals(listOf(16 * ms, 16 * ms), s.blankEpisodes.sorted())
        assertEquals(listOf(16 * ms), s.uncoveredEpisodes)
        // Not recording after stop.
        onMain { assertFalse(sampler.beginFrame(48 * ms)) }
    }

    private fun awaitFrames(count: Int) = awaitChoreographerFrames(count)
}
