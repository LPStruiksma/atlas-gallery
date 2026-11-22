package nl.atlas.gallery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #602: the cached catalogue carries its MediaStore checkpoint, so a cold
 * start (every app update) resumes with a delta instead of a full rescan.
 * Invented rows, provider versions and generations only.
 */
class ColdStartCheckpointTest {
    private val full = GalleryMediaGrant(true, true, false)
    private val checkpoint = GalleryMediaCheckpoint(
        linkedMapOf("external_primary" to "v1", "1234-abcd" to "v9"),
        linkedMapOf("external_primary" to 500L, "1234-abcd" to 20L))

    private fun record(id: Long, video: Boolean = false, volume: String = "external_primary",
                       generation: Long = id, name: String = "Invented $id.jpg") = MediaRecord(
        id = id, uri = "content://media/$volume/${if (video) "video" else "images"}/media/$id",
        name = name, relativePath = "DCIM/Camera/", sizeBytes = id * 10,
        takenAtMillis = id * 1000, isVideo = video, durationMillis = 0L,
        modifiedAtSeconds = id, volumeName = volume, providerVersion = "v1",
        generationModified = generation, widthPixels = 4000, heightPixels = 3000,
        orientationDegrees = 0, isFavorite = false, favoriteKnown = true,
        dateSource = MediaDateSource.TAKEN)

    private fun encode(items: List<MediaRecord>, cp: GalleryMediaCheckpoint?) =
        ByteArrayOutputStream().also {
            GalleryInventoryCacheCodec.encode(it, full, items, checkpoint = cp)
        }.toByteArray()

    @Test fun theCheckpointRoundTripsWithTheCatalogue() {
        val items = (1L..50L).map { record(it) }
        val decoded = requireNotNull(GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(encode(items, checkpoint))))
        assertEquals(items, decoded.items)
        assertEquals(checkpoint, decoded.checkpoint)
        assertNull(GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(encode(items, null)))!!.checkpoint)
    }

    @Test fun aVersionTwoCacheStillOpensWithoutACheckpoint() {
        // The v2 layout: magic, version 2, grant bits, string table, rows.
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x41544c43); out.writeInt(2); out.writeByte(3)
            out.writeInt(0); out.writeInt(0)
        }
        val decoded = requireNotNull(GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(bytes.toByteArray())))
        assertTrue(decoded.items.isEmpty())
        assertNull("after the update, the first cold start runs the full scan", decoded.checkpoint)
    }

    @Test fun anUnusableCheckpointIsNotStored() {
        val noVersion = GalleryMediaCheckpoint(mapOf("external_primary" to null),
            mapOf("external_primary" to 1L))
        assertNull(GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(encode(listOf(record(1)), noVersion)))!!.checkpoint)
        // A corrupt volume count rejects the whole file, never a guessed checkpoint.
        val bytes = encode(listOf(record(1)), checkpoint)
        bytes[9] = 0x7f
        assertNull(GalleryInventoryCacheCodec.decode(ByteArrayInputStream(bytes)))
    }

    @Test fun resumeOnlyFromTheSameProviderAndNoGenerationBehind() {
        val now = checkpoint.generations.mapValues { (_, g) -> g!! + 3 }
        assertTrue(coldDeltaMayResume(checkpoint, checkpoint.versions, now))
        assertTrue(coldDeltaMayResume(checkpoint, checkpoint.versions, checkpoint.generations))
        assertFalse("provider reset", coldDeltaMayResume(checkpoint,
            checkpoint.versions + ("external_primary" to "v2"), now))
        assertFalse("card removed", coldDeltaMayResume(checkpoint,
            mapOf("external_primary" to "v1"), mapOf("external_primary" to 900L)))
        assertFalse("generation behind", coldDeltaMayResume(checkpoint, checkpoint.versions,
            now + ("1234-abcd" to 19L)))
        assertFalse("API 29 shape", coldDeltaCheckpointUsable(
            GalleryMediaCheckpoint(checkpoint.versions, emptyMap())))
    }

    /**
     * The cold delta's result equals what a full scan would list, by identity
     * and revision, for an add, an edit, a delete, a video and a second volume.
     */
    @Test fun deltaPlusPresencePassEqualsAFullScan() {
        val cached = listOf(record(1), record(2), record(3), record(4, video = true),
            record(5, volume = "1234-abcd"))
        // While ATLAS was not running: 2 edited, 3 deleted, 6 added, 5's card row deleted.
        val edited = record(2, generation = 600, name = "Invented 2 edited.jpg")
        val added = record(6, generation = 601)
        val provider = listOf(record(1), edited, record(4, video = true), added)
        val changedSinceCheckpoint = provider.filter { it.generationModified > 500 }
        val present = provider.map(GalleryMediaDeltaKey::of).toSet()
        val changes = changedSinceCheckpoint.map {
            GalleryMediaDeltaChange(GalleryMediaDeltaKey.of(it), it)
        } + coldDeltaDeletions(cached, present)
        val merged = mergeGalleryMediaDelta(cached, changes)
        assertEquals(provider.associateBy(GalleryMediaDeltaKey::of),
            merged.associateBy(GalleryMediaDeltaKey::of))
        assertEquals(provider.size, merged.size)
        // Unchanged rows keep their objects (no second copy of the library).
        assertTrue(merged.first() === cached.first())
    }

    @Test fun anUnchangedLibraryReadsNoFullRows() {
        val cached = (1L..1000L).map { record(it) }
        val present = cached.map(GalleryMediaDeltaKey::of).toSet()
        assertTrue(coldDeltaDeletions(cached, present).isEmpty())
        assertTrue(mergeGalleryMediaDelta(cached, emptyList()) === cached)
        assertNotNull(COLD_DELTA_MAX_CHANGES)
    }

    private fun presence(rows: List<MediaRecord>, changedAbove: Long) = rows.map {
        GalleryColdPresence(GalleryMediaDeltaKey.of(it), it.generationModified > changedAbove)
    }

    /**
     * Review of #977: the cold delta lists exactly what the full scan lists,
     * in the same order (images then videos, each newest first), for an add,
     * an edit, a delete, a new video and a second volume.
     */
    @Test fun assembledDeltaEqualsTheFullScanListInOrder() {
        // Full-scan order: images DATE_ADDED DESC (here: id DESC), then videos.
        val cached = listOf(record(5, volume = "1234-abcd"), record(3), record(2), record(1),
            record(4, video = true))
        val edited = record(2, generation = 600, name = "Invented 2 edited.jpg")
        val addedImage = record(6, generation = 601)
        val addedVideo = record(7, video = true, generation = 602)
        // While ATLAS was not running: 6 added (newest), 2 edited, 3 deleted, 7 added.
        val providerImages = listOf(addedImage, record(5, volume = "1234-abcd"), edited, record(1))
        val providerVideos = listOf(addedVideo, record(4, video = true))
        val fullScan = providerImages + providerVideos
        val changed = (providerImages + providerVideos).filter { it.generationModified > 500 }
            .associateBy(GalleryMediaDeltaKey::of)
        val assembled = assembleColdDelta(cached,
            presence(providerImages, 500), presence(providerVideos, 500), changed)!!
        assertEquals(fullScan.map(GalleryMediaDeltaKey::of), assembled.map(GalleryMediaDeltaKey::of))
        assertEquals(fullScan, assembled)
        // Unchanged rows keep their objects (no second copy of the library).
        assertTrue(assembled[1] === cached[0])
        assertTrue(assembled[5] === cached[4])
    }

    @Test fun anUnchangedLibraryAssemblesToTheCachedListItself() {
        val cached = (1000L downTo 1L).map { record(it) }
        val assembled = assembleColdDelta(cached, presence(cached, 5000), emptyList(), emptyMap())
        assertTrue(assembled === cached)
    }

    @Test fun aChangedRowThatWasNotReReadFallsBackToTheFullScan() {
        val cached = listOf(record(2), record(1))
        val rows = listOf(GalleryColdPresence(GalleryMediaDeltaKey.of(record(2)), changed = true),
            GalleryColdPresence(GalleryMediaDeltaKey.of(record(1)), changed = false))
        assertNull(assembleColdDelta(cached, rows, emptyList(), emptyMap()))
        // A present row nobody has a record for: also the full scan.
        val unknown = listOf(GalleryColdPresence(GalleryMediaDeltaKey.of(record(9)), false))
        assertNull(assembleColdDelta(cached, unknown, emptyList(), emptyMap()))
    }

    @Test fun aRowAddedDuringTheReadGoesFirstInItsCollection() {
        val cached = listOf(record(1), record(3, video = true))
        val late = record(8, generation = 700)
        val assembled = assembleColdDelta(cached, presence(listOf(record(1)), 500),
            presence(listOf(record(3, video = true)), 500),
            mapOf(GalleryMediaDeltaKey.of(late) to late))!!
        assertEquals(listOf(8L, 1L, 3L), assembled.map { it.id })
    }
}
