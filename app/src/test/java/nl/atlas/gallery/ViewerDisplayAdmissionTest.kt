package nl.atlas.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic records; Android permission/regrant remains emulator-gated. */
class ViewerDisplayAdmissionTest {
    private fun record(i: Long) = MediaRecord(
        i, "content://media/external_primary/images/media/$i",
        "fiction-$i.jpg", "DCIM/", 12L, i * 100L, false)

    @Test fun openViewerMayDisplay() {
        val list = listOf(record(1))
        assertTrue(canDisplayAuthorizedViewer(list, list[0].uri, 42L, true))
    }

    @Test fun fullAccessLossHidesPreviouslyDecodedViewer() {
        val list = listOf(record(1))
        assertFalse(canDisplayAuthorizedViewer(list, list[0].uri, 42L, false))
    }

    @Test fun backgroundRefreshKeepsViewerOpenOnItsOwnList() {
        // #505: a newer catalogue publication no longer closes the viewer.
        val opened = listOf(record(1), record(2))
        assertTrue(canDisplayAuthorizedViewer(opened, opened[1].uri, 42L, true))
        assertFalse(canDisplayAuthorizedViewer(opened, opened[1].uri, -1L, true))
    }

    @Test fun noSourceOrUriNeverShowsPhoto() {
        val list = listOf(record(1))
        assertFalse(canDisplayAuthorizedViewer(null, list[0].uri, 42L, true))
        assertFalse(canDisplayAuthorizedViewer(list, null, 42L, true))
        assertFalse(canDisplayAuthorizedViewer(list, "", 42L, true))
    }
}
