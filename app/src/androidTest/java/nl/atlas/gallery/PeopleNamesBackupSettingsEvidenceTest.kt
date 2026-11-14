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
class PeopleNamesBackupSettingsEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun peopleSettingsNamesActionsAreVisibleAndDelegated() {
        var exported = 0
        var imported = 0
        compose.setContent {
            AtlasEvidenceHarness {
                AtlasSettingsScreen(
                    scanPhase = null, onPauseScan = null, onResumeScan = null,
                    visualIndexOn = null, showPhotoDetails = false,
                    versionName = "0.3.0",
                    onVisualIndex = {}, onPeopleNumbers = {}, onPhotoDetails = {},
                    onImportV4 = {}, onLinkV4 = {}, onDiagnostics = {},
                    onExportNames = { exported++ },
                    onImportNames = { imported++ }
                )
            }
        }
        compose.onNodeWithTag("atlas-library-people-tools").assertExists()
        compose.onNodeWithTag("atlas-people-names-export").assertExists()
        compose.onNodeWithTag("atlas-people-names-import").assertExists()
        compose.waitForIdle()
        saveUiEvidence("people-names-backup-settings-62.png")
        compose.onNodeWithTag("atlas-people-names-export").performClick()
        compose.onNodeWithTag("atlas-people-names-import").performClick()
        compose.runOnIdle {
            assertEquals(1, exported)
            assertEquals(1, imported)
        }
    }
}
