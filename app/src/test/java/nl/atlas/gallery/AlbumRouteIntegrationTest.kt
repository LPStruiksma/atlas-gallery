package nl.atlas.gallery

import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fictional per-volume album fixtures: no provider, user data or MediaStore access. */
class AlbumRouteIntegrationTest {
    private fun record(id: Long, volume: String, folder: String, video: Boolean = false) =
        MediaRecord(id = id, uri = "content://fiction/$volume/$id", name = "item$id.jpg",
            relativePath = folder, sizeBytes = 10, takenAtMillis = 1000,
            isVideo = video, volumeName = volume)

    @Test fun exactFolderAndVolumeAreRequired() {
        val route = AlbumRoute("DCIM/Camera/", AlbumMediaFilter.ALL, "external_primary")
        assertTrue(matchesAlbumRoute(record(1, "external_primary", "DCIM/Camera/"), route))
        assertFalse(matchesAlbumRoute(record(1, "0123-4567", "DCIM/Camera/"), route))
        assertFalse(matchesAlbumRoute(record(2, "external_primary", "Pictures/Camera/"), route))
        assertFalse(matchesAlbumRoute(record(3, "external_primary", "DCIM/Camera/sub/"), route))
    }

    @Test fun videosAndPhotosNeverCrossMediaFilter() {
        val photo = record(10, "external_primary", "DCIM/", false)
        val video = record(11, "external_primary", "DCIM/", true)
        val photos = AlbumRoute("DCIM/", AlbumMediaFilter.PHOTOS, "external_primary")
        val videos = photos.copy(mediaFilter = AlbumMediaFilter.VIDEOS)
        assertTrue(matchesAlbumRoute(photo, photos))
        assertFalse(matchesAlbumRoute(video, photos))
        assertTrue(matchesAlbumRoute(video, videos))
        assertFalse(matchesAlbumRoute(photo, videos))
    }

    @Test fun emptyRelativePathIsAnAlbumNotTheAllItemsSentinel() {
        val root = AlbumRoute("", AlbumMediaFilter.ALL, "external_primary")
        assertTrue(matchesAlbumRoute(record(1, "external_primary", ""), root))
        assertFalse(matchesAlbumRoute(record(2, "external_primary", "DCIM/"), root))
        assertFalse(matchesAlbumRoute(record(3, "9999-1111", ""), root))
    }

    @Test fun fictional150kRecordsProduceExactBucketMembership() {
        val items = (0 until 150_000).map { i ->
            record(i.toLong(), if (i % 2 == 0) "external_primary" else "1234-5678",
                if (i % 3 == 0) "DCIM/" else "Pictures/", i % 5 == 0)
        }
        val route = AlbumRoute("DCIM/", AlbumMediaFilter.PHOTOS, "external_primary")
        val selected = items.count { matchesAlbumRoute(it, route) }
        val expected = (0 until 150_000).count { it % 2 == 0 && it % 3 == 0 && it % 5 != 0 }
        assertEquals(expected, selected)
    }
}
