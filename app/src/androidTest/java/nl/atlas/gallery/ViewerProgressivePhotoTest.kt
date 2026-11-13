package nl.atlas.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Android Compose clock tests real GalleryViewer progressive request gating.
 * Pixel content is invented in the isolated emulator's app-private cache.
 * No user gallery or MediaStore permissions, no People/Trash operations.
 */
@RunWith(AndroidJUnit4::class)
class ViewerProgressivePhotoTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fullResolutionRequestOnlyAfterSelectedPhotoSettles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "atlas-viewer-fake-photo.png")
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(android.graphics.Color.CYAN)
            file.outputStream().use { stream ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally { image.recycle() }
        val photo = MediaRecord(
            id = 1L, uri = Uri.fromFile(file).toString(),
            name = "Fictional viewer fixture", relativePath = "DCIM/ATLAS-TEST/",
            sizeBytes = file.length(), takenAtMillis = 1L, isVideo = false
        )

        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                GalleryViewer(items = listOf(photo), startUri = photo.uri, onBack = {})
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-photo-surface").assertExists()
        rule.onNodeWithTag("atlas-photo-hires", useUnmergedTree = true).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(240)
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-photo-hires", useUnmergedTree = true).assertExists()
        rule.mainClock.autoAdvance = true
    }
}
