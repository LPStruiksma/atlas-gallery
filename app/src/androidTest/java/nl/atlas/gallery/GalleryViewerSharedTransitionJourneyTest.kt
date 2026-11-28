package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.testkit.awaitThat
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
    private val fixtures = SyntheticHeaderFixtures()
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
        val a3Before = actionSamples(3)
        val a7Before = actionSamples(7)
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
            // A3 must publish while the viewer is still mounted, not on Back.
            compose.mainClock.autoAdvance = true
            compose.awaitThat("shared entry publishes A3 before Back") { actionSamples(3) > a3Before }
            compose.mainClock.autoAdvance = false
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
        compose.awaitThat("shared exit publishes A7") { actionSamples(7) > a7Before }

        // Reopen, move to a different settled photo, and Back. The original
        // thumbnail must not be reused for a different MediaStore URI.
        val reopenA3Before = actionSamples(3)
        compose.onNodeWithTag(tileTag, useUnmergedTree = true).performClick()
        compose.waitForNode(hasTestTag("atlas-photo-counter"))
        compose.awaitThat("reopened focused preview is a Coil memory cache hit") {
            compose.onAllNodes(SemanticsMatcher("memory-cached focused preview") {
                it.config.getOrNull(ViewerPhotoPreviewMemoryHitKey) == true
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.awaitThat("cached reopen publishes a fresh A3 before Back") {
            actionSamples(3) > reopenA3Before
        }
        val a4Before = actionSamples(4)
        val start = counter().substringBefore(" of ").toInt() - 1
        val target = if (start == 0) 1 else start - 1
        compose.onNodeWithTag("atlas-thumbnail-$target").performClick()
        compose.awaitThat("filmstrip target settled") { counter().startsWith("${target + 1} of ") }
        compose.awaitThat("filmstrip selection publishes A4 before Back") { actionSamples(4) > a4Before }
        compose.onNodeWithTag("atlas-back").performClick()
        compose.onNodeWithTag("atlas-shared-photo-exit").assertDoesNotExist()
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-viewer")).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        saveUiEvidence("shared-23-far-scrub-$mode.png")
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

    private fun actionSamples(action: Int): Int {
        var count = -1
        compose.runOnIdle {
            count = PerformanceSession.aggregate.snapshot("action_a${action}_ms").count
        }
        return count
    }
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
