package nl.atlas.gallery

import android.accessibilityservice.AccessibilityService
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.featuredesign.AtlasGalleryNavigation
import nl.atlas.gallery.featuredesign.AtlasHeaderMenuItem
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.PeopleActionAvailability
import nl.atlas.gallery.featurepeopleui.PeopleHomeGroup
import nl.atlas.gallery.featurepeopleui.PeopleHomePerson
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import nl.atlas.gallery.featurepeopleui.PeopleProfileUi
import nl.atlas.gallery.featuresearch.EphemeralSearchHistory
import nl.atlas.gallery.featuresearch.LocalSearchControls
import nl.atlas.gallery.featuresearch.LocalSearchFacets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #516 rendered proof in the real Activity on invented PNGs: People is a
 * plain page (no banner, explainer, V4 card or index panel), its secondary
 * actions sit in ⋮, and Search is one field. Screenshots go to the
 * emulator-only Pictures/ATLAS-UI-Evidence folder for the PR.
 */
@RunWith(AndroidJUnit4::class)
class PeopleSearchAppEvidenceTest {
    private val fixtures = SyntheticHeaderFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    // #584: phone-size renders (Galaxy S24+, 1080×2340).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun peopleIsPlainAndSearchIsOneField() {
        // This is an empty-library evidence render. In particular, the
        // upgrade proof's fictional confirmed person must not survive into it.
        PeopleRepository(InstrumentationRegistry.getInstrumentation().targetContext).use {
            assertTrue("Previous instrumentation leaked confirmed People into the screenshot",
                it.people().isEmpty())
        }
        waitFor(compose, hasTestTag("atlas-nav-people"))
        compose.onNodeWithTag("atlas-nav-people").performClick()
        waitFor(compose, hasTestTag("atlas-people-home"))
        // None of the owner-flagged chrome (#526 owner evidence) is on People.
        listOf("atlas-visual-index", "atlas-people-details").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        assertNoText(compose, "Face discovery needs attention", "Your confirmed people",
            "Your People collection starts here", "Already use Monochrome Studio V4?",
            "Choose V4 export", "Connect V4")
        compose.waitForIdle()
        saveUiEvidence("people-empty.png")

        compose.onNodeWithTag("atlas-header-more").performClick()
        // #584: Samsung's People ⋮ → Hidden.
        waitFor(compose, hasTestTag("atlas-people-hidden-open"))
        compose.onNodeWithTag("atlas-library-status").assertExists()
        // #643: maintenance and developer tools are not People actions.
        listOf("atlas-people-import-v4", "atlas-people-link-v4", "atlas-people-visual-index",
            "atlas-people-numbers-open", "atlas-people-details").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.waitForIdle()
        saveUiEvidence("people-menu.png")
        compose.onNodeWithTag("atlas-people-hidden-open").performClick()
        waitFor(compose, hasTestTag("atlas-people-hidden"))
        waitFor(compose, androidx.compose.ui.test.hasText("No hidden people"))
        compose.waitForIdle()
        saveUiEvidence("people-hidden.png")
        dismissWithBack(compose, hasTestTag("atlas-people-hidden"))
        waitFor(compose, hasTestTag("atlas-people-home"))

        // #664: Settings is the one home for People maintenance.
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor(compose, hasTestTag("atlas-library-status"))
        compose.onNodeWithTag("atlas-library-status").performClick()
        waitFor(compose, hasTestTag("atlas-settings-screen"))
        compose.onNodeWithTag("atlas-people-numbers-open").assertExists()
        compose.onNodeWithTag("atlas-people-import-v4").assertDoesNotExist()
        compose.onNodeWithTag("atlas-settings-advanced").performClick()
        listOf("atlas-people-import-v4", "atlas-people-link-v4",
            "atlas-settings-diagnostics").forEach {
            compose.onNodeWithTag(it).assertExists()
        }
        compose.waitForIdle()
        saveUiEvidence("people-settings.png")
        compose.onNodeWithTag("atlas-header-back").performClick()
        waitFor(compose, hasTestTag("atlas-people-home"))

        compose.onNodeWithTag("atlas-nav-search").performClick()
        waitFor(compose, hasTestTag("atlas-search-field"))
        assertNoText(compose, "Exact local search")
        // #644: at rest only the field (one ⋮), recents and suggestions.
        listOf("atlas-header", "atlas-header-more", "atlas-grid-view-as",
            "atlas-search-kind-ALL", "atlas-feature-grid").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("atlas-search-overflow").assertExists()
        compose.onNodeWithTag("atlas-search-by-face").assertExists()
        compose.waitForIdle()
        saveUiEvidence("search.png")

        // #654: a query with no hits says "No results", not "No photos here yet".
        compose.onNodeWithTag("atlas-search-field").performTextInput("qzxqzx")
        waitFor(compose, hasTestTag("atlas-feature-grid-empty") and
            androidx.compose.ui.test.hasText("No results"))
        assertNoText(compose, "No photos here yet")
        compose.waitForIdle()
        saveUiEvidence("search-654-empty.png")
    }
}

/**
 * #516 rendered proof of states the empty emulator library cannot reach:
 * a People grid of named round portraits while a scan runs, and Search with
 * recents. Names and portraits are invented and drawn locally.
 */
@RunWith(AndroidJUnit4::class)
class PeopleSearchStateEvidenceTest {
    private val compose = createComposeRule()

    // #645: evidence at the owner's S24+ size (1080x2340).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val names = listOf("Ada", "Ben", "Chloé", "Daan", "Eva", "Femke", "Gijs", "")

    /**
     * #643: while a scan runs, People shows the grid and one thin line under
     * the title: no status text, no button. Pause and review sit in ⋮, next
     * to Hidden; the V4 import is not there. Idle, the line is gone too.
     */
    @Test fun settingsPausesAndResumesOnlyWhenTheHostOffersActions() {
        var phase by mutableStateOf(FaceDiscoveryPhase.SCANNING)
        var pauses = 0
        var resumes = 0
        compose.setContent {
            AtlasEvidenceHarness {
                AtlasSettingsScreen(
                    scanPhase = phase,
                    onPauseScan = { pauses++; phase = FaceDiscoveryPhase.PAUSED },
                    onResumeScan = { resumes++; phase = FaceDiscoveryPhase.SCANNING },
                    visualIndexOn = false,
                    showPhotoDetails = false,
                    versionName = "test",
                    onVisualIndex = {},
                    onPeopleNumbers = {},
                    onPhotoDetails = {},
                    onImportV4 = {},
                    onLinkV4 = {},
                    onDiagnostics = {}
                )
            }
        }
        waitFor(compose, hasTestTag("atlas-settings-face-scanning"))
        compose.onNodeWithTag("atlas-settings-face-scanning").performClick()
        compose.runOnIdle { assertEquals(1, pauses); assertEquals(FaceDiscoveryPhase.PAUSED, phase) }
        compose.onNodeWithTag("atlas-settings-face-scanning").performClick()
        compose.runOnIdle { assertEquals(1, resumes); assertEquals(FaceDiscoveryPhase.SCANNING, phase) }
        compose.waitForIdle()
        saveUiEvidence("settings-664-scan-controls.png")
    }

    @Test fun peopleGridWithRoundPortraitsWhileScanning() {
        var reviews = 0
        var phase by mutableStateOf(FaceDiscoveryPhase.SCANNING)
        val profiles = names.mapIndexed { i, name ->
            PeopleProfileUi(i + 1L, name, accessiblePhotos = 40 - i * 3,
                confirmedPhotoReferences = 40 - i * 3)
        }
        compose.setContent {
            Shell(AtlasDestination.PEOPLE) { modifier ->
                PeopleHomeScreen(
                    state = PeopleHomeUiState(
                        discovery = FaceDiscoveryUiState(
                            phase = phase, processed = 320,
                            eligible = 1000, unknownObservations = 12,
                            observedPermissionEpoch = "synthetic-grant"),
                        profiles = profiles),
                    actions = PeopleActionAvailability(mayPauseScan = true,
                        mayOpenReview = true),
                    onOpenProfile = {},
                    currentPermissionEpoch = "synthetic-grant",
                    onOpenReview = { reviews++ },
                    menuItems = listOf(AtlasHeaderMenuItem("Hidden",
                        "atlas-people-hidden-open", onClick = {})),
                    onOpenLibraryStatus = {},
                    modifier = modifier,
                    portrait = { SyntheticFace(it.personId) }
                )
            }
        }
        waitFor(compose, hasTestTag("atlas-people-profile-1"))
        waitFor(compose, hasTestTag("atlas-people-progress"))
        compose.onNodeWithTag("atlas-people-progress-line")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Finding people · 320 of 1000"))
        assertNoText(compose, "Finding people", "Pause scanning", "Review unknown faces")
        compose.waitForIdle()
        saveUiEvidence("people-grid.png")

        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor(compose, hasTestTag("atlas-people-review"))
        listOf("atlas-people-hidden-open", "atlas-people-review", "atlas-library-status")
            .forEach { compose.onNodeWithTag(it).assertExists() }
        listOf("atlas-people-pause", "atlas-people-resume",
            "atlas-people-import-v4", "atlas-people-link-v4").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.waitForIdle()
        saveUiEvidence("people-menu-scanning.png")
        compose.onNodeWithTag("atlas-people-review").performClick()
        compose.runOnIdle { assertEquals(1, reviews) }

        compose.runOnIdle { phase = FaceDiscoveryPhase.FINISHED }
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-people-progress"), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("atlas-people-pause").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("people-grid-idle.png")
    }

    /** #579: named people first, then one "Add name" circle per unnamed group of 3+. */
    @Test fun peopleGridShowsUnnamedGroupsAfterNamedPeople() {
        val face = { n: Int -> "%032x".format(0xA000 + n) }
        val summary = PeopleHomeSummary("synthetic-grant", "synthetic-generation",
            mapOf(PersonKey("p-ada") to PeopleHomePerson("Ada", 120, face(1)),
                PersonKey("p-ben") to PeopleHomePerson("Ben", 45, face(2))),
            List(7) { PeopleHomeGroup(face(10 + it), 300 - it * 40) })
        var reviews = 0
        compose.setContent {
            Shell(AtlasDestination.PEOPLE) { modifier ->
                PeopleHomeScreen(
                    state = PeopleHomeUiState(discovery = FaceDiscoveryUiState(
                        phase = FaceDiscoveryPhase.FINISHED,
                        observedPermissionEpoch = "synthetic-grant")),
                    onOpenProfile = {},
                    currentPermissionEpoch = "synthetic-grant",
                    onOpenReview = { reviews++ },
                    nativePeopleReview = summary,
                    nativePeopleSourceGeneration = "synthetic-generation",
                    onOpenNativePersonAlbum = {},
                    liveNativePeopleAtAction = { summary },
                    modifier = modifier,
                    nativePortrait = { SyntheticFace(it.localHex.takeLast(2).toLong(16)) }
                )
            }
        }
        waitFor(compose, hasTestTag("atlas-people-unnamed"))
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodes(hasTestTag("atlas-native-person-album"))
            .fetchSemanticsNodes().size)
        assertEquals(7, compose.onAllNodes(hasTestTag("atlas-people-unnamed"))
            .fetchSemanticsNodes().size)
        saveUiEvidence("people-unnamed.png")
        compose.onAllNodes(hasTestTag("atlas-people-unnamed"))[0].performClick()
        compose.runOnIdle { assertEquals(1, reviews) }
    }

    @Test fun searchWithRecentsAndChips() {
        compose.setContent {
            Shell(AtlasDestination.SEARCH) { modifier ->
                Column(modifier.padding(top = 12.dp)) {
                    LocalSearchControls(
                        filters = LocalSearchFacets(),
                        people = emptyList(),
                        history = EphemeralSearchHistory(
                            listOf("beach", "Ada", "birthday", "snow"), "synthetic-grant"),
                        onlyTheseFacesAvailable = false,
                        onFiltersChange = {}, onSubmittedQuery = {}, onClearHistory = {},
                        currentPermissionEpoch = "synthetic-grant",
                        onSearchByFace = {}, onFindSimilar = {}
                    )
                }
            }
        }
        waitFor(compose, hasTestTag("atlas-search-field"))
        compose.waitForIdle()
        saveUiEvidence("search-recents.png")
    }

    @androidx.compose.runtime.Composable
    private fun Shell(
        selected: AtlasDestination,
        content: @androidx.compose.runtime.Composable (Modifier) -> Unit
    ) {
        AtlasGalleryTheme(dark = true) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = { AtlasGalleryNavigation(selected, onSelect = {}) }
            ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
        }
    }
}

/** A flat, invented face: coloured backdrop, skin oval, two eyes. Never a real photo. */
@androidx.compose.runtime.Composable
private fun SyntheticFace(seed: Long) {
    val backdrops = listOf(0xFF5C6B73, 0xFF8D6E63, 0xFF4E7D6B, 0xFF6D5C8A, 0xFF8A6D3B,
        0xFF3F6E8C, 0xFF7A4E5A, 0xFF52606D)
    val skins = listOf(0xFFF1C9A5, 0xFFD9A47E, 0xFFB57B55, 0xFF8D5B3C)
    val backdrop = Color(backdrops[(seed % backdrops.size).toInt()])
    val skin = Color(skins[(seed % skins.size).toInt()])
    Canvas(Modifier.fillMaxSize()) {
        drawRect(backdrop)
        val w = size.width
        val h = size.height
        drawOval(skin, Offset(w * 0.28f, h * 0.2f), Size(w * 0.44f, h * 0.56f))
        drawOval(skin, Offset(w * 0.12f, h * 0.78f), Size(w * 0.76f, h * 0.5f))
        drawCircle(Color(0xFF2B2B2B), w * 0.035f, Offset(w * 0.42f, h * 0.44f))
        drawCircle(Color(0xFF2B2B2B), w * 0.035f, Offset(w * 0.58f, h * 0.44f))
    }
}

private fun waitFor(
    compose: androidx.compose.ui.test.junit4.ComposeTestRule,
    matcher: SemanticsMatcher
) {
    try {
        compose.waitUntil(60_000L) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (e: Throwable) {
        val tree = runCatching { compose.onRoot().printToString() }.getOrDefault("?")
        throw AssertionError("Timed out waiting for ${matcher.description}. Screen:\n$tree", e)
    }
}

private fun assertNoText(
    compose: androidx.compose.ui.test.junit4.ComposeTestRule,
    vararg texts: String
) {
    texts.forEach { text ->
        assertTrue("'$text' must not be on screen",
            compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true),
                useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}

/**
 * Closes a popup or dialog the way a person does, with the system Back
 * action, retrying while it is still shown: an injected key can land before
 * the new window has focus. Only sends Back while [shown] is on screen, so a
 * late close never becomes a second Back on the Activity.
 *
 * #610: on a slow CI emulator a system "isn't responding" dialog for another
 * process can hold focus; it is not cancelable, so it swallows every Back.
 * Press its Wait first, as a person would, then send Back to the app. A
 * failure names what had focus.
 */
internal fun dismissWithBack(
    compose: androidx.compose.ui.test.junit4.ComposeTestRule,
    shown: SemanticsMatcher
) {
    fun visible() = compose.onAllNodes(shown, useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    val automation = interactiveUiAutomation()
    repeat(4) { attempt ->
        compose.waitForIdle()
        if (!visible()) return
        val cleared = clearSystemAnrDialogs()
        if (cleared > 0) Log.w(SYSTEM_WINDOWS_LOG_TAG,
            "dismissWithBack: cleared $cleared system ANR dialog(s) before Back ${attempt + 1}")
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        try {
            compose.waitUntil(5_000L) { !visible() }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Back arrived before the window had focus; send it again.
            Log.w(SYSTEM_WINDOWS_LOG_TAG,
                "dismissWithBack: ${shown.description} still shown after Back ${attempt + 1}; ${describeFocus()}")
        }
    }
    check(!visible()) { "${shown.description} still shown after Back; ${describeFocus()}" }
}
