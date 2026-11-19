package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERF-9 (#138): the swipe prefetch window, its direction and its memory gate. */
class ViewerSwipePrefetchTest {
    private val noVideos: (Int) -> Boolean = { false }
    private fun plan(focus: Int, previous: Int, count: Int = 100, bases: Int = 2,
                     isVideo: (Int) -> Boolean = noVideos) =
        viewerSwipePrefetchPlan(focus, previous, count, bases, isVideo)
    private fun step(index: Int, base: Boolean) = ViewerPrefetchStep(index, base)

    @Test fun forwardSwipeWarmsTheNextBaseFirst() {
        assertEquals(listOf(step(11, true), step(9, true), step(12, false), step(8, false)),
            plan(10, 9))
    }

    @Test fun backwardSwipeWarmsThePreviousBaseFirst() {
        assertEquals(listOf(step(9, true), step(11, true), step(8, false), step(12, false)),
            plan(10, 11))
    }

    @Test fun aJumpKeepsItsDirection() {
        assertEquals(step(51, true), plan(50, 3).first())
        assertEquals(step(49, true), plan(50, 97).first())
    }

    @Test fun noKnownDirectionStartsForward() {
        assertEquals(listOf(step(11, true), step(9, true), step(12, false), step(8, false)),
            plan(10, 10))
        assertEquals(plan(10, 10), plan(10, -1))
        assertEquals(plan(10, 10), plan(10, 100))
    }

    @Test fun atMostOneBaseOnEachSide() {
        for (focus in 0 until 20) for (previous in -1..20) {
            val steps = plan(focus, previous, count = 20)
            val bases = steps.filter { it.base }.map { it.index - focus }
            assertTrue("$focus/$previous: $bases", bases.all { it == 1 || it == -1 })
            assertEquals(bases.size, bases.distinct().size)
            assertTrue(steps.all { kotlin.math.abs(it.index - focus) in 1..2 })
            assertEquals(steps.size, steps.map { it.index }.distinct().size)
        }
    }

    @Test fun edgesStayInsideTheCatalogue() {
        assertEquals(listOf(step(1, true), step(2, false)), plan(0, 1, count = 5))
        assertEquals(listOf(step(3, true), step(2, false)), plan(4, 3, count = 5))
        assertEquals(listOf(step(1, true)), plan(0, -1, count = 2))
        assertTrue(plan(0, -1, count = 1).isEmpty())
        assertTrue(plan(0, -1, count = 0).isEmpty())
        assertTrue(plan(-1, 0).isEmpty())
        assertTrue(plan(100, 0).isEmpty())
    }

    @Test fun videosAreSkipped() {
        assertEquals(listOf(step(9, true), step(12, false)),
            plan(10, 9, isVideo = { it == 11 || it == 8 }))
    }

    @Test fun isVideoIsAskedOnlyInsideTheCatalogue() {
        val asked = mutableListOf<Int>()
        plan(0, -1, count = 3, isVideo = { asked += it; false })
        assertTrue(asked.all { it in 0 until 3 })
    }

    @Test fun withoutRoomForBasesOnlyPreviewsAreWarmed() {
        assertEquals(listOf(step(11, false), step(9, false), step(12, false), step(8, false)),
            plan(10, 9, bases = 0))
    }

    @Test fun aBudgetOfOneWarmsOnlyTheBaseAhead() {
        assertEquals(listOf(step(11, true), step(9, false), step(12, false), step(8, false)),
            plan(10, 9, bases = 1))
        assertEquals(listOf(step(9, true), step(11, false), step(8, false), step(12, false)),
            plan(10, 11, bases = 1))
    }

    @Test fun baseBudgetFollowsTheCacheSize() {
        val base = ViewerSwipePrefetch.BASE_BYTES
        assertEquals(2400L * 2400 * 4, base)
        assertEquals(0, viewerNeighbourBaseBudget(0L))
        assertEquals(0, viewerNeighbourBaseBudget(4 * base - 1))
        assertEquals(1, viewerNeighbourBaseBudget(4 * base))
        assertEquals(1, viewerNeighbourBaseBudget(6 * base - 1))
        assertEquals(2, viewerNeighbourBaseBudget(6 * base))
        // A low-RAM device on Coil's default (20% of a 256 MB heap class): previews only.
        assertEquals(0, viewerNeighbourBaseBudget((256L shl 20) / 5))
        // A 4 GB phone (RAM / 48 = 85 MB): previews only. 6 GB (128 MB): the base ahead.
        assertEquals(0, viewerNeighbourBaseBudget((4096L shl 20) / 48))
        assertEquals(1, viewerNeighbourBaseBudget(128L shl 20))
        // The S24+ cache (256 MB): both sides.
        assertEquals(2, viewerNeighbourBaseBudget(256L shl 20))
    }

    @Test fun baseKeyFollowsThePageRevision() {
        val uri = "content://media/external/images/media/7"
        assertEquals("p2400", viewerBaseMemoryKey(uri, false, 0L) { "p$it" })
        assertNull(viewerBaseMemoryKey(uri, false, 0L) { null })
        assertEquals(editedPhotoMemoryKey(uri, 3L, 2400),
            viewerBaseMemoryKey(uri, true, 3L) { "p$it" })
    }

    @Test fun aCachedBaseSkipsTheSettle() {
        assertEquals(110L, viewerBaseDelayMs(openingPage = false, baseCached = false))
        assertEquals(0L, viewerBaseDelayMs(openingPage = false, baseCached = true))
        assertEquals(0L, viewerBaseDelayMs(openingPage = true, baseCached = false))
    }
}
