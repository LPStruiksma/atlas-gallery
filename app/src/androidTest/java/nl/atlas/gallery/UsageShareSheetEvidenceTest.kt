package nl.atlas.gallery

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.testkit.awaitScreenStill
import nl.atlas.gallery.testkit.awaitThat
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
        assertTrue(file.readText().startsWith("{\"schema_version\":1,\"days\":["))
        // The chooser is a system window: read it through the interactive-windows automation (the
        // plain one cannot see it). The synthetic recipient declared in the androidTest manifest
        // must be listed and the sheet must stop moving before the capture, so the PNG shows the
        // real ACTION_SEND sheet, not the screen underneath.
        val automation = interactiveUiAutomation()
        val own = compose.activity.packageName
        fun sheetHas(text: String): Boolean {
            val roots = runCatching { automation.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            return roots.filter { it.packageName?.toString() != own }
                .any { it.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true }
        }
        fun shell(command: String): String = automation.executeShellCommand(command).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
        }
        try {
            compose.awaitThat("the share sheet to list the synthetic recipient", timeoutMs = 20_000L) {
                sheetHas("Test recipient") && sheetHas("Test recipient two")
            }
        } catch (e: AssertionError) {
            // Facts for the next fix: what the system did with the chooser intent.
            val activities = shell("dumpsys activity activities").lines()
                .filter { Regex("Resolver|Chooser|intentresolver|UsageShareTarget").containsMatchIn(it) }
                .take(12).joinToString(" | ") { it.trim() }
            val logcat = shell("logcat -d -t 600").lines()
                .filter { Regex("Chooser|Resolver|intentresolver|FATAL|AndroidRuntime|UsageShareTarget|ActivityTaskManager: START").containsMatchIn(it) }
                .takeLast(25).joinToString(" | ") { it.trim().take(220) }
            throw AssertionError(e.message + " || activities: " + activities + " || logcat: " + logcat, e)
        }
        android.util.Log.i("ATLAS_EVIDENCE", "share sheet shows the file name: ${sheetHas(log.exportFileName())}")
        compose.awaitScreenStill("the share sheet")
        saveUiEvidence("usage-0-share-sheet.png")
        automation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
    }
}
