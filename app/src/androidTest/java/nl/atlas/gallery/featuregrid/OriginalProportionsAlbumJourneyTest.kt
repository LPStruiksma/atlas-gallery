package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.waitForNode
import nl.atlas.gallery.S24DisplayRule
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import nl.atlas.gallery.AtlasEvidenceHarness
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.displayAspectRatioOrNull
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.MainActivity
import nl.atlas.gallery.SyntheticHeaderFixtures
import nl.atlas.gallery.saveUiEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * #527 acceptance on the owner's phone geometry. The emulator display is set
 * to 1080x2340 px at 450 dpi (Galaxy S24+ defaults) for the test, then reset.
 *
 * A synthetic album of solid-colour PNGs in every shape Lukas has (4:3, 3:4,
 * 16:9, 1:1, 3:2, 2:3, 9:16 and a very tall 0.35) is opened from Albums and
 * shown at original proportions in both timeline modes. Every tile must
 * keep its photo's ratio within 2%, contain only that photo's colour (no bar of
 * any colour on any edge), and every row except the last must span the width.
 */
@RunWith(AndroidJUnit4::class)
class OriginalProportionsAlbumJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(sizes = ALBUM_SIZES)
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun albumAtOriginalProportionsTimelineOn() {
        openFixtureAlbum()
        waitFor(hasTestTag("atlas-timeline-grid"))
        compose.waitForIdle()
        saveUiEvidence("ratio-527-album-square-grid.png")
        enterOriginalProportions()
        assertAlbumRowsHaveRealRatiosAndNoBars("ratio-527-album-original-timeline-on.png")
    }

    @Test fun albumAtOriginalProportionsTimelineOff() {
        openFixtureAlbum()
        compose.onNodeWithTag("atlas-header-more").performClick()
        compose.onNodeWithTag("atlas-timeline-toggle").performClick()
        waitFor(hasTestTag("atlas-feature-grid"))
        enterOriginalProportions()
        assertAlbumRowsHaveRealRatiosAndNoBars("ratio-527-album-original-timeline-off.png")
    }

    private fun openFixtureAlbum() {
        waitFor(hasTestTag("atlas-nav-albums"))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        val card = hasTestTag("atlas-album-card") and
            hasText("ATLAS-Header-Fixture", substring = true)
        waitFor(card)
        compose.onNode(card).performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText("ATLAS-Header-Fixture"))
    }

    /** The TalkBack route; the pinch itself is covered by PinchRealRatioJourneyTest. */
    private fun enterOriginalProportions() {
        val withAction = SemanticsMatcher("offers $ORIGINAL_PROPORTIONS_ACTION") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)
                ?.any { it.label == ORIGINAL_PROPORTIONS_ACTION } == true
        }
        waitFor(withAction)
        val actions = compose.onNode(withAction).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == ORIGINAL_PROPORTIONS_ACTION }.action() }
        waitFor(hasTestTag("atlas-ratio-gallery"))
    }

    private fun assertAlbumRowsHaveRealRatiosAndNoBars(evidence: String) {
        val seen = mutableSetOf<Int>()
        waitFor(fixtureTile)
        compose.waitForIdle()
        checkVisibleTiles(compose, seen)
        saveUiEvidence(evidence)
        // Anything below the fold: scroll each unchecked shape into view too.
        for (step in ALBUM_SIZES.indices.map { it + 1 }) {
            if (step in seen) continue
            compose.onNodeWithTag("atlas-ratio-gallery").performScrollToNode(
                hasContentDescription("IMG_HEADER_FIXTURE_$step", substring = true))
            compose.waitForIdle()
            checkVisibleTiles(compose, seen)
        }
        // #652: rows scroll under the floating tab pill. A last-row tile behind
        // it is inside the viewport, so performScrollToNode doesn't move it;
        // at the end of the list that row sits above the pill.
        if (!seen.containsAll(ALBUM_SIZES.indices.map { it + 1 })) {
            repeat(20) {
                val range = compose.onNodeWithTag("atlas-ratio-gallery").fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                if (range == null || range.value() >= range.maxValue()) return@repeat
                compose.onNodeWithTag("atlas-ratio-gallery").performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
            checkVisibleTiles(compose, seen)
        }
        assertEquals("every fixture shape must have been checked",
            ALBUM_SIZES.indices.map { it + 1 }.toSet(), seen)
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}

/**
 * Rendered decoder fallback on a real on-device synthetic PNG. The fixture
 * remains a MediaStore URI, but the metadata supplied to AtlasRatioGallery
 * is deliberately zeroed before the layout sees it. This is equivalent to
 * a provider returning WIDTH/HEIGHT=0, without modifying MediaStore data or
 * changing production code solely for a test.
 */
@RunWith(AndroidJUnit4::class)
class OriginalProportionsUnknownMetadataTest {
    private val fixtures = SyntheticHeaderFixtures(sizes = ALBUM_SIZES)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun decodedPngReplacesUnknownSquareWithoutBars() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projection: List<MediaRecord> = context.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME),
            "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("IMG_HEADER_FIXTURE_%"), null
        )!!.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(
                android.provider.MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(
                android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            buildList<MediaRecord> {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val step = name.removePrefix("IMG_HEADER_FIXTURE_")
                        .substringBefore('.').toIntOrNull()
                    if (step != null && step in 1..ALBUM_SIZES.size) {
                        val id = cursor.getLong(idCol)
                        val uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        add(MediaRecord(id, uri.toString(), name,
                            "Pictures/ATLAS-Header-Fixture/",
                            1L, 1_600_000_000_000L + step, false).copy(
                                widthPixels = 0, heightPixels = 0))
                    }
                }
            }.sortedBy { it.name }
        }
        assertEquals(ALBUM_SIZES.size, projection.size)
        // These are real decodable images, while the planning metadata is 0/0.
        assertTrue(projection.all { it.displayAspectRatioOrNull() == null })
        compose.setContent {
            // #645: app theme and insets, not a white stock-Material page.
            AtlasEvidenceHarness {
                AtlasRatioGallery(
                    items = projection,
                    aspectRatioOf = { it.displayAspectRatioOrNull() },
                    onOpen = {},
                    modifier = Modifier.fillMaxSize(),
                    state = rememberLazyListState()
                )
            }
        }
        val expected = ALBUM_SIZES[0].let { it.first.toDouble() / it.second }
        compose.waitUntil(60_000L) {
            val nodes = compose.onAllNodes(fixtureTile).fetchSemanticsNodes()
            nodes.any { node ->
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty().firstOrNull { it.startsWith("IMG_HEADER_FIXTURE_1.") }
                label != null && abs(node.size.width.toDouble() /
                    node.size.height - expected) <= expected * 0.02
            }
        }
        // Verify every synthetic image, not only the first, acquires its
        // decoded aspect ratio after beginning as an unknown square.
        val seen = mutableSetOf<Int>()
        for (step in ALBUM_SIZES.indices.map { it + 1 }) {
            compose.onNodeWithTag("atlas-ratio-gallery").performScrollToNode(
                hasContentDescription("IMG_HEADER_FIXTURE_$step.", substring = true))
            compose.waitUntil(60_000L) {
                val target = compose.onAllNodes(fixtureTile).fetchSemanticsNodes()
                    .firstOrNull { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription)
                            .orEmpty().any { it.startsWith("IMG_HEADER_FIXTURE_$step.") }
                    }
                val (w, h) = ALBUM_SIZES[step - 1]
                target != null && abs(target.size.width.toDouble() /
                    target.size.height - w.toDouble() / h) <=
                    (w.toDouble() / h) * 0.02
            }
            seen.add(step)
        }
        assertEquals(ALBUM_SIZES.size, seen.size)
        compose.onNodeWithTag("atlas-ratio-gallery").performScrollToNode(
            hasContentDescription("IMG_HEADER_FIXTURE_1.", substring = true))
        compose.waitForIdle()
        checkVisibleTiles(compose, mutableSetOf())
        saveUiEvidence("ratio-527-album-original-missing-metadata.png")
    }
}

/** Galaxy S24+ default: 1080x2340 px, 450 dpi (2.8125 px per dp). */
private val fixtureTile = SemanticsMatcher("synthetic real-ratio tile") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)
        ?.startsWith("atlas-ratio-media-") == true &&
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            .any { it.startsWith("IMG_HEADER_FIXTURE_") }
}

/**
 * For every fully visible fixture tile: ratio within 2% of the photo (unless
 * its row hit the height cap), and every sampled pixel, edge to edge, is the
 * photo's own colour. Rows other than the last fill the gallery width.
 */
private fun checkVisibleTiles(compose: ComposeTestRule, seen: MutableSet<Int>) {
    val nodes = compose.onAllNodes(fixtureTile).fetchSemanticsNodes()
    val gallery = compose.onAllNodes(hasTestTag("atlas-ratio-gallery"))
        .fetchSemanticsNodes().single()
    // #652: rows scroll under the floating tab pill; a tile behind it is not
    // visible, so the visible area ends at the pill's top edge.
    val pillTop = compose.onAllNodes(hasTestTag("atlas-nav-pill")).fetchSemanticsNodes()
        .firstOrNull()?.boundsInRoot?.top
    val galleryBounds = gallery.boundsInRoot.let { b ->
        if (pillTop != null && pillTop < b.bottom) b.copy(bottom = pillTop) else b
    }
    val maxRowPx = gallery.layoutInfo.density.run { 240f * density }
    val gapPx = gallery.layoutInfo.density.run { RATIO_GAP_DP * density }
    val visible = nodes.filter { it.boundsInRoot.isFullyInside(galleryBounds) }
    assertTrue("no fully visible fixture tiles", visible.isNotEmpty())
    for (node in visible) {
        val step = stepOf(node)
        val (w, h) = ALBUM_SIZES[step - 1]
        val real = w.toDouble() / h
        val size = node.size
        if (size.height < maxRowPx - 1) {
            assertEquals("IMG_HEADER_FIXTURE_$step tile ${size.width}x${size.height} " +
                "must keep its ${w}x$h ratio", real,
                size.width.toDouble() / size.height, real * 0.02)
        }
        val index = nodes.indexOf(node)
        compose.waitUntil(30_000L) {
            val pixels = compose.onAllNodes(fixtureTile)[index].captureToImage().toPixelMap()
            tileIsOneColour(pixels)
        }
        seen += step
    }
    // Group by row (same top); all rows but the bottom-most must fill the width.
    val rows = visible.groupBy { it.boundsInRoot.top.toInt() }.toSortedMap()
    val isLastRowOfAlbum = { top: Int -> top == rows.lastKey() }
    for ((top, cells) in rows) {
        if (isLastRowOfAlbum(top)) continue
        val all = nodes.filter { it.boundsInRoot.top.toInt() == top }
        val used = all.sumOf { it.size.width } + gapPx * (all.size - 1)
        assertEquals("row at $top must span the width", galleryBounds.width, used, 1.5f)
    }
}

private fun Rect.isFullyInside(outer: Rect) =
    left >= outer.left - 0.5f && right <= outer.right + 0.5f &&
        top >= outer.top - 0.5f && bottom <= outer.bottom + 0.5f

private fun stepOf(node: SemanticsNode): Int =
    checkNotNull(node.config.getOrNull(SemanticsProperties.ContentDescription)) {
        "tile ${node.id} lost its description: the rows were re-planned mid-check"
    }.first { it.startsWith("IMG_HEADER_FIXTURE_") }
        .removePrefix("IMG_HEADER_FIXTURE_").substringBefore('.').toInt()

/** Every 3rd pixel, including the outermost rows and columns, matches the centre. */
private fun tileIsOneColour(pixels: PixelMap): Boolean {
    val centre = pixels[pixels.width / 2, pixels.height / 2]
    if (centre.alpha < 0.99f) return false
    fun same(c: Color) = abs(c.red - centre.red) < 0.04f &&
        abs(c.green - centre.green) < 0.04f && abs(c.blue - centre.blue) < 0.04f
    val xs = (0 until pixels.width step 3) + (pixels.width - 1)
    val ys = (0 until pixels.height step 3) + (pixels.height - 1)
    return xs.all { x -> ys.all { y -> same(pixels[x, y]) } }
}

/**
 * Solid-colour album in every shape on the owner's phone, newest first:
 * 4:3, 3:4, 16:9, 1:1, 3:2, 2:3, very tall 0.35, 9:16, then a second mix.
 */
private val ALBUM_SIZES = listOf(
    400 to 300, 300 to 400, 480 to 270, 300 to 300, 450 to 300, 300 to 450,
    105 to 300, 270 to 480, 400 to 300, 480 to 270, 300 to 400, 300 to 300
)
