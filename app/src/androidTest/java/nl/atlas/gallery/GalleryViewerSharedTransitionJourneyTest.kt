package nl.atlas.gallery

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import nl.atlas.gallery.featuregrid.ORIGINAL_PROPORTIONS_ACTION
import nl.atlas.gallery.testkit.assertFullyVisible
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.tap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * Invented MediaStore PNGs; capture both themes at 1080x2340.
 */
@RunWith(AndroidJUnit4::class)
class SharedPhotoLightJourneyTest : SharedPhotoJourneyTest(dark = false)

@RunWith(AndroidJUnit4::class)
class SharedPhotoDarkJourneyTest : SharedPhotoJourneyTest(dark = true)

abstract class SharedPhotoJourneyTest(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val mixedSizes = listOf(
        240 to 160, 160 to 240, 180 to 180, 320 to 100, 100 to 320, 240 to 180)
    // Only the offscreen case needs a large dated catalogue. Restore the two
    // sort preferences after its disposable activity and fixtures are gone.
    private val fixtures = TestRule { base, description ->
        if (description.methodName == "viewerOpenTimingFromSyntheticGridTile") {
            SyntheticHeaderFixtures(sizes = listOf(4032 to 3024),
                folderOf = { "ATLAS-Flow3-Timing" }).apply(base, description)
        } else if (description.methodName == "nonGridStoryReturnFadesWithoutTile") {
            val today = java.time.LocalDate.now()
            val zone = java.time.ZoneId.systemDefault()
            SyntheticHeaderFixtures(
                sizes = mixedSizes,
                folderOf = { "ATLAS-Flow3-Story" },
                takenAtOf = { step -> today.minusYears(step.toLong()).atTime(12, 0)
                    .atZone(zone).toInstant().toEpochMilli() }
            ).apply(base, description)
        } else if (description.methodName != "offscreenSettledPhotoReturnsToItsOwnTile") {
            SyntheticHeaderFixtures(sizes = mixedSizes).apply(base, description)
        } else {
            object : Statement() {
                override fun evaluate() {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val prefs = context.getSharedPreferences("atlas_media_sort", Context.MODE_PRIVATE)
                    val key = prefs.getString("pictures.key", null)
                    val direction = prefs.getString("pictures.direction", null)
                    try {
                        check(prefs.edit().putString("pictures.key", "DATE_CREATED")
                            .putString("pictures.direction", "DESCENDING").commit())
                        SyntheticHeaderFixtures(
                            sizes = List(OFFSCREEN_COUNT) { mixedSizes[it % mixedSizes.size] },
                            folderOf = { OFFSCREEN_FOLDER }
                        ).apply(base, description).evaluate()
                    } finally {
                        check(prefs.edit().putString("pictures.key", key)
                            .putString("pictures.direction", direction).commit())
                    }
                }
            }
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
        compose.mainClock.advanceTimeBy(600L)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("atlas-back").performClick()
            captureReturnMidpoint("flow3-paged-back-mid-$mode.png")
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitClosed()
        saveUiEvidence("shared-23-far-scrub-$mode.png")
    }

    private fun captureReturnMidpoint(shot: String) {
        // Measurement is asynchronous; don't sample at a fixed time after Back.
        repeat(100) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (compose.onAllNodes(hasTestTag("atlas-shared-photo-exit"))
                    .fetchSemanticsNodes().isNotEmpty()) {
                compose.mainClock.advanceTimeBy(64L)
                compose.waitForIdle()
                compose.onNodeWithTag("atlas-shared-photo-exit").assertExists()
                saveUiEvidence(shot)
                compose.mainClock.advanceTimeBy(320L)
                return
            }
        }
        throw AssertionError("Settled synthetic photo did not start a measured return")
    }

    private fun awaitClosed() {
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-viewer")).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    @Test fun downwardGestureReversesThenCommitsToMeasuredTile() {
        val tileTag = firstTile()
        compose.onNodeWithTag(tileTag, useUnmergedTree = true).performClick()
        compose.waitForNode(hasTestTag("atlas-photo-counter"))
        val decoded = SemanticsMatcher("focused decoded photo") {
            it.config.getOrNull(ViewerPhotoDecodedKey) == true
        }
        compose.waitUntil(30_000L) {
            compose.onAllNodes(decoded).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        val photo = compose.onNodeWithTag("atlas-viewer-drag-photo")
        val resting = photo.fetchSemanticsNode().boundsInRoot
        val startCounter = counter()
        val pager = compose.onNodeWithTag("atlas-main-pager")
        compose.mainClock.autoAdvance = false
        try {
            pager.performTouchInput {
                down(center.copy(y = height * .25f))
                repeat(6) { moveBy(Offset(0f, height * .025f)) }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val dragged = photo.fetchSemanticsNode().boundsInRoot
            assertTrue("Held drag must move and shrink the photo",
                dragged.top > resting.top && dragged.width < resting.width)
            saveUiEvidence("flow3-drag-held-$mode.png")
            pager.performTouchInput {
                repeat(6) { moveBy(Offset(0f, -height * .025f)) }
                up()
            }
            compose.mainClock.advanceTimeBy(600L)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertTrue("Reversed drag must keep the settled photo", counter() == startCounter)
        assertTrue("Reversal must restore fitted width",
            kotlin.math.abs(photo.fetchSemanticsNode().boundsInRoot.width - resting.width) < 2f)
        saveUiEvidence("flow3-drag-reversed-$mode.png")

        compose.mainClock.autoAdvance = false
        try {
            pager.performTouchInput {
                down(center.copy(y = height * .25f))
                repeat(10) { moveBy(Offset(0f, height * .035f)) }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            saveUiEvidence("flow3-drag-commit-held-$mode.png")
            pager.performTouchInput { up() }
            captureReturnMidpoint("flow3-drag-commit-mid-$mode.png")
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitClosed()
        compose.onNodeWithTag(tileTag, useUnmergedTree = true).assertExists()
        saveUiEvidence("flow3-drag-returned-$mode.png")
    }

    @Test fun belowFitPinchReversesThenCommitsToMeasuredTile() {
        val tileTag = firstTile()
        compose.onNodeWithTag(tileTag, useUnmergedTree = true).performClick()
        compose.waitForNode(hasTestTag("atlas-photo-counter"))
        val decoded = SemanticsMatcher("focused decoded photo") {
            it.config.getOrNull(ViewerPhotoDecodedKey) == true
        }
        compose.waitUntil(30_000L) {
            compose.onAllNodes(decoded).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        val startCounter = counter()
        val pager = compose.onNodeWithTag("atlas-main-pager")

        fun moveSpan(from: Float, to: Float) {
            pager.performTouchInput {
                for (i in 1..8) {
                    val span = width * (from + (to - from) * i / 8f)
                    moveTo(0, center - Offset(span, 0f))
                    moveTo(1, center + Offset(span, 0f))
                }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

        fun beginPinch() {
            pager.performTouchInput {
                down(0, center - Offset(width * .25f, 0f))
                down(1, center + Offset(width * .25f, 0f))
            }
        }

        compose.mainClock.autoAdvance = false
        try {
            beginPinch()
            moveSpan(.25f, .17f)
            compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
            saveUiEvidence("flow3-pinch-held-$mode.png")
            moveSpan(.17f, .25f)
            pager.performTouchInput { up(0); up(1) }
            compose.mainClock.advanceTimeBy(600L)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertTrue("Reversed pinch must keep the settled photo", counter() == startCounter)
        compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
        saveUiEvidence("flow3-pinch-reversed-$mode.png")

        compose.mainClock.autoAdvance = false
        try {
            beginPinch()
            moveSpan(.25f, .13f)
            pager.performTouchInput { up(0); up(1) }
            captureReturnMidpoint("flow3-pinch-commit-mid-$mode.png")
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitClosed()
        compose.onNodeWithTag(tileTag, useUnmergedTree = true).assertExists()
        saveUiEvidence("flow3-pinch-returned-$mode.png")
    }

    // Resolve only the IDs of this journey's synthetic fixture rows. The
    // final grid assertion must name the same photo chosen in the filmstrip.
    private fun offscreenTileTag(step: Int): String {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val id = requireNotNull(resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("Pictures/$OFFSCREEN_FOLDER/", "IMG_HEADER_FIXTURE_$step.png"),
            null
        )).use { cursor ->
            check(cursor.moveToFirst()) { "Missing synthetic offscreen fixture $step" }
            val result = cursor.getLong(0)
            check(!cursor.moveToNext()) { "Duplicate synthetic offscreen fixture $step" }
            result
        }
        return "atlas-timeline-media-$id"
    }

    @Test fun offscreenSettledPhotoReturnsToItsOwnTile() {
        val openingTag = offscreenTileTag(1)
        val targetTag = offscreenTileTag(OFFSCREEN_COUNT)
        compose.waitForNode(hasTestTag(openingTag))
        compose.onNodeWithTag(openingTag, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(targetTag, useUnmergedTree = true).assertDoesNotExist()
        saveUiEvidence("flow3-offscreen-before-$mode.png")

        compose.onNodeWithTag(openingTag, useUnmergedTree = true).performClick()
        compose.waitForNode(hasTestTag("atlas-photo-counter"))
        assertTrue("Expected isolated dated fixture order", counter() == "1 of $OFFSCREEN_COUNT")
        compose.onNodeWithTag("atlas-filmstrip").performScrollToIndex(OFFSCREEN_COUNT - 1)
        compose.waitForNode(hasTestTag("atlas-thumbnail-${OFFSCREEN_COUNT - 1}"))
        compose.onNodeWithTag("atlas-thumbnail-${OFFSCREEN_COUNT - 1}").performClick()
        compose.waitUntil(10_000L) { counter() == "$OFFSCREEN_COUNT of $OFFSCREEN_COUNT" }
        compose.mainClock.advanceTimeBy(600L)

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("atlas-back").performClick()
            captureReturnMidpoint("flow3-offscreen-back-mid-$mode.png")
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitClosed()
        compose.onNodeWithTag(targetTag, useUnmergedTree = true).assertIsDisplayed()
        saveUiEvidence("flow3-offscreen-returned-$mode.png")
    }

    @Test fun quickBackDuringEntryDoesNotLeakIntoNextPhoto() {
        val firstTag = firstTile()
        val enter = hasTestTag("atlas-shared-photo-enter")
        val exit = hasTestTag("atlas-shared-photo-exit")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag(firstTag, useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeBy(80L)
            compose.waitForIdle()
            assertTrue("Quick Back requires an active entry overlay",
                compose.onAllNodes(enter).fetchSemanticsNodes().size == 1)
            saveUiEvidence("flow3-quick-entry-$mode.png")
            compose.onNodeWithTag("atlas-back").performClick()
            repeat(20) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val entering = compose.onAllNodes(enter).fetchSemanticsNodes().size
                val exiting = compose.onAllNodes(exit).fetchSemanticsNodes().size
                assertTrue("Entry and return overlays must not coexist", entering + exiting <= 1)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitClosed()
        compose.onNodeWithTag("atlas-shared-photo-enter").assertDoesNotExist()
        compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
        compose.onNodeWithTag(firstTag, useUnmergedTree = true).assertIsDisplayed()
        saveUiEvidence("flow3-quick-returned-$mode.png")

        val secondTag = compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }.first { it != firstTag }
        compose.onNodeWithTag(secondTag, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(secondTag, useUnmergedTree = true).performClick()
        compose.waitForNode(hasTestTag("atlas-photo-counter"))
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-shared-photo-enter").assertDoesNotExist()
        compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
        saveUiEvidence("flow3-quick-reopened-$mode.png")
        compose.onNodeWithTag("atlas-back").performClick()
        awaitClosed()
        compose.onNodeWithTag(secondTag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun originalRatioPhotoTravelsBackToSameTile() {
        firstTile()
        val grid = compose.onAllNodes(hasTestTag("atlas-timeline-grid") or hasTestTag("atlas-feature-grid"))
            .fetchSemanticsNodes()
        // Use the existing accessible layout action, not an unrelated pinch threshold.
        val action = grid.flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
            .first { it.label == ORIGINAL_PROPORTIONS_ACTION }
        compose.runOnIdle { check(action.action()) }
        compose.waitForNode(hasTestTag("atlas-ratio-gallery"))
        val ratioTile = SemanticsMatcher("non-square synthetic ratio photo") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            tag.startsWith("atlas-ratio-media-") && tag.endsWith("-photo") &&
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .any { it.startsWith("IMG_HEADER_FIXTURE_") } &&
                node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f &&
                kotlin.math.abs(node.boundsInRoot.width / node.boundsInRoot.height - 1f) > .15f
        }
        compose.waitForNode(ratioTile)
        compose.waitForIdle()
        val tileTag = compose.onAllNodes(ratioTile).fetchSemanticsNodes()
            .first().config[SemanticsProperties.TestTag]
        compose.onNodeWithTag(tileTag).assertIsDisplayed()
        saveUiEvidence("flow3-ratio-before-$mode.png")

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag(tileTag).performClick()
            compose.mainClock.advanceTimeBy(80L)
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-shared-photo-enter").assertExists()
            saveUiEvidence("flow3-ratio-enter-$mode.png")
            compose.mainClock.advanceTimeBy(320L)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForNode(hasTestTag("atlas-photo-counter"))
        compose.mainClock.advanceTimeBy(600L)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("atlas-back").performClick()
            captureReturnMidpoint("flow3-ratio-back-mid-$mode.png")
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitClosed()
        compose.onNodeWithTag("atlas-ratio-gallery").assertIsDisplayed()
        compose.onNodeWithTag(tileTag).assertIsDisplayed()
        saveUiEvidence("flow3-ratio-returned-$mode.png")
    }

    // Stories remains on the Albums tab (no mounted return grid). Exercise
    // the real host's no-target branch rather than making a fake tile vanish.
    @Test fun nonGridStoryReturnFadesWithoutTile() {
        firstTile()
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        compose.waitForNode(hasTestTag("atlas-albums-stories-entry"))
        compose.onNodeWithTag("atlas-albums-stories-entry").performClick()
        compose.waitForNode(hasTestTag("atlas-story-on_this_day"))
        compose.onNodeWithTag("atlas-story-on_this_day").performClick()
        compose.waitForNode(hasTestTag("atlas-story-open-gallery"))
        val pause = hasContentDescription("Pause slideshow")
        if (compose.onAllNodes(pause).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(pause).performClick()
        }
        val moment = SemanticsMatcher("current synthetic story moment") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .any { it.startsWith("Moment ") }
        }
        compose.waitForNode(moment)
        val before = compose.onNode(moment).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
        saveUiEvidence("flow3-story-before-$mode.png")
        compose.onNodeWithTag("atlas-story-open-gallery").performClick()
        compose.waitForNode(hasTestTag("atlas-photo-counter"))
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-shared-photo-enter").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("atlas-back").performClick()
            repeat(5) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
            }
            compose.onNodeWithTag("atlas-viewer").assertExists()
            saveUiEvidence("flow3-story-fade-mid-$mode.png")
            repeat(20) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitClosed()
        compose.waitForNode(hasTestTag("atlas-story-player"))
        compose.waitForNode(moment)
        assertTrue("No-target Back must restore the same story moment",
            compose.onNode(moment).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription] == before)
        saveUiEvidence("flow3-story-restored-$mode.png")
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
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("atlas-back").performClick()
            compose.mainClock.advanceTimeBy(48L)
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
            compose.onNodeWithTag("atlas-viewer").assertDoesNotExist()
            compose.mainClock.autoAdvance = true
            compose.waitForNode(hasTestTag(tileTag))
            compose.onNodeWithTag(tileTag, useUnmergedTree = true).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            if (before == "null" || before.isBlank())
                shell23("settings delete global animator_duration_scale")
            else shell23("settings put global animator_duration_scale $before")
        }
    }

    private companion object {
        const val OFFSCREEN_COUNT = 40
        const val OFFSCREEN_FOLDER = "ATLAS-Flow3-Offscreen"
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
