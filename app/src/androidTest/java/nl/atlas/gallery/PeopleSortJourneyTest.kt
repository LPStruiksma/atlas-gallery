package nl.atlas.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import nl.atlas.gallery.featurepeopleui.PeopleProfileUi
import nl.atlas.gallery.featurepeopleui.PeopleSortChoice
import nl.atlas.gallery.featurepeopleui.PeopleSortKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendering/navigation on synthetic metadata only. Screenshots must be captured by CI emulator. */
@RunWith(AndroidJUnit4::class)
class PeopleSortJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sortSheetSelectsAndRestoresAllFiveKeys() {
        var chosen by mutableStateOf(PeopleSortChoice())
        val profiles = listOf(
            PeopleProfileUi(1L, "Synthetic Ada", 3, 3,
                firstAppearanceMs = 100L, mostRecentAppearanceMs = 300L),
            PeopleProfileUi(2L, "Synthetic Ben", 8, 8,
                firstAppearanceMs = 200L, mostRecentAppearanceMs = 500L)
        )
        compose.setContent {
            AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                    PeopleHomeScreen(
                        state = PeopleHomeUiState(profiles = profiles),
                        onOpenProfile = {},
                        sortChoice = chosen,
                        onSortChoice = { chosen = it }
                    )
                }
            }
        }
        compose.onNodeWithTag("atlas-people-sort-open").performClick()
        compose.onNodeWithTag("atlas-people-sort-sheet").assertExists()
        // All choices are represented in one bottom sheet, with both directions.
        PeopleSortKey.entries.forEach { key ->
            compose.onNodeWithTag("atlas-people-sort-open").apply {
                // The sheet may already be open after the initial launch.
            }
            compose.onNodeWithTag("atlas-people-sort-${key.name}-asc")
                .performClick()
            compose.runOnIdle { assertEquals(PeopleSortChoice(key, false), chosen) }
            compose.onNodeWithTag("atlas-people-sort-open").performClick()
            compose.onNodeWithTag("atlas-people-sort-${key.name}-desc")
                .performClick()
            compose.runOnIdle { assertEquals(PeopleSortChoice(key, true), chosen) }
            if (key != PeopleSortKey.RANDOM)
                compose.onNodeWithTag("atlas-people-sort-open").performClick()
        }
        compose.onNodeWithTag("atlas-people-sort-open").assertExists()
    }
}
