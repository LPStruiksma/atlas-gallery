package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastCrashSettingsAndroidTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lastCrashInAdvancedCanCopyAndShareRedactedText() {
        val report = "ATLAS last crash\nException: java.lang.IllegalStateException"
        var copied = ""
        var shared = ""
        rule.setContent {
            MaterialTheme {
                AtlasSettingsScreen(
                    scanPhase = null, onPauseScan = null, onResumeScan = null,
                    visualIndexOn = null, showPhotoDetails = false,
                    versionName = "0.3.0", onVisualIndex = {}, onPeopleNumbers = {},
                    onPhotoDetails = {}, onImportV4 = {}, onLinkV4 = {},
                    onDiagnostics = {}, onReadLastCrash = { report },
                    onCopyLastCrash = { copied = it }, onShareLastCrash = { shared = it }
                )
            }
        }
        rule.onNodeWithTag("atlas-settings-advanced").performClick()
        rule.onNodeWithTag("atlas-settings-last-crash").performClick()
        rule.onNodeWithTag("atlas-settings-last-crash-text").assertExists()
        rule.onNodeWithTag("atlas-settings-last-crash-copy").performClick()
        rule.onNodeWithTag("atlas-settings-last-crash-share").performClick()
        rule.runOnIdle {
            assertEquals(report, copied)
            assertEquals(report, shared)
        }
    }
}
