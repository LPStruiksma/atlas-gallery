package nl.atlas.gallery

import nl.atlas.gallery.perf.threadAllocatedBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * PERF-18 (#225): building the previous-inventory lookup no longer goes
 * through sortedBy (boxed Long per comparison, an extra list) and skips the
 * sort for an inventory already in id order. Answers must be identical to
 * the frozen pre-PERF-18 lookup. Invented rows only.
 */
class GalleryScanRecordReusePerfTest {
    private fun row(id: Long, volume: String = "external_primary", revision: Long = id) = MediaRecord(
        id = id,
        uri = StringBuilder("content://media/").append(volume).append("/images/media/").append(id).toString(),
        name = StringBuilder("synthetic-").append(id).append(".jpg").toString(),
        relativePath = "DCIM/Synthetic/",
        sizeBytes = 1_000L + id,
        takenAtMillis = id * 1_000L,
        isVideo = false,
        modifiedAtSeconds = 1_700_000_000L + id,
        volumeName = volume,
        providerVersion = "v1",
        generationModified = revision
    )

    /** Frozen pre-PERF-18 lookup. */
    private class OldReuse(previous: List<MediaRecord>) {
        private val ids: LongArray?
        private val records: Array<MediaRecord>?
        init {
            if (previous.isEmpty()) { ids = null; records = null } else {
                val sorted = previous.sortedBy { it.id }.toTypedArray()
                records = sorted
                ids = LongArray(sorted.size) { sorted[it].id }
            }
        }
        var reused = 0
        fun canonical(fresh: MediaRecord): MediaRecord {
            val ids = ids ?: return fresh
            val records = records ?: return fresh
            var i = java.util.Arrays.binarySearch(ids, fresh.id)
            if (i < 0) return fresh
            while (i > 0 && ids[i - 1] == fresh.id) i--
            while (i < ids.size && ids[i] == fresh.id) {
                if (records[i].hasSameUri(fresh)) {
                    return if (records[i] == fresh) { reused++; records[i] } else fresh
                }
                i++
            }
            return fresh
        }
    }

    @Test fun adversarialInventoriesGiveTheSameObjectsAsTheOldLookup() {
        val rng = Random(225)
        val previous = ArrayList<MediaRecord>()
        for (id in 1L..3_000L) {
            previous += row(id)
            if (id % 7 == 0L) previous += row(id, volume = "sd_card") // same id, other volume
        }
        previous.shuffle(rng)
        val fresh = ArrayList<MediaRecord>()
        for (id in 1L..3_200L) {
            fresh += when {
                id % 11 == 0L -> row(id, revision = id + 1) // changed revision
                else -> row(id)
            }
            if (id % 7 == 0L) fresh += row(id, volume = "sd_card")
            if (id % 13 == 0L) fresh += row(id, volume = "usb") // id seen, uri never seen
        }
        for (prev in listOf(previous, previous.sortedBy { it.id }, previous.sortedByDescending { it.id },
                emptyList(), listOf(row(5)))) {
            val old = OldReuse(prev)
            val new = GalleryScanRecordReuse(prev)
            for (f in fresh) assertSame(old.canonical(f), new.canonical(f))
            assertEquals(old.reused, new.reused)
        }
    }

    @Test fun reports250kRepublishBeforeAfter() {
        val total = 250_000
        val rng = Random(250)
        val inIdOrder = (1L..total.toLong()).map { row(it) }
        val dateOrder = inIdOrder.shuffled(rng) // MediaStore order is by date, not id
        val fresh = (1L..total.toLong()).map { row(it) }
        fun measure(previous: List<MediaRecord>, old: Boolean): Pair<Long, Long> {
            val a0 = threadAllocatedBytes()
            val t = System.nanoTime()
            var reused: Int
            if (old) {
                val r = OldReuse(previous); for (f in fresh) r.canonical(f); reused = r.reused
            } else {
                val r = GalleryScanRecordReuse(previous); for (f in fresh) r.canonical(f); reused = r.reused
            }
            val ns = System.nanoTime() - t
            val a1 = threadAllocatedBytes()
            assertEquals(total, reused)
            return ns to (if (a0 != null && a1 != null) a1 - a0 else -1L)
        }
        repeat(2) { for (p in listOf(dateOrder, inIdOrder)) { measure(p, true); measure(p, false) } }
        val runs = 7
        val lines = ArrayList<String>()
        var lowerAllocation = true
        for ((name, previous) in listOf("date order" to dateOrder, "id order" to inIdOrder)) {
            val before = (1..runs).map { measure(previous, true) }
            val after = (1..runs).map { measure(previous, false) }
            fun p(xs: List<Pair<Long, Long>>, q: Double) = xs.map { it.first }.sorted()[((runs - 1) * q).toInt()] / 1e6
            val bAlloc = before.map { it.second }.sorted()[runs / 2]
            val aAlloc = after.map { it.second }.sorted()[runs / 2]
            if (bAlloc >= 0 && aAlloc >= bAlloc) lowerAllocation = false
            lines += "%s: before p50=%.1f ms p95=%.1f ms alloc=%d B; after p50=%.1f ms p95=%.1f ms alloc=%d B".format(
                name, p(before, .5), p(before, .95), bAlloc, p(after, .5), p(after, .95), aAlloc)
        }
        println("PERF-18 250k re-publish, lookup build + canonical per row (JVM, n=$runs): " + lines.joinToString("; "))
        assertTrue("allocation did not drop", lowerAllocation)
    }
}
