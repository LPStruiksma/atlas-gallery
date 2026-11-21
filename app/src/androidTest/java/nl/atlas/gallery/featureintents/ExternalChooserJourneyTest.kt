package nl.atlas.gallery.featureintents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.interactiveUiAutomation
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.testkit.DisplayPrefsRule
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * BENCH-2 (#573) Phase B acceptance: a calling app asks Android to open a photo and
 * the real system chooser ("Open with") lists ATLAS. The test plays the calling app
 * (one generated PNG, a chooser started the way any app starts one), captures the
 * chooser with ATLAS visibly listed, taps ATLAS in it, and checks that the read-only
 * viewer opens. Nothing here calls MainActivity directly.
 */
@RunWith(AndroidJUnit4::class)
class ExternalChooserJourneyTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = false)).around(compose)
    private val fixture = ExternalIntentFixture()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() = fixture.insertMedia()
    @After fun tearDown() {
        // Closes the chooser if the test failed before it was dismissed.
        interactiveUiAutomation().executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
        fixture.cleanUp()
    }

    /** Text nodes called exactly "ATLAS" in a window that is not ATLAS's own. */
    private fun atlasChoice(): AccessibilityNodeInfo? {
        val automation = interactiveUiAutomation()
        val roots = runCatching { automation.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return roots.filter { it.packageName?.toString() != context.packageName }
            .flatMap { it.findAccessibilityNodeInfosByText("ATLAS") }
            .firstOrNull { it.text?.toString()?.trim() == "ATLAS" }
    }

    private fun awaitAtlasChoice(): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            atlasChoice()?.let { return it }
            Thread.sleep(300L)
        }
        throw AssertionError("The system chooser never listed ATLAS")
    }

    @Test fun theSystemChooserListsAtlasAndHandsTheItemOver() {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(fixture.media), "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, "Open with")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val choice = awaitAtlasChoice()
        // Let the sheet finish animating in before the evidence frame.
        Thread.sleep(1_000L)
        saveUiEvidence("external-573-chooser-light.png")

        val clickable = generateSequence(choice) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        assertNotNull("The ATLAS entry in the chooser has no tappable node", clickable)
        clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = false)
    }
}
