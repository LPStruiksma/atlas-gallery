package nl.atlas.gallery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #63: a MediaRecord keeps a shared collection prefix plus its id instead of
 * a URI string per row. URIs, equality and the cache stay exactly as before.
 * Invented rows only; no MediaStore or photo is touched.
 */
class MediaRecordUriMemoryTest {
    private val prefixes = listOf(
        "content://media/external_primary/images/media/",
        "content://media/external_primary/video/media/",
        "content://media/1234-abcd/images/media/"
    )

    // A fresh String per call, as a MediaStore cursor or Uri.toString() returns it.
    private fun uriOf(id: Long) =
        StringBuilder(prefixes[(id % prefixes.size).toInt()]).append(id).toString()

    private fun row(id: Long, uri: String = uriOf(id)) = MediaRecord(
        id = id, uri = uri, name = "synthetic-$id.jpg", relativePath = "DCIM/Synthetic/",
        sizeBytes = 1_000L + id, takenAtMillis = id * 1_000L, isVideo = id % 3 == 1L,
        modifiedAtSeconds = 1_700_000_000L + id, volumeName = "external_primary",
        providerVersion = "v1", generationModified = id
    )

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(4) { System.gc(); Thread.sleep(60) }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test fun everyRowKeepsItsExactUriAndEquality() {
        for (id in 1L..250_000L) {
            val uri = uriOf(id)
            val record = row(id, uri)
            assertEquals(uri, record.uri)
            assertEquals(row(id), record)
            assertEquals(row(id).hashCode(), record.hashCode())
            assertTrue(record.hasSameUri(row(id)))
        }
    }

    @Test fun unusualUrisAreKeptWholeAndCompareCorrectly() {
        val cases = listOf(
            7L to "content://media/external/images/media/07", // zero-padded
            7L to "content://media/external/images/media/17", // longer number
            7L to "content://media/external/images/media/x7", // no slash
            7L to "content://other/7?x=1",
            7L to "7",
            0L to "content://media/external/images/media/0"
        )
        for ((id, uri) in cases) assertEquals(uri, row(id, uri).uri)
        assertNotEquals(row(7L, "content://media/external/images/media/07"), row(7L, "content://media/external/images/media/7"))
        assertFalse(row(7L, "content://a/7").hasSameUri(row(7L, "content://b/7")))
        val copy = row(9L).copy(sizeBytes = 1L)
        assertEquals(uriOf(9L), copy.uri)
        assertEquals("content://new/9", row(9L).copy(uri = "content://new/9").uri)
        assertTrue(row(9L).toString().contains(uriOf(9L)))
    }

    @Test fun rescanReuseStillMatchesByUri() {
        val previous = (1L..2_000L).map { row(it) }
        val reuse = GalleryScanRecordReuse(previous)
        val scanned = (1L..2_000L).map { reuse.canonical(row(it)) }
        scanned.forEachIndexed { index, record -> assertSame(previous[index], record) }
        // Same id, other volume: never answered with the other volume's record.
        val elsewhere = row(5L, "content://media/9999-0000/images/media/5")
        assertSame(elsewhere, reuse.canonical(elsewhere))
    }

    @Test fun cacheRoundTripKeepsUrisOfEveryKind() {
        val items = (1L..5_000L).map { row(it) } +
            row(9_000_001L, "content://media/external/images/media/09000001") +
            row(9_000_002L, "content://other/provider/item")
        val grant = GalleryMediaGrant(images = true, videos = true, selected = false)
        val bytes = ByteArrayOutputStream().also {
            GalleryInventoryCacheCodec.encode(it, grant, items)
        }.toByteArray()
        val decoded = requireNotNull(GalleryInventoryCacheCodec.decode(ByteArrayInputStream(bytes)))
        assertEquals(items, decoded.items)
        assertEquals(items.map { it.uri }, decoded.items.map { it.uri })
    }

    /**
     * The measured number: 250k records alone, then the same records plus the
     * per-row URI strings the former data class held (its record layout had the
     * same reference field). The difference is what #63 takes off the heap.
     */
    @Test fun aQuarterMillionRowCatalogueNoLongerHoldsUriStrings() {
        val count = 250_000
        val base = usedHeap()
        val records = ArrayList<MediaRecord>(count)
        for (id in 1L..count.toLong()) records.add(row(id))
        val withRecords = usedHeap()
        val strings = ArrayList<String>(count)
        for (record in records) strings.add(record.uri)
        val withStrings = usedHeap()
        val recordsMb = (withRecords - base) / (1024.0 * 1024.0)
        val savedMb = (withStrings - withRecords) / (1024.0 * 1024.0)
        val savedPerRow = (withStrings - withRecords) / count
        println("ATLAS_MEM #63 rows=$count catalogue_mb=%.1f uri_strings_saved_mb=%.1f saved_bytes_per_row=%d"
            .format(recordsMb, savedMb, savedPerRow))
        assertEquals(count, strings.size)
        // A ~50-character URI string is 90+ bytes of heap on any JVM layout.
        assertTrue("saved only $savedPerRow bytes/row", savedPerRow >= 60)
        assertEquals(uriOf(count.toLong()), records.last().uri)
    }
}
