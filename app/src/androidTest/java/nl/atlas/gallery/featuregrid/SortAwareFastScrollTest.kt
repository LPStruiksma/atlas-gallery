package nl.atlas.gallery.featuregrid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.AtlasEvidenceHarness
import nl.atlas.gallery.DeepBg
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.SortOrder
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.sortMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlin.math.pow

/**
 * #514 (Lukas 2026-11-09 23:51Z): with Largest, the fast scroll must walk
 * through file sizes, not dates; the slim header stays pinned (#641).
 * Synthetic, locally generated images; sizes are synthetic metadata.
 */
@RunWith(AndroidJUnit4::class)
class SortAwareFastScrollTest {
    private val rule = createComposeRule()

    // #645: evidence at the owner's S24+ size, like every other evidence test.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private fun fixtures(count: Int): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-size-scrub").apply { mkdirs() }
        return (0 until count).map { i ->
            val image = File(dir, "size-$i.png")
            if (!image.exists()) {
                val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.rgb((i * 53) % 256, (i * 29) % 256, (i * 91) % 256))
                FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            // 6 GB down to a few hundred bytes: every size bucket appears.
            val size = (6_000_000_000.0 / 1.03.pow(i)).toLong()
            // Dates unrelated to size, so a date bubble would be obviously wrong.
            MediaRecord(i.toLong(), Uri.fromFile(image).toString(), "Size $i",
                "DCIM/ATLAS-TEST/", size, 1_600_000_000_000L + (i * 7919L % 600) * 86_400_000L, false)
        }
    }

    @Test fun largestFastScrollShowsSizesAndHeaderStaysPinned() {
        val items = sortMedia(fixtures(600).shuffled(java.util.Random(3)), SortOrder.LARGEST, 0L)
        val published = Collections.synchronizedList(ArrayList<TimelineDisplayedSlots>())
        lateinit var gridState: LazyGridState
        rule.setContent {
            gridState = rememberLazyGridState()
            // #645: app theme and insets; the stand-in header is styled like
            // the app's (DeepBg, title type), not a bare label.
            AtlasEvidenceHarness {
                AtlasTimelineGallery(
                    items = items,
                    sort = TimelineSort.LARGEST,
                    gridState = gridState,
                    onOpen = {},
                    modifier = Modifier.fillMaxSize(),
                    viewKey = "size-scrub-test",
                    catalogueEpoch = 3L,
                    onDisplayedSlots = { if (it != null) published.add(it) },
                    header = {
                        Box(Modifier.fillMaxWidth().height(64.dp)
                            .background(DeepBg)
                            .padding(horizontal = 16.dp)
                            .testTag("test-header"),
                            contentAlignment = Alignment.CenterStart) {
                            Text("Photos", style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground)
                        }
                    },
                    fastScrollAlwaysShown = true
                )
            }
        }
        rule.waitUntil(20_000) { published.isNotEmpty() && gridState.layoutInfo.totalItemsCount > 0 }
        rule.waitForIdle()
        saveUiEvidence("fastscroll-514-largest-top.png")

        val scrub = rule.onNodeWithTag("atlas-timeline-date-scrub")
        val topLabel = scrub.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("Largest starts at the largest size bucket", "Over 5 GB", topLabel)
        // #645: the harness keeps the grid clear of the status bar, so "on
        // screen" means at or below the grid's own top, not the window's.
        val gridTop = rule.onNodeWithTag("atlas-timeline-grid").getUnclippedBoundsInRoot().top
        val headerTop = rule.onNodeWithTag("test-header").getUnclippedBoundsInRoot().top
        assertTrue("Header must start on screen", headerTop >= gridTop - 1.dp)

        // Hold the handle half-way: the bubble names a size, never a date.
        scrub.performTouchInput {
            down(Offset(centerX, height * .1f))
            moveTo(Offset(centerX, height * .3f))
            moveTo(Offset(centerX, height * .5f))
        }
        rule.waitForIdle()
        val bubble = rule.onNodeWithTag("atlas-fast-scroll-bubble", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(transform = AnnotatedString::text)
        assertTrue("Bubble must be a size bucket: $bubble",
            bubble.endsWith("MB") || bubble.endsWith("GB") || bubble.endsWith("KB"))
        assertFalse("Bubble must not be a date: $bubble", Regex("\\b(19|20)\\d\\d\\b").containsMatchIn(bubble))
        assertTrue("Grid must follow the handle", gridState.firstVisibleItemIndex > 0)
        saveUiEvidence("fastscroll-514-largest-mid-drag.png")
        scrub.performTouchInput { up() }
        rule.waitForIdle()

        // Whatever the jump, the bucket under the thumb matches the photo at the top.
        val first = gridState.firstVisibleItemIndex
        val slot = published.last().timeline.slots[first]
        val media = (slot as? TimelineSlot.Media)?.mediaIndex
        if (media != null) {
            val label = scrub.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertEquals(sizeBucketLabel(items[media].sizeBytes), label)
        }
        // #641: the slim header stays pinned while the grid scrolls under it.
        rule.onNodeWithTag("atlas-timeline-grid").performTouchInput { swipeUp() }
        rule.waitForIdle()
        val scrolledTop = rule.onNodeWithTag("test-header").getUnclippedBoundsInRoot().top
        assertTrue("Header must stay pinned (top $headerTop, after scroll $scrolledTop)",
            (scrolledTop - headerTop).value in -1f..1f)
        saveUiEvidence("fastscroll-514-largest-mid-scroll.png")

        // Spec §0: a throw carries on after release (platform fling decay)
        // and settles; a placement does not.
        rule.mainClock.autoAdvance = false
        scrub.performTouchInput {
            down(Offset(centerX, height * .15f))
            moveTo(Offset(centerX, height * .25f))
            moveTo(Offset(centerX, height * .35f))
            up()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val atRelease = gridState.firstVisibleItemIndex
        rule.mainClock.advanceTimeBy(3_000L)
        rule.waitForIdle()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val settled = gridState.firstVisibleItemIndex
        assertTrue("Fling must carry on after release ($atRelease -> $settled)", settled > atRelease)
        rule.mainClock.advanceTimeBy(2_000L)
        rule.waitForIdle()
        assertEquals("Fling must settle and stay", settled, gridState.firstVisibleItemIndex)
    }
}
