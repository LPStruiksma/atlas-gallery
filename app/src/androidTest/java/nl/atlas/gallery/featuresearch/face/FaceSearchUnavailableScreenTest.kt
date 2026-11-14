package nl.atlas.gallery.featuresearch.face

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.saveUiEvidence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #64: render the recoverable out-of-memory state on the real Compose results
 * screen. This verifies the visible UI rather than only the session state.
 */
@RunWith(AndroidJUnit4::class)
class FaceSearchUnavailableScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun outOfMemoryShowsRecoveryMessageAndCloseWorks() {
        var closes = 0
        compose.setContent {
            FaceSearchResultsScreen(
                state = FaceSearchState.Unavailable,
                onOpen = { _, _ -> error("Unavailable must not expose results") },
                onOpenAlbum = { _, _ -> error("Unavailable must not expose an album") },
                onOpenPeople = { error("Unavailable must not offer People navigation") },
                onBack = { closes++ }
            )
        }

        compose.onNodeWithTag("atlas-face-results-unavailable").assertExists()
        compose.onNodeWithText("Search unavailable, try again.").assertExists()
        compose.onNodeWithText(
            "This device ran out of memory preparing face search. No photo was changed."
        ).assertExists()
        compose.onNodeWithTag("atlas-face-results-grid").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("face-search-unavailable.png")
        compose.onNodeWithText("Close").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }
}
