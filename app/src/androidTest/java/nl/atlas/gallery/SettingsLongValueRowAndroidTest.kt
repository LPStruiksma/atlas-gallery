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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Lukas 2026-11-19 (#610): the "Face scanning" title rendered one letter per line because
 * the long charger status took the whole row. The title must keep its one-line width and
 * the long status must sit under it; short values stay on the right. Rendered in light,
 * dark and dark at 130 % font scale.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLongValueRowAndroidTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun longScanStatusMovesUnderTheTitleAndShortValuesStayInline() {
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                }
            }
        }
        for ((isDark, scale, name) in listOf(
            Triple(false, 1f, "settings-long-value-row-light"),
            Triple(true, 1f, "settings-long-value-row-dark"),
            Triple(true, 1.3f, "settings-long-value-row-dark-font130"))) {
            compose.runOnIdle { dark = isDark; fontScale = scale }
            compose.waitForIdle()
            assertLayout(scale)
            saveUiEvidence("$name.png")
        }
    }

    private fun assertLayout(scale: Float) {
        val label = compose.onNodeWithTag("atlas-settings-face-scanning-label", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val value = compose.onNodeWithTag("atlas-settings-face-scanning-value", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        // One line of bodyLarge is ~24 dp at 100 %; the broken layout was ~13 letters tall.
        val labelHeight = label.bottom - label.top
        val labelWidth = label.right - label.left
        assertTrue("title wrapped at $scale: $labelHeight", labelHeight < (40 * scale).dp)
        assertTrue("title squeezed at $scale: $labelWidth", labelWidth > 80.dp)
        assertTrue("status not under title at $scale: $value vs $label", value.top >= label.bottom)

        val themeLabel = compose.onNodeWithTag("atlas-settings-theme-label", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val themeValue = compose.onNodeWithTag("atlas-settings-theme-value", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("short value left the title's line at $scale", themeValue.top < themeLabel.bottom)
        assertTrue("short value not right of title at $scale", themeValue.left > themeLabel.right)
    }
}
