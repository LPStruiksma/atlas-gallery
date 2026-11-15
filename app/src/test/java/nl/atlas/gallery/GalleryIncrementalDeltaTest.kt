package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Deterministic synthetic MediaStore rows; no Android provider or real photos. */
class GalleryIncrementalDeltaTest {
    private fun row(id: Long, generation: Long = 1L, video: Boolean = false,
        volume: String = "external_primary") = MediaRecord(
        id = id, uri = "content://media/$volume/${if (video) "video" else "images"}/media/$id",
        name = "fictional_$id.jpg", relativePath = "DCIM/Camera/", sizeBytes = id,
        takenAtMillis = id * 1000L, isVideo = video, volumeName = volume,
        providerVersion = "fixture-version", generationModified = generation
    )

    @Test fun insertChangesOnlyOneItemAndRetainsExistingReferences() {
        val a = row(1); val b = row(2)
        val query = FakeProvider(listOf(a, b))
        val first = listOf(a)
        val result = mergeGalleryMediaDelta(first, query.changedSince(0))
        assertEquals(listOf(a, b), result)
        assertSame(a, result[0])
        assertEquals(1, query.queries)
    }

    @Test fun deleteRemovesOnlyAddressedRowWithoutRescanning() {
        val a = row(1); val b = row(2)
        val provider = FakeProvider(listOf(a, b))
        provider.delete(b)
        val result = mergeGalleryMediaDelta(listOf(a, b), provider.changedSince(0))
        assertEquals(listOf(a), result)
        assertSame(a, result.single())
        assertEquals(1, provider.queries)
    }

    @Test fun burstOfFiftyIsOneCoalescedDiffWithoutDuplicates() {
        val baseline = row(1)
        val burst = (2L..51L).map { row(it, generation = 2) }
        val provider = FakeProvider(listOf(baseline) + burst)
        val result = mergeGalleryMediaDelta(listOf(baseline), provider.changedSince(1))
        assertEquals(51, result.size)
        assertEquals(51, result.map { GalleryMediaDeltaKey.of(it) }.toSet().size)
        assertSame(baseline, result.first())
        assertEquals(1, provider.queries)
    }

    @Test fun sameNumericIdInAnotherVolumeOrKindNeverCollides() {
        val photo = row(9)
        val video = row(9, video = true)
        val external = row(9, volume = "aaaa-bbbb")
        val result = mergeGalleryMediaDelta(listOf(photo, video), listOf(
            GalleryMediaDeltaChange(GalleryMediaDeltaKey.of(external), external)))
        assertEquals(3, result.size)
        assertSame(photo, result[0])
        assertSame(video, result[1])
    }

    @Test fun modifiedGenerationReplacesOnlyItsOwnRecord() {
        val prior = row(3); val unaffected = row(4)
        val newer = row(3, generation = 5)
        val result = mergeGalleryMediaDelta(listOf(prior, unaffected), listOf(
            GalleryMediaDeltaChange(GalleryMediaDeltaKey.of(newer), newer)))
        assertSame(newer, result[0])
        assertSame(unaffected, result[1])
        assertSame(prior, mergeGalleryMediaDelta(listOf(prior), listOf(
            GalleryMediaDeltaChange(GalleryMediaDeltaKey.of(prior), row(3))))[0])
    }

    @Test fun deleteAndReplaceBatchIsAtomicAndDeduplicated() {
        val first = row(1); val second = row(2)
        val result = mergeGalleryMediaDelta(
            listOf(first, second),
            listOf(null to GalleryMediaDeltaKey.of(first),
                row(2, 4) to GalleryMediaDeltaKey.of(second),
                row(3) to GalleryMediaDeltaKey.of(row(3)))
                .map { (value, key) -> GalleryMediaDeltaChange(key, value) }
        )
        assertEquals(listOf(2L, 3L), result.map { it.id })
        assertEquals(4L, result[0].generationModified)
    }

    /** Emulates provider query counts and generation-based changed rows. */
    private class FakeProvider(seed: List<MediaRecord>) {
        private val rows = LinkedHashMap<GalleryMediaDeltaKey, MediaRecord>()
        private val deleted = LinkedHashSet<GalleryMediaDeltaKey>()
        var queries = 0; private set
        init { seed.forEach { rows[GalleryMediaDeltaKey.of(it)] = it } }
        fun delete(item: MediaRecord) {
            val key = GalleryMediaDeltaKey.of(item)
            rows.remove(key)
            deleted.add(key)
        }
        fun changedSince(generation: Long): List<GalleryMediaDeltaChange> {
            queries++
            return rows.values.filter { it.generationModified > generation }
                .map { GalleryMediaDeltaChange(GalleryMediaDeltaKey.of(it), it) } +
                deleted.map { GalleryMediaDeltaChange(it, null) }
        }
    }
}
