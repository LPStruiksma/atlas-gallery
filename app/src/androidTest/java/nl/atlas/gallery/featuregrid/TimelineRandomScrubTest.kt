package nl.atlas.gallery.featuregrid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.SortOrder
import nl.atlas.gallery.sortMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

/**
 * #400, second surface: the Photos timeline scrubber in Random order.
 * Synthetic, locally generated images only; never the user's photos.
 *
 * Contract: releasing the scrubber only moves the position. It never
 * replans/reshuffles the timeline (identity witness: the exact displayed
 * slot instance G publishes to the host) and never rewinds afterwards.
 */
@RunWith(AndroidJUnit4::class)
class TimelineRandomScrubTest {
    @get:Rule val rule = createComposeRule()

    private fun fixtures(count: Int): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-random-scrub").apply { mkdirs() }
        val dayMillis = 86_400_000L
        return (0 until count).map { i ->
            val image = File(dir, "random-$i.png")
            if (!image.exists()) {
                val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.rgb((i * 37) % 256, (i * 67) % 256, (i * 101) % 256))
                FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            MediaRecord(i.toLong(), Uri.fromFile(image).toString(), "Random $i",
                "DCIM/ATLAS-TEST/", image.length(),
                1_600_000_000_000L + i * 3L * dayMillis, false)
        }
    }

    private fun firstVisibleMediaUri(
        state: LazyGridState,
        displayed: TimelineDisplayedSlots,
        items: List<MediaRecord>
    ): String? = state.layoutInfo.visibleItemsInfo.map { it.index }.sorted()
        .firstNotNullOfOrNull { (displayed.timeline.slots.getOrNull(it) as? TimelineSlot.Media)?.mediaIndex }
        ?.let { items.getOrNull(it)?.uri }

    @Test fun randomTimelineScrubReleaseDoesNotRewindOrReshuffle() {
        val items = sortMedia(fixtures(600), SortOrder.RANDOM, 12345L)
        // Deterministic seed: the same permutation is produced twice.
        assertEquals(items.map { it.uri },
            sortMedia(fixtures(600), SortOrder.RANDOM, 12345L).map { it.uri })
        val published = Collections.synchronizedList(ArrayList<TimelineDisplayedSlots>())
        lateinit var gridState: LazyGridState
        rule.setContent {
            gridState = rememberLazyGridState()
            MaterialTheme {
                AtlasTimelineGallery(
                    items = items,
                    sort = TimelineSort.RANDOM,
                    gridState = gridState,
                    onOpen = {},
                    modifier = Modifier.fillMaxSize(),
                    viewKey = "random-scrub-test",
                    catalogueEpoch = 7L,
                    onDisplayedSlots = { if (it != null) published.add(it) },
                    // #514: the right-edge handle normally shows only while scrolling.
                    fastScrollAlwaysShown = true
                )
            }
        }
        rule.waitUntil(20_000) {
            published.isNotEmpty() && gridState.layoutInfo.totalItemsCount > 0
        }
        rule.waitForIdle()
        val plan = published.last()
        val baseline = published.size
        assertSame(items, plan.source)
        assertEquals(7L, plan.catalogueEpoch)
        val slider = rule.onNodeWithTag("atlas-timeline-date-scrub")
        // Random has no meaningful dates: the bubble names a position only.
        val label = slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue("Random scrub must not show date labels: $label", label.startsWith("Photo "))

        fun scrubTo(fraction: Float): Pair<Int, String?> {
            // Place, then hold still before lifting: a placement, not a throw
            // (a throw carries on with fling momentum, spec §0).
            slider.performTouchInput {
                down(Offset(centerX, height * .08f))
                moveTo(Offset(centerX, height * fraction))
                advanceEventTime(250L)
                moveTo(Offset(centerX, height * fraction))
                up()
            }
            rule.waitForIdle()
            // A held/deferred seek settles within a few frames; give a rewind
            // (snap back) or replan every chance to show up before reading.
            rule.mainClock.advanceTimeBy(1_500L)
            rule.waitForIdle()
            val index = gridState.firstVisibleItemIndex
            return index to firstVisibleMediaUri(gridState, published.last(), items)
        }

        val first = scrubTo(.62f)
        assertTrue("Release did not move the grid", first.first > 0)
        assertNotNull(first.second)
        val elsewhere = scrubTo(.25f)
        assertNotEquals("A different fraction must move position", first.first, elsewhere.first)
        val again = scrubTo(.62f)
        assertEquals("Same release fraction must reach the same slot", first.first, again.first)
        assertEquals("Same release fraction must show the same photo", first.second, again.second)

        // No rewind: the settled position holds after further idle frames.
        rule.mainClock.advanceTimeBy(3_000L)
        rule.waitForIdle()
        assertEquals(again.first, gridState.firstVisibleItemIndex)

        // No reshuffle / replan: every published displayed timeline is the
        // exact same plan instance over the exact same Random list reference.
        val afterScrubs = published.drop(baseline - 1)
        assertTrue("Scrub release replanned the Random timeline",
            afterScrubs.all { it.timeline === plan.timeline })
        assertSame(items, published.last().source)
    }

    /**
     * #493 (Lukas on the S24+): the grid must follow the thumb WHILE the
     * scrubber is held. Before the fix it stayed frozen and swapped every
     * photo at release, which in Random order looked like a reshuffle on
     * every stop. Release must then leave the shown photos untouched.
     */
    @Test fun randomTimelineFollowsThumbDuringDragAndReleaseChangesNothing() {
        val items = sortMedia(fixtures(600), SortOrder.RANDOM, 12345L)
        val published = Collections.synchronizedList(ArrayList<TimelineDisplayedSlots>())
        lateinit var gridState: LazyGridState
        rule.setContent {
            gridState = rememberLazyGridState()
            MaterialTheme {
                AtlasTimelineGallery(
                    items = items,
                    sort = TimelineSort.RANDOM,
                    gridState = gridState,
                    onOpen = {},
                    modifier = Modifier.fillMaxSize(),
                    viewKey = "random-live-scrub-test",
                    catalogueEpoch = 9L,
                    onDisplayedSlots = { if (it != null) published.add(it) },
                    // #514: the right-edge handle normally shows only while scrolling.
                    fastScrollAlwaysShown = true
                )
            }
        }
        rule.waitUntil(20_000) {
            published.isNotEmpty() && gridState.layoutInfo.totalItemsCount > 0
        }
        rule.waitForIdle()
        val plan = published.last()
        val slider = rule.onNodeWithTag("atlas-timeline-date-scrub")
        slider.performTouchInput {
            down(Offset(centerX, height * .08f))
            moveTo(Offset(centerX, height * .35f))
            moveTo(Offset(centerX, height * .62f))
            // Finger stops before lifting, so the release carries no momentum.
            advanceEventTime(250L)
            moveTo(Offset(centerX, height * .62f))
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500L)
        rule.waitForIdle()
        val held = gridState.firstVisibleItemIndex
        val heldUri = firstVisibleMediaUri(gridState, published.last(), items)
        assertTrue("Grid stayed frozen while the scrubber was held", held > 0)
        assertNotNull(heldUri)
        slider.performTouchInput { up() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_500L)
        rule.waitForIdle()
        assertEquals("Release moved the grid after a live scrub", held,
            gridState.firstVisibleItemIndex)
        assertEquals("Release changed the shown photo", heldUri,
            firstVisibleMediaUri(gridState, published.last(), items))
        assertTrue("Scrub replanned the Random timeline",
            published.last().timeline === plan.timeline)
    }
}
