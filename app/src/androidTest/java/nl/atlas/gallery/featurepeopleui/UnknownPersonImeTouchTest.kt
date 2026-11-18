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
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
     * first IME `onProgress` (UI thread, mid-transition) releases one
     * background thread that injects DOWN and UP through UiAutomation at [x],
     * [y] (screen pixels), with no Compose synchronisation in between.
     * #411: that thread is started (and its UiAutomation fetched) in
     * [install], parked on a latch, so the release costs no thread start; at
     * 4fe8519 a thread started from onProgress delivered DOWN only after 8
     * progress frames, once the animation had ended. A wrapped
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
        private val release = CountDownLatch(1)
        @Volatile private var cancelled = false
        private var injector: Thread? = null
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
                    if (fired.compareAndSet(false, true)) release.countDown()
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
                release.await()
                if (cancelled) return
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
            injector = Thread(::injectTap, "touch1-ime-tap").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
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
            // Never leave a parked injector behind to tap a later test.
            cancelled = true
            release.countDown()
            injector?.join(5_000)
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
     *
     * #411: at scale 1 the CI keyboard animation lasts about 8 frames, and an
     * injected tap needs a comparable time to travel through the input
     * dispatcher, so DOWN arrived just after the animation ended (run
     * 44319216708). [OPENING_ANIMATOR_SCALE] stretches the show animation so
     * the "tap while it opens" window is wide enough to hit on a slow
     * emulator. It is still a real platform animation; only its duration
     * changes, and only inside this test.
     */
    private fun <T> withAnimatorScale(scale: String, body: () -> T): T {
        val before = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale $scale")
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

    // Block body: JUnit only runs void test methods (an expression body returned
    // SemanticsNodeInteraction and failed the class with initializationError).
    @Test fun openingImeTouchIsNeverSilentlySwallowed() {
        withAnimatorScale(OPENING_ANIMATOR_SCALE) { openingImeTouch() }
    }

    /** IME state read on the UI thread, as "visible=<b> bottom=<px>". */
    private fun imeState(): Pair<Boolean, Int> {
        var state = false to 0
        compose.runOnUiThread {
            val insets = activity().window.decorView.rootWindowInsets
            if (insets != null) {
                state = insets.isVisible(WindowInsets.Type.ime()) to
                    insets.getInsets(WindowInsets.Type.ime()).bottom
            }
        }
        return state
    }

    private fun imeVisible(): Boolean = imeState().let { (visible, bottom) -> visible || bottom > 0 }

    /**
     * waitUntil that names its step and the IME state on timeout, so a CI
     * failure says which precondition stalled instead of a bare timeout.
     */
    private fun step(name: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (timeout: ComposeTimeoutException) {
            val (visible, bottom) = imeState()
            android.util.Log.i("ATLAS_TOUCH1", "step timeout: $name imeVisible=$visible imeBottom=$bottom")
            throw AssertionError("TOUCH-1 step '$name' timed out (imeVisible=$visible imeBottom=$bottom)", timeout)
        }
    }

    /**
     * Realistic opening case: the person has typed a name (results listed), the
     * keyboard went away, and they tap the field again and immediately tap the
     * suggestion while the keyboard is coming back up. Results only exist for a
     * non-blank query, so the name is typed first.
     */
    private fun openingImeTouch() {
        fixture()
        step("animators enabled", 5_000) { ValueAnimator.areAnimatorsEnabled() }
        val search = compose.onNodeWithTag("atlas-group-1-destination-search")
        search.performTextInput("Morgan")
        // Same settled-IME proof as settledImeTouchSelectsPerson: hiding while
        // the show request is still in flight can be dropped by the IME.
        val frames = ImeFrames(activity())
        frames.start()
        val keyboardHeight: Int
        try {
            step("keyboard settled after typing") { frames.latestBottom > 0 && frames.stableFrames >= 2 }
            keyboardHeight = frames.latestBottom
        } finally {
            frames.stop()
        }
        // Hide, re-requesting once a second in case one request is dropped.
        var lastHide = 0L
        step("keyboard hidden") {
            val now = SystemClock.uptimeMillis()
            if (now - lastHide >= 1_000) {
                lastHide = now
                compose.runOnUiThread { activity().window.insetsController?.hide(WindowInsets.Type.ime()) }
            }
            !imeVisible()
        }
        compose.waitForIdle()
        val screen = compose.onNodeWithTag("atlas-unknown-face-review-screen")
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-option-1"))
        // #424: the tap must land on screen that the keyboard does not take
        // over. The IME window takes touches in its final frame as soon as
        // its show animation starts. A tap injected below the settled
        // keyboard's top reached LatinIME, which opened its own
        // PermissionsActivity, and never reached this window (run
        // 44303296470). That is the platform routing the tap to the
        // keyboard, not ATLAS swallowing it. So lift the choice above the
        // keyboard's top first, and fail loudly if it cannot be lifted.
        val option = compose.onNodeWithTag("atlas-group-1-destination-option-1")
        val windowHeight = compose.runOnIdle { activity().window.decorView.height }
        val keyboardTop = windowHeight - keyboardHeight
        val clearance = 48f * activity().resources.displayMetrics.density
        val firstBottom = option.fetchSemanticsNode().boundsInWindow.bottom
        val lift = firstBottom - (keyboardTop - clearance)
        if (lift > 0f) {
            screen.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, lift) }
            compose.waitForIdle()
        }
        // Measured with the keyboard closed, where the person sees the result.
        val bounds = option.fetchSemanticsNode().boundsInWindow
        android.util.Log.i(
            "ATLAS_TOUCH1",
            "tap target: window=$windowHeight keyboard=$keyboardHeight keyboardTop=$keyboardTop " +
                "optionBottom before=$firstBottom after=${bounds.bottom} lift=$lift"
        )
        assertTrue(
            "Precondition: the choice (bottom ${bounds.bottom}) must sit above the settled " +
                "keyboard top ($keyboardTop) so the opening tap is ATLAS's to handle",
            bounds.bottom <= keyboardTop
        )
        val window = IntArray(2)
        compose.runOnIdle { activity().window.decorView.getLocationOnScreen(window) }
        val tap = ImeOpeningTap(
            activity(), window[0] + bounds.center.x, window[1] + bounds.center.y
        )
        tap.install()
        try {
            // A real tap on the field brings the keyboard back; the first IME
            // onProgress of that show animation injects the one tap on the
            // suggestion (see ImeOpeningTap).
            search.performTouchInput { click() }
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
            leaveKeyboardClosed()
        }
    }

    /**
     * #411 teardown: later tests in the same process must not inherit this
     * test's keyboard, and a tap that reached the keyboard window may have
     * brought one of the IME's own activities (LatinIME's PermissionsActivity
     * in run 44303296470) in front of the next test. Hide the IME, wait for
     * it to go, and send Back if a non-ATLAS activity took the foreground.
     * Best effort: teardown never masks the test's own result.
     */
    private fun leaveKeyboardClosed() {
        runCatching {
            compose.runOnUiThread { activity().window.insetsController?.hide(WindowInsets.Type.ime()) }
            compose.waitUntil(5_000) { !imeVisible() }
        }
        runCatching {
            val top = shell("dumpsys activity activities").lineSequence()
                .firstOrNull { "topResumedActivity" in it || "mResumedActivity" in it }.orEmpty()
            val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            if (top.isNotBlank() && "$pkg/" !in top && "${pkg}.test/" !in top) {
                android.util.Log.i("ATLAS_TOUCH1", "teardown: foreign top activity, sending Back: ${top.trim()}")
                shell("input keyevent KEYCODE_BACK")
            }
        }
    }

    private companion object {
        const val OPENING_ANIMATOR_SCALE = "4"
    }
}
