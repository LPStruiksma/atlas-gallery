package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun advancedUsageControlsRenderOffByDefault() {
        compose.setContent {
            MaterialTheme {
                AtlasSettingsScreen(
                    scanPhase = null, onPauseScan = null, onResumeScan = null,
                    visualIndexOn = null, showPhotoDetails = false, versionName = "Test",
                    onVisualIndex = {}, onPeopleNumbers = {}, onPhotoDetails = {},
                    onImportV4 = {}, onLinkV4 = {}, onDiagnostics = {}
                )
            }
        }
        compose.onNodeWithTag("atlas-settings-advanced").performClick()
        compose.onNodeWithTag("atlas-settings-usage-toggle").assertExists()
            .assertTextContains("Off", substring = true)
        compose.onNodeWithTag("atlas-settings-usage-export").assertExists()
        compose.onNodeWithTag("atlas-settings-usage-clear").assertExists()
        val image = compose.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }
}
