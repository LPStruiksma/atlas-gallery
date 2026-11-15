package nl.atlas.gallery.featurepeopleui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic profiles only: verifies the shipped People grid, not nonexistent count tags. */
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

    private fun assertSyntheticProfiles() {
        compose.onAllNodesWithTag("atlas-people-home").assertCountEquals(1)
        compose.onAllNodesWithTag("atlas-people-profile-101").assertCountEquals(1)
        compose.onAllNodesWithTag("atlas-people-profile-102").assertCountEquals(1)
        compose.onAllNodesWithTag("atlas-people-profile-103").assertCountEquals(1)
        compose.onNodeWithText("Synthetic Ada").assertIsDisplayed()
        compose.onNodeWithText("Synthetic Bea").assertIsDisplayed()
        compose.onNodeWithText("Add name").assertIsDisplayed()
    }

    @Test fun namedAndUnnamedCaptionsRenderInLightTheme() {
        compose.setContent { Gallery(dark = false) }
        assertSyntheticProfiles()
    }

    @Test fun namedAndUnnamedCaptionsRenderInDarkTheme() {
        compose.setContent { Gallery(dark = true) }
        assertSyntheticProfiles()
    }
}
