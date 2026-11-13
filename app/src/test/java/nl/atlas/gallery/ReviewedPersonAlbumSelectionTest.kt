package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All media objects in this suite are fictional; no Android provider access. */
class ReviewedPersonAlbumSelectionTest {
    private fun record(id: Long, volume: String = "external_primary",
        relative: String = "DCIM/Camera/", name: String = "photo.jpg",
        video: Boolean = false,
        uri: String = "content://media/$volume/images/media/$id") =
        MediaRecord(id = id, uri = uri, name = name, relativePath = relative,
            sizeBytes = 5L, takenAtMillis = 1000L,
            isVideo = video, volumeName = volume)

    @Test fun onlyPrimaryMediaStoreImagesCanBeReviewedMatches() {
        val primary = record(1)
        val alternate = record(2, volume = "1234-ABCD")
        val video = record(3, video = true)
        val invalid = record(4, uri = "file:///fake")
        assertTrue(isAuthorizedReviewedPrimaryImage(primary))
        assertFalse(isAuthorizedReviewedPrimaryImage(alternate))
        assertFalse(isAuthorizedReviewedPrimaryImage(video))
        assertFalse(isAuthorizedReviewedPrimaryImage(invalid))
    }

    @Test fun forgedCollectionTypesAndIdsDoNotBecomeReviewedFaces() {
        val wrongType = record(7, uri = "content://media/external_primary/video/media/7")
        val otherVolume = record(7, uri = "content://media/external/images/media/7")
        val nonnumeric = record(7, uri = "content://media/external_primary/images/media/7abc")
        val aggregate = record(7, uri = "content://media/external/images/media")
        assertFalse(isAuthorizedReviewedPrimaryImage(wrongType))
        assertFalse(isAuthorizedReviewedPrimaryImage(otherVolume))
        assertFalse(isAuthorizedReviewedPrimaryImage(nonnumeric))
        assertFalse(isAuthorizedReviewedPrimaryImage(aggregate))
    }

    @Test fun legacyPrimaryExternalAliasIsExplicitlyAdmitted() {
        val alias = record(8, volume = "external")
        assertTrue(isAuthorizedReviewedPrimaryImage(alias))
        assertFalse(isAuthorizedReviewedPrimaryImage(
            record(8, volume = "external", uri = "content://media/external_primary/images/media/8")
        ))
    }

    @Test fun invalidRelativePathsCannotClaimReviewedIdentities() {
        val traversal = record(1, relative = "DCIM/../private/")
        val doubled = record(2, relative = "DCIM//Camera/")
        assertFalse(isAuthorizedReviewedPrimaryImage(traversal))
        assertFalse(isAuthorizedReviewedPrimaryImage(doubled))
    }

    @Test fun crossVolumeAndVideoNeverInheritV4Identity() {
        val original = record(1)
        val other = record(2, volume = "ABCD-1234")
        val video = record(3, video = true)
        val path = original.absolutePath
        assertEquals(listOf(original),
            selectAuthorizedReviewedPersonAlbum(listOf(original, other, video), setOf(path)))
    }

    @Test fun ambiguousPrimaryUriClaimFailsClosed() {
        val a = record(1)
        val b = record(2)
        assertEquals(emptyList<MediaRecord>(),
            selectAuthorizedReviewedPersonAlbum(listOf(a,b), setOf(a.absolutePath)))
    }

    @Test fun sameUriRepeatedDoesNotImpersonateAnotherMedia() {
        val a = record(1)
        assertEquals(listOf(a,a),
            selectAuthorizedReviewedPersonAlbum(listOf(a,a), setOf(a.absolutePath)))
    }

    @Test fun blackAndWhiteModeOnlyKeepsMatchingNonvideos() {
        val mono = record(1, relative = "DCIM/Black and White/", name = "a.jpg")
        val regular = record(2, name = "b.jpg")
        assertEquals(listOf(mono), selectAuthorizedReviewedPersonAlbum(
            listOf(regular,mono), setOf(regular.absolutePath,mono.absolutePath), true))
    }

    @Test fun largeCancelledOrRevokedSourceDoesNotInventNames() {
        val invented = (0 until 150_000).map { i ->
            record(i.toLong(), name = "fiction-$i.jpg")
        }
        var checks = 0
        val matches = selectAuthorizedReviewedPersonAlbum(
            invented, setOf(invented.last().absolutePath),
            cancellationCheckpoint = { checks++ })
        assertEquals(1, matches.size)
        assertEquals(invented.last().uri, matches.single().uri)
        assertTrue(checks >= 500)
        assertEquals(emptyList<MediaRecord>(), selectAuthorizedReviewedPersonAlbum(
            emptyList(), setOf(invented.last().absolutePath)))
    }
}
