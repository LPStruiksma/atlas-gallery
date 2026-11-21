package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Trace
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * EDIT-2 measurements against `docs/editor/BUDGETS.md`, on synthetic images. These are EMULATOR
 * figures (device_kind=EMULATOR), printed to logcat under ATLAS_EDIT2 in the BUDGETS record
 * format. They never fail on time: an emulator is not the S24+, and BUDGETS.md says a slow or
 * software-GPU emulator run is evidence of behaviour, not of the phone's latency. They do fail if
 * a frame or tile is missing, or if a 50 MP export runs out of memory.
 */
@RunWith(AndroidJUnit4::class)
class EditorRendererBudgetTest {
    @Before fun needsAgsl() = assumeTrue("AGSL needs API 33", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

    private val env = "device_kind=EMULATOR api=${Build.VERSION.SDK_INT} renderer=hwui-agsl model=${Build.MODEL.replace(' ', '_')}"

    private fun recipe(w: Int, h: Int, exposure: Double = 0.2) = EditRecipe(w, h, listOf(
        EditOperation.Straighten("s", 4.0),
        EditOperation.Crop("c", NormRect(0.05, 0.05, 0.95, 0.95)),
        EditOperation.Adjust("e", AdjustParam.EXPOSURE, exposure),
        EditOperation.Adjust("k", AdjustParam.CONTRAST, 0.2),
        EditOperation.Adjust("t", AdjustParam.SATURATION, 0.15),
        EditOperation.Filter("f", "synthetic-warm", null, 0.6),
    ))

    /** 12 MP (4000 x 3000) synthetic export, 3 repetitions, render to finished bitmap. */
    @Test fun export12MegapixelTiming() {
        val source = BitmapSource(RenderTestKit.picture(4000, 3000))
        val plan = RenderPlan.build(EditRecipe(4000, 3000, recipe(4000, 3000).operations.filter { it !is EditOperation.Straighten && it !is EditOperation.Crop }))
        assertEquals(4000, plan.outputWidth)
        val samples = ArrayList<Long>()
        PlanRenderer(RenderTestKit.assets(), AgslPixelBackend()).use { r ->
            repeat(3) {
                val sink = BitmapTileSink(plan.outputWidth, plan.outputHeight, PixelSpace.SRGB)
                val stats = r.export(plan, source, sink, PixelSpace.SRGB)
                assertEquals(12, stats.tiles)
                samples += stats.elapsedMs
                sink.bitmap.recycle()
            }
        }
        val sorted = samples.sorted()
        val mp = 12.0
        RenderTestKit.log(
            "$env operation=export_render12 fixture=synthetic-4000x3000 sample_count=${sorted.size} " +
                "median_ms=${sorted[sorted.size / 2]} p95_ms=${RenderTestKit.percentile(sorted, 0.95)} worst_ms=${sorted.last()} " +
                "megapixels=$mp ms_per_mp=${"%.1f".format(sorted[sorted.size / 2] / mp)} " +
                "scope=render-only(EDIT-3 adds encode+MediaStore) result=NOT_MEASURED_ON_S24",
        )
    }

    /** A 50 MP (8192 x 6144) export from a procedural source that never exists as one bitmap. */
    @Test fun export50MegapixelWithoutRunningOutOfMemory() {
        val w = 8192; val h = 6144
        val source = ProceduralSource(w, h)
        val plan = RenderPlan.build(EditRecipe(w, h, listOf(
            EditOperation.Straighten("s", 3.0),
            EditOperation.Adjust("e", AdjustParam.EXPOSURE, 0.1),
        )))
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        var peak = before
        var tiles = 0
        var opaque = 0L
        val stats = PlanRenderer(RenderTestKit.assets(), AgslPixelBackend()).use { r ->
            r.export(plan, source, { _, px ->
                tiles++
                peak = max(peak, rt.totalMemory() - rt.freeMemory())
                if (px.getPixel(px.width / 2, px.height / 2) ushr 24 == 0xff) opaque++
            }, PixelSpace.SRGB)
        }
        val peakMb = (peak - before) / 1_000_000
        RenderTestKit.log(
            "$env operation=export_render50 fixture=procedural-8192x6144 output=${plan.outputWidth}x${plan.outputHeight} " +
                "tiles=$tiles elapsed_ms=${stats.elapsedMs} ms_per_mp=${"%.1f".format(stats.msPerMegapixel)} " +
                "peak_region_px=${stats.peakRegionPixels} heap_growth_mb=$peakMb result=PASS_NO_OOM",
        )
        assertEquals(TileGrid.tiles(plan.outputWidth, plan.outputHeight).size, tiles)
        assertEquals(tiles.toLong(), opaque)
        // One full 50 MP ARGB bitmap would be 200 MB; tiling keeps the working set far below.
        assertTrue("heap grew $peakMb MB", peakMb < 120)
        assertTrue(stats.peakRegionPixels < 2_000_000)
    }

    /**
     * A scripted 10 s exposure-slider drag on the live preview: every frame re-plans the recipe and
     * renders through [EditorPreviewSurface]. Reports per-frame render-to-commit time and frames
     * over the 60 Hz deadline (16.67 ms).
     */
    @Test fun previewSliderDragFrameTimes() {
        val srcW = 4000; val srcH = 3000
        val proxy = RenderTestKit.picture(1440, 1080)
        val source = BitmapSource(proxy, srcW, srcH)
        val first = RenderPlan.build(recipe(srcW, srcH), maxLongEdge = 1440)
        val thread = HandlerThread("edit2-drain").apply { start() }
        val reader = RenderTestKit.reader(first.outputWidth, first.outputHeight, images = 4)
        val presented = AtomicInteger()
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); presented.incrementAndGet() }, Handler(thread.looper))
        val preview = EditorPreviewSurface(reader.surface, first.outputWidth, first.outputHeight, RenderTestKit.assets())
        val frameNanos = ArrayList<Long>()
        try {
            // Warm up shader compilation and texture upload outside the measured drag.
            repeat(3) { renderAndWait(preview, first, source, frameNanos) }
            frameNanos.clear()
            Trace.beginSection("preview_drag_start")
            val start = SystemClock.elapsedRealtime()
            var i = 0
            while (SystemClock.elapsedRealtime() - start < 10_000) {
                val t = (SystemClock.elapsedRealtime() - start) / 1000.0
                val exposure = 0.6 * sin(2 * PI * t / 2.5)
                val plan = RenderPlan.build(recipe(srcW, srcH, exposure), maxLongEdge = 1440)
                renderAndWait(preview, plan, source, frameNanos)
                i++
            }
            Trace.endSection()
        } finally {
            preview.close(); reader.close(); thread.quitSafely()
        }
        val ms = frameNanos.map { it / 1_000_000 }.sorted()
        val missed60 = frameNanos.count { it > 16_670_000 }
        val missed120 = frameNanos.count { it > 8_330_000 }
        RenderTestKit.log(
            "$env operation=drag_slider fixture=synthetic-proxy-1440x1080 output=${first.outputWidth}x${first.outputHeight} " +
                "frame_count=${frameNanos.size} presented=${presented.get()} median_ms=${ms[ms.size / 2]} " +
                "p95_ms=${RenderTestKit.percentile(ms, 0.95)} worst_ms=${ms.last()} missed_deadlines_60hz=$missed60 " +
                "missed_deadlines_120hz=$missed120 measure=render-call-to-GPU-commit(not FrameTimeline) result=NOT_MEASURED_ON_S24",
        )
        assertTrue("the drag rendered frames", frameNanos.size >= 20)
    }

    private fun renderAndWait(preview: EditorPreviewSurface, plan: RenderPlan, source: SourceProvider, out: MutableList<Long>) {
        val done = CountDownLatch(1)
        preview.onFrameCommitted = { nanos -> out += nanos; done.countDown() }
        preview.render(plan, source)
        assertTrue("frame committed", done.await(5, TimeUnit.SECONDS))
    }

    /** Generates any region of a large synthetic picture on demand (no full-size bitmap). */
    private class ProceduralSource(override val width: Int, override val height: Int) : SourceProvider {
        override fun region(rect: IntRect, scale: Float): SourceRegion {
            val buf = ByteBuffer.allocate(rect.width * rect.height * 4)
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                buf.put((x * 255 / width).toByte()); buf.put((y * 255 / height).toByte())
                buf.put((if (((x shr 6) + (y shr 6)) % 2 == 0) 200 else 60).toByte()); buf.put(255.toByte())
            }
            buf.rewind()
            val b = Bitmap.createBitmap(rect.width, rect.height, Bitmap.Config.ARGB_8888)
            b.copyPixelsFromBuffer(buf)
            return SourceRegion(b, rect.left.toFloat(), rect.top.toFloat(), 1f, PixelSpace.SRGB)
        }
    }
}
