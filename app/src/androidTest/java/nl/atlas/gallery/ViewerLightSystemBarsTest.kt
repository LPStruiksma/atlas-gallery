package nl.atlas.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
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
import androidx.core.view.WindowCompat
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
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
            name = "Fictional-680.jpg", relativePath = "DCIM/Camera/",
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
                    GalleryViewer(photos, photo.uri, onBack = { viewerOpen.value = false })
                } else {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                        .testTag("test-light-gallery"))
                }
            }
        }

        rule.onNodeWithTag("test-light-gallery").assertExists()
        rule.runOnIdle {
            val w = requireNotNull(window)
            originalContrast = w.isNavigationBarContrastEnforced
            assertEquals(if (dark) Color.rgb(11, 13, 16) else Color.WHITE, w.navigationBarColor)
            assertEquals(!dark, WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars)
            viewerOpen.value = true
        }
        rule.onNodeWithTag("atlas-viewer").assertExists()
        rule.runOnIdle {
            val w = requireNotNull(window)
            val controller = WindowCompat.getInsetsController(w, w.decorView)
            assertEquals("status backdrop", Color.BLACK, w.statusBarColor)
            assertEquals("3-button nav backdrop", Color.BLACK, w.navigationBarColor)
            assertFalse("no grey system contrast scrim", w.isNavigationBarContrastEnforced)
            assertFalse("status icons stay white", controller.isAppearanceLightStatusBars)
            assertFalse("navigation icons stay white", controller.isAppearanceLightNavigationBars)
        }
        rule.waitForIdle()
        saveUiEvidence("680-viewer-$mode.png")

        rule.onNodeWithTag("atlas-viewer-details-open").performClick()
        rule.onNodeWithTag("atlas-viewer-details").assertExists()
        // The final metadata row must be scrollable into the area above the nav buttons.
        rule.onNodeWithText("Resolution").performScrollTo()
        rule.onNodeWithText("Resolution").assertIsDisplayed()
        rule.waitForIdle()
        saveUiEvidence("680-details-$mode.png")

        rule.runOnIdle { viewerOpen.value = false }
        rule.onNodeWithTag("test-light-gallery").assertExists()
        rule.runOnIdle {
            val w = requireNotNull(window)
            val controller = WindowCompat.getInsetsController(w, w.decorView)
            assertEquals(if (dark) Color.rgb(11, 13, 16) else Color.WHITE, w.statusBarColor)
            assertEquals(if (dark) Color.rgb(11, 13, 16) else Color.WHITE, w.navigationBarColor)
            assertEquals(originalContrast, w.isNavigationBarContrastEnforced)
            assertEquals("gallery restores status icon contrast", !dark, controller.isAppearanceLightStatusBars)
            assertEquals("gallery restores nav icon contrast", !dark, controller.isAppearanceLightNavigationBars)
        }
    }
}

private tailrec fun Context.testActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.testActivity()
    else -> null
}
