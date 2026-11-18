package nl.atlas.gallery.featurepeopleui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TOUCH-1: inject actual pointer events (not SemanticsActions.OnClick).
 * Synthetic people only. Insets/frame evidence comes from the host Activity.
 */
@RunWith(AndroidJUnit4::class)
class UnknownPersonImeTouchTest {
    @get:Rule val compose = createComposeRule()
    private var hostActivity: Activity? = null

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

    private fun activity(): Activity = checkNotNull(hostActivity) {
        "Compose host Activity missing"
    }

    /**
     * Read IME bottom on real, distinct Choreographer frames. waitUntil polling
     * alone can observe the same frame twice and is not a settled-IME proof.
     */
    private class ImeFrames(private val activity: Activity) {
        @Volatile var frameCount = 0
        @Volatile var stableFrames = 0
        @Volatile var latestBottom = 0
        private var lastBottom = -1
        private var running = true
        private val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val insets = activity.window.decorView.rootWindowInsets
                val bottom = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                stableFrames = if (bottom > 0 && bottom == lastBottom) stableFrames + 1 else 0
                lastBottom = bottom
                latestBottom = bottom
                frameCount++
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun start() {
            activity.runOnUiThread { Choreographer.getInstance().postFrameCallback(callback) }
        }

        fun stop() {
            activity.runOnUiThread {
                running = false
                Choreographer.getInstance().removeFrameCallback(callback)
            }
        }
    }

    /**
     * The platform invokes onProgress during an actual IME inset transition.
     * Capture that signal on the Activity's real decor view, rather than inferring
     * animation from the order of Compose test helpers.
     */
    private class ImeOpeningProbe(private val activity: Activity) {
        @Volatile var openingProgress = 0
        @Volatile var started = false
        @Volatile var ended = false
        @Volatile var running = false
        private val callback = object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onStart(
                animation: WindowInsetsAnimation,
                bounds: WindowInsetsAnimation.Bounds
            ): WindowInsetsAnimation.Bounds {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                    started = true
                    running = true
                }
                return bounds
            }

            override fun onProgress(
                insets: WindowInsets,
                runningAnimations: MutableList<WindowInsetsAnimation>
            ): WindowInsets {
                if (running && runningAnimations.any { it.typeMask and WindowInsets.Type.ime() != 0 }) {
                    openingProgress++
                }
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                    running = false
                    ended = true
                }
            }
        }

        fun install() {
            activity.runOnUiThread { activity.window.decorView.setWindowInsetsAnimationCallback(callback) }
        }

        fun remove() {
            activity.runOnUiThread { activity.window.decorView.setWindowInsetsAnimationCallback(null) }
        }
    }

    private fun fixture() {
        val initial = review()
        compose.setContent {
            val context = LocalContext.current
            hostActivity = context as Activity
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
        val frames = ImeFrames(activity())
        frames.start()
        try {
            compose.onNodeWithTag("atlas-group-1-destination-search").performTextInput("Morgan")
            compose.waitUntil(10_000) { frames.latestBottom > 0 && frames.stableFrames >= 2 }
            assertTrue("IME inset must remain positive across distinct display frames", frames.stableFrames >= 2)
            assertTrue("Frame callback must have observed real frames", frames.frameCount >= 3)
            compose.onNodeWithTag("atlas-unknown-face-review-screen")
                .performScrollToNode(hasTestTag("atlas-group-1-destination-option-1"))
            tapChoice()
        } finally {
            frames.stop()
        }
    }

    @Test fun openingImeTouchIsNeverSilentlySwallowed() {
        fixture()
        val probe = ImeOpeningProbe(activity())
        probe.install()
        try {
            compose.onNodeWithTag("atlas-group-1-destination-search").performTextInput("Morgan")
            compose.waitUntil(10_000) {
                probe.started && probe.running && probe.openingProgress > 0
            }
            // No retry: phase must still be active at actual pointer injection.
            assertTrue("IME must be animating when the touch is injected", probe.running && !probe.ended)
            tapChoice()
            assertTrue("IME show transition ended before the tap", probe.started && probe.openingProgress > 0)
        } finally {
            probe.remove()
        }
    }
}
