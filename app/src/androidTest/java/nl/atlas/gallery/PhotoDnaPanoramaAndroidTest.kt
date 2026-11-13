package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #49: a real MediaStore provider round trip with invented 8000x1000 pixels.
 * The provider can preserve panorama aspect ratios when handling square
 * loadThumbnail requests. This must hash without copying a huge HARDWARE bitmap.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaPanoramaAndroidTest {
    @Test fun eightThousandByThousandPanoramaIsHashed() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ATLAS_DNA_49_PANORAMA.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-PhotoDna-Panorama/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            val original = Bitmap.createBitmap(8_000, 1_000, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(original)
                val paint = Paint()
                for (stripe in 0 until 80) {
                    val gray = 10 + stripe * 3
                    paint.color = Color.rgb(gray, gray, gray)
                    canvas.drawRect(stripe * 100f, 0f, (stripe + 1) * 100f, 1_000f, paint)
                }
                requireNotNull(resolver.openOutputStream(uri)).use { output ->
                    assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 88, output))
                }
            } finally {
                original.recycle()
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)

            val record = MediaRecord(
                id = requireNotNull(uri.lastPathSegment).toLong(),
                uri = uri.toString(),
                name = "ATLAS_DNA_49_PANORAMA.jpg",
                relativePath = "Pictures/ATLAS-PhotoDna-Panorama/",
                sizeBytes = 1L,
                takenAtMillis = 1L,
                isVideo = false,
                modifiedAtSeconds = 1_700_000_000L,
                widthPixels = 8_000,
                heightPixels = 1_000
            )
            var oversizedSkips = 0
            val hash = visualHashAuthorizedImage(
                resolver, record, stillAuthorized = { true },
                checkCancelled = {}, onOverBudget = { oversizedSkips++ })
            assertNotNull("panorama should not be skipped", hash)
            assertTrue(requireNotNull(hash).informative)
            assertEquals(0, oversizedSkips)
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
