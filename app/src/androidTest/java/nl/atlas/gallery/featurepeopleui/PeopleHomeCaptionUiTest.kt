package nl.atlas.gallery.featurepeopleui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic profile records only: no private photos, identities or MediaStore. */
@RunWith(AndroidJUnit4::class)
class PeopleHomeCaptionUiTest {
    @get:Rule val compose = createComposeRule()

    private val fictional = listOf(
        PeopleProfileUi(101, "Synthetic Ada", 12, 12),
        PeopleProfileUi(102, "", 3, 3),
        PeopleProfileUi(103, "Synthetic Bea", 1, 1)
    )

    @Composable
    private fun Gallery(dark: Boolean) {
        MaterialTheme(colorScheme = if (dark)
            androidx.compose.material3.darkColorScheme()
        else androidx.compose.material3.lightColorScheme()) {
            PeopleHomeScreen(
                state = PeopleHomeUiState(profiles = fictional),
                onOpenProfile = {}
            )
        }
    }

    @Test fun namedAndUnnamedCaptionsMatchCountsInLightTheme() {
        compose.setContent { Gallery(dark = false) }
        compose.onAllNodesWithTag("atlas-people-tile-name", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithTag("atlas-people-tile-count", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithTag("atlas-people-tile-count", useUnmergedTree = true)[0].assertTextEquals("12")
        compose.onAllNodesWithTag("atlas-people-tile-count", useUnmergedTree = true)[1].assertTextEquals("1")
        compose.onAllNodesWithTag("atlas-people-tile-count", useUnmergedTree = true)[2].assertTextEquals("3")
    }

    @Test fun captionsRemainPresentInDarkTheme() {
        compose.setContent { Gallery(dark = true) }
        compose.onAllNodesWithTag("atlas-people-tile-name", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithTag("atlas-people-tile-count", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithTag("atlas-people-tile-count", useUnmergedTree = true)[2].assertTextEquals("3")
    }
}
