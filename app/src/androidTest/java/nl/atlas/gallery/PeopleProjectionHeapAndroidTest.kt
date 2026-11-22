package nl.atlas.gallery

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureface.FaceAuthorizedMediaDirectory
import nl.atlas.gallery.featureface.MediaRecordFaceView
import nl.atlas.gallery.featureface.toFaceVisibleMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PERF-6b (#601) device-level heap evidence at 250k synthetic gallery rows.
 *
 * Holds what a People scope holds at a 250k library (the gallery's
 * `MediaRecord` list, the newest-first People view and the authorized face
 * directory) and reports PERF-0 `heap_fraction` (Java heap used / maximum,
 * the same ratio as `atlas-perf-heap_fraction` in [PerformanceReport]) on
 * the emulator. The pre-PERF-6b materialized projection plus its sorted copy is
 * measured the same way for comparison, then dropped before the new path runs.
 *
 * Synthetic records only: no photos, URIs from MediaStore, permissions or
 * identities. One `ATLAS_SCALE: PERF-6b-people-projection` line is logged and
 * dumped by scripts/ci_android_upgrade_emulator.sh with the other scale lines.
 * Asserted: digest parity, and `heap_fraction` under PERF-6's 60% target (#127).
 */
@RunWith(AndroidJUnit4::class)
class PeopleProjectionHeapAndroidTest {
    private val folders = List(300) { "DCIM/Synthetic$it/" }

    private fun catalogue(n: Int): List<MediaRecord> {
        val start = 1_763_000_000_000L
        return List(n) { i ->
            val id = 1_000_000L + i
            MediaRecord(id,
                StringBuilder("content://media/external_primary/images/media/").append(id).toString(),
                StringBuilder("IMG_20251103_").append(100_000 + i).append(".jpg").toString(),
                folders[i % folders.size], 2_000_000L + i, start - i * 60_000L,
                isVideo = i % 10 == 0, modifiedAtSeconds = 1_763_000_000L,
                volumeName = "external_primary", providerVersion = "v1",
                generationModified = i.toLong(), widthPixels = 4000, heightPixels = 3000)
        }
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(5) { System.gc(); Thread.sleep(80) }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test fun peopleProjectionHeapFractionAt250k() {
        val n = 250_000
        val max = Runtime.getRuntime().maxMemory()
        // Unsorted input exercises the packed newest-first order.
        val records = catalogue(n).asReversed()
        val base = usedHeap()

        // Pre-PERF-6b: full FaceVisibleMedia projection plus a sorted copy.
        // Only scalars leave this function, so nothing stays reachable.
        fun oldPath(): Triple<String, Long, Long> {
            val projected = records.map { it.toFaceVisibleMedia(editorRevision = 0L) }
            val sortedOld = newestFirst(projected)
            val used = usedHeap()
            val digest = faceInventoryDigest(sortedOld)
            check(projected.size == sortedOld.size)
            return Triple(digest, used, used - base)
        }
        val (expectedDigest, oldUsed, oldDelta) = oldPath()

        val view = newestFirst(MediaRecordFaceView(records))
        val directory = FaceAuthorizedMediaDirectory.build(
            "synthetic-grant", view, { it }, { "synthetic-grant" }, { true })
        val newUsed = usedHeap()
        val newDelta = newUsed - base
        assertEquals(expectedDigest, faceInventoryDigest(view))
        assertEquals(n, view.size)
        assertTrue(directory.mediaCount > 0)

        val heapFraction = newUsed.toDouble() / max
        val oldHeapFraction = oldUsed.toDouble() / max
        Log.i("ATLAS_SCALE", "PERF-6b-people-projection rows=$n max_heap_mib=${max shr 20} " +
            "gallery_base_mib=${base shr 20} " +
            "heap_fraction=${"%.4f".format(heapFraction)} " +
            "heap_fraction_before=${"%.4f".format(oldHeapFraction)} " +
            "people_bytes_per_item=${"%.1f".format(newDelta.toDouble() / n)} " +
            "people_bytes_per_item_before=${"%.1f".format(oldDelta.toDouble() / n)}")
        assertTrue("PERF-0 heap_fraction must stay under 0.60 at 250k rows: $heapFraction",
            heapFraction < 0.60)
    }
}
