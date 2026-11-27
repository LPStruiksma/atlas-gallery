package nl.atlas.gallery

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #79: exercise MainActivity's actual OnBackPressedDispatcher against the
 * Settings destination, not a stand-alone mocked Settings composable.
 * Invented, deleted MediaStore fixtures; no personal photos or device state.
 */
@RunWith(AndroidJUnit4::class)
class SettingsBackNavigationJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(sizes = listOf(120 to 90))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun systemBackClosesSettingsOnEveryOriginTabWithoutChangingIt() {
        for (tab in listOf("photos", "albums", "people", "search")) {
            select(tab)
            openSettings(tab)
            systemBack()
            assertReturnedTo(tab)
            if (tab == "people" || tab == "search") {
                // Capture the actual visible destination after dismissing Settings,
                // not a Settings screenshot with the original tab hidden behind it.
                saveUiEvidence("settings-back-79-$tab.png")
            }
        }
    }

    @Test fun diagnosticsIsDismissedBeforeSettingsAndHeaderBackKeepsPeople() {
        select("people")
        openSettings("people")
        compose.onNodeWithTag("atlas-settings-advanced").performClick()
        waitFor(hasTestTag("atlas-settings-diagnostics"))
        compose.onNodeWithTag("atlas-settings-diagnostics").performScrollTo().performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText("Library status"))
        compose.onNodeWithTag("atlas-settings-screen").assertDoesNotExist()

        // Nested Advanced -> Diagnostics must go back to Settings first.
        systemBack()
        waitFor(hasTestTag("atlas-settings-screen"))
        compose.onNodeWithTag("atlas-settings-advanced").assertExists()
        compose.onNodeWithTag("atlas-nav-pill").assertDoesNotExist()

        // The header arrow is a second exit route with the same origin.
        compose.onNodeWithTag("atlas-header-back").performClick()
        assertReturnedTo("people")

        // Outside Settings, the ordinary tab Back action still returns to Photos.
        systemBack()
        compose.onNodeWithTag("atlas-nav-photos").assertIsSelected()
    }

    @Test fun activityRecreationKeepsTheOriginTabBehindSettings() {
        select("albums")
        openSettings("albums")
        compose.activityRule.scenario.recreate()
        waitFor(hasTestTag("atlas-settings-screen"))
        systemBack()
        assertReturnedTo("albums")
    }

    private fun select(tab: String) {
        // BRAND-L4 (#657): People is a Collections section, not a slab place.
        if (tab == "people") {
            compose.openPeopleSection()
            waitFor(hasTestTag("atlas-people-home"))
            return
        }
        waitFor(hasTestTag("atlas-nav-$tab"))
        compose.onNodeWithTag("atlas-nav-$tab").performClick()
        compose.onNodeWithTag("atlas-nav-$tab").assertIsSelected()
    }

    private fun openSettings(tab: String) {
        val opener = if (tab == "search") "atlas-search-overflow" else "atlas-header-more"
        waitFor(hasTestTag(opener))
        compose.tap(hasTestTag(opener))
        // The overflow menu is a separate window (rule 5).
        compose.awaitThat("the $tab overflow menu open") {
            compose.exists(hasTestTag("atlas-library-status"))
        }
        compose.tap(hasTestTag("atlas-library-status"))
        waitFor(hasTestTag("atlas-settings-screen"))
        compose.onNodeWithTag("atlas-header-back").assertIsDisplayed()
    }

    private fun systemBack() {
        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun assertReturnedTo(tab: String) {
        compose.onNodeWithTag("atlas-settings-screen").assertDoesNotExist()
        if (tab == "people") {
            // People keeps Collections current; its section pill is selected.
            waitFor(hasTestTag("atlas-people-home"))
            compose.onNodeWithTag("atlas-nav-albums").assertIsSelected()
            compose.onNodeWithTag("atlas-collections-people").assertIsSelected()
            return
        }
        waitFor(hasTestTag("atlas-nav-$tab"))
        compose.onNodeWithTag("atlas-nav-$tab").assertIsSelected()
        compose.onNodeWithTag("atlas-nav-$tab").assertIsDisplayed()
        // Search has no top-level header; the field proves actual route content.
        if (tab == "search") {
            compose.onNodeWithTag("atlas-search-field").assertIsDisplayed()
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}
