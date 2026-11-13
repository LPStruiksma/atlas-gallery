package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic URI-only editor state: no Samsung installation, Photos or MediaStore.
 */
class EditorReturnStateTest {
    @Test fun successIsConsumedOnceOnReturnAndOnlySelectedUriReloads() {
        val target = "content://media/external/images/media/8"
        val neighbor = "content://media/external/images/media/9"
        val starting = EditorReturnState()
        assertSame(starting, starting.resumed())
        assertEquals(0L, starting.revision)
        assertFalse(starting.mustReload(target))
        val pending = starting.launched(target, didLaunch = true)
        assertEquals(target, pending.pendingUri)
        assertEquals(0L, pending.revision)
        assertFalse(pending.mustReload(target))
        val returned = pending.resumed()
        assertNull(returned.pendingUri)
        assertEquals(target, returned.refreshedUri)
        assertTrue(returned.mustReload(target))
        assertFalse(returned.mustReload(neighbor))
        assertEquals(1L, returned.revision)
        assertSame(returned, returned.resumed())
        assertEquals(1L, returned.resumed().revision)
    }

    @Test fun canceledOrUnavailableEditorDoesNotInvalidateWholeFilmstrip() {
        val url = "content://media/external/images/media/8"
        val beginning = EditorReturnState()
        assertSame(beginning, beginning.launched(url, didLaunch = false))
        assertFalse(beginning.resumed().mustReload(url))
        assertSame(beginning, beginning.launched("file:///DCIM/foo.jpg", didLaunch = true))
        assertSame(beginning, beginning.launched("content://another.provider/photo", didLaunch = true))
    }

    @Test fun refreshKeySeparatesRevisionsAndPreviewSizes() {
        val uri = "content://media/external/images/media/100"
        val thumbnail = editedPhotoMemoryKey(uri, 1L, 144)
        val preview = editedPhotoMemoryKey(uri, 1L, 800)
        val large = editedPhotoMemoryKey(uri, 1L, 2400)
        val nextRevision = editedPhotoMemoryKey(uri, 2L, 800)
        assertNotEquals(thumbnail, preview)
        assertNotEquals(preview, large)
        assertNotEquals(preview, nextRevision)
        assertEquals(preview, editedPhotoMemoryKey(uri, 1L, 800))
        for (size in listOf(0, -1, 8193)) {
            assertThrows(IllegalArgumentException::class.java) {
                editedPhotoMemoryKey(uri, 1L, size)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            editedPhotoMemoryKey(uri, 0L, 800)
        }
        assertThrows(IllegalArgumentException::class.java) {
            editedPhotoMemoryKey("file:///not-medialibrary.jpg", 1L, 800)
        }
    }

    @Test fun secondEditorReturnRefreshesSecondImageWithoutInvalidatingFirst() {
        val first = "content://media/external/images/media/8"
        val second = "content://media/external/images/media/9"
        val firstReturn = EditorReturnState().launched(first, true).resumed()
        val pendingAgain = firstReturn.launched(second, true)
        assertTrue(pendingAgain.mustReload(first))
        assertFalse(pendingAgain.mustReload(second))
        val secondReturn = pendingAgain.resumed()
        assertFalse(secondReturn.mustReload(first))
        assertTrue(secondReturn.mustReload(second))
        assertEquals(2L, secondReturn.revision)
        assertNull(secondReturn.pendingUri)
    }
}
