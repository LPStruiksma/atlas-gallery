package nl.atlas.gallery.featurepeopleui

import android.view.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.MainActivity
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TOUCH-1: real pointer injection, not SemanticsActions.OnClick. Only fictional
 * face vectors and names are used. The Activity supplies a genuine IME window.
 */
@RunWith(AndroidJUnit4::class)
class UnknownPersonImeTouchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val target = PersonKey("touch-synthetic-target")
    private fun face(i: Int) = NativeFaceObservationId(i.toString(16).padStart(32, '0'))

    private fun review(): PeopleClusterReviewState {
        val model = "touch-synthetic-v1"
        val state = FaceClusterState.empty(model, 2, ClusterPolicy.SFACE_CONSERVATIVE)
        var review = PeopleClusterReviewState(
            "touch-grant", "touch-index",
            FaceClusterer.update(state, add = listOf(
                FaceEmbedding(face(1), model, floatArrayOf(1f, 0f)),
                FaceEmbedding(face(2), model, floatArrayOf(0f, 1f))
            ))
        )
        val named = review.clusters.unknownClusters.single { face(1) in it.members }
        review = reviewPeopleCluster(
            review, PeopleReviewAction.NameCluster(named.id, target, "Morgan")
        )!!
        return review
    }

    private fun imeBottom(): Int {
        val window = compose.activity.window
        val decor = window.decorView
        return decor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
    }

    private fun fixture() {
        val initial = review()
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    UnknownFacesReviewScreen(
                        queue = null,
                        currentPermissionEpoch = "touch-grant",
                        currentSourceGeneration = "touch-index",
                        liveReviewStateAtAction = { null },
                        reviewBackendReady = true,
                        onDecide = {}, onClose = {},
                        clusterReview = initial,
                        liveClusterReviewAtAction = { initial },
                        onClusterReviewCommand = {},
                        clusterFacePreview = { Text("Synthetic face") }
                    )
                }
            }
        }
        val screen = compose.onNodeWithTag("atlas-unknown-face-review-screen")
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-toggle-search"))
        // Physical touch, including the expansion control.
        compose.onNodeWithTag("atlas-group-1-destination-toggle-search")
            .performTouchInput { click() }
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-search"))
    }

    private fun tapChoice() {
        compose.onNodeWithTag("atlas-group-1-destination-option-1")
            .performTouchInput { click() }
        compose.onNodeWithTag("atlas-group-1-destination-selected").assertExists()
    }

    @Test fun settledImeTouchSelectsPerson() {
        fixture()
        compose.onNodeWithTag("atlas-group-1-destination-search").performTextInput("Morgan")
        // Observe real IME layout, not an idle-only approximation.
        var last = -1
        var stable = 0
        compose.waitUntil(10_000) {
            val current = imeBottom()
            stable = if (current > 0 && current == last) stable + 1 else 0
            last = current
            stable >= 2
        }
        compose.onNodeWithTag("atlas-unknown-face-review-screen")
            .performScrollToNode(hasTestTag("atlas-group-1-destination-option-1"))
        tapChoice()
    }

    @Test fun openingImeTouchIsNeverSilentlySwallowed() {
        fixture()
        compose.onNodeWithTag("atlas-group-1-destination-search").performTextInput("Morgan")
        // Intentionally no waitForIdle, ime-inset stabilization or scroll after
        // typing: a real tap must survive the window transition.
        tapChoice()
    }
}
