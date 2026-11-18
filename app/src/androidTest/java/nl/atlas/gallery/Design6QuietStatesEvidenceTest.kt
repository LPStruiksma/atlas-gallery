package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurealbums.AlbumLoadIssue
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumSort
import nl.atlas.gallery.featurealbums.AtlasAlbumsScreen
import nl.atlas.gallery.featuredesign.AtlasEmptyState
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuregrid.AtlasTimelineGallery
import nl.atlas.gallery.featuregrid.TimelineSort
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Invented records only: these URIs name no MediaStore row on the emulator. */
    private fun inventedMedia(count: Int, base: Long) = (0 until count).map { i ->
        MediaRecord(i.toLong(), "content://media/external/images/media/${base + i}",
            "Fictional frame ${i + 1}", "DCIM/ATLAS-TEST/", 100,
            1_700_000_000_000L - i * 3_600_000L, isVideo = false)
    }

    @Test fun photosEmptyAndPermissionAreRenderedInBothThemes() {
        var dark by mutableStateOf(false)
        var denied by mutableStateOf(false)
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)) {
                        if (denied) {
                            // Same arguments as MainActivity's Photos permission state.
                            AtlasEmptyState(
                                title = "Photos need access",
                                actionLabel = "Allow access",
                                onAction = {},
                                modifier = Modifier.testTag("atlas-photos-permission"))
                        } else {
                            AtlasTimelineGallery(
                                items = emptyList(),
                                sort = TimelineSort.NEWEST,
                                gridState = rememberLazyGridState(),
                                onOpen = {},
                                viewKey = "photos-136-empty")
                        }
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            val mode = if (theme) "dark" else "light"
            compose.runOnIdle { dark = theme; denied = false }
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-timeline-empty").assertExists()
            compose.onNodeWithText("No photos here yet").assertExists()
            saveUiEvidence("photos-136-empty-$mode.png")

            compose.runOnIdle { denied = true }
            compose.waitForIdle()
            compose.onNodeWithText("Photos need access").assertExists()
            compose.onNodeWithTag("atlas-empty-action").assertExists()
            compose.onNodeWithText("Allow access").assertExists()
            saveUiEvidence("photos-136-denied-$mode.png")
        }
    }

    @Test fun photosQuietLineHoldsTheContentAreaWhenMediaArrives() {
        var dark by mutableStateOf(false)
        var items by mutableStateOf<List<MediaRecord>>(emptyList())
        val invented = inventedMedia(24, 913_600_000L)
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)) {
                        AtlasTimelineGallery(
                            items = items,
                            sort = TimelineSort.NEWEST,
                            gridState = rememberLazyGridState(),
                            onOpen = {},
                            modifier = Modifier.fillMaxSize().testTag("photos-136-area"),
                            viewKey = "photos-136-jump")
                    }
                }
            }
        }
        val tolerance = 1f // dp
        for (theme in listOf(false, true)) {
            val mode = if (theme) "dark" else "light"
            compose.runOnIdle { dark = theme; items = emptyList() }
            compose.waitForIdle()
            val area = compose.onNodeWithTag("photos-136-area").getBoundsInRoot()
            val line = compose.onNodeWithTag("atlas-timeline-empty").getBoundsInRoot()
            // The quiet line sits at the centre of the content area, not the screen.
            assertEquals(((area.top + area.bottom) / 2).value,
                ((line.top + line.bottom) / 2).value, 1f)
            assertEquals(((area.left + area.right) / 2).value,
                ((line.left + line.right) / 2).value, 1f)
            saveUiEvidence("photos-136-jump-before-$mode.png")

            compose.runOnIdle { items = invented }
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithTag("atlas-timeline-grid")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-timeline-empty").assertDoesNotExist()
            val loaded = compose.onNodeWithTag("photos-136-area").getBoundsInRoot()
            // Content arrives in the same box: nothing above or around it moved.
            assertTrue("content area moved: $area -> $loaded",
                kotlin.math.abs((loaded.top - area.top).value) <= tolerance &&
                    kotlin.math.abs((loaded.bottom - area.bottom).value) <= tolerance)
            saveUiEvidence("photos-136-jump-after-$mode.png")
        }
    }

    @Test fun viewerDecodeErrorIsNamedAndNeighbourSwipeStillWorks() {
        var dark by mutableStateOf(false)
        val invented = inventedMedia(3, 913_610_000L)
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GalleryViewer(invented, invented[0].uri, onBack = {})
                }
            }
        }
        fun awaitDecodeError() = compose.waitUntil(15_000L) {
            compose.onAllNodesWithTag("atlas-viewer-decode-error", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        for ((theme, next) in listOf(false to "2 of 3", true to "3 of 3")) {
            val mode = if (theme) "dark" else "light"
            compose.runOnIdle { dark = theme }
            awaitDecodeError()
            compose.waitForIdle()
            compose.onNodeWithText("Can't open photo", useUnmergedTree = true).assertExists()
            saveUiEvidence("viewer-136-decode-error-$mode.png")

            // The failed page must not trap the user: a swipe still pages on.
            compose.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
            compose.waitUntil(10_000L) {
                compose.onAllNodesWithText(next).fetchSemanticsNodes().isNotEmpty()
            }
            awaitDecodeError()
        }
    }
}
