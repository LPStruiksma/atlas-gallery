package nl.atlas.gallery.featuresearch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featureface.search.FaceMatchTier
import nl.atlas.gallery.featuresearch.face.FaceSearchHit
import nl.atlas.gallery.featuresearch.face.FaceSearchResultsScreen
import nl.atlas.gallery.featuresearch.face.FaceSearchState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic in-scope face results: no media-grant, detector, or real person data. */
@RunWith(AndroidJUnit4::class)
class Find2ScopedFaceTiersTest {
    @get:Rule val compose = createComposeRule()

    private fun record(id: Long) = MediaRecord(
        id = id,
        uri = "content://media/external_primary/images/media/$id",
        name = "synthetic-$id.png",
        relativePath = "DCIM/Camera/",
        sizeBytes = 1000L + id,
        takenAtMillis = 1000L * id,
        isVideo = false,
        modifiedAtSeconds = 1_700_000_000L,
        volumeName = "external_primary",
        providerVersion = "synthetic-provider-1",
        generationModified = 10L + id,
        widthPixels = 480,
        heightPixels = 480,
        orientationDegrees = 0)

    @Test fun screenSortStillVisiblyMarksProbableHit() {
        val sure = record(1)
        val possible = record(2)
        val results = FaceSearchState.Results(
            epoch = "synthetic-epoch",
            hits = listOf(
                FaceSearchHit(sure, FaceMatchTier.SAME_PERSON, 0.94f, emptyList()),
                FaceSearchHit(possible, FaceMatchTier.PROBABLE, 0.74f, emptyList())),
            person = null,
            personName = null,
            truncated = false)

        // The authorized screen order is opposite to similarity order.
        compose.setContent {
            MaterialTheme {
                FaceSearchResultsScreen(
                    state = results,
                    onOpen = { _, _ -> },
                    onOpenAlbum = { _, _ -> },
                    onOpenPeople = {},
                    onBack = {},
                    screenMedia = listOf(possible, sure))
            }
        }
        compose.onNodeWithTag("atlas-face-screen-sort").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("atlas-face-results-sure-header")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Screen sort · 2").assertExists()
        compose.onNodeWithTag("atlas-face-result-0").assertExists()
        compose.onNodeWithTag("atlas-face-result-1").assertExists()
        compose.onAllNodesWithTag("atlas-face-result-possible-label", useUnmergedTree = true)
            .assertCountEquals(1)

        // Back to engine ranking: keep both results and the probable tier.
        compose.onNodeWithTag("atlas-face-screen-sort").performClick()
        compose.onNodeWithText("Same person · 1").assertExists()
        compose.onNodeWithText("Possible matches · 1").assertExists()
    }
}
