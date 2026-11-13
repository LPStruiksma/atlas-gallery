package nl.atlas.gallery

import nl.atlas.gallery.featurealbums.*
import org.junit.Assert.*
import org.junit.Test

class AlbumSamsungSortTest {
    private fun record(index: Int, path: String) = MediaRecord(
        index.toLong(), "content://atlas.synthetic/$index",
        "fictional-$index.png", path, 1024L, index.toLong(), false
    )

    @Test fun pinnedAlbumsAlwaysLeadAndNamesSortNaturally() {
        val paths = listOf("Pictures/IMG 10/", "DCIM/Camera/", "Pictures/IMG 9/",
            "Pictures/Screenshots/", "Pictures/Black and White/", "Pictures/Zebra/")
        val media = paths.mapIndexed { index, path -> record(index, path) }
        listOf(AlbumSort.NAME, AlbumSort.COUNT, AlbumSort.RECENT, AlbumSort.CUSTOM).forEach { sort ->
            val albums = buildAlbumSummaries(media, sort)
            assertEquals(listOf("Camera", "Screenshots", "Black and White"),
                albums.take(3).map { it.title })
        }
        assertEquals(listOf("IMG 9", "IMG 10", "Zebra"),
            buildAlbumSummaries(media, AlbumSort.NAME).drop(3).map { it.title })
    }

    @Test fun customOrderUsesExactFolderVolumeAndKeepsPinnedAlbums() {
        val paths = listOf("DCIM/Camera/", "Pictures/IMG 9/", "Pictures/IMG 10/", "Other/IMG 9/")
        val media = paths.mapIndexed { index, path -> record(index, path) }
        val ordered = buildAlbumSummaries(media, AlbumSort.CUSTOM, customOrder = listOf(
            AlbumBucketKey("external", "Pictures/IMG 10/"),
            AlbumBucketKey("external", "Other/IMG 9/"),
            AlbumBucketKey("wrong-volume", "Pictures/IMG 9/")
        ))
        assertEquals(listOf("Camera", "IMG 10", "IMG 9", "IMG 9"),
            ordered.map { it.title })
        assertEquals("Other/IMG 9/", ordered[2].relativePath)
        assertEquals("Pictures/IMG 9/", ordered[3].relativePath)
    }

    @Test fun dragMovesOnlyUnpinnedAlbumsAndPersistsExactBuckets() {
        val paths = listOf("DCIM/Camera/", "Pictures/Screenshots/", "Pictures/A/",
            "Pictures/B/", "Pictures/C/")
        val media = paths.mapIndexed { index, path -> record(index, path) }
        val shown = buildAlbumSummaries(media, AlbumSort.NAME)
        assertEquals(listOf("Camera", "Screenshots", "A", "B", "C"), shown.map { it.title })

        val moved = moveUnpinnedAlbum(shown, 4, 2)
        assertEquals(listOf("Camera", "Screenshots", "C", "A", "B"), moved.map { it.title })
        // Pinned albums are neither sources nor targets; no-ops return the same list.
        assertSame(shown, moveUnpinnedAlbum(shown, 0, 3))
        assertSame(shown, moveUnpinnedAlbum(shown, 3, 1))
        assertSame(shown, moveUnpinnedAlbum(shown, 3, 3))
        assertSame(shown, moveUnpinnedAlbum(shown, 3, 9))

        val saved = customAlbumOrder(moved)
        assertEquals(listOf("Pictures/C/", "Pictures/A/", "Pictures/B/"), saved.map { it.relativePath })
        // Re-projecting with the saved order reproduces the dragged order.
        assertEquals(moved.map { it.title },
            buildAlbumSummaries(media, AlbumSort.CUSTOM, customOrder = saved).map { it.title })
    }

    @Test fun newestAccessiblePhotoRemainsDefaultCover() {
        val media = listOf(record(1, "DCIM/Camera/"),
            record(2, "DCIM/Camera/").copy(isVideo = true))
        assertEquals(media.first().uri,
            buildAlbumSummaries(media).single().coverUri)
    }
}
