package nl.atlas.gallery

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Intent contract and UI selection with invented MediaStore IDs only. */
@RunWith(AndroidJUnit4::class)
class PhotoEditorHandoffTest {
    @get:Rule val rule = createComposeRule()

    private val photo = MediaRecord(
        42L, "content://media/external/images/media/42", "Fictional image",
        "DCIM/Synthetic/", 512L, 100L, false
    )
    private val video = MediaRecord(
        13L, "content://media/external/video/media/13", "Fictional video",
        "DCIM/Synthetic/", 1024L, 101L, true
    )

    @Test fun publicEditorIntentGrantsOnlySelectedPhotoAccess() {
        val uri = Uri.parse(photo.uri)
        assertTrue(PhotoEditorHandoff.eligible(photo))
        assertFalse(PhotoEditorHandoff.eligible(video))
        assertFalse(PhotoEditorHandoff.eligible(
            photo.copy(uri = "file:///storage/emulated/0/DCIM/example.jpg")
        ))
        assertFalse(PhotoEditorHandoff.eligible(
            photo.copy(uri = "content://not-media.example/private/42")
        ))

        val edit = PhotoEditorHandoff.makeIntent(uri, Intent.ACTION_EDIT, true)
        assertEquals(Intent.ACTION_EDIT, edit.action)
        assertEquals("com.sec.android.gallery3d", edit.`package`)
        assertEquals(uri, edit.data)
        assertEquals("image/*", edit.type)
        assertEquals(uri, edit.clipData!!.getItemAt(0).uri)
        assertEquals(1, edit.clipData!!.itemCount)
        assertTrue(edit.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(edit.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertNull(edit.getParcelableExtra<Uri>(android.provider.MediaStore.EXTRA_OUTPUT))

        val viewer = PhotoEditorHandoff.makeIntent(uri, Intent.ACTION_VIEW, true)
        assertEquals("com.sec.android.gallery3d", viewer.`package`)
        assertTrue(viewer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, viewer.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        val fallback = PhotoEditorHandoff.makeIntent(uri, Intent.ACTION_EDIT, false)
        assertNull(fallback.`package`)
        assertEquals(uri, fallback.data)
        try {
            PhotoEditorHandoff.makeIntent(Uri.parse("file:///private.jpg"),
                Intent.ACTION_EDIT, true)
            fail("file URI should not escape to another editor")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun viewerEditButtonTargetsFocusedImageButNotVideo() {
        val items = listOf(photo, video, photo.copy(
            id = 77, uri = "content://media/external/images/media/77",
            name = "Second fictional image"
        ))
        val edited = mutableListOf<Long>()
        rule.setContent {
            MaterialTheme {
                GalleryViewer(items, photo.uri, onEdit = { edited.add(it.id) }) {}
            }
        }
        rule.onNodeWithTag("atlas-edit-photo").assertExists().performClick()
        rule.runOnIdle { assertEquals(listOf(42L), edited) }

        rule.onNodeWithTag("atlas-thumbnail-1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-edit-photo").assertDoesNotExist()

        rule.onNodeWithTag("atlas-thumbnail-2").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-edit-photo").assertExists().performClick()
        rule.runOnIdle { assertEquals(listOf(42L, 77L), edited) }
    }
}
