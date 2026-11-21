package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #404: rotation only re-admits still-authorized keys from the identical source identity. */
class SelectionRotationStateTest {
    private fun media(n: Int) = (0 until n).map {
        MediaRecord(it.toLong(), "content://media/external/images/media/$it",
            "synthetic-$it.jpg", "DCIM/Synthetic/", 1L, it.toLong(), false)
    }

    private fun saved(items: List<MediaRecord>) = SelectionRotationState.capture(
        GalleryBatchSelection.of(items, 17L, setOf(items[0].uri, items[1].uri)),
        path = "DCIM/Synthetic/", volume = "external_primary", filter = "ALL",
        grantBits = "true:false:false"
    )!!

    private fun restore(
        state: SelectionRotationState, current: List<MediaRecord>,
        epoch: Long = 17L, grant: String = "true:false:false",
        path: String = "DCIM/Synthetic/", volume: String = "external_primary",
        filter: String = "ALL", authorized: Boolean = true
    ) = state.restore(current, epoch, grant, path, volume, filter, authorized)

    @Test fun samePublicationRestoresExactKeysAndDropsMissingKeys() {
        val items = media(4)
        val snapshot = saved(items)
        val rebased = restore(snapshot, items.toList())!!
        assertEquals(setOf(items[0].uri, items[1].uri), rebased.uris)
        assertEquals(2, rebased.count)
        assertEquals(17L, rebased.epoch)
        val narrowed = restore(snapshot, items.drop(1))!!
        assertEquals(setOf(items[1].uri), narrowed.uris)
        assertEquals(1, narrowed.count)
        assertNull(restore(snapshot, items.drop(2)))
    }

    @Test fun epochGrantOrAlbumScopeChangesNeverReAdmitSelection() {
        val items = media(3)
        val snapshot = saved(items)
        assertNull(restore(snapshot, items, epoch = 18L))
        assertNull(restore(snapshot, items, grant = "false:false:false"))
        assertNull(restore(snapshot, items, authorized = false))
        assertNull(restore(snapshot, items, path = "DCIM/Elsewhere/"))
        assertNull(restore(snapshot, items, volume = "external"))
        assertNull(restore(snapshot, items, filter = "PHOTOS"))
    }

    @Test fun malformedAndExcessSnapshotsFailClosed() {
        val items = media(3)
        val snapshot = saved(items)
        assertNull(restore(snapshot.copy(keys = setOf("")), items))
        assertNull(restore(snapshot.copy(epoch = -1L), items))
        assertNull(restore(snapshot.copy(keys = (0..SelectionRotationState.MAX_KEYS)
            .map { "content://synthetic/$it" }.toSet()), items))
    }

    @Test fun capturesOnlyBoundedDistinctKeys() {
        val items = media(SelectionRotationState.MAX_KEYS + 1)
        assertNotNull(SelectionRotationState.capture(
            GalleryBatchSelection.of(items, 17L, items.take(500).map { it.uri }.toSet()),
            null, null, null, "grant"))
        assertNull(SelectionRotationState.capture(
            GalleryBatchSelection.of(items, 17L, items.map { it.uri }.toSet()),
            null, null, null, "grant"))
    }
}
