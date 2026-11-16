package nl.atlas.gallery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DAILY-4: synthetic MediaStore identifiers only. Never opens, reads, or sends
 * a real photo. Captures the chooser before it can leave the test process.
 */
@RunWith(AndroidJUnit4::class)
class ViewerShareHandoffContractTest {
    private fun media(uri: String, video: Boolean = false) = MediaRecord(
        42L, uri, "Synthetic selected media", "DCIM/ATLAS-TEST/", 1L, 0L, video
    )

    private class CapturingContext(
        private val failure: RuntimeException? = null
    ) : ContextWrapper(null) {
        var launches = 0
        var chooser: Intent? = null

        override fun startActivity(intent: Intent) {
            launches++
            failure?.let { throw it }
            chooser = intent
        }
    }

    @Suppress("DEPRECATION")
    private fun assertSelectedStream(intent: Intent, expected: Uri) {
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(expected, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertNotNull(intent.clipData)
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(expected, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_HTML_TEXT))
    }

    @Suppress("DEPRECATION")
    private fun assertChooser(media: MediaRecord, expectedMime: String, title: String) {
        val context = CapturingContext()
        assertEquals(ViewerShareHandoff.Result.STARTED, ViewerShareHandoff.launch(context, media))
        assertEquals(1, context.launches)
        val chooser = requireNotNull(context.chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(title, chooser.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, chooser.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, chooser.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(1, chooser.clipData!!.itemCount)
        val expectedUri = Uri.parse(media.uri)
        assertEquals(expectedUri, chooser.clipData!!.getItemAt(0).uri)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertSelectedStream(send, expectedUri)
        assertEquals(expectedMime, send.type)
        assertNull(chooser.component)
        assertNull(chooser.`package`)
        assertFalse(send.toUri(0).contains("file://"))
    }

    @Test fun authorizedPhotoAndVideoUseReadOnlySingleItemChooser() {
        assertChooser(media("content://media/external/images/media/42"), "image/*", "Share photo")
        assertChooser(media("content://media/external/video/media/73", true), "video/*", "Share video")
    }

    @Test fun invalidCollectionForeignAuthorityMismatchedTypeAndIdsNeverLaunch() {
        val invalid = listOf(
            media("content://media/external/images/media"),
            media("content://media/external/video/media"),
            media("content://media/external/images/media/42", true),
            media("content://media/external/video/media/42", false),
            media("content://foreign.provider/external/images/media/42"),
            media("content://media/external/images/media/0"),
            media("content://media/external/images/media/-1"),
            media("content://media/external/images/media/not-a-row"),
            media("content://media/external/images/media/9223372036854775808"),
            media("file:///storage/emulated/0/DCIM/private.jpg"),
            media("/storage/emulated/0/DCIM/private.jpg")
        )
        for (item in invalid) {
            val context = CapturingContext()
            assertFalse("Eligible unexpectedly: ${item.uri}", ViewerShareHandoff.eligible(item))
            assertNull(ViewerShareHandoff.makeSendIntent(item))
            assertEquals(ViewerShareHandoff.Result.NOT_SHAREABLE, ViewerShareHandoff.launch(context, item))
            assertEquals("Started chooser for ${item.uri}", 0, context.launches)
        }
    }

    @Test fun missingShareTargetDoesNotCrashOrRelaxValidation() {
        val context = CapturingContext(ActivityNotFoundException("No resolver"))
        assertEquals(
            ViewerShareHandoff.Result.UNAVAILABLE,
            ViewerShareHandoff.launch(context, media("content://media/external/images/media/42"))
        )
        assertEquals(1, context.launches)
        assertEquals(
            ViewerShareHandoff.Result.NOT_SHAREABLE,
            ViewerShareHandoff.launch(context, media("content://media/external/images/media/0"))
        )
        assertEquals(1, context.launches)
    }

    @Test fun revokedPermissionDoesNotCrashOrRelaxValidation() {
        val context = CapturingContext(SecurityException("Permission revoked"))
        assertEquals(
            ViewerShareHandoff.Result.UNAVAILABLE,
            ViewerShareHandoff.launch(context, media("content://media/external/video/media/73", true))
        )
        assertEquals(1, context.launches)
        assertEquals(
            ViewerShareHandoff.Result.NOT_SHAREABLE,
            ViewerShareHandoff.launch(context, media("content://foreign.provider/external/video/media/73", true))
        )
        assertEquals(1, context.launches)
    }
}
