package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** #568: Samsung hidden albums (parity research GRID:A7) stored, toggled and applied to Photos only. */
class HiddenAlbumsTest {
    private fun record(id: Long, path: String, volume: String = "external_primary") =
        MediaRecord(id, "content://media/$volume/images/media/$id", "$id.jpg", path, 1L, id, false,
            volumeName = volume)

    private val screenshots = AlbumBucketKey("external_primary", "Pictures/Screenshots/")
    private val sdCamera = AlbumBucketKey("1234-abcd", "DCIM/Camera/")

    @Test fun storedSetRoundTripsAndDropsMalformedLines() {
        val hidden = setOf(screenshots, sdCamera)
        assertEquals(hidden, HiddenAlbums.decode(HiddenAlbums.encode(hidden)))
        assertEquals(emptySet<AlbumBucketKey>(), HiddenAlbums.decode(null))
        assertEquals(setOf(screenshots), HiddenAlbums.decode(
            "external_primary\u0001Pictures/Screenshots/\nnoseparator\n\u0001x\ny\u0001\na\u0001b\u0001c"))
    }

    @Test fun toggleHidesAndUnhides() {
        val once = HiddenAlbums.toggled(emptySet(), screenshots)
        assertEquals(setOf(screenshots), once)
        assertEquals(emptySet<AlbumBucketKey>(), HiddenAlbums.toggled(once, screenshots))
    }

    @Test fun photosDropHiddenAlbumsOnTheirVolumeOnlyAndKeepOrder() {
        val media = listOf(
            record(1, "DCIM/Camera/"), record(2, "Pictures/Screenshots/"),
            record(3, "DCIM/Camera/", "1234-abcd"), record(4, "Pictures/Screenshots/", "1234-abcd"),
            record(5, "Pictures/Screenshots/Sub/"))
        val shown = HiddenAlbums.filterPhotos(media, setOf(screenshots, sdCamera))
        assertEquals(listOf(1L, 4L, 5L), shown.map { it.id })
    }

    @Test fun nothingHiddenOrNothingMatchedIsTheSameList() {
        val media = listOf(record(1, "DCIM/Camera/"), record(2, "Pictures/Trip/"))
        assertSame(media, HiddenAlbums.filterPhotos(media, emptySet()))
        assertSame(media, HiddenAlbums.filterPhotos(media, setOf(screenshots)))
    }

    @Test fun albumsGridLeavesOutHiddenAlbums() {
        fun album(path: String, volume: String = "external_primary") =
            AlbumSummary(path, path, 1, 1, 0, null, false, 0L, volumeName = volume)
        val all = listOf(album("DCIM/Camera/"), album("Pictures/Screenshots/"), album("DCIM/Camera/", "1234-abcd"))
        assertSame(all, HiddenAlbums.visibleAlbums(all, emptySet()))
        assertEquals(listOf(all[0]), HiddenAlbums.visibleAlbums(all, setOf(screenshots, sdCamera)))
    }
}
