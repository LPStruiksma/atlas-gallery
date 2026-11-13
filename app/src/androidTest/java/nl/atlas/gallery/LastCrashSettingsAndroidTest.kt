package nl.atlas.gallery

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastCrashSettingsAndroidTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun lastCrashInAdvancedCanCopyAndShareRedactedText() {
        val report = "ATLAS last crash\nException: java.lang.IllegalStateException"
        var copied = ""
        var shared = ""
        compose.setContent {
            AtlasEvidenceHarness {
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
        compose.onNodeWithTag("atlas-settings-advanced").performClick()
        compose.onNodeWithTag("atlas-settings-last-crash").performClick()
        compose.onNodeWithTag("atlas-settings-last-crash-text").assertExists()
        compose.waitForIdle()
        saveUiEvidence("settings-33-last-crash.png")
        compose.onNodeWithTag("atlas-settings-last-crash-copy").performClick()
        compose.onNodeWithTag("atlas-settings-last-crash-share").performClick()
        compose.runOnIdle {
            assertEquals(report, copied)
            assertEquals(report, shared)
        }
    }
}
