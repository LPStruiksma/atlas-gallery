package nl.atlas.gallery.featureintents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.interactiveUiAutomation
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitThat
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

    /** Waits for the sheet to list ATLAS and for its entry to stop moving, then returns the entry. */
    private fun awaitAtlasChoice(): AccessibilityNodeInfo {
        var last: android.graphics.Rect? = null
        var found: AccessibilityNodeInfo? = null
        compose.awaitThat("the system chooser to list ATLAS and stop animating", timeoutMs = 20_000L) {
            val node = atlasChoice()
            val bounds = node?.let { android.graphics.Rect().also { r -> it.getBoundsInScreen(r) } }
            val still = bounds != null && bounds == last
            last = bounds
            if (still) found = node
            still
        }
        return requireNotNull(found)
    }

    @Test fun theSystemChooserListsAtlasAndHandsTheItemOver() {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(fixture.media), "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, "Open with")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val choice = awaitAtlasChoice()
        saveUiEvidence("external-573-chooser-light.png")

        val clickable = generateSequence(choice) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        assertNotNull("The ATLAS entry in the chooser has no tappable node", clickable)
        clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = false)
    }
}
