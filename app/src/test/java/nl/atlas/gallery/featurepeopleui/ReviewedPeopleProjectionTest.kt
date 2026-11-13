package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedPeopleProjectionTest {
    private fun image(id: Long, filename: String, video: Boolean = false) =
        MediaRecord(id, "content://media/external/images/media/$id",
            filename, "DCIM/", 200, 123, video)

    @Test fun exactConfirmedPathsOnlyAndNoFilenameGuessing() {
        val a = image(1, "a.jpg")
        val b = image(2, "b.jpg")
        val p = PersonEntry(7, "Fictional Ana",
            setOf(a.absolutePath, "/storage/emulated/0/DCIM/missing.jpg"))
        val result = buildReviewedPeopleProjection(listOf(a, b), listOf(p), "grant1",
            isAuthorizedPrimaryImage = { true })
        assertEquals(1, result.profiles.single().accessiblePhotos)
        assertEquals(2, result.profiles.single().confirmedPhotoReferences)
        assertEquals(a.uri, result.profiles.single().coverUri)
    }

    @Test fun conflictingMediaUrisCannotCreateMisattributedPortrait() {
        val source = image(1, "same.jpg")
        val competing = source.copy(id = 9,
            uri = "content://media/external/images/media/9")
        val p = PersonEntry(1, "Fictional Person", setOf(source.absolutePath))
        val result = buildReviewedPeopleProjection(listOf(source, competing),
            listOf(p), "grant1", isAuthorizedPrimaryImage = { true })
        assertEquals(1, result.ambiguousPathCount)
        assertEquals(0, result.profiles.single().accessiblePhotos)
        assertNull(result.profiles.single().coverUri)
    }

    @Test fun conflictingReviewedPersonIdCannotPublishMislabelledPeopleCards() {
        val a = image(1, "a.jpg")
        val b = image(2, "b.jpg")
        val first = PersonEntry(7L, "Fictional Ana", setOf(a.absolutePath))
        val conflicting = PersonEntry(7L, "Fictional Bea", setOf(b.absolutePath))
        val roster = listOf(first, conflicting)
        val source = listOf(a, b)
        val result = buildReviewedPeopleProjection(
            source, roster, "grant1", isAuthorizedPrimaryImage = { true }
        )
        assertTrue(result.profiles.isEmpty())
        // No silent last-wins portrait ownership; publication is still
        // bound to the same exact roster and authorized source generation.
        assertTrue(currentReviewedPeopleProjectionOrNull(
            result, source, roster, "grant1")!!.isEmpty())
        assertNull(currentReviewedPeopleProjectionOrNull(
            result, source, roster, "grant2"))
        // Two separately confirmed IDs with identical names ARE valid.
        val homonyms = listOf(first, conflicting.copy(id = 8L, name = first.name))
        assertEquals(2, buildReviewedPeopleProjection(
            source, homonyms, "grant1", isAuthorizedPrimaryImage = { true }
        ).profiles.size)
    }

    @Test fun sameMediaUriDuplicateRowsDoNotCreateFalseAmbiguity() {
        val a = image(1, "a.jpg")
        val p = PersonEntry(1, "Fictional Person", setOf(a.absolutePath))
        val result = buildReviewedPeopleProjection(listOf(a, a),
            listOf(p), "grant1", isAuthorizedPrimaryImage = { true })
        assertEquals(0, result.ambiguousPathCount)
        assertEquals(1, result.profiles.single().accessiblePhotos)
    }

    @Test fun unauthorizedOtherVolumeOrVideoIsNotAReviewedImage() {
        val a = image(1, "a.jpg")
        val foreign = image(2, "b.jpg")
        val clip = image(3, "c.mp4", video = true)
        val p = PersonEntry(1, "Fictional Person",
            setOf(a.absolutePath, foreign.absolutePath, clip.absolutePath))
        val result = buildReviewedPeopleProjection(
            listOf(a, foreign, clip), listOf(p), "grant1",
            isAuthorizedPrimaryImage = { it.id == 1L || it.id == 3L }
        )
        assertEquals(1, result.profiles.single().accessiblePhotos)
        assertEquals(a.uri, result.profiles.single().coverUri)
    }

    @Test fun onlyExactCurrentGrantAndObjectIdentityMayPublish() {
        val a = image(1, "a.jpg")
        val p = PersonEntry(1, "Fictional Person", setOf(a.absolutePath))
        val source = listOf(a)
        val persons = listOf(p)
        val result = buildReviewedPeopleProjection(source, persons, "grant1",
            isAuthorizedPrimaryImage = { true })
        assertEquals(1, currentReviewedPeopleProjectionOrNull(
            result, source, persons, "grant1")?.size)
        assertNull(currentReviewedPeopleProjectionOrNull(
            result, source.toList(), persons, "grant1"))
        assertNull(currentReviewedPeopleProjectionOrNull(
            result, source, persons.toList(), "grant1"))
        assertNull(currentReviewedPeopleProjectionOrNull(
            result, source, persons, "grant2"))
        assertNull(currentReviewedPeopleProjectionOrNull(
            result, source, persons, null))
    }

    @Test fun largeSyntheticInventoryRemainsCancellableAndBounded() {
        val person = PersonEntry(1, "Fictional Person",
            setOf("/storage/emulated/0/DCIM/last.jpg"))
        val catalog = (1..150000).map { image(it.toLong(), "fixture_$it.jpg") } +
            image(150001, "last.jpg")
        var checks = 0
        val snapshot = buildReviewedPeopleProjection(
            catalog, listOf(person), "grant1",
            isAuthorizedPrimaryImage = { true },
            cancellationCheckpoint = { checks++ }
        )
        assertEquals(1, snapshot.profiles.single().accessiblePhotos)
        assertTrue(checks >= 292)
    }

    @Test fun blankGrantRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            buildReviewedPeopleProjection(emptyList(), emptyList(), "",
                isAuthorizedPrimaryImage = { true })
        }
    }
}
