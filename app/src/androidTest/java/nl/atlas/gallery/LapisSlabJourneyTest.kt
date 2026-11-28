package nl.atlas.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasTokens
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.KeyboardAfter
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.assertAboveKeyboard
import nl.atlas.gallery.testkit.awaitScreenStill
import nl.atlas.gallery.testkit.assertFullyVisible
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.typeText
import nl.atlas.gallery.testkit.withAnimatorScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.pow

/**
 * BRAND-L4 (#657): real MainActivity on S24+ (1080 × 2340) over 120 synthetic
 * colour tiles spread across four months. One journey per theme captures the
 * brief's states: Pictures idle, a fast-scroll sequence (the month sphere
 * across four held positions), the slab on each place (Pictures,
 * Collections → Albums, Stories and People, search with the keyboard up), a
 * selection of 3 (held spheres), and the viewer filmstrip (ring + sphere,
 * lapis action slab, a tap on the neighbouring item). Every tap is the
 * kit's pointer tap with its outcome. The reduced-motion class repeats
 * Pictures and the fast scroll with the system "remove animations" setting on.
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
        .around(DisplayPrefsRule(dark = false)).around(fixtures).around(compose)

    @Test fun fastScrollSphereAppearsAndFollowsWithoutAnimation() = withAnimatorScale("0") {
        compose.waitForNode(hasTestTag("atlas-nav-pill"))
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        compose.awaitScreenStill("l4-657-pictures-reduced-motion")
        saveUiEvidence("l4-657-pictures-reduced-motion.png")
        val month = holdFastScroll(compose, 0.45f)
        assertTrue("month sphere shows a short month: $month", month.length in 3..5)
        compose.awaitScreenStill("l4-657-fastscroll-reduced-motion")
        saveUiEvidence("l4-657-fastscroll-reduced-motion.png")
        releaseFastScroll(compose)
    }
}

/** The slab and Collections sections in the real app at 200 % font, dark. */
@RunWith(AndroidJUnit4::class)
class LapisSlabLargeFontJourneyTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = lapisFixtures()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = true, fontScale = 2f)).around(fixtures).around(compose)

    @Test fun slabAndSectionsStayWholeAt200PercentFont() {
        compose.waitForNode(hasTestTag("atlas-nav-pill"))
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        val slab = compose.onNodeWithTag("atlas-nav-pill").fetchSemanticsNode().boundsInRoot
        for (name in listOf("photos", "albums", "search")) {
            val box = compose.onNodeWithTag("atlas-nav-$name").fetchSemanticsNode().boundsInRoot
            assertTrue("$name inside the slab at 200 %: $box in $slab",
                box.left >= slab.left - 1f && box.right <= slab.right + 1f &&
                    box.top >= slab.top - 1f && box.bottom <= slab.bottom + 1f)
        }
        compose.awaitScreenStill("l4-657-pictures-font200-dark")
        saveUiEvidence("l4-657-pictures-font200-dark.png")
        compose.tap(hasTestTag("atlas-nav-albums")) {
            compose.exists(hasTestTag("atlas-collections-people"))
        }
        compose.tap(hasTestTag("atlas-collections-people")) {
            compose.exists(hasTestTag("atlas-people-home"))
        }
        compose.awaitScreenStill("l4-657-collections-people-font200-dark")
        saveUiEvidence("l4-657-collections-people-font200-dark.png")
    }
}

abstract class LapisSlabJourney(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = lapisFixtures()

    // The fixed test keyboard (outer rule, chosen before the Activity starts)
    // so search with the keyboard up is a stable, known height.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.Fixed))
        .around(S24DisplayRule()).around(DisplayPrefsRule(dark)).around(fixtures).around(compose)

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
        assertSlabGeometry()
        compose.awaitScreenStill("l4-657-pictures-$mode")
        saveUiEvidence("l4-657-pictures-$mode.png")

        // Fast scroll: one finger held on the thumb, moved down the track in
        // four steps. Each frame is captured; the 46 dp sphere names the month.
        val months = fastScrollSequence(compose, listOf(.1f, .4f, .65f, .92f)) { step, month ->
            assertTrue("month sphere shows a short month: $month", month.length in 3..5)
            compose.awaitScreenStill("l4-657-fastscroll-$step-$mode")
            saveUiEvidence("l4-657-fastscroll-$step-$mode.png")
        }
        assertTrue("the sphere's month changes along the track: $months", months.toSet().size >= 2)
        // The short recording (Strategy decision on #657): frames taken during
        // one continuous drag at real timing, as an APNG plus a timestamped sheet.
        recordFastScroll(compose, "l4-657-fastscroll-recording-$mode")

        // While the fast scroller shows, a tap on the right-hand column (left
        // of the 28 dp track, away from the thumb) still opens the photo.
        tapRightColumnTileWhileScrollerShows()

        // Selection of 3: each pick is a lapis sphere with a white halo.
        val tileMatcher = SemanticsMatcher("timeline tile") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atlas-timeline-media-") == true
        }
        val tiles = compose.onAllNodes(tileMatcher).fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left })).take(3)
            .map { it.config[SemanticsProperties.TestTag] }
        assertEquals(3, tiles.size)
        // Long press has no kit helper; its outcome is awaited like a tap's.
        compose.onNodeWithTag(tiles[0]).performTouchInput { longClick() }
        compose.waitForNode(hasTestTag("atlas-selection-count"))
        compose.tap(hasTestTag(tiles[1])) {
            compose.exists(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        }
        compose.tap(hasTestTag(tiles[2])) {
            compose.exists(hasTestTag("atlas-selection-count") and hasText("3 selected"))
        }
        // Each picked tile carries its own sphere (a fully picked day's header
        // check shows one too, so count spheres inside the three tiles).
        compose.awaitThat("a held sphere on each of the 3 picked tiles") {
            val spheres = compose.onAllNodesWithTag("atlas-selection-sphere", useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInRoot }
            tiles.all { tag ->
                val tile = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                spheres.any { tile.contains(it.center) }
            }
        }
        compose.awaitScreenStill("l4-657-selection-3-$mode")
        saveUiEvidence("l4-657-selection-3-$mode.png")
        systemBack()
        compose.waitForNode(hasTestTag("atlas-nav-pill"))

        // Viewer: filmstrip ring + sphere on the current item, lapis action slab.
        compose.tap(hasTestTag(tiles[1])) {
            compose.exists(hasTestTag("atlas-filmstrip-sphere"), useUnmergedTree = true)
        }
        compose.waitForNode(hasTestTag("atlas-filmstrip-ring"), useUnmergedTree = true)
        assertEquals(1, compose.onAllNodesWithTag("atlas-filmstrip-sphere", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        compose.awaitScreenStill("l4-657-viewer-$mode")
        saveUiEvidence("l4-657-viewer-$mode.png")
        tapNeighbouringFilmstripItem()
        systemBack()
        compose.waitForNode(hasTestTag("atlas-nav-pill"))

        // Collections opens on Albums; People and Stories are its sections.
        compose.tap(hasTestTag("atlas-nav-albums")) {
            compose.exists(hasTestTag("atlas-album-grid"))
        }
        compose.onNodeWithTag("atlas-nav-albums").assertIsSelected()
        compose.onNodeWithTag("atlas-collections-albums").assertIsSelected()
        compose.onNodeWithTag("atlas-collections-people").assertIsNotSelected()
        compose.onNodeWithTag("atlas-collections-stories").assertIsNotSelected()
        compose.awaitScreenStill("l4-657-collections-albums-$mode")
        saveUiEvidence("l4-657-collections-albums-$mode.png")

        // Stories opens from its section; system Back returns to Albums.
        compose.tap(hasTestTag("atlas-collections-stories")) {
            listOf("atlas-stories-grid", "atlas-stories-empty", "atlas-stories-planning")
                .any { compose.exists(hasTestTag(it)) }
        }
        compose.awaitScreenStill("l4-657-collections-stories-$mode")
        saveUiEvidence("l4-657-collections-stories-$mode.png")
        systemBack()
        compose.waitForNode(hasTestTag("atlas-album-grid"))
        compose.onNodeWithTag("atlas-collections-albums").assertIsSelected()
        compose.onNodeWithTag("atlas-nav-albums").assertIsSelected()

        compose.tap(hasTestTag("atlas-collections-people")) {
            compose.exists(hasTestTag("atlas-people-home"))
        }
        // People keeps Collections current in the slab.
        compose.onNodeWithTag("atlas-nav-albums").assertIsSelected()
        compose.onNodeWithTag("atlas-collections-people").assertIsSelected()
        compose.awaitScreenStill("l4-657-collections-people-$mode")
        saveUiEvidence("l4-657-collections-people-$mode.png")
        // System Back from People behaves as before: back to Pictures.
        systemBack()
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        compose.onNodeWithTag("atlas-nav-photos").assertIsSelected()

        // The search sphere opens the existing search.
        compose.tap(hasTestTag("atlas-nav-search")) {
            compose.exists(hasTestTag("atlas-search-field"))
        }
        compose.onNodeWithTag("atlas-nav-search").assertIsSelected()
        compose.onNodeWithTag("atlas-nav-photos").assertIsNotSelected()
        compose.onNodeWithTag("atlas-nav-albums").assertIsNotSelected()
        compose.awaitScreenStill("l4-657-search-$mode")
        saveUiEvidence("l4-657-search-$mode.png")
        // With the keyboard up, the field stays whole above it.
        compose.typeText(hasTestTag("atlas-search-field"), "a", KeyboardAfter.Shown)
        compose.assertAboveKeyboard(hasTestTag("atlas-search-field"))
        compose.awaitScreenStill("l4-657-search-keyboard-$mode")
        saveUiEvidence("l4-657-search-keyboard-$mode.png")
    }

    /**
     * "Gestures inside screens behave as today": the full-height scroll track
     * stays 28 dp wide; only the thumb widens to the sphere's 48 dp. A slow
     * drag (no fling) shows the scroller; with the clock held, it is still
     * shown (it hides 1.5 s after scrolling stops) when the tap lands in the
     * 20 dp band between the old track and the sphere's width, on a
     * right-column tile clear of the thumb. The viewer must open.
     */
    private fun tapRightColumnTileWhileScrollerShows() {
        val tileMatcher = SemanticsMatcher("timeline tile") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atlas-timeline-media-") == true
        }
        val width = compose.activity.window.decorView.width.toFloat()
        val (bandLeft, bandRight) = with(compose.density) {
            width - 48.dp.toPx() to width - 28.dp.toPx()
        }
        val autoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("atlas-timeline-grid").performTouchInput {
                swipeUp(startY = height * .6f, endY = height * .5f, durationMillis = 1_500L)
            }
            compose.mainClock.advanceTimeBy(100L)
            val thumb = compose.onNodeWithTag("atlas-fast-scroll-thumb", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val header = compose.onAllNodes(hasTestTag("atlas-scrolling-header"))
                .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.bottom ?: 0f
            val slab = compose.onNodeWithTag("atlas-nav-pill").fetchSemanticsNode().boundsInRoot.top
            val tile = compose.onAllNodes(tileMatcher).fetchSemanticsNodes().firstOrNull {
                val b = it.boundsInRoot
                b.left < bandLeft && b.right > bandRight && b.top > header && b.bottom < slab &&
                    (b.bottom < thumb.top || b.top > thumb.bottom)
            }
            assertTrue("a right-column tile clear of the thumb", tile != null)
            val b = tile!!.boundsInRoot
            compose.onNodeWithTag(tile.config[SemanticsProperties.TestTag]).performTouchInput {
                click(Offset((bandLeft + bandRight) / 2f - b.left, b.height / 2f))
            }
        } finally {
            compose.mainClock.autoAdvance = autoAdvance
        }
        compose.awaitThat("the right-column tap to open the viewer") {
            compose.exists(hasTestTag("atlas-filmstrip"))
        }
        systemBack()
        compose.waitForNode(hasTestTag("atlas-nav-pill"))
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
    }

    /**
     * The slab's three targets: each at least 48 dp both ways, none
     * overlapping, all inside the slab, and the slab centred on the screen.
     */
    private fun assertSlabGeometry() {
        val minPx = with(compose.density) { 48.dp.toPx() } - 1f
        val slab = compose.onNodeWithTag("atlas-nav-pill").fetchSemanticsNode().boundsInRoot
        val boxes = listOf("photos", "albums", "search").map { name ->
            name to compose.onNodeWithTag("atlas-nav-$name").fetchSemanticsNode().boundsInRoot
        }
        for ((name, box) in boxes) {
            assertTrue("$name is at least 48 dp: $box", box.width >= minPx && box.height >= minPx)
            assertTrue("$name inside the slab: $box in $slab",
                box.left >= slab.left - 1f && box.right <= slab.right + 1f &&
                    box.top >= slab.top - 1f && box.bottom <= slab.bottom + 1f)
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val (a, ra) = boxes[i]
            val (b, rb) = boxes[j]
            val overlap = ra.intersect(rb)
            assertTrue("$a and $b overlap: $ra, $rb", overlap.width <= 1f || overlap.height <= 1f)
        }
        val width = compose.activity.window.decorView.width
        assertTrue("slab centred: $slab on a $width px screen",
            abs(slab.center.x - width / 2f) <= 2f)
    }

    /**
     * Filmstrip items are touching, non-overlapping 48 dp targets; a pointer
     * tap on the item beside the current one makes it current.
     */
    private fun tapNeighbouringFilmstripItem() {
        val items = compose.onAllNodes(SemanticsMatcher("filmstrip item") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atlas-thumbnail-") == true
        }).fetchSemanticsNodes().sortedBy { it.boundsInRoot.left }
        val current = items.indexOfFirst { it.config.getOrNull(SemanticsProperties.Selected) == true }
        assertTrue("one filmstrip item is current", current >= 0)
        val neighbour = if (current + 1 < items.size) current + 1 else current - 1
        assertTrue("the filmstrip shows a neighbour", neighbour in items.indices)
        val minPx = with(compose.density) { 48.dp.toPx() } - 1f
        val here = items[current].boundsInRoot
        val there = items[neighbour].boundsInRoot
        assertTrue("filmstrip targets are at least 48 dp: $here, $there",
            here.width >= minPx && there.width >= minPx && here.height >= minPx)
        val gap = if (neighbour > current) there.left - here.right else here.left - there.right
        assertTrue("neighbouring filmstrip targets touch without overlap: gap $gap px", abs(gap) <= 1f)
        val tag = items[neighbour].config[SemanticsProperties.TestTag]
        // A real pointer DOWN/UP at the neighbour's centre. The kit's tap()
        // refuses a node that moves while the pointer is down, and this one
        // does by design: on UP it becomes current and the strip recentres on
        // it (one 48 dp stride, instantly with animations off). So check it is
        // whole on screen first, then require the outcome.
        compose.onNodeWithTag(tag).assertFullyVisible()
        compose.onNodeWithTag(tag).performTouchInput { click(center) }
        compose.awaitThat("the tapped neighbour to become the current item") {
            compose.exists(hasTestTag(tag) and isSelected())
        }
        compose.waitForNode(hasTestTag("atlas-filmstrip-sphere"), useUnmergedTree = true)
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
    folderOf = { step -> "ATLAS-Lapis-657-${step % 4}" },
    // The date is also in the name (IMG_yyyyMMdd_HHmmss), so the months hold
    // even if the emulator's scanner drops DATE_TAKEN on these PNGs.
    nameOf = { step, taken ->
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date(taken))
        "IMG_${stamp}_L657_$step.png"
    }
)

/**
 * Scrolls the grid so the fast scroller shows, then puts one finger on its
 * thumb and moves it to each of [fractions] of the track in turn, calling
 * [atEach] with the step (1-based) and the month in the held sphere once the
 * move has landed. The finger lifts at the end. Returns the months seen.
 */
private fun fastScrollSequence(
    compose: androidx.compose.ui.test.junit4.ComposeTestRule,
    fractions: List<Float>,
    atEach: (step: Int, month: String) -> Unit
): List<String> {
    startFastScroll(compose)
    val months = fractions.mapIndexed { i, fraction ->
        compose.onNodeWithTag("atlas-timeline-date-scrub").performTouchInput {
            moveTo(Offset(centerX, height * fraction))
        }
        val month = heldMonth(compose)
        atEach(i + 1, month)
        month
    }
    releaseFastScroll(compose)
    return months
}

/**
 * The fast-scroll recording: one finger goes down on the thumb and drags it
 * down the track in [steps] moves without lifting; a screenshot is taken
 * after each move lands, stamped with the real clock (no still-screen wait,
 * so the frames show the motion as it happens). Writes `[name].png` (APNG)
 * and `[name]-sheet.png` (contact sheet with each frame's ms).
 */
private fun recordFastScroll(
    compose: androidx.compose.ui.test.junit4.ComposeTestRule,
    name: String,
    steps: Int = 16
) {
    val recorder = FastScrollRecorder()
    startFastScroll(compose)
    compose.waitForIdle()
    recorder.capture()
    var firstMonth = ""
    for (i in 1..steps) {
        val fraction = .05f + .9f * i / steps
        compose.onNodeWithTag("atlas-timeline-date-scrub").performTouchInput {
            moveTo(Offset(centerX, height * fraction))
        }
        compose.waitForIdle()
        recorder.capture()
        if (i == 1) firstMonth = heldMonth(compose)
    }
    val lastMonth = heldMonth(compose)
    releaseFastScroll(compose)
    recorder.capture()
    val times = recorder.timestamps
    assertEquals(steps + 2, times.size)
    assertTrue("recording frames are in real time order: $times",
        times.zipWithNext().all { (a, b) -> b > a })
    assertTrue("the held sphere's month changes during the recording: $firstMonth → $lastMonth",
        firstMonth != lastMonth)
    recorder.save(name)
}

/**
 * Holds the thumb at [fraction] of the track and returns the month in the
 * held sphere. The finger stays down; [releaseFastScroll] lifts it.
 */
private fun holdFastScroll(
    compose: androidx.compose.ui.test.junit4.ComposeTestRule,
    fraction: Float
): String {
    startFastScroll(compose)
    compose.onNodeWithTag("atlas-timeline-date-scrub").performTouchInput {
        moveTo(Offset(centerX, height * (fraction / 2f)))
        moveTo(Offset(centerX, height * fraction))
    }
    return heldMonth(compose)
}

private fun startFastScroll(compose: androidx.compose.ui.test.junit4.ComposeTestRule) {
    compose.onNodeWithTag("atlas-timeline-grid").performTouchInput {
        swipeUp(startY = height * .7f, endY = height * .5f, durationMillis = 300L)
    }
    compose.waitForNode(hasTestTag("atlas-fast-scroll-sphere"), useUnmergedTree = true)
    compose.onNodeWithTag("atlas-timeline-date-scrub").performTouchInput {
        down(Offset(centerX, height * .05f))
    }
}

private fun heldMonth(compose: androidx.compose.ui.test.junit4.ComposeTestRule): String {
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
