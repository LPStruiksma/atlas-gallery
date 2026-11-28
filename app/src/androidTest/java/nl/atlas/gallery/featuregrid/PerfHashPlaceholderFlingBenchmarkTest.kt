package nl.atlas.gallery.featuregrid

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.GalleryRepository
import nl.atlas.gallery.LocalGalleryPublicationEpoch
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.clearSystemAnrDialogs
import nl.atlas.gallery.featuremedia.MicroThumbnailStore
import nl.atlas.gallery.featuremedia.MicroThumbnailWarmer
import nl.atlas.gallery.featuremedia.MicroThumbnails
import nl.atlas.gallery.featuremedia.ThumbnailPipeline
import nl.atlas.gallery.featuremedia.TileImageProbe
import nl.atlas.gallery.featuremedia.microThumbnailDiskKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * PERF-HASH (#592): do tiny in-memory placeholders beat today's micro-thumbnail
 * tier during fast flings? Test-only measurement; no app/src/main change.
 *
 * Journey: the PERF-A2A (#547) fling shape (five real-uptime touchscreen
 * throws, down, down, up, up, down, from 76% to 30% of the grid height) at 4
 * and 7 columns, plus two constant-speed flings (8k px/s down, 30k px/s up,
 * as in the #555 benchmark, because an emulator with animations off ends a
 * real fling almost at once). Frames come from a re-arming
 * Choreographer.FrameCallback; "over budget" means an interval above 1.5x the
 * refresh period (PERF-A2A definition).
 *
 * Fixtures: 1,000 synthetic 1280x960 JPEGs (16 invented patterns) in a
 * throwaway MediaStore folder, deleted afterwards. PERF-A2A's 96x72 PNGs decode
 * in microseconds, so they would hide exactly the blank time this unit measures.
 *
 * The real [AtlasTimelineGallery] runs in a plain activity, with a test-owned
 * overlay Canvas above it that redraws every frame in every arm. Tiles are
 * sampled INSIDE that draw pass ([TileCoverageSampler]), in the overlay's own
 * coordinates (LazyGrid item offset plus the grid's start and top content
 * padding, clipped to the viewport). A tile is BLANK while its own layers draw
 * nothing ([TileImageProbe], the #555 probe: no micro bitmap and no sharp
 * painter) and COVERED only when the overlay actually drew a placeholder over
 * it in that frame. Before each placeholder arm, [verifyOverlayGeometry] paints
 * a marker over every visible tile and checks a real screenshot: marker inside
 * each tile, none in the 3 dp gutters. Throws a system dialog swallowed are
 * retried once and reported as invalid trials. Arms, per density:
 * - `today_cold`: empty micro store, memory cleared (first scroll after install);
 * - `today_warm`: [MicroThumbnailWarmer] filled the store, memory cleared;
 * - `avg_warm` / `hash_warm`: as `today_warm`, plus a test-owned overlay above
 *   the grid that draws the item's placeholder over every blank visible tile,
 *   every frame. Placeholders come from one primitive array per encoding,
 *   computed from the micro thumbnails the warm pass produced (the added
 *   encode time is a separate post-warm ESTIMATE; the excluded micro
 *   read/decode time is reported next to it). Its real draw
 *   cost lands in the measured frames; a hash is decoded on first sight into a
 *   small LRU, on the main thread, like a naive production bind would.
 * A cold store has no placeholders either (the same warm pass makes both), so
 * cold variants equal `today_cold` by construction and are not run.
 *
 * Opt-in: runs only when this class is the instrumentation target
 * (`-e class ...PerfHashPlaceholderFlingBenchmarkTest`, e.g. the stress lane)
 * or with `-e atlas_perf_hash_opt_in true`, so the full suite pays nothing.
 * One JSON line per arm and a final table go to logcat tags ATLAS_SCALE and
 * ATLAS_TESTKIT: counts and timings only, no pixels or URIs.
 */
@RunWith(AndroidJUnit4::class)
class PerfHashPlaceholderFlingBenchmarkTest {
    @get:Rule val display: org.junit.rules.TestRule = S24DisplayRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    private enum class Variant { TODAY, AVG, HASH }

    @Test fun placeholderThreeWayAtFourAndSevenColumns() {
        val targeted = args.getString("class").orEmpty().contains(javaClass.simpleName) ||
            args.getString("atlas_perf_hash_opt_in") == "true"
        assumeTrue("PERF-HASH runs only as an explicit target", targeted)
        assumeTrue("Synthetic MediaStore fixtures are emulator-only",
            (Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true)) &&
                (Build.FINGERPRINT.contains("generic", true) || Build.PRODUCT.contains("sdk", true)))
        val count = args.getString("atlas_perf_hash_count")?.toIntOrNull()?.coerceIn(200, 20_000) ?: 1_000
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val folder = "Pictures/ATLAS-PERF-HASH-592-${System.nanoTime()}/"
        val inserted = Collections.synchronizedList(ArrayList<android.net.Uri>(count))
        val prefs = context.applicationContext.getSharedPreferences("atlas_featuregrid_density", 0)
        val rows = ArrayList<JSONObject>()
        try {
            val insertStarted = SystemClock.elapsedRealtime()
            insertFixtures(folder, count, inserted)
            val items = loadFixtures(folder)
            assertTrue("expected $count fixtures, got ${items.size}", items.size == count)
            log(JSONObject().put("bench", "perf_hash_setup").put("photos", items.size)
                .put("insert_ms", SystemClock.elapsedRealtime() - insertStarted))
            MicroThumbnails.applyGrant(images = true, videos = true, selected = false)
            val store = checkNotNull(MicroThumbnails.store) { "micro-thumbnail store not installed" }
            ThumbnailPipeline.enabled = true

            for (density in listOf(GridDensity.MEDIUM, GridDensity.SMALL)) {
                store.clear()
                rows += runArm(Variant.TODAY, "cold", density, items, null, null)
            }

            store.clear()
            val warmStarted = SystemClock.elapsedRealtime()
            val warm = runBlocking { // Named: a trailing lambda here would bind to `gate` and skip the whole pass.
                MicroThumbnailWarmer.warm(context.contentResolver, items, yieldWhile = { false }) }
            val warmMs = SystemClock.elapsedRealtime() - warmStarted
            val avg = AverageColourTable(items.size)
            val hashes = ThumbHashTable(items.size)
            val encode = encodePlaceholders(store, items, avg, hashes)
            val warmJson = JSONObject().put("bench", "perf_hash_warm").put("photos", items.size)
                .put("warm_pass_ms", warmMs).put("warmer", warm.json()).put("store_bytes", store.scan())
                .put("placeholders", encode)
                .put("avg_table_bytes", avg.bytes).put("hash_table_bytes", hashes.bytes)
                .put("hash_mean_bytes", rounded(hashes.meanHashBytes()))
                .put("avg_bytes_at_250k", AverageColourTable(250_000).bytes)
                .put("hash_bytes_at_250k", 250_000L * ThumbHashTable.STRIDE)
            log(warmJson)
            assertTrue("warm pass stored nothing", avg.count() > items.size * 9 / 10)

            for (density in listOf(GridDensity.MEDIUM, GridDensity.SMALL)) {
                for (variant in Variant.entries) {
                    rows += runArm(variant, "warm", density, items, avg, hashes)
                }
            }
            logTable(rows, warmJson, warmMs)
        } catch (t: Throwable) {
            Log.e(TAG, "perf_hash failed", t)
            throw t
        } finally {
            ThumbnailPipeline.probe = null
            MicroThumbnails.store?.clear()
            MicroThumbnails.memory.clear()
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(VIEW_KEY_PREFIX) }.forEach { edit.remove(it) }
            edit.commit()
            deleteFixtures(inserted)
        }
    }

    /** Test-only addition to the warm pass: one placeholder of each kind per stored micro thumbnail. */
    private fun encodePlaceholders(
        store: MicroThumbnailStore, items: List<MediaRecord>,
        avg: AverageColourTable, hashes: ThumbHashTable
    ): JSONObject {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val avgNanos = ArrayList<Long>(items.size)
        val hashNanos = ArrayList<Long>(items.size)
        var readDecodeNanos = 0L
        items.forEachIndexed { index, media ->
            val key = microThumbnailDiskKey(media) ?: return@forEachIndexed
            val started = System.nanoTime()
            val bytes = store.read(key) ?: return@forEachIndexed
            val micro = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@forEachIndexed
            // The real warm pass already holds this bitmap; the read is not counted as added cost.
            readDecodeNanos += System.nanoTime() - started
            val a = System.nanoTime()
            avg.put(index, PerfHashCodec.averageColour2x2(micro))
            val b = System.nanoTime()
            hashes.put(index, PerfHashCodec.thumbHash(micro))
            val c = System.nanoTime()
            avgNanos += b - a
            hashNanos += c - b
            micro.recycle()
        }
        return JSONObject().put("items", avgNanos.size)
            .put("avg_encode", nanosStats(avgNanos))
            .put("hash_encode", nanosStats(hashNanos))
            .put("excluded_micro_read_decode_ms", rounded(readDecodeNanos / 1e6))
    }

    private fun runArm(
        variant: Variant, storeState: String, density: GridDensity, items: List<MediaRecord>,
        avg: AverageColourTable?, hashes: ThumbHashTable?
    ): JSONObject {
        val arm = variant.name.lowercase() + "_" + storeState
        ThumbnailPipeline.stats.reset()
        val probe = TileImageProbe()
        ThumbnailPipeline.probe = probe
        SingletonImageLoader.get(context).memoryCache?.clear()
        MicroThumbnails.memory.clear()
        val epoch = 1L
        val gridRef = AtomicReference<LazyGridState?>(null)
        val displayedRef = AtomicReference<TimelineDisplayedSlots?>(null)
        val ready = CountDownLatch(1)
        val decodeNanos = AtomicLong()
        val decodes = AtomicInteger()
        val decodedCache = object : LinkedHashMap<Int, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>?): Boolean =
                size > HASH_LRU_ENTRIES
        }
        // Placeholder availability for this arm, by index into [items].
        val hasPlaceholder: (Int) -> Boolean = when (variant) {
            Variant.TODAY -> { _ -> false }
            Variant.AVG -> { i -> avg?.has(i) == true }
            Variant.HASH -> { i -> hashes?.has(i) == true }
        }
        val sampler = TileCoverageSampler()
        // Geometry check: while true the overlay paints MARKER over every visible tile.
        val markAll = AtomicBoolean(false)
        val overlayOnScreen = AtomicReference<Offset?>(null)
        var metrics: android.util.DisplayMetrics? = null
        var refreshHz = 0f
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
        try {
            scenario.onActivity { activity ->
                metrics = activity.resources.displayMetrics
                activity.setContent {
                    val gridState = rememberLazyGridState()
                    gridRef.set(gridState)
                    // Every arm redraws (and samples in) the overlay node each frame,
                    // so TODAY pays the same ticker cost and differs only by drawing nothing.
                    val tick = remember { mutableLongStateOf(0L) }
                    LaunchedEffect(Unit) { while (true) withFrameNanos { tick.longValue = it } }
                    val startPadPx = with(LocalDensity.current) { GRID_START_PADDING.toPx() }
                    MaterialTheme {
                        CompositionLocalProvider(LocalGalleryPublicationEpoch provides epoch) {
                            Box(Modifier.fillMaxSize()) {
                                AtlasTimelineGallery(
                                    items = items, sort = TimelineSort.NEWEST, gridState = gridState,
                                    onOpen = {}, modifier = Modifier.fillMaxSize(),
                                    viewKey = VIEW_KEY_PREFIX + density.columns, catalogueEpoch = epoch,
                                    onDisplayedSlots = { if (it != null) { displayedRef.set(it); ready.countDown() } },
                                    entryDensity = density
                                )
                                ComposeCanvas(Modifier.fillMaxSize()
                                    .onGloballyPositioned { overlayOnScreen.set(it.positionOnScreen()) }) draw@{
                                    val frame = tick.longValue
                                    val slots = displayedRef.get()?.timeline?.slots ?: return@draw
                                    val sampling = sampler.beginFrame(frame)
                                    val marking = markAll.get()
                                    val info = gridState.layoutInfo
                                    val topPad = info.beforeContentPadding.toFloat()
                                    clipRect(0f, 0f, size.width, size.height) {
                                        for (cell in info.visibleItemsInfo) {
                                            val index = (slots.getOrNull(cell.index) as? TimelineSlot.Media)
                                                ?.mediaIndex ?: continue
                                            val media = items.getOrNull(index) ?: continue
                                            // LazyGrid item offsets exclude the content padding
                                            // (AtlasTimelineGallery adds it back the same way).
                                            val left = cell.offset.x + startPadPx
                                            val top = cell.offset.y + topPad
                                            val w = cell.size.width.toFloat()
                                            val h = cell.size.height.toFloat()
                                            if (left >= size.width || top >= size.height || left + w <= 0f || top + h <= 0f) continue
                                            if (marking) {
                                                drawRect(androidx.compose.ui.graphics.Color(MARKER), Offset(left, top), Size(w, h))
                                                continue
                                            }
                                            val blank = !probe.showsImage(media.uri)
                                            var drawn = false
                                            if (blank && hasPlaceholder(index)) {
                                                if (variant == Variant.AVG) {
                                                    val packed = avg!!.get(index)
                                                    for (q in 0 until 4) {
                                                        drawRect(androidx.compose.ui.graphics.Color(PerfHashCodec.quadrantArgb(packed, q)),
                                                            topLeft = Offset(left + (q % 2) * w / 2f, top + (q / 2) * h / 2f),
                                                            size = Size(w / 2f, h / 2f))
                                                    }
                                                } else {
                                                    val image = decodedCache[index] ?: run {
                                                        val started = System.nanoTime()
                                                        val decoded = hashes!!.decode(index).asImageBitmap()
                                                        decodeNanos.addAndGet(System.nanoTime() - started)
                                                        decodes.incrementAndGet()
                                                        decodedCache[index] = decoded
                                                        decoded
                                                    }
                                                    // Centre crop to the square cell, as the tile's ContentScale.Crop does.
                                                    val side = min(image.width, image.height)
                                                    drawImage(image,
                                                        srcOffset = IntOffset((image.width - side) / 2, (image.height - side) / 2),
                                                        srcSize = IntSize(side, side),
                                                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                                        dstSize = IntSize(cell.size.width, cell.size.height),
                                                        filterQuality = FilterQuality.Low)
                                                }
                                                drawn = true
                                            }
                                            if (sampling) sampler.tile(index, blank, covered = drawn)
                                        }
                                    }
                                    if (sampling) sampler.endFrame()
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("timeline never planned", ready.await(60, TimeUnit.SECONDS))
            awaitChoreographerFrames(120) // first screen settles (~2 s of real frames); not measured
            scenario.onActivity { refreshHz = it.window.decorView.display?.refreshRate ?: 0f }
            val screen = checkNotNull(metrics)
            val gridState = checkNotNull(gridRef.get())
            val budgetNs = 1e9 / (if (refreshHz > 0f) refreshHz else 60f)
            val geometry = if (variant == Variant.TODAY) null
                else verifyOverlayGeometry(markAll, overlayOnScreen, gridState, displayedRef)
            val intervalsAll = ArrayList<Long>()
            val pooled = ArrayList<TileCoverageSampler.Snapshot>()
            var anrCleared = 0
            val phases = JSONObject()
            fun phase(name: String, extra: JSONObject = JSONObject(), body: (JSONObject) -> Unit) {
                anrCleared += clearSystemAnrDialogs()
                val intervals = FrameIntervalRecorder()
                var gaps: List<Long> = emptyList()
                var tiles: TileCoverageSampler.Snapshot? = null
                try {
                    intervals.start()
                    sampler.start()
                    body(extra)
                    awaitChoreographerFrames(60) // settle (~1 s of real frames): does the screen fill in?
                } finally {
                    // Disarm both on the main thread whatever happened in the body.
                    tiles = sampler.stop()
                    gaps = intervals.stop()
                    intervals.close()
                }
                val snapshot = checkNotNull(tiles)
                intervalsAll += gaps
                pooled += snapshot
                phases.put(name, coverageJson(snapshot, gaps, budgetNs).also { json ->
                    extra.keys().forEach { json.put(it, extra.get(it)) }
                })
            }
            val x = screen.widthPixels / 2f
            val top = screen.heightPixels * .30f
            val bottom = screen.heightPixels * .76f
            var invalidThrows = 0
            phase("throws_a2a") { extra ->
                val trials = JSONArray()
                for (down in listOf(true, true, false, false, true)) {
                    var retried = false
                    var moved = false
                    for (attempt in 0..1) {
                        if (attempt == 1) {
                            retried = true
                            anrCleared += clearSystemAnrDialogs()
                        }
                        val before = gridPosition(gridState)
                        injectFling(x, if (down) bottom else top, if (down) top else bottom)
                        awaitChoreographerFrames(60) // fill-in after release (~1 s of real frames) is measured
                        moved = gridPosition(gridState) != before
                        if (moved) break
                    }
                    if (!moved) invalidThrows++
                    trials.put(JSONObject().put("direction", if (down) "down_grid" else "up_grid")
                        .put("moved", moved).put("retried", retried))
                }
                extra.put("trials", trials).put("invalid_throws", invalidThrows)
            }
            var flingsMoved = 0
            phase("fling_8k_down") {
                val before = gridPosition(gridState)
                scrollAtSpeed(scope, gridState, 8_000f, 3_000)
                if (gridPosition(gridState) != before) flingsMoved++
            }
            phase("fling_30k_up") {
                val before = gridPosition(gridState)
                scrollAtSpeed(scope, gridState, -30_000f, 1_500)
                if (gridPosition(gridState) != before) flingsMoved++
            }
            // The journey must really happen; a couple of throws swallowed by a
            // system dialog are reported as invalid trials, not hidden.
            val valid = invalidThrows == 0 && flingsMoved == 2
            assertTrue("$arm: journey did not complete (invalid throws $invalidThrows, flings moved $flingsMoved)",
                invalidThrows <= 2 && flingsMoved == 2)
            val result = JSONObject().put("bench", "perf_hash_arm").put("arm", arm)
                .put("columns", density.columns).put("photos", items.size)
                .put("screen", "${screen.widthPixels}x${screen.heightPixels}@${screen.densityDpi}")
                .put("refresh_hz", rounded(refreshHz))
                .put("valid", valid).put("invalid_throws", invalidThrows).put("flings_moved", flingsMoved)
                .put("geometry_check", geometry ?: JSONObject.NULL)
                .put("all", coverageJson(merge(pooled), intervalsAll, budgetNs)).put("phases", phases)
                .put("micro", ThumbnailPipeline.stats.json())
                .put("hash_decodes", decodes.get())
                .put("hash_decode_mean_ms", if (decodes.get() == 0) 0.0
                    else rounded(decodeNanos.get() / 1e6 / decodes.get()))
                .put("hash_lru_bytes", if (variant == Variant.HASH) onMain { decodedCache.values.sumOf {
                    it.width.toLong() * it.height * 4 } } else 0L)
                .put("system_anr_dialogs_cleared", anrCleared)
            log(result)
            return result
        } finally {
            scope.cancel()
            scenario.close()
        }
    }

    private fun gridPosition(state: LazyGridState): Pair<Int, Int> =
        onMain { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }

    /**
     * Review finding 1: proves the overlay lands on the tiles. Paints MARKER
     * over every visible tile, takes a real screenshot and checks, for fully
     * visible tiles, that the tile's centre and just inside its left and top
     * edges are MARKER while the 3 dp gutter just outside them is not.
     */
    private fun verifyOverlayGeometry(
        markAll: AtomicBoolean, overlayOnScreen: AtomicReference<Offset?>,
        state: LazyGridState, displayed: AtomicReference<TimelineDisplayedSlots?>
    ): JSONObject {
        markAll.set(true)
        try {
            awaitChoreographerFrames(10)
            val origin = checkNotNull(overlayOnScreen.get()) { "overlay never positioned" }
            val startPadPx = 3f * context.resources.displayMetrics.density
            val cells = onMain {
                val info = state.layoutInfo
                val slots = displayed.get()?.timeline?.slots
                info.visibleItemsInfo.filter { slots?.getOrNull(it.index) is TimelineSlot.Media }.map {
                    floatArrayOf(it.offset.x + startPadPx, (it.offset.y + info.beforeContentPadding).toFloat(),
                        it.size.width.toFloat(), it.size.height.toFloat())
                } to info.viewportSize
            }
            val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "no screenshot" }
            try {
                val inset = 3
                val gutter = (startPadPx / 2f).roundToInt().coerceAtLeast(2)
                var checked = 0
                val failures = ArrayList<String>()
                for (c in cells.first) {
                    val left = origin.x + c[0]; val top = origin.y + c[1]
                    val right = left + c[2]; val bottom = top + c[3]
                    // Fully visible tiles with a gutter on screen to the left and above.
                    if (c[0] < gutter + 1 || c[1] < gutter + 1 || c[0] + c[2] > cells.second.width ||
                        c[1] + c[3] > cells.second.height || right >= shot.width || bottom >= shot.height) continue
                    if (checked >= 12) break
                    val cx = ((left + right) / 2f).roundToInt(); val cy = ((top + bottom) / 2f).roundToInt()
                    val probes = listOf(
                        "centre" to Triple(cx, cy, true),
                        "inside_left" to Triple((left + inset).roundToInt(), cy, true),
                        "gutter_left" to Triple((left - gutter).roundToInt(), cy, false),
                        "inside_top" to Triple(cx, (top + inset).roundToInt(), true),
                        "gutter_top" to Triple(cx, (top - gutter).roundToInt(), false))
                    for ((name, p) in probes) {
                        val isMarker = isMarker(shot.getPixel(p.first, p.second))
                        if (isMarker != p.third) failures += "$name@${p.first},${p.second}"
                    }
                    checked++
                }
                val json = JSONObject().put("tiles_checked", checked).put("failures", failures.size)
                    .put("first_failures", JSONArray(failures.take(4)))
                assertTrue("overlay geometry: $json", checked >= 4 && failures.isEmpty())
                return json
            } finally {
                shot.recycle()
            }
        } finally {
            markAll.set(false)
            awaitChoreographerFrames(5)
        }
    }

    private fun isMarker(pixel: Int): Boolean =
        abs(Color.red(pixel) - Color.red(MARKER)) < 40 && abs(Color.green(pixel) - Color.green(MARKER)) < 40 &&
            abs(Color.blue(pixel) - Color.blue(MARKER)) < 40

    private fun merge(parts: List<TileCoverageSampler.Snapshot>) = TileCoverageSampler.Snapshot(
        parts.sumOf { it.frames }, parts.sumOf { it.tileFrames }, parts.sumOf { it.blankTileFrames },
        parts.sumOf { it.coveredTileFrames }, parts.sumOf { it.framesWithBlank },
        parts.sumOf { it.framesWithUncovered }, parts.flatMap { it.blankEpisodes },
        parts.flatMap { it.uncoveredEpisodes })

    /** Drawn frames from the overlay's sampler; intervals from the Choreographer recorder. */
    private fun coverageJson(t: TileCoverageSampler.Snapshot, intervals: List<Long>, budgetNs: Double): JSONObject {
        val n = max(1, t.frames)
        val skip1 = budgetNs * 1.5
        val over = intervals.count { it > skip1 }
        return JSONObject()
            .put("drawn_frames", t.frames)
            .put("tile_frames", t.tileFrames)
            .put("blank_tile_frames", t.blankTileFrames)
            .put("blank_tiles_per_frame", rounded(t.blankTileFrames.toDouble() / n))
            .put("blank_frame_share_pct", rounded(100.0 * t.framesWithBlank / n))
            .put("blank_tile_share_pct", if (t.tileFrames == 0L) 0.0
                else rounded(100.0 * t.blankTileFrames / t.tileFrames))
            .put("blank_episodes", t.blankEpisodes.size)
            .put("blank_episode_ms", nanosStats(t.blankEpisodes))
            .put("covered_tile_frames", t.coveredTileFrames)
            .put("uncovered_tile_frames", t.uncoveredTileFrames)
            .put("uncovered_tiles_per_frame", rounded(t.uncoveredTileFrames.toDouble() / n))
            .put("uncovered_frame_share_pct", rounded(100.0 * t.framesWithUncovered / n))
            .put("uncovered_episode_ms", nanosStats(t.uncoveredEpisodes))
            .put("interval_samples", intervals.size)
            .put("interval_p50_ms", rounded(percentile(intervals, 50) / 1e6))
            .put("interval_p95_ms", rounded(percentile(intervals, 95) / 1e6))
            .put("over_budget_threshold_ms", rounded(skip1 / 1e6))
            .put("over_budget_intervals", over)
            .put("over_budget_pct", if (intervals.isEmpty()) -1.0 else rounded(100.0 * over / intervals.size))
    }

    /** One row per arm: the three-way table the issue asks for, plus the warm-pass cost. */
    private fun logTable(rows: List<JSONObject>, warm: JSONObject, warmMs: Long) {
        val placeholders = warm.getJSONObject("placeholders")
        val table = JSONArray()
        for (row in rows) {
            val all = row.getJSONObject("all")
            val arm = row.getString("arm")
            val memory = when {
                arm.startsWith("avg") -> warm.getLong("avg_table_bytes")
                arm.startsWith("hash") -> warm.getLong("hash_table_bytes") + row.getLong("hash_lru_bytes")
                else -> 0L
            }
            val addedWarmMs = when {
                arm.startsWith("avg") -> placeholders.getJSONObject("avg_encode").getDouble("total_ms")
                arm.startsWith("hash") -> placeholders.getJSONObject("hash_encode").getDouble("total_ms")
                else -> 0.0
            }
            table.put(JSONObject().put("arm", arm).put("columns", row.getInt("columns"))
                .put("valid", row.getBoolean("valid")).put("invalid_throws", row.getInt("invalid_throws"))
                .put("blank_tiles_per_frame", all.getDouble("blank_tiles_per_frame"))
                .put("uncovered_tiles_per_frame", all.getDouble("uncovered_tiles_per_frame"))
                .put("uncovered_frame_share_pct", all.getDouble("uncovered_frame_share_pct"))
                .put("blank_episode_p95_ms", all.getJSONObject("blank_episode_ms").getDouble("p95_ms"))
                .put("uncovered_episode_p95_ms", all.getJSONObject("uncovered_episode_ms").getDouble("p95_ms"))
                .put("over_budget_pct", all.getDouble("over_budget_pct"))
                .put("placeholder_memory_bytes", memory)
                .put("added_warm_pass_estimate_ms", addedWarmMs)
                .put("warm_pass_ms", if (arm.endsWith("cold")) 0L else warmMs))
        }
        log(JSONObject().put("bench", "perf_hash_table").put("rows", table))
    }

    private fun log(json: JSONObject) {
        val text = "PERF-HASH " + json
        // logcat drops the tail of a line over ~4 KB; split long lines in order.
        text.chunked(3_500).forEachIndexed { i, part ->
            val line = if (i == 0) part else "PERF-HASH+ $part"
            Log.i(TAG, line)
            Log.i(KIT_TAG, line)
        }
    }

    // --- Helpers copied from the PERF-A2A and #555 benchmarks (those files stay untouched). ---

    private fun injectFling(x: Float, startY: Float, endY: Float) {
        val automation = instrumentation.uiAutomation
        val downAt = SystemClock.uptimeMillis()
        val scheduled = Executors.newSingleThreadScheduledExecutor()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        try {
            for (step in 0..13) {
                scheduled.schedule(Runnable {
                    try {
                        if (failure.get() == null) {
                            val action = when (step) {
                                0 -> MotionEvent.ACTION_DOWN
                                13 -> MotionEvent.ACTION_UP
                                else -> MotionEvent.ACTION_MOVE
                            }
                            val y = when (step) {
                                0 -> startY
                                13 -> endY
                                else -> startY + (endY - startY) * step / 12f
                            }
                            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
                            try {
                                event.source = InputDevice.SOURCE_TOUCHSCREEN
                                check(automation.injectInputEvent(event, true)) {
                                    "Real touchscreen injection failed at step $step"
                                }
                            } finally { event.recycle() }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        if (step == 13) finished.countDown()
                    }
                }, step * 16L, TimeUnit.MILLISECONDS)
            }
            check(finished.await(10, TimeUnit.SECONDS)) { "Scheduled real-time gesture never finished" }
            failure.get()?.let { throw it }
        } finally {
            scheduled.shutdownNow()
        }
    }

    private fun scrollAtSpeed(scope: CoroutineScope, state: LazyGridState, pxPerSecond: Float, millis: Long) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            scope.launch {
                try {
                    state.scroll {
                        var last = withFrameNanos { it }
                        val end = last + millis * 1_000_000L
                        while (last < end) {
                            val now = withFrameNanos { it }
                            scrollBy(pxPerSecond * (now - last) / 1e9f)
                            last = now
                        }
                    }
                } finally { done.countDown() }
            }
        }
        assertTrue(done.await(millis + 30_000, TimeUnit.MILLISECONDS))
    }

    private fun loadFixtures(folder: String): List<MediaRecord> {
        var last: Throwable? = null
        repeat(5) {
            try {
                return GalleryRepository(context).load()
                    .filter { it.relativePath == folder }
                    .sortedByDescending { it.takenAtMillis }
            } catch (t: Exception) {
                last = t
                awaitChoreographerFrames(180) // provider settles its generation (~3 s)
            }
        }
        throw checkNotNull(last)
    }

    private fun deleteFixtures(uris: List<android.net.Uri>) {
        val ids = synchronized(uris) { uris.mapNotNull { it.lastPathSegment?.toLongOrNull() } }
        ids.chunked(400).forEach { chunk ->
            runCatching {
                context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})", null)
            }.onFailure { Log.w(TAG, "fixture cleanup batch failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun insertFixtures(folder: String, count: Int, inserted: MutableList<android.net.Uri>) {
        val variants = (0 until 16).map { syntheticJpeg(it) }
        val now = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(4)
        val failures = AtomicInteger()
        try {
            val futures = (0 until count).map { i ->
                pool.submit {
                    runCatching { inserted += insertOne(folder, i, now - i * 3_600_000L, variants[i % 16]) }
                        .onFailure { failures.incrementAndGet() }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        check(failures.get() == 0) { "${failures.get()} synthetic inserts failed" }
    }

    private fun insertOne(folder: String, i: Int, taken: Long, bytes: ByteArray): android.net.Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PERF_HASH_$i.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
        }, null, null)
        return uri
    }

    private fun syntheticJpeg(seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = Random(seed)
        canvas.drawColor(Color.HSVToColor(floatArrayOf(seed * 22.5f, 0.5f, 0.8f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(60) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(random.nextFloat() * 1280, random.nextFloat() * 960,
                20f + random.nextFloat() * 160f, paint)
        }
        return ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it))
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private companion object {
        const val TAG = "ATLAS_SCALE"
        const val KIT_TAG = "ATLAS_TESTKIT"
        const val VIEW_KEY_PREFIX = "perf-hash-592-"
        const val HASH_LRU_ENTRIES = 256
        /** Opaque magenta: absent from the synthetic fixtures' 16 base hues at full saturation. */
        const val MARKER = 0xFFFF00FF.toInt()
        val GRID_START_PADDING = 3.dp // AtlasTimelineGallery contentPadding start

        fun rounded(n: Number): Double = kotlin.math.round(n.toDouble() * 100.0) / 100.0

        fun percentile(values: List<Long>, p: Int): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * p + 50) / 100].toDouble()
        }

        fun nanosStats(values: List<Long>): JSONObject = JSONObject()
            .put("n", values.size)
            .put("p50_ms", rounded(percentile(values, 50) / 1e6))
            .put("p95_ms", rounded(percentile(values, 95) / 1e6))
            .put("max_ms", rounded((values.maxOrNull() ?: 0L) / 1e6))
            .put("total_ms", rounded(values.sum() / 1e6))
    }
}
