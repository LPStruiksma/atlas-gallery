package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
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
 * Six invented MediaStore PNGs; capture both themes at 1080x2340.
 */
@RunWith(AndroidJUnit4::class)
class SharedPhotoLightJourneyTest : SharedPhotoJourneyTest(dark = false)

@RunWith(AndroidJUnit4::class)
class SharedPhotoDarkJourneyTest : SharedPhotoJourneyTest(dark = true)

abstract class SharedPhotoJourneyTest(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = SyntheticHeaderFixtures(sizes = listOf(
        240 to 160, 160 to 240, 180 to 180, 320 to 100, 100 to 320, 240 to 180))
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
