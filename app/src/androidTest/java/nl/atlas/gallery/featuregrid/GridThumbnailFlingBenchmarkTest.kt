package nl.atlas.gallery.featuregrid

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import nl.atlas.gallery.GalleryRepository
import nl.atlas.gallery.LocalGalleryPublicationEpoch
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.clearSystemAnrDialogs
import nl.atlas.gallery.visibleSystemAnrRoot
import nl.atlas.gallery.featuremedia.MicroThumbnailWarmer
import nl.atlas.gallery.featuremedia.MicroThumbnails
import nl.atlas.gallery.featuremedia.ThumbnailPipeline
import nl.atlas.gallery.featuremedia.TileImageProbe
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * #555 opt-in macro benchmark: grey tiles while flinging and scrubbing.
 *
 * Off by default (one short emulator test per PR, #509). Runs only with
 * `-e atlas_thumbnail_fling_opt_in true` on an emulator, on the Galaxy S24+
 * screen (1080x2340 px, 450 dpi, [S24DisplayRule]). Inserts N synthetic
 * 1280x960 JPEGs (default 20,000) into a throwaway MediaStore folder, shows
 * the real Photos timeline in a plain activity (real frame clock, not the
 * Compose test clock), and measures in the same run:
 *
 * - `before`: the old path ([ThumbnailPipeline.enabled] = false: full decode
 *   of the original per tile, no micro tier, no prefetch);
 * - `after_cold`: the #555 pipeline with an empty micro-thumbnail store
 *   (first scroll after install, before the background warmer ran);
 * - `after_warm`: the #555 pipeline after [MicroThumbnailWarmer] filled the
 *   store (steady state), memory cache cleared.
 *
 * `before` and `after_warm` repeat `atlas_thumbnail_fling_reps` times
 * (default 3), interleaved, so run-to-run noise is visible.
 * One mid-fling screenshot per arm (8k px/s phase, full 1080x2340, JPEG) goes
 * to logcat tag ATLAS_SHOT as base64 lines (synthetic pixels only).
 *
 * Phases per arm: a constant-speed fling-like scroll at two speeds (a real
 * fling ends instantly on CI because the emulator has animations off) and
 * a real-time touch drag of the fast-scroll handle (scrub). For every frame
 * it records the frame interval and whether any visible photo tile showed
 * no pixels (probe in MediaPreview: Coil painter null). One JSON line per arm
 * goes to logcat tag ATLAS_SCALE. Only counts and timings; no pixels or URIs.
 */
@RunWith(AndroidJUnit4::class)
class GridThumbnailFlingBenchmarkTest {
    @get:Rule val display: org.junit.rules.TestRule = S24DisplayRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun flingAndScrubPlaceholderFramesBeforeAndAfter() {
        assumeTrue("Explicit atlas_thumbnail_fling_opt_in=true required",
            args.getString("atlas_thumbnail_fling_opt_in") == "true")
        assumeTrue("Synthetic MediaStore fixtures are emulator-only",
            (Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true)) &&
                (Build.FINGERPRINT.contains("generic", true) || Build.PRODUCT.contains("sdk", true)))
        val count = args.getString("atlas_thumbnail_fling_count")?.toIntOrNull() ?: 20_000
        val reps = args.getString("atlas_thumbnail_fling_reps")?.toIntOrNull()?.coerceIn(1, 5) ?: 3
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val folder = "Pictures/ATLAS-Fling-Bench-${System.nanoTime()}/"
        val inserted = java.util.Collections.synchronizedList(ArrayList<android.net.Uri>(count))
        try {
            val insertMs = measure { insertFixtures(folder, count, inserted) }
            Log.i(TAG, JSONObject().put("bench", "thumbnail_fling_setup")
                .put("photos", inserted.size).put("insert_ms", insertMs).toString())
            val items = loadFixtures(folder)
            assertTrue("expected $count fixtures, got ${items.size}", items.size == count)
            MicroThumbnails.applyGrant(images = true, videos = true, selected = false)
            val store = checkNotNull(MicroThumbnails.store) { "micro-thumbnail store not installed" }
            store.clear()
            Log.i(TAG, runArm("before", 1, items).toString())
            Log.i(TAG, runArm("after_cold", 1, items).toString())
            // Steady state: the background warmer fills the store newest-first.
            store.clear()
            ThumbnailPipeline.enabled = true
            val warm = runBlocking { MicroThumbnailWarmer.warm(context.contentResolver, items) { false } }
            Log.i(TAG, JSONObject().put("bench", "thumbnail_fling_warm").put("photos", items.size)
                .put("warmer", warm.json()).put("store_bytes", store.scan())
                .put("quiet_read_decode", quietMicroReads(store, items)).toString())
            for (rep in 1..reps) {
                if (rep > 1) Log.i(TAG, runArm("before", rep, items).toString())
                Log.i(TAG, runArm("after_warm", rep, items).toString())
            }
        } catch (t: Throwable) {
            // Keep the real cause visible even if cleanup fails afterwards.
            Log.e(TAG, "thumbnail_fling failed", t)
            throw t
        } finally {
            ThumbnailPipeline.enabled = true
            ThumbnailPipeline.probe = null
            MicroThumbnails.store?.clear()
            deleteFixtures(inserted)
        }
    }

    /**
     * The provider may still bump its generation while it settles the bulk
     * insert, which makes a scan refuse a torn snapshot; retry a few times.
     */
    private fun loadFixtures(folder: String): List<MediaRecord> {
        var last: Throwable? = null
        repeat(5) {
            try {
                return GalleryRepository(context).load()
                    .filter { it.relativePath == folder }
                    .sortedByDescending { it.takenAtMillis }
            } catch (t: Exception) {
                last = t
                SystemClock.sleep(3_000)
            }
        }
        throw checkNotNull(last)
    }

    /** Row-id batches: one 20k-row delete overflows MediaProvider's CursorWindow. */
    private fun deleteFixtures(uris: List<android.net.Uri>) {
        val ids = synchronized(uris) { uris.mapNotNull { it.lastPathSegment?.toLongOrNull() } }
        ids.chunked(400).forEach { chunk ->
            runCatching {
                context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})", null)
            }.onFailure { Log.w(TAG, "fixture cleanup batch failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun runArm(arm: String, rep: Int, items: List<MediaRecord>): JSONObject {
        ThumbnailPipeline.enabled = arm != "before"
        ThumbnailPipeline.stats.reset()
        val probe = TileImageProbe()
        ThumbnailPipeline.probe = probe
        SingletonImageLoader.get(context).memoryCache?.clear()
        MicroThumbnails.memory.clear()
        val epoch = 1L
        lateinit var gridState: LazyGridState
        var metrics: android.util.DisplayMetrics? = null
        var displayed: TimelineDisplayedSlots? = null
        val ready = CountDownLatch(1)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        // AndroidUiDispatcher.Main carries the Choreographer frame clock that
        // withFrameNanos needs; plain Dispatchers.Main does not.
        val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
        try {
            scenario.onActivity { activity ->
                metrics = activity.resources.displayMetrics
                activity.setContent {
                    gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    MaterialTheme {
                        CompositionLocalProvider(LocalGalleryPublicationEpoch provides epoch) {
                            AtlasTimelineGallery(
                                items = items, sort = TimelineSort.NEWEST, gridState = gridState,
                                onOpen = {}, modifier = Modifier.fillMaxSize(),
                                viewKey = "fling-bench-$arm", catalogueEpoch = epoch,
                                onDisplayedSlots = { if (it != null) { displayed = it; ready.countDown() } },
                                fastScrollAlwaysShown = true
                            )
                        }
                    }
                }
            }
            assertTrue("timeline never planned", ready.await(60, TimeUnit.SECONDS))
            SystemClock.sleep(2_000) // first screen settles; not measured
            val screen = checkNotNull(metrics)
            val result = JSONObject().put("bench", "thumbnail_fling").put("arm", arm)
                .put("rep", rep).put("photos", items.size)
                .put("screen", "${screen.widthPixels}x${screen.heightPixels}@${screen.densityDpi}")
            var anrCleared = 0
            fun phase(name: String, body: () -> Unit) {
                // #610: a slow emulator can show "Quickstep isn't responding" over
                // the app, which swallows the injected drag; press Wait first.
                anrCleared += clearSystemAnrDialogs()
                val stats = recordFrames(gridState, { displayed }, items, probe, body)
                result.put(name, stats)
            }
            // Ordinary scroll (3k px/s), a quick flick (8k) and a hard fling back up (30k).
            phase("scroll_3k") { scrollAtSpeed(scope, gridState, 3_000f, 5_000) }
            phase("fling_8k") {
                val shot = Thread {
                    SystemClock.sleep(1_500)
                    screenshot("$arm-$rep")
                }
                shot.start()
                scrollAtSpeed(scope, gridState, 8_000f, 4_000)
                shot.join()
            }
            phase("fling_30k") { scrollAtSpeed(scope, gridState, -30_000f, 2_000) }
            phase("scrub") { dragScrubHandle(gridState, screen) }
            result.put("micro", ThumbnailPipeline.stats.json())
            result.put("system_anr_dialogs_cleared", anrCleared)
            return result
        } finally {
            scope.cancel()
            scenario.close()
        }
    }

    /** Constant-speed scroll on the real frame clock; blocks until done. */
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

    /**
     * Real-time finger drag of the fast-scroll handle, top to bottom over 4 s,
     * a 0.5 s hold, then release (injected touch events, like a user).
     */
    private fun dragScrubHandle(state: LazyGridState, metrics: android.util.DisplayMetrics) {
        // The handle's touch track spans the full height at the right edge of
        // the full-screen gallery.
        val x = metrics.widthPixels - 10 * metrics.density
        val heightPx = metrics.heightPixels
        val top = heightPx * 0.12f
        val bottom = heightPx * 0.88f
        val downAt = SystemClock.uptimeMillis()
        val startIndex = state.firstVisibleItemIndex
        inject(MotionEvent.ACTION_DOWN, downAt, downAt, x, top)
        val steps = 240 // 4 s at ~60 Hz
        for (i in 1..steps) {
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_MOVE, downAt, SystemClock.uptimeMillis(), x,
                top + (bottom - top) * i / steps)
        }
        repeat(30) {
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_MOVE, downAt, SystemClock.uptimeMillis(), x, bottom)
        }
        inject(MotionEvent.ACTION_UP, downAt, SystemClock.uptimeMillis(), x, bottom)
        check(state.firstVisibleItemIndex != startIndex) {
            "scrub drag did not move the grid" + if (visibleSystemAnrRoot() != null) " (system ANR dialog on screen)" else ""
        }
    }

    /**
     * Service time of one micro thumbnail on an idle system: file read plus
     * RGB_565 decode, 500 sequential reads, no grid on screen. Separates the
     * cost of the tier itself from queueing behind a busy emulator.
     */
    private fun quietMicroReads(store: nl.atlas.gallery.featuremedia.MicroThumbnailStore,
                                items: List<MediaRecord>): JSONObject {
        val options = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val times = items.take(500).mapNotNull { media ->
            val key = nl.atlas.gallery.featuremedia.microThumbnailDiskKey(media) ?: return@mapNotNull null
            val started = System.nanoTime()
            val bytes = store.read(key) ?: return@mapNotNull null
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@mapNotNull null
            val nanos = System.nanoTime() - started
            bitmap.recycle()
            nanos
        }.sorted()
        fun pct(p: Int) = if (times.isEmpty()) 0.0
            else Math.round(times[((times.size * p + 99) / 100 - 1).coerceIn(0, times.lastIndex)] / 1e4) / 100.0
        return JSONObject().put("reads", times.size).put("p50_ms", pct(50)).put("p95_ms", pct(95))
    }

    /** Full-resolution screenshot as JPEG base64 lines on logcat (synthetic fixtures only). */
    private fun screenshot(label: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
            it.toByteArray()
        }
        val size = "${bitmap.width}x${bitmap.height}"
        bitmap.recycle()
        val text = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val lines = text.chunked(3_000)
        Log.i(SHOT, "begin $label $size ${lines.size}")
        lines.forEachIndexed { i, line -> Log.i(SHOT, "$label $i $line") }
        Log.i(SHOT, "end $label")
    }

    private fun inject(action: Int, downAt: Long, at: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    /**
     * Runs [body] (blocking, off the main thread) plus 1 s of settling while a
     * Choreographer callback samples every frame: interval and whether any
     * visible photo tile had no pixels.
     */
    private fun recordFrames(
        state: LazyGridState,
        displayed: () -> TimelineDisplayedSlots?,
        items: List<MediaRecord>,
        probe: TileImageProbe,
        body: () -> Unit
    ): JSONObject {
        val intervals = ArrayList<Long>(2048)
        val frames = AtomicInteger()
        val placeholderFrames = AtomicInteger()
        var greyTileFrames = 0L
        // Frames after the gesture ended (1 s settle): does the screen fill in?
        val gestureDone = java.util.concurrent.atomic.AtomicBoolean(false)
        var settleFrames = 0
        var settlePlaceholderFrames = 0
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val stopped = CountDownLatch(1)
        var lastNanos = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastNanos != 0L) intervals.add(frameTimeNanos - lastNanos)
                lastNanos = frameTimeNanos
                val slots = displayed()?.timeline?.slots
                var grey = 0
                if (slots != null) {
                    for (cell in state.layoutInfo.visibleItemsInfo) {
                        val media = (slots.getOrNull(cell.index) as? TimelineSlot.Media)
                            ?.let { items.getOrNull(it.mediaIndex) } ?: continue
                        if (!probe.showsImage(media.uri)) grey++
                    }
                }
                frames.incrementAndGet()
                if (grey > 0) placeholderFrames.incrementAndGet()
                if (gestureDone.get()) {
                    settleFrames++
                    if (grey > 0) settlePlaceholderFrames++
                }
                greyTileFrames += grey
                if (running.get()) Choreographer.getInstance().postFrameCallback(this)
                else stopped.countDown()
            }
        }
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
        body()
        gestureDone.set(true)
        SystemClock.sleep(1_000)
        running.set(false)
        stopped.await(5, TimeUnit.SECONDS)
        val sorted = intervals.sorted()
        fun pct(p: Int): Double = if (sorted.isEmpty()) 0.0
            else sorted[((sorted.size * p + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)] / 1e6
        val n = frames.get().coerceAtLeast(1)
        return JSONObject()
            .put("frames", frames.get())
            .put("placeholder_frames", placeholderFrames.get())
            .put("placeholder_share_pct", Math.round(placeholderFrames.get() * 1000.0 / n) / 10.0)
            .put("grey_tiles_per_frame", Math.round(greyTileFrames * 100.0 / n) / 100.0)
            .put("frame_p50_ms", Math.round(pct(50) * 10) / 10.0)
            .put("frame_p95_ms", Math.round(pct(95) * 10) / 10.0)
            .put("frames_over_33ms", intervals.count { it > 33_000_000L })
            .put("settle_frames", settleFrames)
            .put("settle_placeholder_frames", settlePlaceholderFrames)
    }

    private fun insertFixtures(folder: String, count: Int, inserted: MutableList<android.net.Uri>) {
        // 16 distinct synthetic photos, re-used across rows (decode cost is per row).
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
            put(MediaStore.Images.Media.DISPLAY_NAME, "FLING_BENCH_$i.jpg")
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
            val x = random.nextFloat() * 1280; val y = random.nextFloat() * 960
            canvas.drawCircle(x, y, 20f + random.nextFloat() * 160f, paint)
        }
        return ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it))
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private companion object {
        const val TAG = "ATLAS_SCALE"
        const val SHOT = "ATLAS_SHOT"
    }
}
