package nl.atlas.gallery.testkit

import android.os.Looper
import android.view.Choreographer
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import nl.atlas.gallery.screenDump

/**
 * The one wait for emulator tests (docs/testing/EMULATOR_TESTS.md, rule 1):
 * wait for an outcome, never for a time. On timeout the failure names the
 * outcome, the test line, what was on screen and the device state, so a red
 * shard can be read without a diagnostic branch.
 *
 * Each poll advances the Compose (virtual) clock by one frame and lets about
 * 10 ms of real time pass, so both product timers and real work move on.
 */
fun ComposeTestRule.awaitThat(
    label: String, timeoutMs: Long = 15_000L, condition: () -> Boolean
) {
    try {
        waitUntil(timeoutMs) { condition() }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("timed out (${timeoutMs / 1000} s) at ${testLine()} waiting for: " +
            "$label; on screen: ${screenDump(label)}; device: ${describeDevice()}", timeout)
    }
}

/** Whether a node matching [matcher] is in the tree right now. For use inside [awaitThat]. */
fun ComposeTestRule.exists(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false): Boolean =
    onAllNodes(matcher, useUnmergedTree).fetchSemanticsNodes(atLeastOneRootRequired = false)
        .isNotEmpty()

/**
 * Reads [read] on [frames] display frames in a row, on the UI thread. A
 * polling loop alone can read the same frame twice and call a value that is
 * still moving "stable". Call from the test thread only.
 */
internal fun <T> sampleOnFrames(frames: Int = 2, read: () -> T): List<T> {
    val samples = ArrayList<T>(frames)
    val done = CountDownLatch(1)
    val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            samples += read()
            if (samples.size >= frames) done.countDown()
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        Choreographer.getInstance().postFrameCallback(callback)
    }
    check(done.await(5, TimeUnit.SECONDS)) { "the display produced no frame for 5 s" }
    return samples
}

/** Runs [read] on the UI thread, from the test thread or from the UI thread itself. */
internal fun <T> onMainThread(read: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return read()
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(read) }
    return result!!.getOrThrow()
}

/** The Android views that host this rule's Compose roots (the activity first, then popups). */
internal fun ComposeTestRule.rootViews(): List<View> =
    onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false)
        .mapNotNull { (it.root as? ViewRootForTest)?.view }

/** The test file and line that called the kit, so a shared helper still names its step. */
internal fun testLine(): String = Throwable().stackTrace
    .firstOrNull {
        it.fileName?.endsWith("Test.kt") == true && !it.className.contains(".testkit.") ||
            it.fileName == "TestKitSelfTest.kt"
    }
    ?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown step"
