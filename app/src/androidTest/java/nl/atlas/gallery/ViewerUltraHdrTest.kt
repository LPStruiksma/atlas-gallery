package nl.atlas.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import java.io.File
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featuredesign.AtlasDarkRoute
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * BENCH-1 (#572): Ultra HDR photos in the viewer. Uses a generated synthetic
 * Ultra HDR JPEG (gradient base + uniform gain map, encoded by Android's own
 * JPEG/R encoder; no personal media) and a plain SDR JPEG.
 *
 * 1. Decode paths: the viewer's real 2400 px Coil base request and its
 *    region-tile source (upright and rotated) keep the gain map.
 * 2. Window mode: HDR while the Ultra HDR photo is focused, default after a
 *    swipe to the SDR photo, HDR again on swiping back, prior mode on close.
 *
 * Android screenshots are SDR, so the saved renders only prove the viewer
 * chrome and insets are unchanged; the on-phone HDR look stays unverified
 * until the owner sees it on the S24+.
 */
@RunWith(AndroidJUnit4::class)
class ViewerUltraHdrTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun baseAndTileDecodesKeepTheGainmap() {
        assumeTrue("gain maps need API 34+", Build.VERSION.SDK_INT >= 34)
        val hdr = syntheticPhoto(hdr = true)
        val sdr = syntheticPhoto(hdr = false)
        val file = File(Uri.parse(hdr.uri).path!!)
        // The fixture itself: Android's encoder wrote a JPEG/R with a gain map.
        assertTrue("synthetic fixture must carry a gain map",
            BitmapFactory.decodeFile(file.path).hasGainmap())

        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        fun baseHasGainmap(media: MediaRecord): Boolean {
            val request = viewerBaseRequestOrNull(context, media, 1L, forceFresh = false,
                imageRevision = 0L, requireKey = false)!!
            val result = runBlocking { loader.execute(request) }
            assertTrue("base decode failed: $result", result is SuccessResult)
            val image = (result as SuccessResult).image
            assertTrue("base must be the 2400 px decode: ${image.width}x${image.height}",
                maxOf(image.width, image.height) <= ViewerSwipePrefetch.BASE_PIXELS)
            return viewerImageHasGainmap(image)
        }
        val baseKeeps = baseHasGainmap(hdr)
        val sdrBase = baseHasGainmap(sdr)

        // Raw region decode, exactly the options ViewerRegionSource uses.
        val decoder = BitmapRegionDecoder.newInstance(file.path)
        val rawTile = decoder.decodeRegion(Rect(0, 0, 1024, 1024), BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
        })
        decoder.recycle()
        val rawTileKeeps = rawTile?.hasGainmap() == true

        // The production tile source, upright and with the rotate transform.
        fun productionTileKeeps(degrees: Int): Boolean {
            val upright = degrees == 90 || degrees == 270
            val baseW = if (upright) 1800 else 2400
            val baseH = if (upright) 2400 else 1800
            val source = ViewerRegionSource.open(context.contentResolver, Uri.parse(hdr.uri),
                degrees, baseW, baseH)
            requireNotNull(source) { "tile source refused the synthetic JPEG at $degrees°" }
            var tile: Bitmap? = null
            // decode() refuses the main thread; the instrumentation thread is not it.
            try { tile = source.decode(ViewerSourceRect(0, 0, 1024, 1024), 2) }
            finally { source.close() }
            requireNotNull(tile) { "tile decode failed at $degrees°" }
            return tile.hasGainmap()
        }
        val tile0 = productionTileKeeps(0)
        val tile90 = productionTileKeeps(90)

        Log.i("ATLAS_HDR", "BENCH-1 #572 gain map retention, API ${Build.VERSION.SDK_INT}: " +
            "coil base 2400px=$baseKeeps (sdr control=$sdrBase), raw BitmapRegionDecoder=$rawTileKeeps, " +
            "production tile 0deg=$tile0, production tile 90deg (rotate transform)=$tile90")
        assertTrue("SDR control must not report a gain map", !sdrBase)
        assertTrue("2400 px Coil base must keep the gain map", baseKeeps)
        assertTrue("BitmapRegionDecoder tile must keep the gain map", rawTileKeeps)
        assertTrue("production upright tile must keep the gain map", tile0)
        assertTrue("production rotated tile must keep the gain map", tile90)
    }

    @Test fun lightWindowModeFollowsTheFocusedPhoto() = verifyWindowMode(dark = false)

    @Test fun darkWindowModeFollowsTheFocusedPhoto() = verifyWindowMode(dark = true)

    private fun verifyWindowMode(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val hdr = syntheticPhoto(hdr = true)
        val sdr = syntheticPhoto(hdr = false)
        val photos = listOf(hdr, sdr)
        val viewerOpen = mutableStateOf(false)
        var window: Window? = null
        SingletonImageLoader.get(context).memoryCache?.clear()

        rule.setContent {
            val view = LocalView.current
            SideEffect { window = view.context.hdrTestActivity()?.window }
            CompositionLocalProvider(LocalGalleryPublicationEpoch provides 1L) {
                AtlasGalleryTheme(dark = dark) {
                    if (viewerOpen.value) AtlasDarkRoute {
                        GalleryViewer(photos, hdr.uri, onBack = { viewerOpen.value = false })
                    } else Box(Modifier.fillMaxSize().background(
                        if (dark) androidx.compose.ui.graphics.Color(0xFF0B0D10)
                        else androidx.compose.ui.graphics.Color.White
                    ).testTag("test-hdr-gallery"))
                }
            }
        }
        rule.onNodeWithTag("test-hdr-gallery").assertExists()
        val prior = rule.runOnIdle { requireNotNull(window).colorMode }
        assertTrue("gallery must not start in HDR", prior != ActivityInfo.COLOR_MODE_HDR)
        rule.runOnIdle { viewerOpen.value = true }
        rule.onNodeWithTag("atlas-viewer").assertExists()

        val wantsHdr = Build.VERSION.SDK_INT >= 34
        val focusedHdr = if (wantsHdr) ActivityInfo.COLOR_MODE_HDR else prior
        rule.waitUntil(30_000L) { window?.colorMode == focusedHdr }
        rule.waitForIdle()
        saveUiEvidence("572-viewer-ultra-hdr-focused-$mode.png")

        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
        rule.waitUntil(30_000L) { window?.colorMode == prior }
        rule.waitForIdle()
        saveUiEvidence("572-viewer-sdr-neighbour-$mode.png")

        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeRight() }
        rule.waitUntil(30_000L) { window?.colorMode == focusedHdr }

        rule.runOnIdle { viewerOpen.value = false }
        rule.onNodeWithTag("test-hdr-gallery").assertExists()
        rule.runOnIdle {
            assertEquals("closing the viewer restores the prior colour mode",
                prior, requireNotNull(window).colorMode)
        }
        Log.i("ATLAS_HDR", "BENCH-1 #572 window mode $mode API ${Build.VERSION.SDK_INT}: " +
            "prior=$prior focusedUltraHdr=$focusedHdr sdrNeighbour=$prior closed=$prior")
    }

    /** 4032×3024 synthetic gradient; [hdr] adds a uniform gain map (Ultra HDR JPEG). */
    private fun syntheticPhoto(hdr: Boolean): MediaRecord {
        val name = if (hdr) "atlas-572-synthetic-ultrahdr.jpg" else "atlas-572-synthetic-sdr.jpg"
        val file = File(context.cacheDir, name)
        if (!file.exists() || file.length() == 0L) {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val paint = Paint()
            paint.shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                if (hdr) Color.rgb(250, 190, 90) else Color.rgb(60, 120, 200),
                if (hdr) Color.rgb(40, 40, 80) else Color.rgb(200, 220, 240),
                Shader.TileMode.CLAMP)
            Canvas(bitmap).drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
            if (hdr && Build.VERSION.SDK_INT >= 34) {
                val contents = Bitmap.createBitmap(WIDTH / 4, HEIGHT / 4, Bitmap.Config.ALPHA_8)
                contents.eraseColor(Color.argb(200, 0, 0, 0))
                bitmap.gainmap = Gainmap(contents).apply {
                    setRatioMin(1f, 1f, 1f)
                    setRatioMax(4f, 4f, 4f)
                    setDisplayRatioForFullHdr(4f)
                    setMinDisplayRatioForHdrTransition(1f)
                }
            }
            val partial = File(file.path + ".part")
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            check(partial.renameTo(file))
        }
        return MediaRecord(if (hdr) 5721L else 5722L, Uri.fromFile(file).toString(),
            if (hdr) "Synthetic Ultra HDR" else "Synthetic SDR", "DCIM/", file.length(),
            1_700_000_000_000L - (if (hdr) 0L else 60_000L), isVideo = false,
            modifiedAtSeconds = 1_700_000_000L, providerVersion = "synthetic-572",
            generationModified = 1L, widthPixels = WIDTH, heightPixels = HEIGHT)
    }

    private companion object {
        const val WIDTH = 4032
        const val HEIGHT = 3024
    }
}

private tailrec fun Context.hdrTestActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hdrTestActivity()
    else -> null
}
