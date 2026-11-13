package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #41: a rescan must not keep a second copy of an unchanged library on the
 * heap, and running out of heap in the scan must fail the refresh, not the
 * app. Invented rows only; no MediaStore, storage or photo is touched.
 */
class GalleryScanMemoryTest {
    // Fresh String objects every call, as a MediaStore cursor returns them.
    private fun row(id: Long, sizeBytes: Long = 1_000L + id) = MediaRecord(
        id = id,
        uri = StringBuilder("content://media/external_primary/images/media/").append(id).toString(),
        name = StringBuilder("synthetic-").append(id).append(".jpg").toString(),
        relativePath = StringBuilder("DCIM/").append("Synthetic/").toString(),
        sizeBytes = sizeBytes,
        takenAtMillis = id * 1_000L,
        isVideo = false,
        modifiedAtSeconds = 1_700_000_000L + id,
        volumeName = StringBuilder("external_").append("primary").toString(),
        providerVersion = "v1",
        generationModified = id
    )

    @Test fun unchangedRowsAreAnsweredWithThePreviousObjects() {
        val previous = (1L..2_000L).map { row(it) }
        val reuse = GalleryScanRecordReuse(previous)
        val scanned = (1L..2_000L).map { reuse.canonical(row(it)) }
        assertEquals(previous, scanned)
        scanned.forEachIndexed { index, record -> assertSame(previous[index], record) }
        assertEquals(2_000, reuse.reused)
    }

    @Test fun aChangedRowIsNeverReplacedByItsPredecessor() {
        val previous = listOf(row(1), row(2), row(3))
        val reuse = GalleryScanRecordReuse(previous)
        val edited = row(2, sizeBytes = 99L)
        val result = reuse.canonical(edited)
        assertSame(edited, result)
        assertEquals(99L, result.sizeBytes)
        assertNotSame(previous[1], result)
        // A row the previous inventory never held is kept as read.
        val added = row(4)
        assertSame(added, reuse.canonical(added))
        assertEquals(0, reuse.reused)
    }

    @Test fun sameUriOfAnotherMediaKindOrRevisionIsNotReused() {
        val previous = listOf(row(7))
        val reuse = GalleryScanRecordReuse(previous)
        val newRevision = row(7).copy(generationModified = 8L)
        assertSame(newRevision, reuse.canonical(newRevision))
        val newDate = row(7).copy(takenAtMillis = 1L)
        assertSame(newDate, reuse.canonical(newDate))
        assertEquals(0, reuse.reused)
    }

    @Test fun firstScanWithoutAPreviousInventoryKeepsEveryRow() {
        val reuse = GalleryScanRecordReuse()
        val fresh = row(1)
        assertSame(fresh, reuse.canonical(fresh))
        assertEquals(0, reuse.reused)
    }

    @Test fun folderAndVolumeNamesAreStoredOncePerScan() {
        val reuse = GalleryScanRecordReuse()
        val first = reuse.shared(StringBuilder("DCIM/").append("Camera/").toString())
        val second = reuse.shared(StringBuilder("DCIM/").append("Camera/").toString())
        assertSame(first, second)
        assertEquals("DCIM/Camera/", second)
        assertNotSame(first, reuse.shared("Pictures/"))
    }

    @Test fun heapExhaustionInTheScanBecomesAFailedRefresh() {
        try {
            failGalleryScanOnHeapExhaustion<Unit> { throw OutOfMemoryError("synthetic") }
            fail("The scan must fail")
        } catch (failure: GalleryScanOutOfMemory) {
            val evidence = galleryScanFailureEvidence(failure, observerChanged = false,
                grantAtStartStillMatches = true, latestRequest = true,
                explicitlyRevoked = false, elapsedMilliseconds = 1_000L)
            assertEquals(GalleryScanFailureCode.OUT_OF_MEMORY, evidence.code)
            assertTrue(galleryScanFailureMessage(failure).contains("low on memory"))
        }
        assertEquals(5, failGalleryScanOnHeapExhaustion { 5 })
    }

    @Test fun otherScanFailuresPassThroughUnchanged() {
        try {
            failGalleryScanOnHeapExhaustion<Unit> { throw SecurityException("fictional denial") }
            fail("The denial must propagate")
        } catch (_: SecurityException) {
        }
    }

    @Test fun heapFieldsAreNumbersOnly() {
        assertEquals("heap_used_mb=200 heap_max_mb=256",
            heapUseLogFields(200L * 1024 * 1024, 256L * 1024 * 1024))
        assertEquals("heap_used_mb=0 heap_max_mb=0", heapUseLogFields(-5L, -1L))
        assertTrue(Regex("heap_used_mb=[0-9]+ heap_max_mb=[0-9]+").matches(heapUseLogFields()))
    }

    @Test fun crashReportCarriesTheHeapLineOnlyInItsFixedForm() {
        val report = formatCrashReport(1_700_000_000_000L, "0.3.0", "background",
            OutOfMemoryError("content://private"), heap = "heap_used_mb=255 heap_max_mb=256")
        assertTrue(report.contains("Exception: java.lang.OutOfMemoryError"))
        assertTrue(report.contains("Heap: heap_used_mb=255 heap_max_mb=256"))
        assertFalse(report.contains("content://"))
        val refused = formatCrashReport(1_700_000_000_000L, "0.3.0", "background",
            OutOfMemoryError(), heap = "heap_used_mb=1 /sdcard/DCIM/secret.jpg")
        assertFalse(refused.contains("Heap:"))
        assertFalse(refused.contains("secret"))
    }

    @Test fun processExitsListReasonAndMemoryWithoutFreeText() {
        assertEquals("", formatProcessExits(emptyList()))
        val text = formatProcessExits(listOf(
            ProcessExitFact(1_700_000_000_000L, processExitReasonName(3), 512_000L, 700_000L),
            ProcessExitFact(1_699_999_000_000L, processExitReasonName(5), 0L, -4L),
            ProcessExitFact(1_699_998_000_000L, "content://private/Jane Doe", 1L, 1L),
            ProcessExitFact(1_699_997_000_000L, processExitReasonName(999), 1L, 1L)
        ))
        val lines = text.trimEnd().lines()
        assertEquals("Recent app exits (newest first)", lines[0])
        assertTrue(lines[1].endsWith("low_memory pss_mb=500 rss_mb=683"))
        assertTrue(lines[2].endsWith("crash_native pss_mb=0 rss_mb=0"))
        assertTrue(lines[3].contains(" other "))
        assertTrue(lines[4].contains(" other "))
        assertFalse(text.contains("Jane"))
        assertFalse(text.contains("content://"))
        assertEquals("crash", processExitReasonName(4))
        assertEquals("not_responding", processExitReasonName(6))
    }

    @Test fun processExitListIsBounded() {
        val many = (1..50).map { ProcessExitFact(1_700_000_000_000L - it, "crash", 1L, 1L) }
        assertEquals(9, formatProcessExits(many).trimEnd().lines().size)
    }
}
