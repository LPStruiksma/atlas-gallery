package nl.atlas.gallery

import nl.atlas.gallery.featurealbums.buildAlbumSummaries
import nl.atlas.gallery.featureface.FaceAuthorizedMediaDirectory
import nl.atlas.gallery.featureface.MediaRecordFaceView
import nl.atlas.gallery.featureface.toFaceVisibleMedia
import nl.atlas.gallery.featuregrid.GridDensity
import nl.atlas.gallery.featuregrid.GridTimelineIndex
import nl.atlas.gallery.featuregrid.TimelineMedia
import nl.atlas.gallery.featuregrid.TimelineSlot
import nl.atlas.gallery.featuregrid.TimelineSort
import nl.atlas.gallery.featuregrid.buildGridTimelineIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * PERF-6 (#127): retained heap of the in-memory gallery model at 250k
 * synthetic MediaStore-shaped rows, holder by holder. Invented rows only.
 * Retained-heap readings are published, not asserted (GC is not exact); the
 * assertion is on what each holder structurally keeps.
 */
class GalleryInventoryMemoryTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val clock = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), zone)
    private val folders = List(300) { "DCIM/Synthetic$it/" }

    // A fresh URI and name String per row, as a MediaStore cursor returns them;
    // folder, volume and version are shared, as GalleryScanRecordReuse does.
    private fun catalogue(n: Int, step: Long = 60_000L): List<MediaRecord> {
        val start = 1_763_000_000_000L
        return List(n) { i ->
            val id = 1_000_000L + i
            MediaRecord(id,
                StringBuilder("content://media/external_primary/images/media/").append(id).toString(),
                StringBuilder("IMG_20251103_").append(100_000 + i).append(".jpg").toString(),
                folders[i % folders.size], 2_000_000L + i, start - i * step,
                isVideo = i % 10 == 0, modifiedAtSeconds = 1_763_000_000L,
                volumeName = "external_primary", providerVersion = "v1",
                generationModified = i.toLong(), widthPixels = 4000, heightPixels = 3000)
        }
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(5) { System.gc(); Thread.sleep(60) }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** The index built the way the timeline did before PERF-6. */
    private fun projectedIndex(items: List<MediaRecord>, sort: TimelineSort, density: GridDensity,
        merge: Int = 0): GridTimelineIndex {
        val projection = ArrayList<TimelineMedia>(items.size)
        items.forEach { projection.add(TimelineMedia(it.uri, it.takenAtMillis)) }
        return buildGridTimelineIndex(projection, sort, density, zone, Locale.US,
            mergeSparseDaysBelow = merge, clock = clock)
    }

    private fun catalogueIndex(items: List<MediaRecord>, sort: TimelineSort, density: GridDensity,
        merge: Int = 0): GridTimelineIndex =
        buildGridTimelineIndex(items.size, { items[it].takenAtMillis }, { items[it].uri },
            sort, density, zone, Locale.US, mergeSparseDaysBelow = merge, clock = clock)

    @Test fun catalogueIndexEqualsTheProjectedIndex() {
        val dense = catalogue(5_000)
        val sparse = catalogue(3_000, step = 26L * 3_600_000L) // about one photo a day
        val shuffled = dense.shuffled(java.util.Random(4))
        for ((items, sort) in listOf(dense to TimelineSort.NEWEST,
            dense.reversed() to TimelineSort.OLDEST, shuffled to TimelineSort.NEWEST,
            shuffled to TimelineSort.RANDOM, sparse to TimelineSort.NEWEST)) {
            for (density in GridDensity.entries) for (merge in listOf(0, density.columns)) {
                val before = projectedIndex(items, sort, density, merge)
                val after = catalogueIndex(items, sort, density, merge)
                assertEquals(before.grouped, after.grouped)
                assertArrayEquals(before.mediaEntryPositions, after.mediaEntryPositions)
                assertEquals(before.headers, after.headers)
                assertEquals(before.slots.size, after.slots.size)
                for (i in before.slots.indices) {
                    val a = before.slots[i]
                    val b = after.slots[i]
                    assertEquals(a, b)
                    assertEquals(a.stableKey, b.stableKey)
                    if (a is TimelineSlot.Media) assertEquals(a.mediaIndex, (b as TimelineSlot.Media).mediaIndex)
                }
            }
        }
        val single = catalogue(1)
        assertEquals("media:" + single[0].uri,
            catalogueIndex(single, TimelineSort.NEWEST, GridDensity.MEDIUM).slots[1].stableKey)
        assertEquals(0, catalogueIndex(emptyList(), TimelineSort.NEWEST, GridDensity.MEDIUM).slots.size)
    }

    /**
     * PERF-6b (#601): the face digest and stable newest-first order are unchanged,
     * but a People publication retains the gallery rows, not a copied face row
     * and URI per item. Use an unsorted view to exercise the packed index.
     */
    @Test fun peopleProjectionAt250kRetainsOnlyIndicesAndPreservesDigest() {
        val n = 250_000
        val records = catalogue(n).asReversed()
        // Baseline intentionally materializes the old projection; it is not kept
        // after the digest is computed.
        val expected = faceInventoryDigest(newestFirst(
            records.map { it.toFaceVisibleMedia(editorRevision = 0L) }))
        val h0 = usedHeap()
        val view = MediaRecordFaceView(records)
        val sorted = newestFirst(view)
        val h1 = usedHeap()
        assertSame(sorted, newestFirst(sorted)) // no second publication-time sort list
        assertEquals(expected, faceInventoryDigest(sorted))
        assertEquals(records.size, sorted.size)
        assertEquals(records.first().id, sorted.last().id)
        val directory = FaceAuthorizedMediaDirectory.build(
            "synthetic-grant", sorted, { it }, { "synthetic-grant" }, { true })
        val h2 = usedHeap()
        // The directory must use this immutable view, not freeze 250k projected
        // FaceVisibleMedia objects or rebuild 250k URI strings.
        val sourcesField = directory.javaClass.getDeclaredField("sources")
        sourcesField.isAccessible = true
        assertSame(sorted, sourcesField.get(directory))
        val offsetsField = directory.javaClass.getDeclaredField("sourceOffsets")
        offsetsField.isAccessible = true
        assertEquals(null, offsetsField.get(directory)) // 20-bit direct source offsets
        val retainedIndexBytes = sorted.retainedIndexBytes + 8L * directory.mediaCount
        val bytesPerItem = retainedIndexBytes.toDouble() / n
        println("PERF-6b People projection at 250k (JVM, bytes/item): " +
            "view_heap=${(h1 - h0).toDouble() / n} " +
            "directory_heap=${(h2 - h1).toDouble() / n} " +
            "retained_indices=$bytesPerItem")
        assertTrue("People index must retain <= 16 bytes per gallery row: $bytesPerItem",
            bytesPerItem <= 16.0)
    }

    @Test fun retainedHeapPerHolderAt250k() {
        val n = 250_000
        var keep: Any? = null
        val h0 = usedHeap()
        val records = catalogue(n)
        val h1 = usedHeap()
        val sorted = sortMedia(records, SortOrder.NEWEST, 0L)
        val h2 = usedHeap()
        keep = projectedIndex(sorted, TimelineSort.NEWEST, GridDensity.MEDIUM)
        val h3 = usedHeap()
        keep = null
        val h3b = usedHeap()
        keep = catalogueIndex(sorted, TimelineSort.NEWEST, GridDensity.MEDIUM)
        val h4 = usedHeap()
        val after = keep as GridTimelineIndex
        val albums = buildAlbumSummaries(sorted)
        val h5 = usedHeap()
        fun perItem(bytes: Long) = "%.1f".format(bytes.toDouble() / n)
        println("PERF-6 retained heap at 250k synthetic rows (JVM, bytes/item): " +
            "records=${perItem(h1 - h0)} sorted_copy=${perItem(h2 - h1)} " +
            "timeline_index_before=${perItem(h3 - h2)} timeline_index_after=${perItem(h4 - h3b)} " +
            "album_summaries=${perItem(h5 - h4)}; the timeline keeps up to 2 density plans")
        assertEquals(n, after.mediaCount)
        assertEquals(300, albums.size)
        assertEquals(n, records.size)
        // Keys still resolve exactly, from the catalogue, on demand.
        for (i in listOf(0, 1, n / 2, n - 1)) {
            assertEquals("media:" + sorted[i].uri, after.slots[after.entryForMedia(i)].stableKey)
        }
    }
}
