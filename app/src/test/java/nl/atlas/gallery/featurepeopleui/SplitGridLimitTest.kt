package nl.atlas.gallery.featurepeopleui

import org.junit.Assert.assertEquals
import org.junit.Test

/** #356: "Some faces are different" pages its grid instead of composing every crop. */
class SplitGridLimitTest {
    @Test fun showMoreAddsOnePageAndStopsAtTheGroupSize() {
        assertEquals(40, SPLIT_GRID_PAGE_FACES)
        assertEquals(80, nextSplitGridLimit(40, 300))
        assertEquals(300, nextSplitGridLimit(280, 300))
        assertEquals(300, nextSplitGridLimit(300, 300))
    }

    @Test fun neverShrinksWhenTheGroupGotSmaller() {
        assertEquals(40, nextSplitGridLimit(40, 12))
    }
}
