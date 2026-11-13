package nl.atlas.gallery

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * #400 in the real Activity at the owner's S24+ size: with Photos set to
 * Random, opening a photo and scrubbing the viewer's filmstrip, releasing,
 * scrubbing back, going Back and reopening never changes the order.
 *
 * Witness: 40 invented PNGs in their own album. Random order depends only on
 * each item's own key and the seed, so the album's order must equal
 * [sortMedia] of exactly these 40 records with the app's starting seed (12345)
 * at every step, in the grid and in every composed filmstrip tile. Reshuffle
 * at the end must move to seed 12346, which proves the witness can see a
 * change. Emulator only; the fixtures are deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RandomViewerScrubJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(
        sizes = List(FIXTURES) { 120 to 90 },
        folderOf = { FOLDER })
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(S24DisplayRule()).around(fixtures).around(compose)

    @Test fun filmstripScrubAndReleaseNeverReshufflesRandomOrder() {
        val records = fixtureRecords()
        assertEquals("all fixtures inserted", FIXTURES, records.size)
        val expected = sortMedia(records, SortOrder.RANDOM, 12345L).map { it.name }
        assertNotEquals("seeded order must differ from insertion order",
            records.sortedBy { it.id }.map { it.name }, expected)

        // Photos -> Random, then the fixture album (it inherits the order).
        waitFor(hasText("photos & videos", substring = true))
        compose.onNodeWithTag("atlas-photos-sort").performClick()
        compose.onNodeWithTag("atlas-photos-sort-random").performClick()
        waitFor(hasText("photos & videos", substring = true))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        val card = hasTestTag("atlas-album-card") and hasText(FOLDER, substring = true)
        waitFor(card)
        compose.onNode(card).performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText(FOLDER))
        assertGridOrder("album before viewer", records, expected)
        saveUiEvidence("random-400-album.png")

        // Open the photo at Random position 21 (index 20).
        val startIndex = 20
        val startId = records.first { it.name == expected[startIndex] }.id
        compose.onNode(tileOf(startId)).performClick()
        waitFor(hasTestTag("atlas-photo-counter"))
        compose.waitForIdle()
        assertEquals("viewer opens on the tapped photo", startIndex, selectedIndex())
        assertViewer("viewer opened", expected)

        // Forward filmstrip fling and release.
        val strip = compose.onNodeWithTag("atlas-filmstrip")
        strip.performTouchInput { swipeLeft(startX = width * .80f, endX = width * .30f, durationMillis = 200) }
        compose.waitForIdle()
        val afterForward = selectedIndex()
        assertTrue("forward scrub moved ($afterForward)", afterForward > startIndex)
        assertViewer("forward scrub release", expected)
        saveUiEvidence("random-400-viewer-after-scrub.png")

        // Backward fling and release.
        strip.performTouchInput { swipeRight(startX = width * .30f, endX = width * .80f, durationMillis = 200) }
        compose.waitForIdle()
        val afterBackward = selectedIndex()
        assertTrue("backward scrub moved ($afterBackward)", afterBackward < afterForward)
        assertViewer("backward scrub release", expected)

        // Same start, same gesture: same photo (no hidden reorder in between).
        compose.onNodeWithTag("atlas-thumbnail-$startIndex").performClick()
        compose.waitUntil(10_000L) { selectedIndex() == startIndex }
        compose.waitForIdle()
        strip.performTouchInput { swipeLeft(startX = width * .80f, endX = width * .30f, durationMillis = 200) }
        compose.waitForIdle()
        assertEquals("repeating the same scrub lands on the same photo", afterForward, selectedIndex())
        assertViewer("repeated scrub release", expected)

        // Back to the album: same order; reopening the start photo finds it at the same place.
        compose.onNodeWithTag("atlas-back").performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText(FOLDER))
        assertGridOrder("album after viewer", records, expected)
        saveUiEvidence("random-400-album-after-viewer.png")
        compose.onNode(tileOf(startId)).performClick()
        waitFor(hasTestTag("atlas-photo-counter"))
        compose.waitForIdle()
        assertEquals("reopened photo keeps its Random position", startIndex, selectedIndex())
        assertViewer("viewer reopened", expected)
        compose.onNodeWithTag("atlas-back").performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText(FOLDER))

        // Only an explicit Reshuffle changes the order, to the next seed.
        compose.onNodeWithTag("atlas-header-back").performClick()
        compose.onNodeWithTag("atlas-nav-photos").performClick()
        waitFor(hasText("photos & videos", substring = true))
        compose.onNodeWithTag("atlas-photos-sort").performClick()
        compose.onNodeWithTag("atlas-photos-reshuffle").performClick()
        waitFor(hasText("photos & videos", substring = true))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor(card)
        compose.onNode(card).performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText(FOLDER))
        val reshuffled = sortMedia(records, SortOrder.RANDOM, 12346L).map { it.name }
        assertNotEquals("Reshuffle must pick a different order", expected, reshuffled)
        assertGridOrder("album after Reshuffle", records, reshuffled)
    }

    /** The 40 fixtures as the app reads them (id, display name, volume). */
    private fun fixtureRecords(): List<MediaRecord> {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val out = mutableListOf<MediaRecord>()
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.VOLUME_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("Pictures/$FOLDER/"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += MediaRecord(id, Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()).toString(),
                    c.getString(1), "Pictures/$FOLDER/", 0L, 0L, false,
                    volumeName = c.getString(2))
            }
        }
        return out
    }

    private fun tileOf(id: Long) = SemanticsMatcher("album tile $id") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        tag == "atlas-timeline-media-$id" || tag == "atlas-grid-media-$id-photo"
    }

    /** Every fixture tile on screen, in reading order, must be a contiguous run of [expected]. */
    private fun assertGridOrder(label: String, records: List<MediaRecord>, expected: List<String>) {
        val nameOf = records.associate { it.id to it.name }
        fun visible(): List<String> = compose.onAllNodes(SemanticsMatcher("fixture tile") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            tileId(tag)?.let { it in nameOf } == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.top.toInt() / 8 }, { it.boundsInRoot.left }))
            .map { nameOf.getValue(tileId(it.config[SemanticsProperties.TestTag])!!) }
            .distinct()
        compose.waitUntil(60_000L) { visible().size >= 8 }
        compose.waitForIdle()
        val seen = visible()
        val first = expected.indexOf(seen.first())
        assertEquals("$label: album tiles out of seeded Random order",
            expected.subList(first, (first + seen.size).coerceAtMost(expected.size)), seen)
    }

    private fun tileId(tag: String): Long? = when {
        tag.startsWith("atlas-timeline-media-") -> tag.removePrefix("atlas-timeline-media-").toLongOrNull()
        tag.startsWith("atlas-grid-media-") -> tag.removePrefix("atlas-grid-media-")
            .substringBefore('-').toLongOrNull()
        else -> null
    }

    private fun selectedIndex(): Int = compose.onNodeWithTag("atlas-photo-counter")
        .fetchSemanticsNode().config[SemanticsProperties.Text]
        .joinToString("") { it.text }.substringBefore(" of ").toInt() - 1

    /**
     * The counter, the highlighted centred tile and every composed filmstrip
     * tile all name the record the seeded order puts at that index.
     */
    private fun assertViewer(label: String, expected: List<String>) {
        val counter = compose.onNodeWithTag("atlas-photo-counter").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals("$label: viewer list size", " of ${expected.size}", counter.substring(counter.indexOf(" of ")))
        val selected = selectedIndex()
        compose.onNodeWithTag("atlas-thumbnail-$selected").assertIsSelected()
        val strip = compose.onNodeWithTag("atlas-filmstrip").fetchSemanticsNode().boundsInRoot
        val tile = compose.onNodeWithTag("atlas-thumbnail-$selected").fetchSemanticsNode().boundsInRoot
        assertTrue("$label: selected tile not centred", abs(strip.center.x - tile.center.x) < tile.width * .25f)
        val tiles = compose.onAllNodes(SemanticsMatcher("filmstrip tile") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atlas-thumbnail-") == true
        }).fetchSemanticsNodes()
        assertTrue("$label: filmstrip shows neighbours", tiles.size >= 3)
        for (node in tiles) {
            val index = node.config[SemanticsProperties.TestTag].removePrefix("atlas-thumbnail-").toInt()
            val description = node.config[SemanticsProperties.ContentDescription].joinToString(" ")
            assertTrue("$label: filmstrip tile $index is '$description', seeded order says ${expected[index]}",
                description.endsWith(": ${expected[index]}"))
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)

    private companion object {
        const val FIXTURES = 40
        const val FOLDER = "ATLAS-Random-400"
    }
}
