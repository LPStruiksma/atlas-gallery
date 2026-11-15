package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.UnknownFacesReviewScreen
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * S24+ 1080x2340 synthetic-only proof for group-first layout in both themes.
 * This test never reads a device photo. Its vector portrait is a neutral icon,
 * not a representation of any real person.
 */
@RunWith(AndroidJUnit4::class)
class UnknownGroupDesignEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private fun face(i: Int) = NativeFaceObservationId(i.toString(16).padStart(32, '0'))

    private fun review(): PeopleClusterReviewState {
        val model = "synthetic-group-sheet-v1"
        val embeddings = (1..8).map { i ->
            FaceEmbedding(face(i), model, floatArrayOf(1f, i * 0.0001f))
        }
        return PeopleClusterReviewState(
            "synthetic-grant", "synthetic-index",
            FaceClusterer.update(FaceClusterState.empty(
                model, 2, ClusterPolicy.SFACE_CONSERVATIVE), add = embeddings)
        )
    }

    @Test fun groupFacesNameAndActionFitTogetherLightDarkWithKeyboard() {
        val state = review()
        var dark by mutableStateOf(false)
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)) {
                        UnknownFacesReviewScreen(
                            queue = null, currentPermissionEpoch = "synthetic-grant",
                            currentSourceGeneration = "synthetic-index",
                            liveReviewStateAtAction = { null },
                            reviewBackendReady = true,
                            onDecide = {}, onClose = {},
                            clusterReview = state,
                            liveClusterReviewAtAction = { state },
                            onClusterReviewCommand = {},
                            clusterFacePreview = {
                                Box(Modifier.fillMaxSize().clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Person, contentDescription = "Face",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        )
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            val mode = if (isDark) "dark" else "light"
            compose.runOnIdle { dark = isDark }
            compose.waitForIdle()
            for (index in 1..6) {
                compose.onNodeWithTag("atlas-group-face-$index").assertIsDisplayed()
            }
            compose.onNodeWithTag("atlas-group-name").assertIsDisplayed()
            compose.onNodeWithTag("atlas-group-create-person").assertIsDisplayed()
            saveUiEvidence("group-sheet-266-ready-$mode.png")
            compose.onNodeWithTag("atlas-group-name").performTextInput("Morgan")
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-group-face-1").assertIsDisplayed()
            compose.onNodeWithTag("atlas-group-name").assertIsDisplayed()
            saveUiEvidence("group-sheet-266-typing-$mode.png")
            // Reset input for the next theme, with a new committed reference.
            compose.onNodeWithTag("atlas-group-name")
                .performTextInput("") // no-op: leave valid named draft, never commit it
        }
    }
}
