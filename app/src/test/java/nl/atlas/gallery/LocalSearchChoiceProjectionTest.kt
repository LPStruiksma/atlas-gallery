package nl.atlas.gallery

import nl.atlas.gallery.featurealbums.AlbumSummary
import nl.atlas.gallery.featuresearch.SearchSuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchChoiceProjectionTest {
    private fun album(path: String, volume: String) = AlbumSummary(
        relativePath = path,
        title = path.trimEnd('/').substringAfterLast('/').ifEmpty { "On device" },
        mediaCount = 5, photoCount = 5, videoCount = 0, coverUri = null,
        coverIsVideo = false, latestAtMillis = 1L, volumeName = volume)

    @Test fun emptyQueryNeverShowsPrivateNames() {
        assertEquals(emptyList<Any>(), buildAuthorizedSearchChoiceSources(
            listOf(album("DCIM/Camera/", "external_primary")),
            listOf(PersonEntry(1L, "Miriam", emptySet())), " "))
    }

    @Test fun sameAlbumNameAcrossVolumesKeepsTypedLocations() {
        val result = buildAuthorizedSearchChoiceSources(
            listOf(album("DCIM/Camera/", "external_primary"),
                   album("DCIM/Camera/", "AAAA-BBBB")),
            emptyList(), "camera")
        assertEquals(2, result.size)
        assertEquals(setOf("external_primary", "AAAA-BBBB"),
            result.mapNotNull { it.albumVolume }.toSet())
        assertTrue(result.all { it.kind == SearchSuggestionKind.ALBUM })
    }

    @Test fun identicalReviewedNamesRemainDistinctPeople() {
        val result = buildAuthorizedSearchChoiceSources(
            emptyList(), listOf(PersonEntry(3L, "Kim", emptySet()),
                PersonEntry(4L, "Kim", emptySet())), "kim")
        assertEquals(setOf(3L, 4L), result.mapNotNull { it.personId }.toSet())
    }

    @Test fun resultIsBoundedAndOnlyContainsMatchingTitles() {
        val albums = (0 until 40).map { album("Pictures/Camera${it}/", "external_primary") }
        val result = buildAuthorizedSearchChoiceSources(albums, emptyList(), "camera")
        assertEquals(10, result.size)
        assertTrue(result.all { it.title.startsWith("Camera") })
    }

    @Test fun workerChecksCancellationAt150kScale() {
        var checkpoints = 0
        val albums = (0 until 150_000).map { album("Pictures/Nope${it}/", "external_primary") }
        val result = buildAuthorizedSearchChoiceSources(
            albums, emptyList(), "camera", { checkpoints++ })
        assertTrue(result.isEmpty())
        assertTrue(checkpoints > 250)
    }
}
