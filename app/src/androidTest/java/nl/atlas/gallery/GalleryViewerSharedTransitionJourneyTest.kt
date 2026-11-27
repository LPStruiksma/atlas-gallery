package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import nl.atlas.gallery.testkit.assertFullyVisible
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.tap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #23: the real MainActivity and its dated Photos grid, not a mock navigator.
 * Six invented MediaStore PNGs; capture both themes at 1080x2340.
 */
@RunWith(AndroidJUnit4::class)
class SharedPhotoLightJourneyTest : SharedPhotoJourneyTest(dark = false)

@RunWith(AndroidJUnit4::class)
class SharedPhotoDarkJourneyTest : SharedPhotoJourneyTest(dark = true)

abstract class SharedPhotoJourneyTest(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = TestRule { base, description ->
        if (description.methodName == "viewerOpenTimingFromSyntheticGridTile") {
            SyntheticHeaderFixtures(sizes = listOf(4032 to 3024),
                folderOf = { "ATLAS-Flow3-Timing" }).apply(base, description)
        } else {
            SyntheticHeaderFixtures().apply(base, description)
        }
    }
    private val mode = if (dark) "dark" else "light"

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(SharedPhotoNightRule(dark)).around(SharedPhotoScaleRule()).around(fixtures).around(compose)

    private val tile = SemanticsMatcher("authorized photo tile") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        tag.startsWith("atlas-timeline-media-") || tag.startsWith("atlas-grid-media-")
    }

    private fun firstTile(): String {
        compose.waitUntil(60_000L) {
            compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
            .first().config[SemanticsProperties.TestTag]
    }

    @Test fun photoTravelsFromMeasuredTileAndReturnsToSameCell() {
        val tileTag = firstTile()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag(tileTag, useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeBy(80L)
            compose.waitForIdle()
            val moving = compose.onNodeWithTag("atlas-shared-photo-enter")
                .fetchSemanticsNode().boundsInRoot
            val viewer = compose.onNodeWithTag("atlas-viewer")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("entry should expand from a measured thumbnail", moving.width < viewer.width)
            assertTrue("entry height should be smaller than full viewer", moving.height < viewer.height)
            saveUiEvidence("shared-23-enter-$mode.png")

            compose.mainClock.advanceTimeBy(320L)
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-photo-counter").assertExists()
            compose.onNodeWithTag("atlas-back").performClick()
            compose.mainClock.advanceTimeBy(80L)
            compose.waitForIdle()
            val returning = compose.onNodeWithTag("atlas-shared-photo-exit")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Back should contract the displayed photo", returning.width < viewer.width)
            saveUiEvidence("shared-23-exit-$mode.png")

            compose.mainClock.advanceTimeBy(320L)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-viewer")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(tileTag, useUnmergedTree = true).assertExists()

        // Reopen, move to a different settled photo, and Back. The original
        // thumbnail must not be reused for a different MediaStore URI.
        compose.onNodeWithTag(tileTag, useUnmergedTree = true).performClick()
        compose.waitForNode(hasTestTag("atlas-photo-counter"))
        val start = counter().substringBefore(" of ").toInt() - 1
        val target = if (start == 0) 1 else start - 1
        compose.onNodeWithTag("atlas-thumbnail-$target").performClick()
        compose.waitUntil(10_000L) { counter().startsWith("${target + 1} of ") }
        compose.onNodeWithTag("atlas-back").performClick()
        compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-viewer")).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        saveUiEvidence("shared-23-far-scrub-$mode.png")
    }

    /**
     * PERF-3 on the actual grid -> MainActivity viewer path, not the isolated
     * GalleryViewer arm switch. Synthetic 12 MP PNG, one first open and four
     * repeats; no cold-cache or physical-device claim. A real touchscreen
     * event is required here because timing a Compose test-kit tap includes
     * its layout preflight and virtual-clock manipulation.
     */
    @Test fun viewerOpenTimingFromSyntheticGridTile() {
        val timingTile = SemanticsMatcher("synthetic 12 MP timing tile") { node ->
            tile.matches(node) && node.config.getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty().any { it.startsWith("IMG_HEADER_FIXTURE_1.") }
        }
        compose.waitForNode(timingTile)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val window = compose.activity.window
        val frames = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val dropped = java.util.concurrent.atomic.AtomicInteger()
        val thread = android.os.HandlerThread("flow3-frame-metrics").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            dropped.addAndGet(lost)
            val start = metrics.getMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val duration = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
            if (start > 0L && duration > 0L) frames.add(start to duration)
        }
        data class Open(val start: Long, val end: Long, val first: Long, val sharp: Long)
        val opens = mutableListOf<Open>()
        val focused = SemanticsMatcher("timing photo with both PERF-3 stamps") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .any { it.startsWith("Photo viewer: IMG_HEADER_FIXTURE_1.") } &&
                (node.config.getOrNull(ViewerPhotoFirstFrameAtKey) ?: 0L) > 0L &&
                (node.config.getOrNull(ViewerPhotoSharpAtKey) ?: 0L) > 0L
        }
        try {
            instrumentation.runOnMainSync {
                window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(thread.looper))
            }
            repeat(5) {
                val target = compose.onNode(timingTile, useUnmergedTree = true)
                    .assertFullyVisible()
                compose.waitForIdle()
                val bounds = target.fetchSemanticsNode().boundsInWindow
                val origin = IntArray(2)
                instrumentation.runOnMainSync { window.decorView.getLocationOnScreen(origin) }
                val x = bounds.center.x + origin[0]
                val y = bounds.center.y + origin[1]
                val start = System.nanoTime()
                val downAt = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(
                        downAt, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                    try {
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        check(instrumentation.uiAutomation.injectInputEvent(event, true)) {
                            "FLOW-3 real timing tap rejected"
                        }
                    } finally { event.recycle() }
                }
                compose.awaitThat("the tapped synthetic photo has first and sharp callbacks", 60_000L) {
                    compose.onAllNodes(focused, useUnmergedTree = true).fetchSemanticsNodes().size == 1
                }
                val config = compose.onNode(focused, useUnmergedTree = true).fetchSemanticsNode().config
                val first = config[ViewerPhotoFirstFrameAtKey]
                val sharp = config[ViewerPhotoSharpAtKey]
                compose.awaitThat("the shared entry overlay has completed") {
                    compose.onAllNodes(hasTestTag("atlas-shared-photo-enter")).fetchSemanticsNodes().isEmpty()
                }
                compose.waitForIdle()
                val end = System.nanoTime()
                assertTrue("PERF-3 callback stamps must belong to this open",
                    first in start..end && sharp in start..end)
                opens += Open(start, end, first, sharp)
                compose.tap(hasTestTag("atlas-back")) {
                    compose.onAllNodes(hasTestTag("atlas-viewer")).fetchSemanticsNodes().isEmpty()
                }
                compose.waitForIdle()
                compose.onNode(timingTile, useUnmergedTree = true).assertIsDisplayed()
            }
        } finally {
            instrumentation.runOnMainSync { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            thread.join(5000L)
            check(!thread.isAlive) { "FLOW-3 frame metrics listener did not stop" }
        }
        val captured = synchronized(frames) { frames.toList() }
        fun ms(ns: Long) = ns / 1e6
        fun p90(values: List<Long>): Double =
            ms(values.sorted()[kotlin.math.ceil(values.size * .90).toInt() - 1])
        for ((index, open) in opens.withIndex()) {
            val durations = captured.filter { (vsync, _) -> vsync in open.start..open.end }
                .map { it.second }
            assertTrue("FLOW-3 open " + (index + 1) + " has no real Window frame metrics", durations.isNotEmpty())
            val report = org.json.JSONObject()
                .put("theme", mode)
                .put("trial", index + 1)
                .put("cache", if (index == 0) "first_open_grid_visible" else "repeat_same_photo")
                .put("first_callback_ms", ms(open.first - open.start))
                .put("sharp_2400_callback_ms", ms(open.sharp - open.start))
                .put("frame_count", durations.size)
                .put("frame_p90_ms", p90(durations))
                .put("frame_total_ms", org.json.JSONArray(durations.map { ms(it) }))
                .put("listener_drops_all_trials", dropped.get())
                .put("refresh_hz", compose.activity.display?.refreshRate ?: 0f)
            android.util.Log.i("ATLAS_SCALE", "FLOW3_OPEN_TIMING " + report)
            println("FLOW3_OPEN_TIMING " + report)
        }
        assertTrue("Expected five independent open windows", opens.size == 5)
    }

    @Test fun reducedMotionSkipsOverlayWithoutBreakingViewerBack() {
        val tileTag = firstTile()
        val before = shell23("settings get global animator_duration_scale").trim()
        shell23("settings put global animator_duration_scale 0")
        try {
            compose.onNodeWithTag(tileTag, useUnmergedTree = true).performClick()
            compose.waitForNode(hasTestTag("atlas-photo-counter"))
            compose.onNodeWithTag("atlas-shared-photo-enter").assertDoesNotExist()
            saveUiEvidence("shared-23-reduced-$mode.png")
            compose.onNodeWithTag("atlas-back").performClick()
            compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
            compose.waitUntil(10_000L) {
                compose.onAllNodes(hasTestTag("atlas-viewer")).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            if (before == "null" || before.isBlank())
                shell23("settings delete global animator_duration_scale")
            else shell23("settings put global animator_duration_scale $before")
        }
    }

    private fun counter() = compose.onNodeWithTag("atlas-photo-counter")
        .fetchSemanticsNode().config[SemanticsProperties.Text]
        .joinToString("") { it.text }
}

/**
 * The standard CI emulator launches with disable-animations=true. Enable the
 * two relevant Android scales ONLY for this synthetic transition journey and
 * restore their exact previous values (including missing settings) afterward.
 */
private class SharedPhotoScaleRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val animator = shell23("settings get global animator_duration_scale").trim()
                val transition = shell23("settings get global transition_animation_scale").trim()
                shell23("settings put global animator_duration_scale 1")
                shell23("settings put global transition_animation_scale 1")
                try { base.evaluate() }
                finally {
                    restore23("animator_duration_scale", animator)
                    restore23("transition_animation_scale", transition)
                }
            }
        }
}

private fun restore23(key: String, oldValue: String) {
    if (oldValue == "null" || oldValue.isBlank())
        shell23("settings delete global $key")
    else shell23("settings put global $key $oldValue")
}

private class SharedPhotoNightRule(private val dark: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = shell23("cmd uimode night").substringAfter(":").trim()
                shell23("cmd uimode night " + if (dark) "yes" else "no")
                try { base.evaluate() }
                finally {
                    if (before in setOf("yes", "no", "auto"))
                        shell23("cmd uimode night $before")
                }
            }
        }
}

private fun shell23(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd)
        .use { it.readBytes().decodeToString() }
}
