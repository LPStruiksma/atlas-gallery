package nl.atlas.gallery

import java.io.File
import java.nio.file.Files
import nl.atlas.gallery.featurecuration.CurationVisualHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #604: synthetic MediaStore rows and fake thumbnails, no personal media. */
class PhotoDnaVisualCacheStoreTest {
    private val grant = GalleryMediaGrant(images = true, videos = false, selected = false)

    private fun photo(id: Long, volume: String = "external") = MediaRecord(
        id = id,
        uri = "content://media/$volume/images/media/$id",
        volumeName = volume,
        providerVersion = "synthetic-provider-v1",
        name = "fixture-$id.jpg",
        relativePath = "DCIM/Fixtures/",
        sizeBytes = 1000L + id,
        takenAtMillis = id * 1000L,
        isVideo = false,
        modifiedAtSeconds = 1_700_000_000L,
        generationModified = 7L
    )

    private fun value(record: MediaRecord): CurationVisualHash =
        CurationVisualHash(0x123456789abcde00L xor (record.id and 7L),
            informative = record.id % 5L != 0L)

    private fun populated(source: List<MediaRecord>): PhotoDnaVisualCache =
        PhotoDnaVisualCache().also { cache ->
            cache.bindGrant(grant)
            source.forEach { cache.put(it, value(it)) }
        }

    private fun withFile(test: (File, PhotoDnaVisualCacheStore) -> Unit) {
        val directory = Files.createTempDirectory("dna-604-test").toFile()
        try {
            val file = File(directory, "photo-dna-dhash-v1.bin")
            test(file, PhotoDnaVisualCacheStore(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun freshScanRequiredEvenIfCachedInventoryCarriesSamePermissionBits() {
        val source = listOf(photo(1))
        val scan = GalleryPublishedInventory(4L, source, grant)
        val coldSnapshot = GalleryPublishedInventory(4L, source, grant, fromCache = true)
        val changed = GalleryPublishedInventory(5L, source, grant.copy(videos = true))
        assertTrue(photoDnaPersistentPublicationAllowed(grant, scan))
        assertFalse(photoDnaPersistentPublicationAllowed(grant, coldSnapshot))
        assertFalse(photoDnaPersistentPublicationAllowed(grant, changed))
        assertFalse(photoDnaPersistentPublicationAllowed(
            grant.copy(selected = true), scan))
        assertFalse(photoDnaPersistentPublicationAllowed(
            GalleryMediaGrant(false, false, false), scan))
    }

    @Test fun coldProcessRestoresEveryUnchangedHashAndDecodesOnlyEditedRevision() = withFile {
        _, store ->
        val source = (1L..12L).map(::photo)
        val original = populated(source)
        assertEquals(12, store.save(original, grant, source, { true }))
        assertTrue(store.diskBytes() > 0L)

        val reloaded = PhotoDnaVisualCache()
        reloaded.bindGrant(grant)
        assertEquals(12, store.restoreInto(reloaded, grant, source, { true }))
        assertEquals(12, reloaded.size)
        for (record in source) assertEquals(value(record), reloaded.get(record))

        val tokens = PhotoDnaTokens("synthetic-604-cold")
        val bytePlan = planPhotoDna(source, tokens, hash = { null })
        var decodes = 0
        val decode: (MediaRecord) -> CurationVisualHash? = {
            decodes++
            value(it)
        }
        val expected = planPhotoDnaVisual(source, tokens, bytePlan.plan,
            populated(source), decode, { true })
        assertEquals(0, expected.decodedFiles)
        val warm = planPhotoDnaVisual(source, tokens, bytePlan.plan,
            reloaded, decode, { true })
        assertEquals(0, decodes)
        assertEquals(0, warm.decodedFiles)
        assertEquals(expected.evidence, warm.evidence)
        assertEquals(expected.assets, warm.assets)
        assertEquals(expected.deferredFiles, warm.deferredFiles)
        assertEquals(expected.hashedFiles, warm.hashedFiles)

        val edited = source.toMutableList()
        edited[3] = edited[3].copy(generationModified = 8L)
        val newProcess = PhotoDnaVisualCache()
        newProcess.bindGrant(grant)
        assertEquals(11, store.restoreInto(newProcess, grant, edited, { true }))
        val changedPlan = planPhotoDna(edited, tokens, hash = { null })
        val changed = planPhotoDnaVisual(edited, tokens, changedPlan.plan,
            newProcess, decode, { true })
        assertEquals(1, changed.decodedFiles)
        assertEquals(1, decodes)
        assertEquals(expected.evidence.map { it.kind },
            changed.evidence.map { it.kind })
        assertEquals(expected.hashedFiles, changed.hashedFiles)
        assertEquals(expected.deferredFiles, changed.deferredFiles)
        println("PHOTO_DNA_604 coldDecodes=0 editedDecodes=1 " +
            "entries=12 diskBytes=" + store.diskBytes())
    }

    @Test fun scopeChangesRevokeAndSelectionRepickNeverReadOldDisk() = withFile {
        file, store ->
        val source = (1L..4L).map(::photo)
        assertEquals(4, store.save(populated(source), grant, source, { true }))
        val missing = GalleryMediaGrant(false, true, false)
        assertEquals(0, store.restoreInto(PhotoDnaVisualCache(), missing, source, { true }))
        store.purgeUnlessGrant(missing)
        assertFalse(file.exists())

        store.save(populated(source), grant, source, { true })
        val narrowed = grant.copy(videos = true)
        assertEquals(0, store.restoreInto(PhotoDnaVisualCache(), narrowed, source, { true }))
        assertFalse(file.exists())

        store.save(populated(source), grant, source, { true })
        // Android 14+ selected photos may be re-picked without permission-bit changes.
        val selected = grant.copy(selected = true)
        assertTrue(selected.selectionScoped)
        assertEquals(0, store.restoreInto(PhotoDnaVisualCache(), selected, source, { true }))
        assertEquals(0, store.save(populated(source), selected, source, { true }))
        store.purgeUnlessGrant(selected)
        assertFalse(file.exists())
    }

    @Test fun providerVersionAndVolumeReplacementsCannotReuseRecycledIds() = withFile {
        file, store ->
        val source = listOf(photo(1), photo(2))
        store.save(populated(source), grant, source, { true })
        val newVersion = source.map { it.copy(providerVersion = "new-provider") }
        val cache = PhotoDnaVisualCache()
        cache.bindGrant(grant)
        assertEquals(0, store.restoreInto(cache, grant, newVersion, { true }))
        assertEquals(0, cache.size)
        assertFalse(file.exists())

        store.save(populated(source), grant, source, { true })
        assertEquals(0, store.restoreInto(cache, grant,
            source.map { it.copy(volumeName = "different") }, { true }))
        assertFalse(file.exists())
    }

    @Test fun corruptPartialVersionMismatchAndTrailingBytesRecomputeSafely() = withFile {
        file, store ->
        val source = listOf(photo(1), photo(2))
        fun refill() {
            assertEquals(2, store.save(populated(source), grant, source, { true }))
        }
        fun rejected() {
            val target = PhotoDnaVisualCache()
            target.bindGrant(grant)
            assertEquals(0, store.restoreInto(target, grant, source, { true }))
            assertEquals(0, target.size)
            assertFalse(file.exists())
        }
        refill()
        file.writeBytes(byteArrayOf(1, 2, 3))
        rejected()
        refill()
        val bytes = file.readBytes()
        bytes[7] = 99 // version
        file.writeBytes(bytes)
        rejected()
        refill()
        file.appendBytes(byteArrayOf(42))
        rejected()
        refill()
        val truncated = file.readBytes()
        file.writeBytes(truncated.copyOf(truncated.size - 5))
        rejected()
    }

    @Test fun revokedMidRestoreOrWriteNeverCommitsUnauthorizedBytes() = withFile {
        file, store ->
        val source = (1L..2_048L).map(::photo)
        store.save(populated(source), grant, source, { true })
        val next = PhotoDnaVisualCache()
        next.bindGrant(grant)
        var checks = 0
        val restored = store.restoreInto(next, grant, source,
            authorized = { ++checks < 3 })
        assertEquals(0, restored)
        assertEquals(0, next.size)

        checks = 0
        assertEquals(0, store.save(populated(source), grant, source,
            authorized = { ++checks < 3 }))
        // An interrupted save must not publish its partial temporary file.
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
        store.purgeUnlessGrant(GalleryMediaGrant(false, false, false))
        assertFalse(file.exists())
    }

    @Test fun diskIsBoundedAndOnlySuccessfulHashesSurvive() = withFile {
        _, store ->
        // AbstractList avoids retaining another 250k MediaRecords just for this test.
        val source = object : AbstractList<MediaRecord>() {
            override val size: Int get() = 250_000
            override fun get(index: Int): MediaRecord = photo(index + 1L)
        }
        val cache = PhotoDnaVisualCache()
        cache.bindGrant(grant)
        for (id in 1L..250_000L) {
            if (id % 20L != 0L) cache.put(photo(id), value(photo(id)))
            else cache.putFailure(photo(id))
        }
        assertEquals(237_500, cache.size)
        assertEquals(12_500, cache.failedSize)
        assertEquals(237_500, store.save(cache, grant, source, { true }))
        val bytes = store.diskBytes()
        assertTrue("disk bytes exceed 8 MiB: " + bytes, bytes <= 8L * 1024 * 1024)
        val reloaded = PhotoDnaVisualCache()
        reloaded.bindGrant(grant)
        assertEquals(237_500, store.restoreInto(reloaded, grant, source, { true }))
        assertEquals(0, reloaded.failedSize)
        assertNull(reloaded.get(photo(20)))
        assertEquals(value(photo(19)), reloaded.get(photo(19)))
        assertTrue("retained memory target", reloaded.primitiveBytes + 160L <=
            250_000L * 40L)
        println("PHOTO_DNA_604 diskBytes=" + bytes +
            " entries=" + reloaded.size +
            " primitiveBytes=" + reloaded.primitiveBytes)
    }
}
