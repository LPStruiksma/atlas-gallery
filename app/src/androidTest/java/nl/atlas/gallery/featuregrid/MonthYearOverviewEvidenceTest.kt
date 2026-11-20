package nl.atlas.gallery.featuregrid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.applyAtlasSystemBars
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.saveUiEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * #16: rendered 1080x2340 S24+ evidence in both themes, plus a real TalkBack
 * progression 7 -> 12 -> 28 -> original proportions. No personal media.
 */
@RunWith(AndroidJUnit4::class)
class MonthYearOverviewEvidenceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private fun inventedPhotos(): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-month-year-16").apply { mkdirs() }
        val epoch = Instant.parse("2027-11-10T12:00:00Z").toEpochMilli()
        return (0 until 420).map { index ->
            val file = File(dir, "overview-$index.png")
            if (!file.exists()) {
                val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                Canvas(image).drawColor(Color.HSVToColor(
                    floatArrayOf((index * 47f) % 360f, .6f, .8f)))
                FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            MediaRecord(index.toLong(), Uri.fromFile(file).toString(), "Overview $index",
                "DCIM/ATLAS-TEST/", 2_000L + index, epoch - index * 2L * 86_400_000L, false)
        }
    }

    /**
     * Photo tiles per row in this layout (0 without tiles): headers span the
     * whole line, tiles are one of at least three columns. The widest row
     * counts, since a month's last row may be short.
     */
    private fun LazyGridLayoutInfo.drawnPhotoColumns(): Int =
        visibleItemsInfo.filter { it.size.width in 1 until viewportSize.width / 2 }
            .groupingBy { it.offset.y }.eachCount().values.maxOrNull() ?: 0

    @Test fun capturesSevenMonthYearAndOriginalInLightAndDark() {
        val items = inventedPhotos()
        val dark = mutableStateOf(false)
        val ratio = mutableStateOf(false)
        val displayed = AtomicReference<TimelineDisplayedSlots?>()
        // #570: photo columns in the last draw pass whose grid held tiles.
        val drawnColumns = AtomicInteger(0)
        compose.setContent {
            val useDark = dark.value
            SideEffect { applyAtlasSystemBars(compose.activity.window, useDark) }
            AtlasGalleryTheme(dark = useDark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        key(useDark) {
                            if (ratio.value) {
                                AtlasRatioGallery(
                                    items = items,
                                    aspectRatioOf = { if (it.id % 2L == 0L) 1.6f else .7f },
                                    onOpen = {},
                                    catalogueEpoch = 16L
                                )
                            } else {
                                val gridState = rememberLazyGridState()
                                // Like Perf11 (#569): reading layoutInfo while
                                // drawing redraws this box when the grid lays
                                // out, so drawnColumns is set by a real frame.
                                Box(Modifier.fillMaxSize().drawWithContent {
                                    drawContent()
                                    drawnColumns.set(gridState.layoutInfo.drawnPhotoColumns())
                                }) {
                                AtlasTimelineGallery(
                                    items = items,
                                    sort = TimelineSort.NEWEST,
                                    gridState = gridState,
                                    onOpen = {},
                                    viewKey = "overview-16-${if (useDark) "dark" else "light"}",
                                    catalogueEpoch = 16L,
                                    entryDensity = GridDensity.SMALL,
                                    onDisplayedSlots = { displayed.set(it) },
                                    onOriginalProportionsRequested = { request ->
                                        if (request.destination == GalleryZoomStop.ORIGINAL)
                                            ratio.value = true
                                    }
                                )
                                }
                            }
                        }
                    }
                }
            }
        }

        fun awaitLevel(level: GridDensity, bucket: TimelineBucket) {
            compose.waitUntil(30_000L) {
                compose.onAllNodes(hasTestTag("atlas-timeline-grid")
                    .and(SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription, "${level.columns} columns")))
                    .fetchSemanticsNodes().isNotEmpty() &&
                    displayed.get()?.timeline?.headers?.isNotEmpty() == true &&
                    displayed.get()?.timeline?.headers?.all {
                        it.header.stableKey.startsWith("header:${bucket.name}:")
                    } == true &&
                    // #570: semantics and slots can be ready while no frame
                    // has drawn the tiles yet; capture only after one has.
                    drawnColumns.get() == level.columns
            }
            assertTrue(displayed.get()!!.timeline.grouped)
            compose.waitForIdle()
        }

        fun chooseSmaller() {
            val actions = compose.onNodeWithTag("atlas-timeline-grid")
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            val smaller = actions.first { it.label == SMALLER_TILES_ACTION }
            compose.runOnIdle { assertTrue(smaller.action()) }
        }

        for (useDark in listOf(false, true)) {
            compose.runOnIdle {
                dark.value = useDark
                ratio.value = false
                // Do not clear the slot callback here: the first composition may
                // already have published it, and an unchanged ready plan will
                // not re-emit after this idle callback. A remounted theme clears
                // its old slots through AtlasTimelineGallery.onDispose.
            }
            val theme = if (useDark) "dark" else "light"
            awaitLevel(GridDensity.SMALL, TimelineBucket.MONTH)
            saveUiEvidence("zoom-16-$theme-7.png")

            chooseSmaller()
            awaitLevel(GridDensity.MONTH, TimelineBucket.MONTH)
            saveUiEvidence("zoom-16-$theme-12-month.png")

            chooseSmaller()
            awaitLevel(GridDensity.YEAR, TimelineBucket.YEAR)
            assertEquals(28, GridDensity.YEAR.columns)
            saveUiEvidence("zoom-16-$theme-28-year.png")

            val actions = compose.onNodeWithTag("atlas-timeline-grid")
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            val original = actions.first { it.label == ORIGINAL_PROPORTIONS_ACTION }
            compose.runOnIdle { assertTrue(original.action()) }
            compose.waitUntil(30_000L) {
                compose.onAllNodes(hasTestTag("atlas-ratio-gallery"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            saveUiEvidence("zoom-16-$theme-original.png")
        }
    }
}
