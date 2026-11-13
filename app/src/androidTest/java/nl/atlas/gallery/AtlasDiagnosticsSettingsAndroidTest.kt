package nl.atlas.gallery

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtlasDiagnosticsSettingsAndroidTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun explicitExportPreviewsCopiesAndSharesOnlyPreparedText() {
        val report = formatAtlasDiagnostics(AtlasDiagnosticsSnapshot(
            version = "0.3.261113172", images = 42, videos = 2,
            peoplePhase = "SCANNING", peopleEligible = 50, peopleProcessed = 12
        ))
        var copied = ""
        var shared = ""
        compose.setContent {
            AtlasEvidenceHarness {
                AtlasSettingsScreen(
                    scanPhase = null, onPauseScan = null, onResumeScan = null,
                    visualIndexOn = null, showPhotoDetails = false,
                    versionName = "0.3.261113172", onVisualIndex = {}, onPeopleNumbers = {},
                    onPhotoDetails = {}, onImportV4 = {}, onLinkV4 = {},
                    onDiagnostics = {}, onReadDiagnostics = { report },
                    onCopyDiagnostics = { copied = it }, onShareDiagnostics = { shared = it }
                )
            }
        }
        compose.onNodeWithTag("atlas-settings-advanced").performClick()
        compose.onNodeWithTag("atlas-settings-export-diagnostics").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-settings-export-diagnostics-text").assertExists()
        saveUiEvidence("settings-45-export-diagnostics.png")
        compose.onNodeWithTag("atlas-settings-export-diagnostics-copy").performScrollTo().performClick()
        compose.onNodeWithTag("atlas-settings-export-diagnostics-share").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(report, copied)
            assertEquals(report, shared)
        }
    }
}
