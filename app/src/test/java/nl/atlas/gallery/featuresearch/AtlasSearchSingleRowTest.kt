package nl.atlas.gallery.featuresearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIND-5 (#341, frame L): when the shared search panel switches to one row. */
class AtlasSearchSingleRowTest {
    @Test fun portraitKeepsTwoRowsWithAndWithoutKeyboard() {
        assertFalse(atlasSearchSingleRow(windowHeightDp = 832, imeBottomDp = 0))
        assertFalse(atlasSearchSingleRow(windowHeightDp = 832, imeBottomDp = 324))
    }

    @Test fun landscapeSwitchesOnlyWhileTheKeyboardIsUp() {
        assertFalse(atlasSearchSingleRow(windowHeightDp = 384, imeBottomDp = 0))
        assertTrue(atlasSearchSingleRow(windowHeightDp = 384, imeBottomDp = 184))
    }

    @Test fun thresholdIsStrict() {
        assertFalse(atlasSearchSingleRow(windowHeightDp = 480, imeBottomDp = 200))
        assertTrue(atlasSearchSingleRow(windowHeightDp = 479, imeBottomDp = 200))
    }
}
