package nl.atlas.gallery

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure invented MediaStore URI strings: no photos opened or exported. */
@RunWith(AndroidJUnit4::class)
class ViewerShareHandoffTest {
    private fun media(uri: String, video: Boolean = false) = MediaRecord(
        42L, uri, "Invented test media", "DCIM/TEST/", 1L, 0L, video
    )

    @Test fun imageSharesExactlyOneUriWithTemporaryReadGrantOnly() {
        val uri = "content://media/external/images/media/42"
        val intent = ViewerShareHandoff.makeSendIntent(media(uri))!!
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/*", intent.type)
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(Uri.parse(uri), stream)
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(Uri.parse(uri), intent.clipData!!.getItemAt(0).uri)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(intent.component)
        assertNull(intent.`package`)
    }

    @Test fun videoIsSharedAsVideoNotImage() {
        val uri = "content://media/external/video/media/74"
        val intent = ViewerShareHandoff.makeSendIntent(media(uri, true))!!
        assertEquals("video/*", intent.type)
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(Uri.parse(uri), stream)
    }

    @Test fun rejectsFilesystemAndUnrelatedContentWithoutStartingShare() {
        listOf(
            "file:///sdcard/DCIM/private.jpg",
            "/storage/emulated/0/Pictures/private.jpg",
            "https://example.org/photo.jpg",
            "content://com.example.documents/image/42",
            "content://media",
            "content://media/external/images/media", // collection, not one item
            "content://media/external/images/media/not-an-id",
            "content://media/external/images/media/-7",
            "content://media/external/file/42"
        ).forEach { uri ->
            assertFalse("Must not expose $uri", ViewerShareHandoff.eligible(media(uri)))
            assertNull(ViewerShareHandoff.makeSendIntent(media(uri)))
        }

        assertFalse("Image item cannot be shared as a video",
            ViewerShareHandoff.eligible(media(
                "content://media/external/images/media/42", true)))
    }
}
