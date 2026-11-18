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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurealbums.AlbumLoadIssue
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumSort
import nl.atlas.gallery.featurealbums.AtlasAlbumsScreen
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * DESIGN-6 #136: real production composables with synthetic, grant-free UI
 * state. These are not MainActivity permission or MediaStore integration tests.
 * Each screenshot uses the owner's 1080x2340 display rule. No personal data.
 */
@RunWith(AndroidJUnit4::class)
class Design6QuietStatesEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun albumsEmptyDeniedAndRetryAreRenderedInBothThemes() {
        var dark by mutableStateOf(false)
        var issue by mutableStateOf<AlbumLoadIssue?>(null)
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)) {
                        AtlasAlbumsScreen(
                            albums = emptyList(),
                            sort = AlbumSort.RECENT, onSort = {},
                            onOpenAlbum = {},
                            mediaFilter = AlbumMediaFilter.ALL,
                            onMediaFilterChange = {},
                            loadIssue = issue,
                            onOpenLibraryStatus = {},
                            onRetry = {})
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            val mode = if (theme) "dark" else "light"
            compose.runOnIdle { dark = theme; issue = null }
            compose.waitForIdle()
            compose.onNodeWithText("Your albums will appear here").assertExists()
            compose.onNodeWithTag("atlas-empty-action").assertDoesNotExist()
            saveUiEvidence("albums-136-empty-$mode.png")

            compose.runOnIdle { issue = AlbumLoadIssue.ACCESS_NOT_GRANTED }
            compose.waitForIdle()
            compose.onNodeWithText("Albums need photo access").assertExists()
            compose.onNodeWithText("Library status").assertExists()
            saveUiEvidence("albums-136-denied-$mode.png")

            compose.runOnIdle { issue = AlbumLoadIssue.READ_FAILED }
            compose.waitForIdle()
            compose.onNodeWithText("Couldn’t refresh albums").assertExists()
            compose.onNodeWithTag("atlas-empty-action").assertExists()
            saveUiEvidence("albums-136-error-$mode.png")
        }
    }

    @Test fun peopleNotScannedRestoringAndScanningAreRenderedInBothThemes() {
        var dark by mutableStateOf(false)
        var phase by mutableStateOf(FaceDiscoveryPhase.READY)
        var restoring by mutableStateOf(false)
        val epoch = "synthetic-design-6-grant"
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)) {
                        PeopleHomeScreen(
                            state = PeopleHomeUiState(
                                discovery = FaceDiscoveryUiState(
                                    phase = phase,
                                    processed = if (phase == FaceDiscoveryPhase.SCANNING) 3 else 0,
                                    eligible = if (phase == FaceDiscoveryPhase.SCANNING) 10 else 0,
                                    observedPermissionEpoch = epoch)),
                            currentPermissionEpoch = epoch,
                            restoring = restoring,
                            onOpenProfile = {},
                            onStartDiscovery = {})
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            val mode = if (theme) "dark" else "light"
            compose.runOnIdle {
                dark = theme; phase = FaceDiscoveryPhase.READY; restoring = false
            }
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-people-empty").assertExists()
            compose.onNodeWithText("No people").assertExists()
            saveUiEvidence("people-136-not-scanned-$mode.png")

            compose.runOnIdle { restoring = true }
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-people-restoring").assertExists()
            compose.onNodeWithTag("atlas-people-empty").assertDoesNotExist()
            saveUiEvidence("people-136-restoring-$mode.png")

            compose.runOnIdle {
                restoring = false
                phase = FaceDiscoveryPhase.SCANNING
            }
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-people-progress").assertExists()
            saveUiEvidence("people-136-scanning-$mode.png")
        }
    }
}
