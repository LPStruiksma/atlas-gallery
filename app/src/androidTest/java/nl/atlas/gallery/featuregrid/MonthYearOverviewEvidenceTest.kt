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
import androidx.compose.ui.semantics.getOrNull
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
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
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
import kotlin.math.abs

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

    /** Real UiAutomation pixels, not Compose layout or an image-loaded callback. */
    private data class PaintProbe(
        val visibleIds: List<Long>,
        val matchedIds: List<Long>,
        val counts: List<Int>,
        val frame: String
    ) {
        fun diagnostic(): String =
            "frame=$frame visible=$visibleIds painted=$matchedIds matches=$counts"
    }

    /** Only the current LazyGrid's composed photo tiles can nominate expected colors. */
    private fun visiblePhotoIds(original: Boolean): List<Long> {
        val prefix = if (original) "atlas-ratio-media-" else "atlas-timeline-media-"
        val matcher = SemanticsMatcher("visible synthetic photo tile") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }
        return compose.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .filter { it.boundsInRoot.width >= 16f && it.boundsInRoot.height >= 16f }
            .mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)
                    ?.removePrefix(prefix)?.substringBefore('-')?.toLongOrNull()
            }.distinct().take(12)
    }

    /**
     * #780: a column count or drawWithContent callback can succeed while the
     * display still contains plain ground or undecoded surfaceVariant tiles.
     * The fixture is made of solid known HSV photos, so demand three distinct
     * visible photo colors in a *device* screenshot before saving the evidence.
     * This also works for the original-proportions layout.
     */
    private fun paintedTileColors(visibleIds: List<Long>): PaintProbe {
        if (visibleIds.size < 3) return PaintProbe(visibleIds, emptyList(), emptyList(), "no tiles")
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: return PaintProbe(visibleIds, emptyList(), emptyList(), "no screenshot")
        val pixels = if (shot.config == Bitmap.Config.HARDWARE)
            requireNotNull(shot.copy(Bitmap.Config.ARGB_8888, false))
        else shot
        try {
            val colors = visibleIds.map { id ->
                Color.HSVToColor(floatArrayOf((id * 47f) % 360f, .6f, .8f))
            }
            val hits = IntArray(colors.size)
            // Ignore system bars. At 28 columns a tile is only ~35 px wide;
            // a 6 px stride still yields many samples of its solid photo ink.
            for (y in pixels.height * 8 / 100 until pixels.height * 92 / 100 step 6) {
                for (x in 0 until pixels.width step 6) {
                    val pixel = pixels.getPixel(x, y)
                    for (i in colors.indices) {
                        val expected = colors[i]
                        if (abs(Color.red(pixel) - Color.red(expected)) <= 12 &&
                            abs(Color.green(pixel) - Color.green(expected)) <= 12 &&
                            abs(Color.blue(pixel) - Color.blue(expected)) <= 12) hits[i]++
                    }
                }
            }
            return PaintProbe(
                visibleIds,
                visibleIds.filterIndexed { i, _ -> hits[i] >= 8 },
                hits.toList(),
                "${pixels.width}x${pixels.height}"
            )
        } finally {
            if (pixels !== shot) pixels.recycle()
            shot.recycle()
        }
    }

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

        fun levelIsReady(level: GridDensity, bucket: TimelineBucket): Boolean =
            compose.onAllNodes(hasTestTag("atlas-timeline-grid")
                .and(SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription, "${level.columns} columns")))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                displayed.get()?.timeline?.headers?.isNotEmpty() == true &&
                displayed.get()?.timeline?.headers?.all {
                    it.header.stableKey.startsWith("header:${bucket.name}:")
                } == true &&
                drawnColumns.get() == level.columns

        fun awaitLevel(level: GridDensity, bucket: TimelineBucket) {
            compose.awaitThat("${level.columns} ${bucket.name} columns laid out", 30_000L) {
                levelIsReady(level, bucket)
            }
            assertTrue(displayed.get()!!.timeline.grouped)
            compose.waitForIdle()
        }

        fun savePaintedEvidence(
            filename: String, level: GridDensity? = null, bucket: TimelineBucket? = null
        ) {
            // Initial layout may take 30 s on a loaded emulator, but once
            // capture begins its *entire* paint/relook budget is only 10 s.
            if (level != null) awaitLevel(level, requireNotNull(bucket))
            else {
                compose.awaitThat("original-proportions layout", 30_000L) {
                    compose.exists(hasTestTag("atlas-ratio-gallery"))
                }
                compose.waitForIdle()
            }
            val startedAt = android.os.SystemClock.uptimeMillis()
            var lastDiagnostic = "no sampled frame yet"
            saveUiEvidence(filename) {
                val remaining = 10_000L - (android.os.SystemClock.uptimeMillis() - startedAt)
                check(remaining > 0L) {
                    "UI evidence $filename exceeded its 10 s paint deadline: $lastDiagnostic"
                }
                try {
                    compose.awaitThat("real painted photo pixels in $filename", remaining) {
                        val ready = if (level == null)
                            compose.exists(hasTestTag("atlas-ratio-gallery"))
                        else levelIsReady(level, requireNotNull(bucket))
                        if (!ready) {
                            lastDiagnostic = "layout not ready; columns=${drawnColumns.get()} " +
                                "clock=${compose.mainClock.currentTime}ms " +
                                "headers=${displayed.get()?.timeline?.headers?.size}"
                            false
                        } else {
                            val probe = paintedTileColors(visiblePhotoIds(original = level == null))
                            lastDiagnostic = "clock=${compose.mainClock.currentTime}ms " +
                                "columns=${drawnColumns.get()} ${probe.diagnostic()}"
                            probe.matchedIds.size >= 3
                        }
                    }
                } catch (timeout: AssertionError) {
                    throw AssertionError("UI evidence $filename: $lastDiagnostic", timeout)
                }
                android.util.Log.i("ATLAS_EVIDENCE", "$filename painted: $lastDiagnostic")
                compose.waitForIdle()
            }
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
            // #780: every capture waits for actual rendered synthetic tile
            // colors; semantics and a previous draw pass alone do not suffice.
            savePaintedEvidence("zoom-16-$theme-7.png", GridDensity.SMALL, TimelineBucket.MONTH)

            chooseSmaller()
            savePaintedEvidence("zoom-16-$theme-12-month.png", GridDensity.MONTH, TimelineBucket.MONTH)

            chooseSmaller()
            assertEquals(28, GridDensity.YEAR.columns)
            savePaintedEvidence("zoom-16-$theme-28-year.png", GridDensity.YEAR, TimelineBucket.YEAR)

            val actions = compose.onNodeWithTag("atlas-timeline-grid")
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            val original = actions.first { it.label == ORIGINAL_PROPORTIONS_ACTION }
            compose.runOnIdle { assertTrue(original.action()) }
            savePaintedEvidence("zoom-16-$theme-original.png")
        }
    }
}
