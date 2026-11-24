package nl.atlas.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings rows stack a value under its label once both no longer fit on one line. */
class SettingsRowLayoutTest {
    @Test fun shortValueStaysBesideTheLabel() {
        assertFalse(settingsRowStacks(labelWidth = 300, gap = 36, valueWidth = 400, available = 1000))
        assertFalse(settingsRowStacks(labelWidth = 300, gap = 36, valueWidth = 664, available = 1000))
    }

    @Test fun longValueMovesUnderTheLabelInsteadOfSqueezingIt() {
        // Lukas's S24+ screenshot (2026-11-19): "Face scanning" next to
        // "Waiting for charging · use Scan now while unplugged" overflows the row.
        assertTrue(settingsRowStacks(labelWidth = 300, gap = 36, valueWidth = 665, available = 1000))
        assertTrue(settingsRowStacks(labelWidth = 330, gap = 36, valueWidth = 1400, available = 960))
    }
}
