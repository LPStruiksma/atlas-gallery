package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/** #631: the Visual search index panel says in plain words what was left out. */
class VisualIndexCopyTest {
    private fun status(done: Int, total: Int, skipped: Int = 0, leftOut: Int = 0) =
        VisualIndexStatus(enabled = true, mayEnable = true, done = done, total = total,
            skipped = skipped, leftOut = leftOut)

    @Test fun nothingLeftOutKeepsTheOldCount() {
        assertEquals("5 of 10 photos done", visualIndexProgressText(status(5, 10)))
        assertEquals("10 of 10 photos done · 1 unreadable, skipped",
            visualIndexProgressText(status(10, 10, skipped = 1)))
    }

    @Test fun leftOutPhotosAreCountedAndExplained() {
        assertEquals("5 of 10 photos done. 1 photo left out: Android gives it no fixed ID, " +
            "so ATLAS can't find it again", visualIndexProgressText(status(5, 10, leftOut = 1)))
        val many = visualIndexProgressText(status(10, 10, skipped = 2, leftOut = 37))
        assertEquals("10 of 10 photos done · 2 unreadable, skipped. 37 photos left out: " +
            "Android gives them no fixed ID, so ATLAS can't find them again", many)
    }
}
