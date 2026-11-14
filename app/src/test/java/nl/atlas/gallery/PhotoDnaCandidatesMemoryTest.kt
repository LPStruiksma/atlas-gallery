package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #65: Photo DNA plans its size classes without a list or map per catalogue
 * row, and picks exactly what the former implementation picked. Synthetic
 * records only.
 */
class PhotoDnaCandidatesMemoryTest {
    private fun photo(
        id: Long, size: Long, uri: String = "content://media/external/images/media/$id",
        name: String = "synthetic-$id.jpg", path: String = "DCIM/Test/",
        modified: Long = 1_700_000_000L
    ) = MediaRecord(id = id, uri = uri, name = name, relativePath = path, sizeBytes = size,
        takenAtMillis = id * 1_000L, isVideo = false, modifiedAtSeconds = modified)

    /** The implementation before #65, kept verbatim as the reference. */
    private fun legacyCandidates(
        source: List<MediaRecord>, budget: PhotoDnaBudget = PhotoDnaBudget()
    ): PhotoDnaCandidates {
        val seen = HashSet<String>()
        val duplicated = HashSet<String>()
        source.forEach { if (!seen.add(it.uri)) duplicated.add(it.uri) }
        val eligible = source.filter { it.uri !in duplicated && legacyEligible(it, budget) }
        val bySize = HashMap<Long, MutableList<MediaRecord>>()
        eligible.forEach { bySize.getOrPut(it.sizeBytes) { ArrayList(2) }.add(it) }
        val classes = bySize.entries.filter { it.value.size > 1 }
            .sortedWith(compareByDescending<Map.Entry<Long, MutableList<MediaRecord>>> {
                it.key * (it.value.size - 1)
            }.thenByDescending { it.key })
        val chosen = ArrayList<MediaRecord>()
        var bytes = 0L
        var deferred = 0
        for ((size, members) in classes) {
            val classBytes = size * members.size
            if (chosen.size + members.size > budget.maxFiles || classBytes > budget.maxTotalBytes - bytes) {
                deferred += members.size
                continue
            }
            members.sortBy { it.uri }
            chosen += members
            bytes += classBytes
        }
        return PhotoDnaCandidates(chosen, deferred)
    }

    private fun legacyEligible(record: MediaRecord, budget: PhotoDnaBudget): Boolean {
        if (record.sizeBytes <= 0L || record.sizeBytes > budget.maxFileBytes ||
            record.modifiedAtSeconds <= 0L || record.id <= 0L) return false
        if (record.name.startsWith(".trashed-") || record.name.startsWith(".pending-")) return false
        if (record.relativePath.split('/').any { it.startsWith(".") }) return false
        return record.uri.startsWith("content://media/")
    }

    private fun mixedFixture(): List<MediaRecord> {
        val rows = ArrayList<MediaRecord>()
        for (id in 1L..600L) rows += photo(id, size = 100L + id % 37)
        rows += photo(601, 120, path = ".hidden/")
        rows += photo(602, 120, path = "DCIM/.thumbs/x/")
        rows += photo(603, 120, name = ".trashed-603.jpg")
        rows += photo(604, 120, modified = 0L)
        rows += photo(605, 0L)
        rows += photo(606, 120, uri = "file:///sdcard/606.jpg")
        rows += photo(607, 121)
        rows += photo(607, 121) // the catalogue repeats this URI
        rows += photo(608, 121, uri = "content://media/1234-abcd/images/media/608")
        rows += photo(608, 121) // same id, other volume: a distinct file
        rows += photo(609, 5_000L)
        rows += photo(610, 5_000L)
        return rows
    }

    @Test fun picksExactlyWhatTheFormerPlannerPicked() {
        val source = mixedFixture()
        for (budget in listOf(PhotoDnaBudget(), PhotoDnaBudget(maxFiles = 40),
            PhotoDnaBudget(maxFiles = 3), PhotoDnaBudget(maxTotalBytes = 2_000L),
            PhotoDnaBudget(maxFileBytes = 130L))) {
            val expected = legacyCandidates(source, budget)
            val actual = photoDnaCandidates(source, budget)
            assertEquals(budget.toString(), expected.records.map { it.uri }, actual.records.map { it.uri })
            assertEquals(budget.toString(), expected.records, actual.records)
            assertEquals(budget.toString(), expected.deferredFiles, actual.deferredFiles)
        }
        assertEquals(source.filter { legacyEligible(it, PhotoDnaBudget()) }
            .filter { it.id != 607L }.map { it.uri },
            photoDnaDistinctEligible(source).map { it.uri })
    }

    /** Bytes this thread allocated while [block] ran (HotSpot's exact counter). */
    private fun allocated(block: () -> Unit): Long {
        // Reflection: the Android unit-test classpath has no java.lang.management stubs.
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        val counter = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        val before = counter.invoke(bean, id) as Long
        block()
        return (counter.invoke(bean, id) as Long) - before
    }

    @Test fun aQuarterMillionRowsPlanWithinBudgetAndMatchTheFormerPlanner() {
        val source = (1L..250_000L).map { photo(it, size = 64L + it / 2) }
        lateinit var result: PhotoDnaCandidates
        lateinit var legacy: PhotoDnaCandidates
        photoDnaCandidates(source); legacyCandidates(source) // warm up
        val newBytes = allocated { result = photoDnaCandidates(source) }
        val oldBytes = allocated { legacy = legacyCandidates(source) }
        val mib = 1024.0 * 1024.0
        println("ATLAS_MEM #65 rows=250000 candidates=${result.records.size} " +
            "deferred=${result.deferredFiles} allocated_mb=%.1f former_allocated_mb=%.1f"
                .format(newBytes / mib, oldBytes / mib))
        assertTrue(result.records.size <= PhotoDnaBudget().maxFiles)
        assertEquals(legacy.records, result.records)
        assertEquals(legacy.deferredFiles, result.deferredFiles)
        assertTrue("allocated ${newBytes / mib} MiB vs ${oldBytes / mib}", newBytes * 2 < oldBytes)
    }
}
