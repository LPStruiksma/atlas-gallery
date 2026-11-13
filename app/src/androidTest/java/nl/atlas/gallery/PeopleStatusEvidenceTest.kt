package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #46: one screenshot of each People and Settings status on S24+ in both
 * themes. All counts and scopes are invented; no private photo is read.
 */
@RunWith(AndroidJUnit4::class)
class PeopleStatusEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun eachWorkerStatusIsReadableInPeopleAndSettingsLightAndDark() {
        val cases = listOf(
            "charger" to FaceDiscoveryPhase.WAITING_FOR_CHARGER,
            "queued" to FaceDiscoveryPhase.QUEUED,
            "finding" to FaceDiscoveryPhase.SCANNING,
            "grouping" to FaceDiscoveryPhase.GROUPING,
            "paused" to FaceDiscoveryPhase.PAUSED,
            "finished" to FaceDiscoveryPhase.FINISHED
        )
        var dark by mutableStateOf(false)
        var phase by mutableStateOf(FaceDiscoveryPhase.WAITING_FOR_CHARGER)
        var showPeople by mutableStateOf(true)
        val epoch = "synthetic-grant"
        compose.setContent {
            val processed = if (phase == FaceDiscoveryPhase.FINISHED) 10
                else if (phase == FaceDiscoveryPhase.QUEUED) 0 else 3
            val ui = FaceDiscoveryUiState(
                phase = phase, processed = processed, eligible = 10,
                observedPermissionEpoch = epoch)
            val visual = VisualIndexStatus(
                enabled = true, mayEnable = true, done = processed, total = 10)
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)) {
                        if (showPeople) {
                            PeopleHomeScreen(
                                state = PeopleHomeUiState(discovery = ui),
                                currentPermissionEpoch = epoch, onOpenProfile = {})
                        } else {
                            AtlasSettingsScreen(
                                scanPhase = phase,
                                onPauseScan = if (phase == FaceDiscoveryPhase.SCANNING) ({}) else null,
                                onResumeScan = if (phase == FaceDiscoveryPhase.PAUSED) ({}) else null,
                                visualIndexOn = true, showPhotoDetails = false,
                                versionName = "synthetic", onVisualIndex = {},
                                onPeopleNumbers = {}, onPhotoDetails = {},
                                onImportV4 = {}, onLinkV4 = {}, onDiagnostics = {},
                                scanHasSavedOptIn = true, visualStatus = visual)
                        }
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            for ((name, next) in cases) {
                compose.runOnIdle { dark = theme; phase = next; showPeople = true }
                compose.waitForIdle()
                compose.onNodeWithTag("atlas-people-status").assertExists()
                val peopleExpected = peopleStatusLine(
                    FaceDiscoveryUiState(next,
                        processed = if (next == FaceDiscoveryPhase.FINISHED) 10
                            else if (next == FaceDiscoveryPhase.QUEUED) 0 else 3,
                        eligible = 10))
                assertTrue(compose.onAllNodesWithText(requireNotNull(peopleExpected))
                    .fetchSemanticsNodes().isNotEmpty())
                saveUiEvidence("status-46-people-$name-${if (theme) "dark" else "light"}.png")

                compose.runOnIdle { showPeople = false }
                compose.waitForIdle()
                compose.onNodeWithTag("atlas-settings-face-scanning").assertExists()
                compose.onNodeWithTag("atlas-people-visual-index").assertExists()
                assertTrue(compose.onAllNodesWithText(faceScanSettingsValue(next, false, true))
                    .fetchSemanticsNodes().isNotEmpty())
                saveUiEvidence("status-46-settings-$name-${if (theme) "dark" else "light"}.png")
            }
        }
    }
}
