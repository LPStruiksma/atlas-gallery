package nl.atlas.gallery

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.testkit.awaitScreenStill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * USAGE-0 (#173): the real export path, on synthetic numbers in an in-memory store: the app writes
 * atlas-usage-<yyyymmdd>.json to its cache, serves it through the existing FileProvider and opens
 * Android's share sheet. The capture is the system sheet; nothing is sent anywhere.
 */
@RunWith(AndroidJUnit4::class)
class UsageShareSheetEvidenceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private class MemoryStore : UsageStore {
        private val days = HashMap<Int, UsageDay>()
        override fun loadAll() = days.values.toList()
        override fun save(day: UsageDay) { days[day.ymd] = day }
        override fun delete(ymd: Int) { days.remove(ymd) }
        override fun clear() { days.clear() }
        override fun isEnabled() = true
        override fun setEnabled(on: Boolean) {}
    }

    @Test fun shareSheetOpensWithTheNamedJsonFile() {
        val log = UsageLog(MemoryStore(), SystemUsageClock, flushIntervalMs = 0)
        log.measured(UsageAction.A1, 412.0); log.count(UsageAction.A15); log.dwell(UsageScreen.D1, 120)
        var result: UsageExportFile.Result? = null
        compose.runOnUiThread { result = UsageExportFile.share(compose.activity, log) }
        assertEquals(UsageExportFile.Result.SHEET_OPENED, result)
        val file = java.io.File(compose.activity.cacheDir, "usage/" + log.exportFileName())
        assertTrue("export file missing: $file", file.isFile)
        assertTrue(file.readText().startsWith("{\"schema\":\"usage-1\""))
        compose.awaitScreenStill("the share sheet")
        saveUiEvidence("usage-0-share-sheet.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
