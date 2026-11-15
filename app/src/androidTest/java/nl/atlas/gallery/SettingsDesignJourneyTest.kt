package nl.atlas.gallery

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** #135: source-owned Settings visuals only; no personal media or mutations. */
@RunWith(AndroidJUnit4::class)
class SettingsDesignJourneyTest {
    private val compose = createComposeRule()
    @get:Rule val chain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun lightAndDarkSettingsUseSameGroupsAndScrollableHierarchy() {
        val darkTheme = mutableStateOf(false)
        var diagnosticsOpened = 0
        compose.setContent {
            val dark = darkTheme.value
            AtlasGalleryTheme(dark = dark) {
                AtlasSettingsScreen(
                    scanPhase = null, onPauseScan = null, onResumeScan = null,
                    visualIndexOn = null, showPhotoDetails = false,
                    versionName = "0.3.0", onVisualIndex = {},
                    onPeopleNumbers = {}, onPhotoDetails = {},
                    onImportV4 = {}, onLinkV4 = {},
                    onDiagnostics = { diagnosticsOpened++ },
                    onExportNames = {}, onImportNames = {}
                )
            }
        }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { darkTheme.value = dark }
            // Return the same scrollable tree to the expanded title.
            compose.onNodeWithTag("atlas-settings-face-scanning").performScrollTo()
            val theme = if (dark) "dark" else "light"
            compose.onNodeWithTag("atlas-settings-screen").assertExists()
            compose.onNodeWithTag("atlas-settings-large-title").assertIsDisplayed()
            compose.onNodeWithTag("atlas-settings-large-title").assertExists()
            compose.onNodeWithTag("atlas-library-people-tools").assertExists()
            compose.onNodeWithTag("atlas-settings-display").assertExists()
            compose.onNodeWithTag("atlas-settings-advanced-group").assertExists()
            compose.waitForIdle()
            saveUiEvidence("settings-135-main-$theme.png")

            compose.onNodeWithTag("atlas-settings-advanced").performScrollTo().performClick()
            compose.onNodeWithTag("atlas-settings-diagnostics").performScrollTo()
                .assertIsDisplayed()
            compose.waitForIdle()
            saveUiEvidence("settings-135-advanced-$theme.png")
            compose.onNodeWithTag("atlas-settings-diagnostics").performClick()
            compose.runOnIdle { check(diagnosticsOpened == if (dark) 2 else 1) }
            compose.onNodeWithTag("atlas-settings-advanced").performScrollTo().performClick()

            compose.onNodeWithTag("atlas-settings-version").performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithTag("atlas-settings-compact-title").assertExists()
            compose.waitForIdle()
            saveUiEvidence("settings-135-scrolled-$theme.png")

            // The next iteration scrolls back to People and rechecks the title.
        }
    }
}
