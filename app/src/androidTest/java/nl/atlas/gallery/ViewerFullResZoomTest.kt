package nl.atlas.gallery

import android.graphics.Color
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * PERF-32 (#334): a generated 50 MP photo (8160 x 6120 checkerboard, written
 * row by row so no full-frame bitmap is ever allocated) zoomed to 8x in the
 * real viewer, first with the 2400 px base layer only, then with region
 * tiles. One emulator test (budget rule); the tiling rules are JVM-tested in
 * ViewerTileZoomTest. Synthetic pixels only.
 */
@RunWith(AndroidJUnit4::class)
class ViewerFullResZoomTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    @After fun restore() {
        ViewerRegionTiles.baseOnlyForTest = false
    }

    private data class Trace(val frames: Int, val p50Ms: Double, val p95Ms: Double,
        val maxMs: Double, val over32: Int, val heapPeakMb: Double)

    private data class Arm(val sharpness: Double, val zoom8: Float, val zoom12: Float,
        val tilesAt8: Int, val trace: Trace)

    @Test fun fiftyMegapixelPhotoShowsFullResolutionDetailAtEightTimesZoom() {
        val photo = syntheticFiftyMegapixelPhoto()
        var arm by mutableIntStateOf(0)
        ViewerRegionTiles.baseOnlyForTest = true
        rule.setContent {
            MaterialTheme { key(arm) { GalleryViewer(listOf(photo), photo.uri, onBack = {}) } }
        }
        val base = runArm("base", tiles = false)

        ViewerRegionTiles.baseOnlyForTest = false
        val decodes0 = ViewerRegionTiles.decodes.get()
        val mainDecodes0 = ViewerRegionTiles.mainThreadDecodes.get()
        rule.runOnIdle { arm = 1 }
        val tiled = runArm("tiles", tiles = true)
        val decodes = ViewerRegionTiles.decodes.get() - decodes0
        val mainDecodes = ViewerRegionTiles.mainThreadDecodes.get() - mainDecodes0

        Log.i("ATLAS_SCALE", ("PERF-32 50MP synthetic, 1080x2340 viewer (emulator): 8x centre " +
            "sharpness base=%.1f tiles=%.1f (tiles drawn %d); zoom 8x=%.1f 12x=%.1f; region decodes=%d " +
            "main-thread decodes=%d; cancelled=%d failed=%d. 12x real-time pan frames: base %s; tiles %s")
            .format(base.sharpness, tiled.sharpness, tiled.tilesAt8, tiled.zoom8, tiled.zoom12,
                decodes, mainDecodes, ViewerRegionTiles.cancelledDecodes.get(),
                ViewerRegionTiles.failedDecodes.get(), base.trace, tiled.trace))

        assertEquals(8f, tiled.zoom8, 0.45f)
        assertTrue("12x must be reachable with a tiled source", tiled.zoom12 > 11f)
        assertTrue("no tiles drawn at 8x", tiled.tilesAt8 > 0)
        assertTrue("tiles must show more detail than the base: base=${base.sharpness} " +
            "tiles=${tiled.sharpness}", tiled.sharpness > base.sharpness * 2.0)
        assertTrue("no region decode ran", decodes > 0)
        assertEquals("a tile was decoded on the main thread", 0, mainDecodes)
    }

    private fun runArm(label: String, tiles: Boolean): Arm {
        rule.waitUntil(120_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(ViewerPhotoDecodedKey, true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        // The first zoom opens the region decoders; only then may a pinch pass 5x.
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { doubleClick(center) }
        rule.waitUntil(60_000) { tileNode()?.config?.getOrNull(ViewerPhotoTilesReadyKey) == true }
        pinchOut(from = 100f, to = 320f) // 2.5x -> 8x
        rule.waitForIdle()
        val zoom8 = tileValue(ViewerPhotoTileZoomKey) ?: 0f
        if (tiles) awaitAllTiles() else assertEquals(0, tileValue(ViewerPhotoTilesWantedKey))
        val tilesAt8 = tileValue(ViewerPhotoTilesShownKey) ?: 0
        Thread.sleep(400L) // let the 150 ms fade finish on screen
        rule.waitForIdle()
        val sharpness = centreSharpness()
        saveUiEvidence("viewer-zoom-8x-$label")
        pinchOut(from = 200f, to = 300f) // 8x -> 12x
        rule.waitForIdle()
        val zoom12 = tileValue(ViewerPhotoTileZoomKey) ?: 0f
        val trace = realTimePanTrace()
        rule.waitForIdle()
        if (tiles) awaitAllTiles()
        return Arm(sharpness, zoom8, zoom12, tilesAt8, trace)
    }

    private fun pinchOut(from: Float, to: Float) {
        rule.onNodeWithTag("atlas-main-pager").performTouchInput {
            pinch(center - Offset(from, 0f), center - Offset(to, 0f),
                center + Offset(from, 0f), center + Offset(to, 0f), 600L)
        }
    }

    private fun tileNode(): SemanticsNode? =
        rule.onAllNodesWithTag("atlas-photo-tiles", useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()

    private fun <T> tileValue(key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>): T? =
        tileNode()?.config?.getOrNull(key)

    private fun awaitAllTiles() {
        rule.waitUntil(180_000) {
            val wanted = tileValue(ViewerPhotoTilesWantedKey) ?: 0
            wanted > 0 && tileValue(ViewerPhotoTilesShownKey) == wanted
        }
    }

    /** Mean luminance step between neighbouring pixels in a 360 px centre square. */
    private fun centreSharpness(): Double {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val size = 360
            val left = shot.width / 2 - size / 2
            val top = shot.height / 2 - size / 2
            val pixels = IntArray(size * size)
            shot.getPixels(pixels, 0, size, left, top, size, size)
            fun luma(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            var sum = 0.0
            for (y in 0 until size) for (x in 0 until size - 1) {
                sum += abs(luma(pixels[y * size + x + 1]) - luma(pixels[y * size + x]))
            }
            return sum / (size * (size - 1))
        } finally {
            shot.recycle()
        }
    }

    /**
     * A one-finger pan at 12x injected in real time (16 ms steps), with every
     * UI-thread frame interval and the heap (Java + native, where Bitmap
     * pixels live) sampled on each frame.
     */
    private fun realTimePanTrace(): Trace {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intervals = ArrayList<Long>()
        var heapPeak = 0L
        var last = 0L
        var tracing = true
        val runtime = Runtime.getRuntime()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (last != 0L) intervals += frameTimeNanos - last
                last = frameTimeNanos
                heapPeak = maxOf(heapPeak, Debug.getNativeHeapAllocatedSize() +
                    runtime.totalMemory() - runtime.freeMemory())
                if (tracing) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
        val display = instrumentation.targetContext.resources.displayMetrics
        val cx = display.widthPixels / 2f
        val cy = display.heightPixels / 2f
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, cx, cy, down)
        val steps = 120
        for (i in 1..steps) {
            Thread.sleep(16L)
            val t = i.toDouble() / steps * 2.0 * PI
            inject(MotionEvent.ACTION_MOVE, cx + (260.0 * sin(t)).toFloat(),
                cy + (420.0 * sin(2.0 * t)).toFloat(), down)
        }
        inject(MotionEvent.ACTION_UP, cx, cy, down)
        Thread.sleep(250L)
        var result: Trace? = null
        instrumentation.runOnMainSync {
            tracing = false
            val ms = intervals.map { it / 1_000_000.0 }.sorted()
            fun p(q: Double) = if (ms.isEmpty()) 0.0 else ms[((ms.size - 1) * q).toInt()]
            result = Trace(ms.size, p(.5), p(.95), ms.lastOrNull() ?: 0.0,
                ms.count { it > 32.0 }, heapPeak / (1024.0 * 1024.0))
        }
        return requireNotNull(result)
    }

    private fun inject(action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    /**
     * 8160 x 6120 RGB PNG: a 4 px black/white checkerboard (detail the 2400 px
     * base cannot hold) with a 2 px red line every 256 px. Rows are deflated
     * as they are produced, so memory stays at a few rows.
     */
    private fun syntheticFiftyMegapixelPhoto(): MediaRecord {
        val width = 8_160
        val height = 6_120
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "atlas-perf32-50mp-synthetic.png")
        if (!file.exists() || file.length() == 0L) {
            val partial = File(file.path + ".part")
            DataOutputStream(partial.outputStream().buffered(1 shl 16)).use { out ->
                out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                val header = java.io.ByteArrayOutputStream()
                DataOutputStream(header).apply {
                    writeInt(width); writeInt(height)
                    writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0)
                }
                chunk(out, "IHDR", header.toByteArray())
                fun row(phase: Int, grid: Boolean): ByteArray {
                    val bytes = ByteArray(1 + width * 3) // filter byte 0 = None
                    for (x in 0 until width) {
                        val red = grid || x % 256 < 2
                        val v = if (((x / 4) + phase) % 2 == 0) 235 else 20
                        val o = 1 + x * 3
                        bytes[o] = (if (red) 230 else v).toByte()
                        bytes[o + 1] = (if (red) 30 else v).toByte()
                        bytes[o + 2] = (if (red) 40 else v).toByte()
                    }
                    return bytes
                }
                val rows = arrayOf(row(0, false), row(1, false), row(0, true))
                val deflater = Deflater(1)
                val buffer = ByteArray(1 shl 16)
                fun drain(finish: Boolean) {
                    while (true) {
                        val n = deflater.deflate(buffer)
                        if (n > 0) chunk(out, "IDAT", buffer.copyOf(n))
                        if (finish) { if (deflater.finished()) return } else if (deflater.needsInput()) return
                    }
                }
                for (y in 0 until height) {
                    deflater.setInput(if (y % 256 < 2) rows[2] else rows[(y / 4) % 2])
                    drain(false)
                }
                deflater.finish()
                drain(true)
                deflater.end()
                chunk(out, "IEND", ByteArray(0))
            }
            check(partial.renameTo(file)) { "could not store the synthetic photo" }
        }
        return MediaRecord(1L, Uri.fromFile(file).toString(), "Synthetic 50 MP",
            "DCIM/SYNTHETIC/", file.length(), 1L, false,
            widthPixels = width, heightPixels = height)
    }

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.writeInt(crc.value.toInt())
    }
}
