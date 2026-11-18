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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * PERF-9 (#138): swipe settle to sharp 2400 px base in the real viewer,
 * without (arm 0) and with (arm 1) the neighbour base prefetch. Generated
 * 12 MP JPEGs (synthetic gradients and grids, no real photo). Each arm opens
 * the first photo from an empty memory cache, then swipes forward through the
 * rest at a steady pace (one swipe per [PACE_MS]). Timestamps come from the
 * focused page itself (ViewerPhotoSettledAtKey, ViewerPhotoSharpAtKey);
 * numbers go to the ATLAS_SCALE log. One test.
 */
@RunWith(AndroidJUnit4::class)
class ViewerSwipeSharpnessTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    @Test fun swipesLandOnAnAlreadyDecodedBase() {
        val photos = List(PHOTOS) { syntheticPhoto(it) }
        val epoch = 1L
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = SingletonImageLoader.get(context).memoryCache!!
        var arm by mutableIntStateOf(0)
        var open by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGalleryPublicationEpoch provides epoch,
                    // Arm 1 forces the gate: the emulator's memory cache is
                    // smaller than the real gate (six bases); an S24+ passes it.
                    LocalViewerBasePrefetchGate provides { _ -> arm == 1 }) {
                    key(arm) {
                        if (open) GalleryViewer(photos, photos[0].uri, onBack = {})
                        else Box(Modifier.size(120.dp))
                    }
                }
            }
        }
        val settleToSharp = List(2) { mutableListOf<Double>() }
        for (a in 0..1) {
            rule.runOnIdle { memory.clear(); open = false; arm = a }
            rule.waitForIdle()
            rule.runOnIdle { open = true }
            awaitStamp(photos[0].name, ViewerPhotoSharpAtKey, 0L)
            for (page in 1 until PHOTOS) {
                Thread.sleep(PACE_MS)
                val swipedAt = System.nanoTime()
                rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
                val settled = awaitStamp(photos[page].name, ViewerPhotoSettledAtKey, swipedAt)
                val sharp = awaitStamp(photos[page].name, ViewerPhotoSharpAtKey, swipedAt)
                settleToSharp[a] += (sharp - settled) / 1e6
            }
        }
        fun p50(values: List<Double>) = values.sorted()[values.size / 2]
        fun within(values: List<Double>) = values.count { it <= TARGET_MS }.toDouble() / values.size
        val before = settleToSharp[0]
        val after = settleToSharp[1]
        Log.i("ATLAS_SCALE", ("PERF-9 viewer swipe, %d synthetic 12MP JPEGs, 1080x2340 (emulator), " +
            "one swipe per %d ms, %d swipes per arm: settle->sharp 2400 px p50 before=%.1f ms " +
            "after=%.1f ms; share sharp within %.0f ms before=%.2f after=%.2f; raw before=%s after=%s")
            .format(PHOTOS, PACE_MS, before.size, p50(before), p50(after), TARGET_MS,
                within(before), within(after),
                before.map { "%.0f".format(it) }, after.map { "%.0f".format(it) }))
        assertTrue("settle->sharp p50 must be <= ${TARGET_MS} ms with the prefetch: $after",
            p50(after) <= TARGET_MS)
    }

    /** The focused page's stamp for [name], once it is later than [after]. */
    private fun awaitStamp(name: String, key: SemanticsPropertyKey<Long>, after: Long): Long {
        val matcher = SemanticsMatcher("$name $key after $after") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { it == "Photo viewer: $name" } == true &&
                (node.config.getOrNull(key) ?: 0L) > after
        }
        rule.waitUntil(60_000) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return rule.onAllNodes(matcher).fetchSemanticsNodes().first().config[key]
    }

    private fun syntheticPhoto(index: Int): MediaRecord {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "atlas-perf9-12mp-synthetic-$index.jpg")
        if (!file.exists() || file.length() == 0L) {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val hue = index * 50f
            paint.shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                Color.HSVToColor(floatArrayOf(hue % 360f, 0.7f, 0.8f)),
                Color.HSVToColor(floatArrayOf((hue + 140f) % 360f, 0.6f, 0.9f)),
                Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
            paint.shader = null
            paint.color = Color.WHITE
            paint.strokeWidth = 6f
            val step = 80 + index * 8
            for (x in 0 until WIDTH step step) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), HEIGHT.toFloat(), paint)
            for (y in 0 until HEIGHT step step) canvas.drawLine(0f, y.toFloat(), WIDTH.toFloat(), y.toFloat(), paint)
            val partial = File(file.path + ".part")
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            check(partial.renameTo(file))
        }
        return MediaRecord(100L + index, Uri.fromFile(file).toString(), "Synthetic swipe $index",
            "DCIM/", file.length(), 1_700_000_000_000L - index * 60_000L, isVideo = false,
            modifiedAtSeconds = 1_700_000_000L, providerVersion = "synthetic-perf9",
            generationModified = 1L, widthPixels = WIDTH, heightPixels = HEIGHT)
    }

    private companion object {
        const val WIDTH = 4032
        const val HEIGHT = 3024
        const val PHOTOS = 6
        const val PACE_MS = 1_500L
        const val TARGET_MS = 100.0
    }
}
