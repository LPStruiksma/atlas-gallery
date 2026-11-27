package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import kotlin.math.pow

/**
 * BRAND-L4 (#657): real MainActivity on S24+ (1080 × 2340) over 120 synthetic
 * colour tiles spread across four months. One journey per theme captures the
 * brief's states: Pictures idle and fast-scrolling (month sphere), the slab
 * on each place (Pictures, Collections → Albums and People, search), a
 * selection of 3 (held spheres), and the viewer filmstrip (ring + sphere,
 * lapis action slab). The reduced-motion class repeats Pictures and the
 * fast scroll with the system "remove animations" setting on.
 * No personal media, network or People data.
 */
@RunWith(AndroidJUnit4::class)
class LapisSlabLightJourneyTest : LapisSlabJourney(dark = false)

@RunWith(AndroidJUnit4::class)
class LapisSlabDarkJourneyTest : LapisSlabJourney(dark = true)

@RunWith(AndroidJUnit4::class)
class LapisSlabReducedMotionJourneyTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = lapisFixtures()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(SlabNightModeRule(dark = false)).around(RemoveAnimationsRule())
        .around(fixtures).around(compose)

    @Test fun fastScrollSphereAppearsAndFollowsWithoutAnimation() {
        compose.waitForNode(hasTestTag("atlas-nav-pill"))
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        compose.waitForIdle()
        saveUiEvidence("l4-657-pictures-reduced-motion.png")
        val month = holdFastScroll(compose, 0.45f)
        assertTrue("month sphere shows a short month: $month", month.length in 3..5)
        saveUiEvidence("l4-657-fastscroll-reduced-motion.png")
        releaseFastScroll(compose)
    }
}

abstract class LapisSlabJourney(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = lapisFixtures()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(SlabNightModeRule(dark)).around(fixtures).around(compose)

    private val mode = if (dark) "dark" else "light"

    @Test fun slabPlacesHeldSphereStatesAndViewerSlab() {
        // Pictures, idle.
        compose.waitForNode(hasTestTag("atlas-nav-pill"))
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        compose.onNodeWithTag("atlas-nav-photos").assertIsSelected()
        compose.onNodeWithTag("atlas-nav-albums").assertIsNotSelected()
        compose.onNodeWithTag("atlas-nav-search").assertIsNotSelected()
        // Two places and the sphere: no four-tab bar, no People place.
        compose.onNodeWithTag("atlas-nav-people").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("l4-657-pictures-$mode.png")

        // Fast scroll: the 46 dp month sphere at the thumb.
        val month = holdFastScroll(compose, 0.55f)
        assertTrue("month sphere shows a short month: $month", month.length in 3..5)
        saveUiEvidence("l4-657-fastscroll-$mode.png")
        releaseFastScroll(compose)

        // Selection of 3: each pick is a lapis sphere with a white halo.
        val tileMatcher = SemanticsMatcher("timeline tile") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atlas-timeline-media-") == true
        }
        val tiles = compose.onAllNodes(tileMatcher).fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left })).take(3)
            .map { it.config[SemanticsProperties.TestTag] }
        assertEquals(3, tiles.size)
        compose.onNodeWithTag(tiles[0]).performTouchInput { longClick() }
        compose.waitForNode(hasTestTag("atlas-selection-count"))
        compose.onNodeWithTag(tiles[1]).performClick()
        compose.onNodeWithTag(tiles[2]).performClick()
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("3 selected"))
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("atlas-selection-sphere", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 3
        }
        compose.waitForIdle()
        saveUiEvidence("l4-657-selection-3-$mode.png")
        systemBack()
        compose.waitForNode(hasTestTag("atlas-nav-pill"))

        // Viewer: filmstrip ring + sphere on the current item, lapis action slab.
        compose.onNodeWithTag(tiles[1]).performClick()
        compose.waitForNode(hasTestTag("atlas-filmstrip-sphere"), useUnmergedTree = true)
        compose.waitForNode(hasTestTag("atlas-filmstrip-ring"), useUnmergedTree = true)
        assertEquals(1, compose.onAllNodesWithTag("atlas-filmstrip-sphere", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        compose.waitForIdle()
        saveUiEvidence("l4-657-viewer-$mode.png")
        systemBack()
        compose.waitForNode(hasTestTag("atlas-nav-pill"))

        // Collections opens on Albums; People and Stories are its sections.
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        compose.waitForNode(hasTestTag("atlas-album-grid"))
        compose.onNodeWithTag("atlas-nav-albums").assertIsSelected()
        compose.onNodeWithTag("atlas-collections-albums").assertIsSelected()
        compose.onNodeWithTag("atlas-collections-people").assertIsNotSelected()
        compose.onNodeWithTag("atlas-collections-stories").assertIsNotSelected()
        compose.waitForIdle()
        saveUiEvidence("l4-657-collections-albums-$mode.png")

        compose.onNodeWithTag("atlas-collections-people").performClick()
        compose.waitForNode(hasTestTag("atlas-people-home"))
        // People keeps Collections current in the slab.
        compose.onNodeWithTag("atlas-nav-albums").assertIsSelected()
        compose.onNodeWithTag("atlas-collections-people").assertIsSelected()
        compose.waitForIdle()
        saveUiEvidence("l4-657-collections-people-$mode.png")
        // System Back from People behaves as before: back to Pictures.
        systemBack()
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        compose.onNodeWithTag("atlas-nav-photos").assertIsSelected()

        // The search sphere opens the existing search.
        compose.onNodeWithTag("atlas-nav-search").performClick()
        compose.waitForNode(hasTestTag("atlas-search-field"))
        compose.onNodeWithTag("atlas-nav-search").assertIsSelected()
        compose.onNodeWithTag("atlas-nav-photos").assertIsNotSelected()
        compose.onNodeWithTag("atlas-nav-albums").assertIsNotSelected()
        compose.waitForIdle()
        saveUiEvidence("l4-657-search-$mode.png")
    }

    /** Slab text meets WCAG 4.5:1: white on the lapis fill, lapis on the white pill. */
    @Test fun slabTextMeetsContrastInBothThemes() {
        val fill = if (dark) AtlasTokens.Colors.Dark.BrandFill else AtlasTokens.Colors.Light.BrandFill
        val rgb = floatArrayOf(fill.red * 255f, fill.green * 255f, fill.blue * 255f)
        val white = floatArrayOf(255f, 255f, 255f)
        val ratio = contrast(rgb, white)
        assertTrue("$mode slab text contrast $ratio", ratio >= 4.5)
    }

    private fun systemBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun contrast(a: FloatArray, b: FloatArray): Double {
        fun luminance(rgb: FloatArray): Double {
            val linear = rgb.map { value ->
                val c = (value / 255f).toDouble()
                if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
            }
            return .2126 * linear[0] + .7152 * linear[1] + .0722 * linear[2]
        }
        val lhs = luminance(a)
        val rhs = luminance(b)
        return (maxOf(lhs, rhs) + .05) / (minOf(lhs, rhs) + .05)
    }
}

/** 120 synthetic tiles, one per day, in four folders: four months of Pictures. */
private fun lapisFixtures() = SyntheticHeaderFixtures(
    sizes = List(120) { 120 to 90 },
    folderOf = { step -> "ATLAS-Lapis-657-${step % 4}" }
)

/**
 * Scrolls the grid so the fast scroller shows, then holds its thumb at
 * [fraction] of the track. Returns the month shown in the held sphere. The
 * finger stays down; [releaseFastScroll] lifts it.
 */
private fun holdFastScroll(
    compose: androidx.compose.ui.test.junit4.ComposeTestRule,
    fraction: Float
): String {
    compose.onNodeWithTag("atlas-timeline-grid").performTouchInput {
        swipeUp(startY = height * .7f, endY = height * .5f, durationMillis = 300L)
    }
    compose.waitForNode(hasTestTag("atlas-fast-scroll-sphere"), useUnmergedTree = true)
    compose.onNodeWithTag("atlas-timeline-date-scrub").performTouchInput {
        down(Offset(centerX, height * .05f))
        moveTo(Offset(centerX, height * (fraction / 2f)))
        moveTo(Offset(centerX, height * fraction))
    }
    compose.waitForNode(hasTestTag("atlas-fast-scroll-sphere-label"), useUnmergedTree = true)
    compose.waitForIdle()
    return compose.onNodeWithTag("atlas-fast-scroll-sphere-label", useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.Text]
        .joinToString(transform = AnnotatedString::text)
}

private fun releaseFastScroll(compose: androidx.compose.ui.test.junit4.ComposeTestRule) {
    compose.onNodeWithTag("atlas-timeline-date-scrub").performTouchInput { up() }
    compose.waitForIdle()
}

/** Test-only system setting; restored even after assertion or emulator failure. */
private class SlabNightModeRule(private val dark: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = shell("cmd uimode night").substringAfter(":").trim()
                shell("cmd uimode night " + if (dark) "yes" else "no")
                try {
                    base.evaluate()
                } finally {
                    if (before in setOf("yes", "no", "auto")) shell("cmd uimode night $before")
                }
            }
        }
}

/** System "remove animations" (animator duration scale 0) for one test; restored after. */
private class RemoveAnimationsRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = shell("settings get global animator_duration_scale").trim()
                shell("settings put global animator_duration_scale 0")
                try {
                    base.evaluate()
                } finally {
                    val restore = before.toFloatOrNull()?.toString() ?: "1.0"
                    shell("settings put global animator_duration_scale $restore")
                }
            }
        }
}

private fun shell(command: String): String {
    val parcel = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(parcel)
        .use { it.readBytes().decodeToString() }
}
