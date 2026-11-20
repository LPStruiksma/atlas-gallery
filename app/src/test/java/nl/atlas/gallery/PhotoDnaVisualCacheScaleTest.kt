package nl.atlas.gallery

import nl.atlas.gallery.featurecuration.CurationVisualHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #604: all records and thumbnail results are synthetic. No MediaStore access. */
class PhotoDnaVisualCacheScaleTest {
    private val grant = GalleryMediaGrant(images = true, videos = false, selected = false)

    private fun photo(id: Long, volume: String = "external") = MediaRecord(
        id = id,
        uri = "content://media/$volume/images/media/$id",
        volumeName = volume,
        name = "synthetic-$id.jpg",
        relativePath = "DCIM/Synthetic/",
        sizeBytes = 1000L + id,
        takenAtMillis = id * 1000,
        isVideo = false,
        modifiedAtSeconds = 1_700_000_000L,
        generationModified = 7L
    )

    private fun heapBytes(): Long {
        System.gc()
        System.runFinalization()
        System.gc()
        val vm = Runtime.getRuntime()
        return vm.totalMemory() - vm.freeMemory()
    }

    @Test fun retainedStorageFor250kPhotosIsUnderFortyBytesPerEntry() {
        // Warm up URI-prefix and cache code before the heap sample.
        val warm = PhotoDnaVisualCache(2)
        warm.put(photo(1), CurationVisualHash(1L, true))
        warm.clear()
        val before = heapBytes()

        val cache = PhotoDnaVisualCache()
        cache.bindGrant(grant)
        for (id in 1L..250_000L) {
            cache.put(photo(id), CurationVisualHash(id xor 0x5555L, id % 2L == 0L))
        }
        assertEquals(250_000, cache.size)
        assertEquals(0, cache.failedSize)
        // Includes all primitive arrays, even unused open-addressing slots.
        // Each of the five JVM array headers is < 32 bytes.
        val backing = cache.primitiveBytes + 5 * 32
        assertTrue("250k backing bytes $backing exceeds 40 B/photo",
            backing <= 250_000L * 40)
        assertEquals(CurationVisualHash(1L xor 0x5555L, false), cache.get(photo(1)))
        assertEquals(CurationVisualHash(250_000L xor 0x5555L, true), cache.get(photo(250_000)))
        val after = heapBytes()
        println("PHOTO_DNA_604 count=${cache.size} primitiveBytes=${cache.primitiveBytes} " +
            "estimatedBytesPerEntry=${backing.toDouble() / cache.size} " +
            "jvmHeapDeltaBytes=${after - before} " +
            "jvmHeapDeltaBytesPerEntry=${(after - before).toDouble() / cache.size}")
        cache.clear()
        assertEquals(0L, cache.primitiveBytes)
        assertEquals(0, cache.size)
    }

    @Test fun volumeMediaKindRevisionAndGrantKeepOldResultsIsolated() {
        val cache = PhotoDnaVisualCache()
        val external = photo(42, "external")
        val primary = photo(42, "external_primary")
        val video = external.copy(
            uri = "content://media/external/video/media/42", isVideo = true)
        cache.bindGrant(grant)
        val good = CurationVisualHash(Long.MIN_VALUE, informative = true)
        val flat = CurationVisualHash(0L, informative = false)
        cache.put(external, good)
        cache.put(primary, flat)
        cache.put(video, flat)
        assertEquals(3, cache.size)
        assertEquals(good, cache.get(external))
        assertEquals(flat, cache.get(primary))
        assertEquals(flat, cache.get(video))
        assertNull(cache.get(external.copy(modifiedAtSeconds = 1_700_000_001L)))
        assertNull(cache.get(external.copy(sizeBytes = 1_100L)))
        assertNull(cache.get(external.copy(generationModified = 8L)))
        assertNull(cache.get(photo(43)))
        cache.putFailure(primary)
        assertEquals(2, cache.size)
        assertEquals(1, cache.failedSize)
        assertTrue(cache.failed(primary))
        assertFalse(cache.failed(primary.copy(generationModified = 8L)))
        cache.put(primary.copy(generationModified = 8L), good)
        assertEquals(3, cache.size)
        assertEquals(0, cache.failedSize)

        // The same grant preserves entries; a new grant or revoke discards them.
        cache.bindGrant(grant)
        assertEquals(good, cache.get(external))
        cache.bindGrant(grant.copy(selected = true))
        assertEquals(0, cache.size)
        assertEquals(0L, cache.primitiveBytes)
        assertNull(cache.get(external))
        cache.put(external, good)
        cache.clear()
        assertNull(cache.get(external))
        assertEquals(0L, cache.primitiveBytes)
    }

    @Test fun boundedCacheAndFailuresStillRespectSeparateCategoryLimits() {
        val cache = PhotoDnaVisualCache(maxEntries = 1)
        val a = photo(1)
        val b = photo(2)
        val c = photo(3)
        val hash = CurationVisualHash(123L, informative = true)
        cache.put(a, hash)
        cache.put(b, hash)
        assertNull(cache.get(b))
        cache.putFailure(b)
        assertEquals(1, cache.size)
        assertEquals(1, cache.failedSize)
        assertTrue(cache.failed(b))
        cache.putFailure(c)
        assertFalse(cache.failed(c))
        // A failure is retained when there is no free successful-hash slot.
        cache.put(b, hash)
        assertTrue(cache.failed(b))
        cache.putFailure(a) // can replace a hash with a failure only when a failure slot is free
        assertEquals(hash, cache.get(a))
        cache.put(a, hash) // updating a hash in a full cache is allowed
        assertEquals(hash, cache.get(a))
    }

    @Test fun repeatedVisualPlanWithUnchangedRevisionsMakesZeroNewDecodes() {
        val source = (1L..6L).map(::photo)
        val tokens = PhotoDnaTokens("synthetic-604")
        val base = planPhotoDna(source, tokens, hash = { null })
        val cache = PhotoDnaVisualCache()
        cache.bindGrant(grant)
        var decodes = 0
        val decode: (MediaRecord) -> CurationVisualHash? = {
            decodes++
            CurationVisualHash(0x123456789abcde00L xor (it.id and 3L), informative = true)
        }
        val first = planPhotoDnaVisual(source, tokens, base.plan, cache, decode, { true })
        assertEquals(6, first.decodedFiles)
        assertEquals(6, decodes)
        val next = planPhotoDnaVisual(source, tokens, base.plan, cache, decode, { true })
        assertEquals(0, next.decodedFiles)
        assertEquals(6, decodes)
        assertEquals(first.evidence, next.evidence)
        assertEquals(first.assets, next.assets)
        assertEquals(first.hashedFiles, next.hashedFiles)
        assertEquals(first.deferredFiles, next.deferredFiles)

        val edited = source.toMutableList()
        edited[2] = edited[2].copy(generationModified = 8)
        val third = planPhotoDnaVisual(edited, tokens, base.plan, cache, decode, { true })
        assertEquals(1, third.decodedFiles)
        assertEquals(7, decodes)
        assertEquals(first.deferredFiles, third.deferredFiles)
        cache.bindGrant(grant.copy(selected = true))
        val freshGrant = planPhotoDnaVisual(source, tokens, base.plan, cache, decode, { true })
        assertEquals(6, freshGrant.decodedFiles)
        assertEquals(13, decodes)
        println("PHOTO_DNA_604 warmDecodes=${next.decodedFiles} changedDecodes=${third.decodedFiles} " +
            "grantResetDecodes=${freshGrant.decodedFiles}")
    }
}
