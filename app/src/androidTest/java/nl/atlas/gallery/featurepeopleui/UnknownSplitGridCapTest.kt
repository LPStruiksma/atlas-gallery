package nl.atlas.gallery.featurepeopleui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #356 (lost in #354): a 300-face synthetic Unknown group composes at most one
 * page of crop previews in "Some faces are different" until "Show more".
 * Synthetic only: no photos, detector or database.
 */
@RunWith(AndroidJUnit4::class)
class UnknownSplitGridCapTest {
    @get:Rule val compose = createComposeRule()

    private fun face(i: Int) = NativeFaceObservationId(i.toString(16).padStart(32, '0'))

    private fun review(size: Int): PeopleClusterReviewState {
        val model = "synthetic-split-cap-v1"
        val embeddings = (1..size).map { i ->
            FaceEmbedding(face(i), model, floatArrayOf(1f, i * 0.00001f))
        }
        return PeopleClusterReviewState(
            "synthetic-grant", "synthetic-index",
            FaceClusterer.update(FaceClusterState.empty(
                model, 2, ClusterPolicy.SFACE_CONSERVATIVE), add = embeddings)
        )
    }

    @Test fun bigGroupComposesOnePageOfCropsUntilShowMore() {
        val state = review(300)
        val group = state.clusters.unknownClusters.single()
        assertEquals(300, group.members.size)
        val composedCrops = mutableSetOf<NativeFaceObservationId>()
        compose.setContent {
            MaterialTheme {
                UnknownFacesReviewScreen(
                    queue = null, currentPermissionEpoch = "synthetic-grant",
                    currentSourceGeneration = "synthetic-index",
                    liveReviewStateAtAction = { null },
                    reviewBackendReady = true,
                    onDecide = {}, onClose = {},
                    clusterReview = state,
                    liveClusterReviewAtAction = { state },
                    onClusterReviewCommand = {},
                    clusterFacePreview = { id ->
                        composedCrops += id
                        Text("crop")
                    }
                )
            }
        }
        val prefix = "atlas-group-1-destination"
        compose.onNodeWithText("Some faces are different").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("$prefix-face-$SPLIT_GRID_PAGE_FACES").assertExists()
        compose.onNodeWithTag("$prefix-face-${SPLIT_GRID_PAGE_FACES + 1}").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("composed ${composedCrops.size} crops",
                composedCrops.size <= SPLIT_GRID_PAGE_FACES)
        }

        compose.onNodeWithTag("$prefix-show-more-faces").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("$prefix-face-${2 * SPLIT_GRID_PAGE_FACES}").assertExists()
        compose.onNodeWithTag("$prefix-face-${2 * SPLIT_GRID_PAGE_FACES + 1}").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("composed ${composedCrops.size} crops",
                composedCrops.size <= 2 * SPLIT_GRID_PAGE_FACES)
        }
    }
}
