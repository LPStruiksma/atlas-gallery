package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.testkit.awaitThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * USAGE-0 (#173): the three usage rows in Settings → Advanced, in the revised brief's state
 * matrix at the S24+ size: light and dark, 200% font, the failure snackbar and Clear.
 * Callbacks are fakes and nothing is recorded: no real usage, no media, no People data.
 * Renders are declared in scripts/ui-evidence.d/173-usage-0.txt.
 */
@RunWith(AndroidJUnit4::class)
class UsageSettingsEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private var dark by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)
    private var enabled by mutableStateOf(false)
    private var hasData by mutableStateOf(false)
    /** False models Settings opened before the recorder finished loading: no state to show yet. */
    private var ready by mutableStateOf(true)
    private var exportResult = UsageExportFile.Result.SHEET_OPENED
    private var exports = 0
    private var switchCalls = mutableListOf<Boolean>()

    private fun show(waitForRows: Boolean = true) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            AtlasSettingsScreen(
                                scanPhase = null, onPauseScan = null, onResumeScan = null,
                                visualIndexOn = null, showPhotoDetails = false,
                                versionName = "0.0.0-test", onVisualIndex = {},
                                onPeopleNumbers = {}, onPhotoDetails = {}, onImportV4 = {},
                                onLinkV4 = {}, onDiagnostics = {},
                                usageEnabled = if (ready) enabled else null, usageHasData = hasData,
                                onUsageEnabled = { switchCalls += it; enabled = it },
                                onExportUsage = { exports++; exportResult },
                                onClearUsage = { hasData = false })
                        }
                    }
                }
            }
        }
        compose.waitForNode(hasTestTag("atlas-settings-advanced"))
        compose.onNodeWithTag("atlas-settings-advanced").performScrollTo().performClick()
        if (waitForRows) compose.waitForNode(hasTestTag("atlas-settings-usage-record"))
    }

    /** Scrolls so the three rows are fully in view, ending at Clear usage. */
    private fun scrollToRows() {
        compose.onNodeWithTag("atlas-settings-usage-record").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("atlas-settings-usage-clear").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
    }

    private fun assertRowsWithinScreen() {
        val root = compose.onNodeWithTag("atlas-settings-screen").fetchSemanticsNode().boundsInRoot
        listOf("record", "export", "clear").forEach { name ->
            val b = compose.onNodeWithTag("atlas-settings-usage-$name").fetchSemanticsNode().boundsInRoot
            assertTrue("$name clipped: $b in $root", b.left >= root.left - 1f && b.right <= root.right + 1f)
            assertTrue("$name touch target under 48 dp", b.height >= 48f * compose.density.density - 1f)
        }
    }

    @Test fun stateMatrixLightDarkAndLargeFont() {
        show()
        // Off + nothing recorded: Export and Clear are inert.
        scrollToRows()
        compose.onNodeWithTag("atlas-settings-usage-record").assertIsOff()
        compose.onNodeWithTag("atlas-settings-usage-export-value").assertTextContains("Nothing recorded yet")
        compose.onNodeWithTag("atlas-settings-usage-export").assertHasNoClick()
        compose.onNodeWithTag("atlas-settings-usage-clear").assertHasNoClick()
        assertRowsWithinScreen()
        saveUiEvidence("usage-0-off-empty-light.png")

        // On + data: the switch row toggles; Export and Clear become actions.
        compose.runOnIdle { hasData = true }
        compose.onNodeWithTag("atlas-settings-usage-record").performClick()
        compose.runOnIdle { assertEquals(listOf(true), switchCalls) }
        compose.onNodeWithTag("atlas-settings-usage-record").assertIsOn()
        scrollToRows()
        compose.onNodeWithTag("atlas-settings-usage-export").assertHasClickAction()
        compose.onNodeWithTag("atlas-settings-usage-clear").assertHasClickAction()
        assertRowsWithinScreen()
        saveUiEvidence("usage-0-on-data-light.png")

        // Off + retained data (dark): recording stopped, data still exportable.
        compose.runOnIdle { dark = true; enabled = false }
        scrollToRows()
        compose.onNodeWithTag("atlas-settings-usage-record").assertIsOff()
        compose.onNodeWithTag("atlas-settings-usage-export").assertHasClickAction()
        saveUiEvidence("usage-0-off-retained-dark.png")

        // 200% font, both themes: labels whole, subtitle wraps, nothing clipped.
        compose.runOnIdle { fontScale = 2f; enabled = true }
        scrollToRows()
        assertRowsWithinScreen()
        saveUiEvidence("usage-0-font200-dark.png")
        compose.runOnIdle { dark = false }
        scrollToRows()
        assertRowsWithinScreen()
        saveUiEvidence("usage-0-font200-light.png")
        compose.runOnIdle { fontScale = 1f }

        // File write fails: snackbar, nothing erased, Export stays available.
        compose.runOnIdle { exportResult = UsageExportFile.Result.WRITE_FAILED }
        scrollToRows()
        compose.onNodeWithTag("atlas-settings-usage-export").performClick()
        compose.waitForNode(hasText("Couldn’t prepare usage file. Try again."))
        compose.runOnIdle { assertEquals(1, exports); assertTrue(hasData) }
        compose.onNodeWithTag("atlas-settings-usage-export").assertHasClickAction()
        saveUiEvidence("usage-0-export-failed-light.png")

        // Clear: immediate, snackbar, Export goes back to "Nothing recorded yet".
        compose.onNodeWithTag("atlas-settings-usage-clear").performClick()
        compose.waitForNode(hasText("Usage cleared"))
        compose.onNodeWithTag("atlas-settings-usage-export-value").assertTextContains("Nothing recorded yet")
        compose.onNodeWithTag("atlas-settings-usage-clear").assertHasNoClick()
        saveUiEvidence("usage-0-cleared-light.png")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertHasNoClick() =
        this.assert(androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(
            androidx.compose.ui.semantics.SemanticsActions.OnClick))

    /** Review #890: Settings opened before the recorder is ready shows no usage rows, then all three. */
    @Test fun rowsAppearOnceTheRecorderIsReady() {
        ready = false
        show(waitForRows = false)
        compose.onNodeWithTag("atlas-settings-export-diagnostics").performScrollTo()
        compose.waitForIdle()
        listOf("record", "export", "clear").forEach {
            compose.onNodeWithTag("atlas-settings-usage-$it").assertDoesNotExist()
        }
        compose.runOnIdle { ready = true }
        compose.waitForNode(hasTestTag("atlas-settings-usage-record"))
        scrollToRows()
        compose.onNodeWithTag("atlas-settings-usage-record").assertIsOff()
        compose.onNodeWithTag("atlas-settings-usage-export").assertIsDisplayed()
    }
}
