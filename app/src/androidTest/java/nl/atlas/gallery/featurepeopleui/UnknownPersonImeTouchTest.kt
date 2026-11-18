package nl.atlas.gallery.featurepeopleui

import android.animation.ValueAnimator
import android.app.Activity
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
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
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
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
     * Real pointer injection timed by the platform's own IME show animation.
     *
     * #388 review: a Compose `performTouchInput` first waits for idle, so the
     * IME transition can finish before the pointer is injected. Instead, the
     * first IME `onProgress` (UI thread, mid-transition) starts one background
     * thread that injects DOWN and UP through UiAutomation at [x], [y] (screen
     * pixels), with no Compose synchronisation in between. A wrapped
     * `Window.Callback` records the IME phase when each event actually reaches
     * the window, which the test asserts afterwards. One tap, no retry.
     */
    private class ImeOpeningTap(
        private val activity: Activity,
        private val x: Float,
        private val y: Float
    ) {
        @Volatile var openingProgress = 0
        @Volatile var started = false
        @Volatile var ended = false
        @Volatile var running = false
        @Volatile var downArrived = false
        @Volatile var upArrived = false
        @Volatile var imeAnimatingAtDown = false
        @Volatile var imeAnimatingAtUp = false
        @Volatile var progressBeforeDown = 0
        @Volatile var injectionError: Throwable? = null
        private val fired = AtomicBoolean(false)
        private var originalCallback: Window.Callback? = null

        private fun isImeAnimating() = running && !ended

        private val animation = object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
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
                    if (fired.compareAndSet(false, true)) Thread(::injectTap, "touch1-ime-tap").start()
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

        private fun injectTap() {
            try {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val downAt = SystemClock.uptimeMillis()
                for ((action, at) in listOf(
                    MotionEvent.ACTION_DOWN to downAt,
                    MotionEvent.ACTION_UP to downAt + 40
                )) {
                    val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    check(automation.injectInputEvent(event, true)) { "injectInputEvent refused $action" }
                    event.recycle()
                }
            } catch (t: Throwable) {
                injectionError = t
            }
        }

        fun install() {
            activity.runOnUiThread {
                val original = activity.window.callback
                originalCallback = original
                activity.window.callback = object : Window.Callback by original {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                imeAnimatingAtDown = isImeAnimating()
                                progressBeforeDown = openingProgress
                                downArrived = true
                            }
                            MotionEvent.ACTION_UP -> {
                                imeAnimatingAtUp = isImeAnimating()
                                upArrived = true
                            }
                        }
                        return original.dispatchTouchEvent(event)
                    }
                }
                activity.window.decorView.setWindowInsetsAnimationCallback(animation)
            }
        }

        fun remove() {
            activity.runOnUiThread {
                activity.window.decorView.setWindowInsetsAnimationCallback(null)
                originalCallback?.let { activity.window.callback = it }
            }
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { it.readBytes().decodeToString() }.trim()
    }

    /**
     * CI emulators run with animator scale 0 (`disable-animations: true`), and
     * then the platform shows the IME with no WindowInsetsAnimation at all:
     * run 44278979909 timed out waiting for one. Phones animate the keyboard, so
     * this one test turns the animator scale on for its own duration and puts
     * the previous value back afterwards.
     */
    private fun <T> withAnimatorScaleOne(body: () -> T): T {
        val before = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 1")
        try {
            return body()
        } finally {
            if (before == "null" || before.isEmpty()) {
                shell("settings delete global animator_duration_scale")
            } else {
                shell("settings put global animator_duration_scale $before")
            }
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

    @Test fun openingImeTouchIsNeverSilentlySwallowed() = withAnimatorScaleOne {
        fixture()
        compose.waitUntil(5_000) { ValueAnimator.areAnimatorsEnabled() }
        val screen = compose.onNodeWithTag("atlas-unknown-face-review-screen")
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-option-1"))
        // Measured with the keyboard closed: the empty filter already lists option 1.
        val bounds = compose.onNodeWithTag("atlas-group-1-destination-option-1")
            .fetchSemanticsNode().boundsInWindow
        val window = IntArray(2)
        compose.runOnIdle { activity().window.decorView.getLocationOnScreen(window) }
        val tap = ImeOpeningTap(
            activity(), window[0] + bounds.center.x, window[1] + bounds.center.y
        )
        tap.install()
        try {
            // Focusing the field starts the IME show animation; its first
            // onProgress injects the one tap (see ImeOpeningTap).
            compose.onNodeWithTag("atlas-group-1-destination-search").performTextInput("Morgan")
            runCatching {
                compose.waitUntil(10_000) { tap.upArrived || tap.injectionError != null }
            }
            android.util.Log.i(
                "ATLAS_TOUCH1",
                "opening tap: started=${tap.started} progressTotal=${tap.openingProgress} " +
                    "down=${tap.downArrived} animatingAtDown=${tap.imeAnimatingAtDown} " +
                    "progressBeforeDown=${tap.progressBeforeDown} up=${tap.upArrived} " +
                    "animatingAtUp=${tap.imeAnimatingAtUp} error=${tap.injectionError}"
            )
            tap.injectionError?.let { throw AssertionError("Pointer injection failed", it) }
            assertTrue("IME show animation must start (no WindowInsetsAnimation seen)", tap.started)
            assertTrue("Pointer DOWN must reach the window", tap.downArrived)
            assertTrue(
                "IME must still be animating when pointer DOWN reaches the window " +
                    "(progress frames before DOWN: ${tap.progressBeforeDown})",
                tap.imeAnimatingAtDown
            )
            assertTrue("Pointer UP must reach the window", tap.upArrived)
            compose.onNodeWithTag("atlas-group-1-destination-selected").assertExists()
        } finally {
            tap.remove()
        }
    }
}
