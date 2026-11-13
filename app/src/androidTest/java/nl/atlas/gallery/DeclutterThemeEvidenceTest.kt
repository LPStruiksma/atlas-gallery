package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.darkGalleryColors
import nl.atlas.gallery.featuredesign.lightGalleryColors
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs

/** #678: 1080x2340 evidence with an owned synthetic image; never use personal media. */
@RunWith(AndroidJUnit4::class)
class DeclutterThemeEvidenceTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    @Test fun deckAndReviewAreReadableInBothThemesWithRealThumbnails() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ATLAS_678_FICTIONAL.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-Declutter-Test/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ))
        try {
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(AndroidColor.rgb(33, 145, 192))
                resolver.openOutputStream(uri)!!.use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            val deck = listOf(MediaRecord(678L, uri.toString(), "ATLAS_678_FICTIONAL.png",
                "Pictures/ATLAS-Declutter-Test/", 4096L, 678L, false))
            var dark by mutableStateOf(false)
            rule.setContent {
                AtlasGalleryTheme(dark = dark) {
                    DeclutterScreen("Pictures/ATLAS-Declutter-Test/", deck,
                        onExit = {}, onTrashRequest = {})
                }
            }
            rule.onNodeWithTag("atlas-header-back").assertExists()
            rule.onNodeWithTag("atlas-header-title").assertTextEquals("Declutter")
            rule.onNodeWithText("← Gallery").assertDoesNotExist()
            rule.onNodeWithText("Largest first", substring = true).assertDoesNotExist()
            rule.onNodeWithTag("atlas-declutter-photo-progressive").assertExists()
            verifySurface(lightGalleryColors.surfaceContainerLowest)
            verifyHeaderInk(lightGalleryColors.onBackground)
            capture("declutter-678-deck-light.png")

            rule.onNodeWithTag("atlas-declutter-discard").performClick()
            rule.onNodeWithTag("atlas-declutter-review").performClick()
            rule.onNodeWithText("Review staged files").assertExists()
            rule.onNodeWithTag("atlas-declutter-thumbnail").assertExists()
            verifySurface(lightGalleryColors.surfaceContainerLowest)
            verifyHeaderInk(lightGalleryColors.onBackground)
            capture("declutter-678-review-light.png")

            rule.runOnIdle { dark = true }
            verifySurface(darkGalleryColors.surfaceContainerLowest)
            verifyHeaderInk(darkGalleryColors.onBackground)
            capture("declutter-678-review-dark.png")
            rule.onNodeWithText("Return to swiping").performClick()
            verifySurface(darkGalleryColors.surfaceContainerLowest)
            verifyHeaderInk(darkGalleryColors.onBackground)
            capture("declutter-678-deck-dark.png")
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        Thread.sleep(700)
        rule.waitForIdle()
        saveUiEvidence(name)
    }

    /** #678: header glyphs must inherit readable ink from the active route theme. */
    private fun verifyHeaderInk(expected: Color) {
        for (tag in listOf("atlas-header-title", "atlas-header-back")) {
            val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            var matches = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (abs(pixel.red - expected.red) < 0.12f &&
                        abs(pixel.green - expected.green) < 0.12f &&
                        abs(pixel.blue - expected.blue) < 0.12f) {
                        matches++
                    }
                }
            }
            assertTrue("$tag needs readable themed ink $expected; only $matches pixels match",
                matches > 10)
        }
    }

    private fun verifySurface(expected: Color) {
        val pixels = rule.onNodeWithTag("atlas-declutter-surface").captureToImage().toPixelMap()
        val sample = pixels[12, 12]
        assertTrue("Declutter backdrop $sample must follow theme $expected",
            abs(sample.red - expected.red) < 0.04f &&
            abs(sample.green - expected.green) < 0.04f &&
            abs(sample.blue - expected.blue) < 0.04f)
    }
}
