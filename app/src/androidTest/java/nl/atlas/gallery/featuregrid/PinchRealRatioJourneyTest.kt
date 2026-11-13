package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.waitForNode
import android.content.Context
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.MainActivity
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.resolveMediaDate
import nl.atlas.gallery.SyntheticHeaderFixtures
import nl.atlas.gallery.saveUiEvidence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #494 smoke test in the real Activity on invented, locally drawn images.
 * One well-formed two-finger spread on the timeline grid must walk the zoom
 * ladder past the largest square stop into the real-ratio rows; the
 * accessibility action (TalkBack's route, no button) returns to the grid.
 * The ladder itself is covered on the JVM by GalleryPinchTrackerTest.
 */
@RunWith(AndroidJUnit4::class)
class PinchRealRatioJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(
        sizes = FIXTURE_SIZES,
        beforeLaunch = ::resetDensity
    )
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(fixtures).around(compose)

    @After fun restoreDensity() =
        resetDensity(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun spreadOnGridOpensRealRatioRowsAndActionReturnsToLargeGrid() {
        waitFor(hasTestTag("atlas-timeline-grid"))
        waitFor(hasText("photos & videos", substring = true))
        compose.onNodeWithTag("atlas-timeline-grid")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 columns"))
        compose.waitForIdle()
        saveUiEvidence("pinch-494-before-grid.png")

        // #507: original rows arrive drawn at the size the fingers left the
        // grid at, then settle. Step frames to catch them mid-transition.
        val ratioTile = SemanticsMatcher("ratio tile") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("atlas-ratio-media-") == true
        }
        fun tallestRatioTile(): Float = compose.onAllNodes(ratioTile)
            .fetchSemanticsNodes().maxOfOrNull { it.boundsInRoot.height } ?: 0f
        compose.mainClock.autoAdvance = false
        val entering = try {
            // Injected on the root: the grid leaves the tree on release, and
            // an injection on that node then fails to find it (paused clock).
            val gridBox = compose.onNodeWithTag("atlas-timeline-grid").fetchSemanticsNode().boundsInRoot
            // #652: the grid now reaches under the floating tab pill, so its
            // centre is no longer over the fixtures; the ratio rows anchor on
            // the item under the fingers, so pinch over the fixture tiles.
            val fixtureTops = compose.onAllNodes(SemanticsMatcher("fixture tile") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)
                    ?.startsWith("atlas-timeline-media-") == true &&
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        .any { it.startsWith("IMG_HEADER_FIXTURE_") }
            }).fetchSemanticsNodes().map { it.boundsInRoot }
            val focusY = if (fixtureTops.isEmpty()) gridBox.center.y
                else (fixtureTops.minOf { it.top } + fixtureTops.maxOf { it.bottom }) / 2f
            compose.onRoot().performTouchInput {
                val mid = Offset(gridBox.center.x, focusY)
                val reach = gridBox.width * 0.4f
                pinch(
                    start0 = mid - Offset(40f, 0f), end0 = mid - Offset(reach, 0f),
                    start1 = mid + Offset(40f, 0f), end1 = mid + Offset(reach, 0f),
                    durationMillis = 400L
                )
            }
            var frames = 0
            while (compose.onAllNodes(ratioTile).fetchSemanticsNodes().isEmpty() && frames++ < 600) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            repeat(4) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            saveUiEvidence("pinch-507-entering-ratio.png")
            tallestRatioTile()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        waitFor(hasTestTag("atlas-ratio-gallery"))
        compose.onNodeWithTag("atlas-grid-view-as").assertDoesNotExist()
        compose.waitForIdle()
        val settled = tallestRatioTile()
        assertTrue("original rows enter enlarged, then settle ($entering -> $settled px)",
            entering > settled * 1.1f)
        assertFixtureTilesHaveRealRatiosAndNoBars()
        saveUiEvidence("pinch-494-after-ratio.png")

        val actions = compose.onNodeWithTag("atlas-ratio-gallery")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == SQUARE_GRID_ACTION }.action() }
        waitFor(hasTestTag("atlas-timeline-grid"))
        compose.onNodeWithTag("atlas-timeline-grid")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 columns"))
    }

    /**
     * #504: the PNG fixtures have no ORIENTATION in MediaStore (like every
     * screenshot/download on the owner's phone). Their tiles must take the
     * PNG's real proportions, not neutral squares, and every tile edge must be
     * photo pixels, not a letterbox bar. Each fixture is one solid colour.
     */
    private fun assertFixtureTilesHaveRealRatiosAndNoBars() {
        val tile = SemanticsMatcher("real-ratio fixture tile") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("atlas-ratio-media-") == true &&
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .any { it.startsWith("IMG_HEADER_FIXTURE_") }
        }
        waitFor(tile)
        val nodes = compose.onAllNodes(tile).fetchSemanticsNodes()
        if (nodes.size < 4) {
            val shown = compose.onAllNodes(SemanticsMatcher("ratio tile") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)
                    ?.startsWith("atlas-ratio-media-") == true
            }).fetchSemanticsNodes().map {
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .joinToString("/")
            }
            throw AssertionError("Expected the synthetic fixtures in the ratio rows, " +
                "found ${nodes.size}. Tiles shown: $shown. Timeline dates: ${timelineDates()}")
        }
        val rootWidth = compose.onNodeWithTag("atlas-ratio-gallery")
            .fetchSemanticsNode().size.width
        for ((index, node) in nodes.withIndex()) {
            val name = node.config[SemanticsProperties.ContentDescription]
                .first { it.startsWith("IMG_HEADER_FIXTURE_") }
            val step = name.removePrefix("IMG_HEADER_FIXTURE_").substringBefore('.').toInt()
            val (w, h) = FIXTURE_SIZES[step - 1]
            val bounds = node.size
            assertTrue("$name tile wider than the screen", bounds.width <= rootWidth)
            assertEquals("$name tile must keep its ${w}x$h proportions",
                w.toDouble() / h, bounds.width.toDouble() / bounds.height, 0.06 * w / h)
            // Wait for the decode, then sample just inside all four edges.
            compose.waitUntil(30_000L) {
                val pixels = compose.onAllNodes(tile)[index].captureToImage().toPixelMap()
                val centre = pixels[pixels.width / 2, pixels.height / 2]
                val edges = listOf(
                    pixels[2, pixels.height / 2], pixels[pixels.width - 3, pixels.height / 2],
                    pixels[pixels.width / 2, 2], pixels[pixels.width / 2, pixels.height - 3])
                centre.alpha > 0.99f && edges.all { edge ->
                    kotlin.math.abs(edge.red - centre.red) < 0.04f &&
                        kotlin.math.abs(edge.green - centre.green) < 0.04f &&
                        kotlin.math.abs(edge.blue - centre.blue) < 0.04f
                }
            }
        }
    }

    /** #577 diagnostics: each fixture/evidence row's timeline date and its source. */
    private fun timelineDates(): String {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, "datetaken",
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED)
        val rows = ArrayList<String>()
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)",
            arrayOf("Pictures/ATLAS-Header-Fixture/", "Pictures/ATLAS-UI-Evidence/"),
            null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0).orEmpty()
                val taken = if (c.isNull(1)) 0L else c.getLong(1)
                val date = resolveMediaDate(taken, name, c.getLong(2), c.getLong(3))
                rows += "$name=${date.source}@${java.time.Instant.ofEpochMilli(date.millis)}" +
                    (if (c.isNull(1)) "(datetaken null)" else "")
            }
        }
        return rows.sorted().joinToString(", ")
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}

private val FIXTURE_SIZES =
    listOf(90 to 160, 160 to 90, 240 to 80, 100 to 100, 90 to 120, 160 to 100)

/** Start from the default four-column grid regardless of earlier tests. */
private fun resetDensity(context: Context) {
    context.getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE)
        .edit().clear().commit()
}
