package nl.atlas.gallery

import android.accessibilityservice.AccessibilityService
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #19, Samsung parity spec §2: the real Photos action opens a bottom choice
 * sheet. These two emulator journeys use invented media, both phone themes
 * and S24+ screenshots. No production/personal MediaStore content is needed.
 */
@RunWith(AndroidJUnit4::class)
class PhotosSortLightJourneyTest : PhotosSortSheetJourneyTest(dark = false)

@RunWith(AndroidJUnit4::class)
class PhotosSortDarkJourneyTest : PhotosSortSheetJourneyTest(dark = true)

abstract class PhotosSortSheetJourneyTest(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = SyntheticHeaderFixtures()
    private val mode = if (dark) "dark" else "light"

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(PhotosSortNightModeRule(dark)).around(fixtures).around(compose)

    @Test fun everySortChoiceIsAccessibleSelectedAndClosesToPhotos() {
        compose.waitForNode(hasTestTag("atlas-photos-sort"))
        compose.waitForNode(hasText("photos & videos", substring = true))
        for (choice in SortOrder.entries) {
            open()
            compose.onNodeWithTag(choice.tag()).performClick()
            closed()
            compose.waitForNode(hasText("photos & videos", substring = true))
            compose.onNodeWithTag("atlas-nav-photos").assertIsSelected()

            open()
            compose.onNodeWithTag(choice.tag()).assertIsSelected()
            SortOrder.entries.forEach { entry ->
                compose.onNodeWithTag(entry.tag()).assertExists()
            }
            // Every choice, including Largest and Smallest, has a rendered
            // light/dark sheet with the active radio indicator.
            compose.waitForIdle()
            saveUiEvidence("sort-12-${choice.name.lowercase()}-$mode.png")
            back()
            closed()
        }

        open()
        compose.onNodeWithTag(SortOrder.RANDOM.tag()).assertIsSelected()
        compose.onNodeWithTag("atlas-photos-reshuffle").assertExists()
        compose.onNodeWithTag("atlas-photos-reshuffle").performClick()
        closed()
        compose.onNodeWithTag("atlas-nav-photos").assertIsSelected()

        // Android Back dismisses without applying another order.
        open()
        compose.onNodeWithTag(SortOrder.RANDOM.tag()).assertIsSelected()
        back()
        closed()
        open()
        compose.onNodeWithTag(SortOrder.RANDOM.tag()).assertIsSelected()
        back()
        closed()
    }

    private fun open() {
        compose.onNodeWithTag("atlas-photos-sort").performClick()
        compose.waitForNode(hasTestTag("atlas-photos-sort-sheet"))
    }

    private fun closed() {
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-photos-sort-sheet"))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun back() {
        // #82: on CI a launcher ANR dialog ("Application Not Responding:
        // com.android.launcher3") took window focus just before the final Back,
        // consumed it, and the sheet correctly stayed open (PR #115 shard 0,
        // ATLAS_SORT_BACK log). Only press Back once ATLAS holds focus; the
        // gesture and the sheet assertion are unchanged.
        awaitAppFocus()
        val before = describeFocus()
        android.util.Log.i("ATLAS_SORT_BACK", "before Android Back: $before")
        val accepted = try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK)
        } finally {
            android.util.Log.i("ATLAS_SORT_BACK", "after Android Back: ${describeFocus()}")
        }
        assertTrue("Android Back action accepted (before: $before)", accepted)
        // The API35 emulator's Launcher3 sometimes raises an ANR between
        // awaitAppFocus() and the global Back action. That system dialog can
        // consume Back, leaving this app's sheet open. Only retry when the
        // launcher ANR was actually focused AND the sheet remains present;
        // an app-owned sheet that fails to close must still fail this test.
        compose.waitForIdle()
        val after = describeFocus()
        if (after.contains("Application Not Responding: com.android.launcher3") &&
            compose.onAllNodes(hasTestTag("atlas-photos-sort-sheet"))
                .fetchSemanticsNodes().isNotEmpty()) {
            clearSystemAnrDialogs(attempts = 1)
            awaitAppFocus()
            val retriedFrom = describeFocus()
            assertTrue("ATLAS must regain focus after Launcher3 ANR: $retriedFrom",
                APP_FOCUS.containsMatchIn(retriedFrom))
            assertTrue("Android Back retry after Launcher3 ANR accepted",
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK))
        }
    }

    private fun awaitAppFocus() {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
        while (!APP_FOCUS.containsMatchIn(describeFocus()) &&
            android.os.SystemClock.uptimeMillis() < deadline) {
            clearSystemAnrDialogs(attempts = 1)
            Thread.sleep(250L)
        }
    }

    private fun SortOrder.tag() = "atlas-photos-sort-${name.lowercase()}"
}

/** Changes the emulator theme before launching MainActivity and restores it. */
private class PhotosSortNightModeRule(private val dark: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = shell("cmd uimode night").substringAfter(":").trim()
                shell("cmd uimode night " + if (dark) "yes" else "no")
                try {
                    base.evaluate()
                } finally {
                    if (before in setOf("yes", "no", "auto")) shell("cmd uimode night $before")
                }
            }
        }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .use { it.readBytes().decodeToString() }
    }
}

/** `dumpsys window` focus line naming an ATLAS window (see [describeFocus]). */
private val APP_FOCUS = Regex("""mCurrentFocus=Window\{\S+ u\d+ nl\.atlas\.gallery/""")
