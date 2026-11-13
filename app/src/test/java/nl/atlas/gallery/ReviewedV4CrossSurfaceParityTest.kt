package nl.atlas.gallery

import nl.atlas.gallery.featuresearch.LocalSearchFacets
import nl.atlas.gallery.featuresearch.searchWithFacets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic reviewed-V4 path consistency across both ATLAS People routes. */
class ReviewedV4CrossSurfaceParityTest {
    private fun record(id: Long, volume: String = "external_primary",
        name: String = "photo.jpg", video: Boolean = false,
        uri: String = "content://media/$volume/images/media/$id") =
        MediaRecord(id, uri, name, "DCIM/Camera/", 64L, id * 1000L,
            video, volumeName = volume)

    private fun compare(items: List<MediaRecord>, paths: Set<String>): List<MediaRecord> {
        val person = PersonEntry(7L, "Reviewed Fictional", paths)
        val fromAlbum = selectAuthorizedReviewedPersonAlbum(items, paths)
        val fromSearch = searchWithFacets(items, listOf(person),
            LocalSearchFacets(confirmedPersonIds = setOf(7L)),
            mediaVolumeOf = { it.volumeName })
        assertEquals(fromAlbum.map { it.uri }, fromSearch.map { it.uri })
        return fromAlbum
    }

    @Test fun primaryAndLegacyAliasBothRemainRecognized() {
        val items = listOf(record(1, name = "primary.jpg"),
            record(2, volume = "external", name = "alias.jpg"),
            record(3, volume = "ABCD-1234", name = "secondary.jpg"),
            record(4, name = "video.mp4", video = true))
        assertEquals(2, compare(items, items.map { it.absolutePath }.toSet()).size)
    }

    @Test fun forgedTypesVolumesAndIdentifiersFailIdentically() {
        val items = listOf(
            record(1, uri = "content://media/external_primary/video/media/1"),
            record(2, uri = "content://media/external/images/media/2"),
            record(3, uri = "content://media/external_primary/images/media/nope"),
            record(4, uri = "content://media/external_primary/images/media/4/extra"))
        assertTrue(compare(items, items.map { it.absolutePath }.toSet()).isEmpty())
    }

    @Test fun twoDistinctPrimaryUrisClaimingOneReviewedPathAreAmbiguous() {
        val items = listOf(record(11), record(12))
        assertTrue(compare(items, setOf(items[0].absolutePath)).isEmpty())
    }

    @Test fun largeInventedCatalogueDoesNotDivergeAcrossSurfaces() {
        val items = (0 until 150_000).map { record(it.toLong(), name = "fiction-$it.jpg") }
        val confirmed = setOf(items[1234].absolutePath, items.last().absolutePath)
        assertEquals(2, compare(items, confirmed).size)
    }
}
