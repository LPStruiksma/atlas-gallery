package nl.atlas.gallery

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Test

/** #551 host helpers on synthetic keys and records only. */
class PersonPageHostTest {
    private fun face(n: Int) = NativeFaceObservationId(n.toString(16).padStart(32, '0'))
    private fun record(id: Long, taken: Long, video: Boolean = false) = MediaRecord(
        id, "content://synthetic/$id", "synthetic-$id.jpg", "DCIM/", 1L, taken, video)

    @Test fun facesAreGroupedByAuthorizedUriAndEachMediumResolvesOnce() {
        val a = IndexedMedia("k1", "r1")
        val b = IndexedMedia("k2", "r1")
        val gone = IndexedMedia("k3", "r1")
        val calls = mutableListOf<IndexedMedia>()
        val grouped = facesByAuthorizedUri(mapOf(
            face(1).localHex to a, face(2).localHex to a,
            face(3).localHex to b, face(4).localHex to gone)) { media ->
            calls += media
            if (media == gone) null else "content://synthetic/${media.key}"
        }
        assertEquals(mapOf("content://synthetic/k1" to setOf(face(1), face(2)),
            "content://synthetic/k2" to setOf(face(3))), grouped)
        assertEquals("one resolve per medium", 3, calls.size)
    }

    @Test fun pageListsPhotosAndVideosNewestFirstWhateverTheGallerySort() {
        val sorted = listOf(record(1, 100), record(2, 300, video = true), record(3, 200),
            record(4, 400))
        val page = selectPersonPageMedia(sorted.sortedBy { it.sizeBytes + it.id },
            setOf("content://synthetic/1", "content://synthetic/2", "content://synthetic/3"))
        assertEquals(listOf(2L, 3L, 1L), page.map { it.id })
        assertEquals(emptyList<MediaRecord>(), selectPersonPageMedia(sorted, emptySet()))
    }
}
