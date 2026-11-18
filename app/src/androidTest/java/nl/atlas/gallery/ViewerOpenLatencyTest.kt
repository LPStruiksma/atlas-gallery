package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * PERF-3 (#124): tap to first image in the real viewer, from a warmed grid
 * tile, without (arm 0) and with (arm 1) the borrowed first frame. A
 * generated 12 MP JPEG (synthetic gradient and grid, no real photo). Each
 * open starts from a memory cache that holds only the grid tile, as after
 * tapping it. Timestamps come from the page itself (ViewerPhotoFirstFrameAtKey
 * and ViewerPhotoSharpAtKey); numbers go to the ATLAS_SCALE log. One test.
 */
@RunWith(AndroidJUnit4::class)
class ViewerOpenLatencyTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private data class Open(val firstMs: Double, val sharpMs: Double, val litAtFirstIdle: Double)

    @Test fun viewerShowsTheGridTileAtOnceAndSharpensWithoutWaiting() {
        val photo = syntheticTwelveMegapixelPhoto()
        val epoch = 1L
        val gridPixels = 352
        val gridKey = photo.presentationCacheKeyOrNull(gridPixels, epoch)!!
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = SingletonImageLoader.get(context).memoryCache!!
        var arm by mutableIntStateOf(0)
        var run by mutableIntStateOf(0)
        var open by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGalleryPublicationEpoch provides epoch,
                    LocalViewerInstantOpen provides (arm == 1)) {
                    key(arm, run) {
                        if (!open) Box(Modifier.size(120.dp)) {
                            MediaPreview(photo, Modifier.fillMaxSize(), ContentScale.Crop, gridPixels,
                                gridTile = true)
                        } else GalleryViewer(listOf(photo), photo.uri, onBack = {})
                    }
                }
            }
        }
        val opens = List(2) { mutableListOf<Open>() }
        for (a in 0..1) for (r in 0 until RUNS) {
            rule.runOnIdle { memory.clear(); open = false; arm = a; run = r }
            // The tapped tile is in memory, nothing else (no 800 or 2400 decode).
            rule.waitUntil(30_000) { memory[MemoryCache.Key(gridKey)] != null }
            rule.waitForIdle()
            var t0 = 0L
            rule.runOnIdle { t0 = System.nanoTime(); open = true }
            rule.waitForIdle()
            val lit = litFraction()
            if (a == 1 && r == 0) saveUiEvidence("viewer-open-first-frame.png")
            val first = awaitStamp(ViewerPhotoFirstFrameAtKey)
            val sharp = awaitStamp(ViewerPhotoSharpAtKey)
            opens[a] += Open((first - t0) / 1e6, (sharp - t0) / 1e6, lit)
        }
        fun p50(values: List<Double>) = values.sorted()[values.size / 2]
        val before = opens[0]
        val after = opens[1]
        Log.i("ATLAS_SCALE", ("PERF-3 viewer open, 12MP synthetic JPEG from a warmed %d px grid tile, " +
            "1080x2340 (emulator), %d opens per arm: tap->first image p50 before=%.1f ms after=%.1f ms; " +
            "tap->sharp 2400 px p50 before=%.1f ms after=%.1f ms; photo area lit at first idle " +
            "before=%.2f after=%.2f; raw first before=%s after=%s; raw sharp before=%s after=%s")
            .format(gridPixels, RUNS, p50(before.map { it.firstMs }), p50(after.map { it.firstMs }),
                p50(before.map { it.sharpMs }), p50(after.map { it.sharpMs }),
                p50(before.map { it.litAtFirstIdle }), p50(after.map { it.litAtFirstIdle }),
                before.map { "%.0f".format(it.firstMs) }, after.map { "%.0f".format(it.firstMs) },
                before.map { "%.0f".format(it.sharpMs) }, after.map { "%.0f".format(it.sharpMs) }))
        assertTrue("tap->first image p50 must be <= 150 ms with the borrowed tile: " +
            after.map { it.firstMs }, p50(after.map { it.firstMs }) <= 150.0)
    }

    private fun awaitStamp(key: SemanticsPropertyKey<Long>): Long {
        val matcher = SemanticsMatcher("$key set") { (it.config.getOrNull(key) ?: 0L) > 0L }
        rule.waitUntil(60_000) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return rule.onAllNodes(matcher).fetchSemanticsNodes().first().config[key]
    }

    /** Share of the centre band that is not black: 0 = an empty viewer page. */
    private fun litFraction(): Double {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val y0 = bitmap.height * 2 / 5
        val y1 = bitmap.height * 3 / 5
        var lit = 0
        var total = 0
        for (y in y0 until y1 step 8) for (x in 0 until bitmap.width step 8) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) + Color.green(c) + Color.blue(c) > 60) lit++
            total++
        }
        return lit.toDouble() / total
    }

    private fun syntheticTwelveMegapixelPhoto(): MediaRecord {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "atlas-perf3-12mp-synthetic.jpg")
        if (!file.exists() || file.length() == 0L) {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                Color.rgb(40, 120, 200), Color.rgb(230, 170, 60), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
            paint.shader = null
            paint.color = Color.WHITE
            paint.strokeWidth = 6f
            for (x in 0 until WIDTH step 96) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), HEIGHT.toFloat(), paint)
            for (y in 0 until HEIGHT step 96) canvas.drawLine(0f, y.toFloat(), WIDTH.toFloat(), y.toFloat(), paint)
            val partial = File(file.path + ".part")
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            check(partial.renameTo(file))
        }
        return MediaRecord(1L, Uri.fromFile(file).toString(), "Synthetic 12 MP", "DCIM/",
            file.length(), 1_700_000_000_000L, isVideo = false,
            modifiedAtSeconds = 1_700_000_000L, providerVersion = "synthetic-perf3",
            generationModified = 1L, widthPixels = WIDTH, heightPixels = HEIGHT)
    }

    private companion object {
        const val WIDTH = 4032
        const val HEIGHT = 3024
        const val RUNS = 5
    }
}
