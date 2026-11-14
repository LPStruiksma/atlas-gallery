package nl.atlas.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.AtlasDarkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** #680: the viewer must own dark system bars even when entered from a light gallery. */
@RunWith(AndroidJUnit4::class)
class ViewerLightSystemBarsTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    @Test fun lightViewerAndDetailsHaveDarkBarsThenRestoreGalleryBars() =
        verifyViewerAndDetails(dark = false)

    @Test fun darkViewerAndDetailsHaveDarkBarsThenRestoreGalleryBars() =
        verifyViewerAndDetails(dark = true)

    private fun verifyViewerAndDetails(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val photo = MediaRecord(
            id = 680L, uri = "content://synthetic-atlas-test/images/680",
            name = "Fictional-680.jpg", relativePath = "DCIM/ATLAS-680/",
            sizeBytes = 1_234_567L, takenAtMillis = 1_600_000_000_000L,
            isVideo = false
        )
        val photos = listOf(photo)
        val viewerOpen = mutableStateOf(false)
        var window: Window? = null
        var originalContrast: Boolean? = null

        rule.setContent {
            val view = LocalView.current
            SideEffect {
                val activity = view.context.testActivity()
                window = activity?.window
                if (!viewerOpen.value) activity?.window?.let {
                    applyAtlasSystemBars(it, dark = dark)
                }
            }
            AtlasGalleryTheme(dark = dark) {
                if (viewerOpen.value) {
                    // Real routes paint a dark edge-to-edge Surface behind transparent
                    // Android 15+ system bars. Exercise that path, not a white test root.
                    AtlasDarkRoute {
                        GalleryViewer(photos, photo.uri, onBack = { viewerOpen.value = false })
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(
                        if (dark) androidx.compose.ui.graphics.Color(0xFF0B0D10)
                        else androidx.compose.ui.graphics.Color.White
                    ).testTag("test-light-gallery"))
                }
            }
        }

        rule.onNodeWithTag("test-light-gallery").assertExists()
        rule.runOnIdle {
            val w = requireNotNull(window)
            originalContrast = w.isNavigationBarContrastEnforced
            // Target 36 uses enforced edge-to-edge on API 35+: the Window
            // color getters can return transparent even while the real pixels
            // behind navigation are correct. Inspect those pixels below.
            if (Build.VERSION.SDK_INT < 35) {
                assertEquals(if (dark) Color.rgb(11, 13, 16) else Color.WHITE, w.navigationBarColor)
            }
            assertEquals(!dark, WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars)
            viewerOpen.value = true
        }
        rule.onNodeWithTag("atlas-viewer").assertExists()
        rule.runOnIdle {
            val w = requireNotNull(window)
            val controller = WindowCompat.getInsetsController(w, w.decorView)
            if (Build.VERSION.SDK_INT < 35) {
                assertEquals("status backdrop", Color.BLACK, w.statusBarColor)
                assertEquals("3-button nav backdrop", Color.BLACK, w.navigationBarColor)
            }
            assertFalse("no grey system contrast scrim", w.isNavigationBarContrastEnforced)
            assertFalse("status icons stay white", controller.isAppearanceLightStatusBars)
            assertFalse("navigation icons stay white", controller.isAppearanceLightNavigationBars)
        }
        rule.waitForIdle()
        assertDarkNavigationPixels("viewer $mode")
        saveUiEvidence("680-viewer-$mode.png")

        rule.onNodeWithTag("atlas-viewer-details-open").performClick()
        rule.onNodeWithTag("atlas-viewer-details").assertExists()
        // This synthetic item has no modified timestamp, so Album is the final row.
        // Scroll its value (below Type / Photo), not Resolution a few rows above.
        val facts = viewerDetailsFacts(photo)
        assertTrue("fixture must end at Album", facts.modifiedAtMillis == null)
        val albumValue = rule.onNodeWithText(facts.album, substring = false)
        albumValue.performScrollTo()
        albumValue.assertIsDisplayed()
        rule.waitForIdle()
        // Bounds include the actual value, not just its label. The last row
        // must clear Android's 3-button navigation area before the PNG is saved.
        val albumBottom = albumValue.fetchSemanticsNode().boundsInWindow.bottom
        val sheetBottom = rule.onNodeWithTag("atlas-viewer-details")
            .fetchSemanticsNode().boundsInWindow.bottom
        val navBottom = ViewCompat.getRootWindowInsets(requireNotNull(window).decorView)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: -1
        assertTrue("navigation inset unavailable: $navBottom", navBottom > 0)
        assertTrue("Album value overlaps navigation: album=$albumBottom sheet=$sheetBottom nav=$navBottom",
            albumBottom <= sheetBottom - navBottom)
        assertDarkNavigationPixels("details $mode")
        saveUiEvidence("680-details-$mode.png")

        rule.runOnIdle { viewerOpen.value = false }
        rule.onNodeWithTag("test-light-gallery").assertExists()
        rule.runOnIdle {
            val w = requireNotNull(window)
            val controller = WindowCompat.getInsetsController(w, w.decorView)
            if (Build.VERSION.SDK_INT < 35) {
                assertEquals(if (dark) Color.rgb(11, 13, 16) else Color.WHITE, w.statusBarColor)
                assertEquals(if (dark) Color.rgb(11, 13, 16) else Color.WHITE, w.navigationBarColor)
            }
            assertEquals(originalContrast, w.isNavigationBarContrastEnforced)
            assertEquals("gallery restores status icon contrast", !dark, controller.isAppearanceLightStatusBars)
            assertEquals("gallery restores nav icon contrast", !dark, controller.isAppearanceLightNavigationBars)
        }
    }

    /** Pixel proof of the actual 3-button band on edge-to-edge Android. */
    private fun assertDarkNavigationPixels(phase: String) {
        val shot = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "No screenshot for $phase" }
        try {
            var total = 0.0
            var samples = 0
            for (y in shot.height - 48 until shot.height step 4) {
                for (x in 0 until shot.width step 8) {
                    val c = shot.getPixel(x, y)
                    total += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) +
                        0.0722 * Color.blue(c)) / 255.0
                    samples++
                }
            }
            assertTrue("$phase: navigation area is light (mean ${total / samples})",
                total / samples < 0.35)
        } finally {
            shot.recycle()
        }
    }
}

private tailrec fun Context.testActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.testActivity()
    else -> null
}
