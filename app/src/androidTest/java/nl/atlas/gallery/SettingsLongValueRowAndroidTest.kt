package nl.atlas.gallery

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Lukas 2026-11-19: the "Face scanning" title rendered one letter per line because the
 * long charger status took the whole row. The title must keep its one-line width and the
 * long status must sit under it; short values stay on the right.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLongValueRowAndroidTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun longScanStatusMovesUnderTheTitleAndShortValuesStayInline() {
        compose.setContent {
            AtlasEvidenceHarness {
                AtlasSettingsScreen(
                    scanPhase = FaceDiscoveryPhase.WAITING_FOR_CHARGER,
                    scanStatusNote = "Waiting for charging · use Scan now while unplugged",
                    onPauseScan = null, onResumeScan = null,
                    visualIndexOn = null, showPhotoDetails = false,
                    versionName = "synthetic", onVisualIndex = {},
                    onPeopleNumbers = {}, onPhotoDetails = {},
                    onImportV4 = {}, onLinkV4 = {}, onDiagnostics = {},
                    scanHasSavedOptIn = true, onScanNow = {})
            }
        }
        compose.waitForIdle()
        val label = compose.onNodeWithTag("atlas-settings-face-scanning-label", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val value = compose.onNodeWithTag("atlas-settings-face-scanning-value", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        // One line of bodyLarge is ~24 dp; the broken layout was ~13 letters tall.
        assertTrue("title wrapped: ${(label.bottom - label.top)}", (label.bottom - label.top) < 40.dp)
        assertTrue("title squeezed: ${(label.right - label.left)}", (label.right - label.left) > 80.dp)
        assertTrue("status not under title: $value vs $label", value.top >= label.bottom)

        val themeLabel = compose.onNodeWithTag("atlas-settings-theme-label", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val themeValue = compose.onNodeWithTag("atlas-settings-theme-value", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("short value left the title's line", themeValue.top < themeLabel.bottom)
        assertTrue("short value not right of title", themeValue.left > themeLabel.right)
        saveUiEvidence("settings-long-value-row.png")
    }
}
