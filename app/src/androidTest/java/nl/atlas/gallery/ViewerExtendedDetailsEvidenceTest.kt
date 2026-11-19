package nl.atlas.gallery

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * #683: synthetic photo with real EXIF bytes and invented video track facts.
 * No personal media, storage permission, external provider or network.
 * Captures the actual Compose sheet on an S24+ sized emulator in each theme.
 */
@RunWith(AndroidJUnit4::class)
class ViewerExtendedDetailsEvidenceTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private fun syntheticJpeg(): File {
        val file = File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.cacheDir, "viewer-683-exif.jpg")
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:09:22 10:30:00")
            setAttribute(ExifInterface.TAG_MAKE, "Samsung")
            setAttribute(ExifInterface.TAG_MODEL, "SM-S928B")
            setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "200")
            // Platform ExifInterface converts these two decimal strings into rationals.
            // Passing "17/10" or "1/125" causes setAttribute to discard the tag.
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.7")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "63/10")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "23")
            setAttribute(ExifInterface.TAG_FLASH, "0")
            saveAttributes()
        }
        return file
    }

    @Test fun photoExifAndVideoRenderInDarkAndLight() {
        val image = syntheticJpeg()
        val extracted = FileInputStream(image).use(::readViewerExif)
        assertEquals("Samsung", extracted.cameraMake)
        assertEquals("SM-S928B", extracted.cameraModel)
        assertNotNull(extracted.originalDateMillis)
        assertNotNull(extracted.iso)
        assertNotNull(extracted.focal35Mm)
        assertEquals(1.7, extracted.apertureF ?: Double.NaN, 0.001)
        assertEquals(0.008, extracted.exposureSeconds ?: Double.NaN, 0.0001)
        val photo = MediaRecord(
            683L, "content://synthetic-atlas-test/images/683", "Camera-photo.jpg",
            "DCIM/Camera/", image.length(), 1_600_000_000_000L, false)
        val video = MediaRecord(
            684L, "content://synthetic-atlas-test/video/684", "Holiday-video.mp4",
            "DCIM/Videos/", 12_000_000L, 1_600_000_000_000L, true,
            durationMillis = 30_000L)
        val videoFacts = ViewerExtendedMetadata(
            videoDurationMillis = 61_500L, videoCodec = "H.264", videoFps = 29.97)
        val metadataReader = ViewerMetadataReader { _, media ->
            if (media.isVideo) videoFacts else FileInputStream(image).use(::readViewerExif)
        }
        val dimensionsReader = ViewerDimensionsReader { _, _ -> ViewerDimensions(4000, 3000) }

        var dark by mutableStateOf(true)
        var selected by mutableStateOf(photo)
        rule.setContent {
            CompositionLocalProvider(
                LocalViewerDimensionsReader provides dimensionsReader,
                LocalViewerMetadataReader provides metadataReader
            ) {
                AtlasGalleryTheme(dark = dark) {
                    Box(Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)) {
                        // The real sheet opens partially; evidence deliberately
                        // presents its fully opened state so rows can be captured.
                        ViewerDetailsSheet(
                            selected, onDismiss = {}, fullyExpanded = true)
                    }
                }
            }
        }
        for (night in listOf(true, false)) {
            rule.runOnIdle {
                dark = night
                selected = photo
            }
            val suffix = if (night) "dark" else "light"
            rule.waitUntil(8_000) {
                rule.onAllNodesWithText("Samsung SM-S928B").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("atlas-details-capture-date").assertIsDisplayed()
            rule.onNodeWithText("Camera-photo.jpg").assertExists()
            rule.onNodeWithText("DCIM/Camera").assertExists()
            rule.onNodeWithText("12.0 MP").assertExists()
            rule.onNodeWithText("Name").performScrollTo()
            rule.onNodeWithText("Camera-photo.jpg").assertIsDisplayed()
            rule.onNodeWithText("12.0 MP").assertIsDisplayed()
            saveUiEvidence("details-683-photo-top-$suffix.png")
            // Scroll to the last required camera fact, not merely the header.
            // Existence in semantics does not prove a row is in the screenshot.
            rule.onNodeWithText("1/125 s").performScrollTo()
            rule.onNodeWithText("Camera details").assertIsDisplayed()
            rule.onNodeWithText("Samsung SM-S928B").assertIsDisplayed()
            rule.onNodeWithText("f/1.7").assertIsDisplayed()
            rule.onNodeWithText("1/125 s").assertIsDisplayed()
            saveUiEvidence("details-683-photo-camera-$suffix.png")

            rule.runOnIdle { selected = video }
            rule.waitUntil(8_000) {
                rule.onAllNodesWithText("29.97 fps").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Holiday-video.mp4").assertExists()
            rule.onNodeWithText("29.97 fps").performScrollTo()
            rule.onNodeWithText("Video details").assertIsDisplayed()
            rule.onNodeWithText("H.264").assertIsDisplayed()
            rule.onNodeWithText("29.97 fps").assertIsDisplayed()
            saveUiEvidence("details-683-video-$suffix.png")
        }
    }
}
