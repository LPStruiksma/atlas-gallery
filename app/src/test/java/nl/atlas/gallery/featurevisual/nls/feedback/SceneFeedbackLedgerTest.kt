package nl.atlas.gallery.featurevisual.nls.feedback

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneFeedbackLedgerTest {
    private val permission = SceneFeedbackLedger.Permission(granted = true, epoch = 7L)
    private val denied = SceneFeedbackLedger.Permission(granted = false, epoch = 7L)

    private class MemoryStore : SceneFeedbackPersistence {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun erase() { bytes = null }
    }

    @Test fun explicitOptInRequiredAndMissingMomentContainsNoMediaId() {
        val store = MemoryStore()
        val ledger = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertFalse(ledger.markMissingMoment("blue train", permission))
        assertFalse(ledger.markIncorrectHit("blue train", 42L, permission))
        assertTrue(ledger.feedback(permission).isEmpty())
        assertNull(store.bytes)
        assertFalse(ledger.optIn(denied))
        assertNull(store.bytes)
        assertTrue(ledger.optIn(permission))
        assertFalse(ledger.markIncorrectHit("blue train", 0L, permission))
        assertFalse(ledger.markIncorrectHit("blue train", -1L, permission))
        assertTrue(ledger.markMissingMoment("blue train", permission))
        assertEquals(null, ledger.feedback(permission).single().mediaId)
        assertEquals(SceneFeedbackLedger.Kind.MISSING_MOMENT, ledger.feedback(permission).single().kind)
    }

    @Test fun keyedDigestIsStableAcrossRestartAndCanonicalTextVariants() {
        val store = MemoryStore()
        val first = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertTrue(first.optIn(permission))
        assertTrue(first.markIncorrectHit("  Purple  DOOR ", 71L, permission))
        assertTrue(first.markMissingMoment("purple door", permission))
        val rows = first.feedback(permission)
        assertEquals(2, rows.size)
        assertEquals(rows[0].queryDigest, rows[1].queryDigest)
        assertEquals(64, rows[0].queryDigest.length)
        assertTrue(rows[0].queryDigest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(71L, rows[0].mediaId)
        assertNull(rows[1].mediaId)
        assertFalse(String(store.bytes!!, Charsets.ISO_8859_1).contains("Purple"))
        assertFalse(String(store.bytes!!, Charsets.ISO_8859_1).contains("purple door"))
        val reopened = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertEquals(rows, reopened.feedback(permission))
        assertFalse(reopened.markIncorrectHit("purple door", 71L, permission))
    }

    @Test fun duplicatesDoNotRefreshTimestampAndDifferentIdsAreDistinct() {
        var time = 10_000_000_000L
        val ledger = SceneFeedbackLedger(MemoryStore()) { time }
        assertTrue(ledger.optIn(permission))
        assertTrue(ledger.markIncorrectHit("a remembered scene", 1L, permission))
        time += 1000L
        assertFalse(ledger.markIncorrectHit("A  REMEMBERED scene", 1L, permission))
        assertTrue(ledger.markIncorrectHit("a remembered scene", 2L, permission))
        assertTrue(ledger.markMissingMoment("a remembered scene", permission))
        assertFalse(ledger.markMissingMoment("a remembered scene", permission))
        val rows = ledger.feedback(permission)
        assertEquals(3, rows.size)
        assertEquals(10_000_000_000L, rows[0].recordedAtMillis)
        assertEquals(10_000_001_000L, rows[1].recordedAtMillis)
        assertEquals(listOf(1L, 2L, null), rows.map { it.mediaId })
    }

    @Test fun denialPurgesConsentRecordsAndKey() {
        val store = MemoryStore()
        val ledger = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertTrue(ledger.optIn(permission))
        assertTrue(ledger.markIncorrectHit("empty railway", 33L, permission))
        val before = ledger.feedback(permission).single().queryDigest
        assertTrue(ledger.feedback(denied).isEmpty())
        assertNull(store.bytes)
        assertFalse(ledger.isOptedIn(permission))
        assertFalse(ledger.markMissingMoment("empty railway", permission))
        assertTrue(ledger.optIn(permission))
        assertTrue(ledger.markIncorrectHit("empty railway", 33L, permission))
        assertNotEquals(before, ledger.feedback(permission).single().queryDigest)
    }

    @Test fun permissionEpochMismatchOnReopenErasesInsteadOfRevealingOldRows() {
        val store = MemoryStore()
        val ledger = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertTrue(ledger.optIn(permission))
        assertTrue(ledger.markMissingMoment("synthetic footbridge", permission))
        val anotherEpoch = SceneFeedbackLedger.Permission(true, 8L)
        val reopened = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertTrue(reopened.feedback(anotherEpoch).isEmpty())
        assertNull(store.bytes)
        assertFalse(reopened.isOptedIn(anotherEpoch))
        assertFalse(reopened.markMissingMoment("synthetic footbridge", anotherEpoch))
        assertTrue(reopened.optIn(anotherEpoch))
        assertTrue(reopened.feedback(anotherEpoch).isEmpty())
    }

    @Test fun retentionIsInclusiveAtBoundaryAndPrunesOnRead() {
        val store = MemoryStore()
        val start = 10_000_000_000L
        var time = start
        val ledger = SceneFeedbackLedger(store) { time }
        assertTrue(ledger.optIn(permission))
        assertTrue(ledger.markMissingMoment("synthetic red bicycle", permission))
        time = start + SceneFeedbackLedger.RETENTION_MILLIS
        assertEquals(1, ledger.feedback(permission).size)
        time++
        assertTrue(ledger.feedback(permission).isEmpty())
        assertTrue(ledger.isOptedIn(permission))
        assertTrue(SceneFeedbackLedger(store) { time }.feedback(permission).isEmpty())
    }

    @Test fun boundedCapacityEvictsOldestWithoutAccumulatingCounters() {
        val store = MemoryStore()
        val ledger = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertTrue(ledger.optIn(permission))
        for (id in 1L..300L) {
            assertTrue(ledger.markIncorrectHit("synthetic query", id, permission))
        }
        val rows = ledger.feedback(permission)
        assertEquals(SceneFeedbackLedger.MAX_ENTRIES, rows.size)
        assertEquals(45L, rows.first().mediaId)
        assertEquals(300L, rows.last().mediaId)
        assertEquals(rows, SceneFeedbackLedger(store) { 10_000_000_000L }.feedback(permission))
    }

    @Test fun clearFeedbackKeepsOptInWhileFullEraseRevokesIt() {
        val store = MemoryStore()
        val ledger = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertTrue(ledger.optIn(permission))
        assertTrue(ledger.markMissingMoment("stone arch", permission))
        ledger.clearFeedback(permission)
        assertTrue(ledger.feedback(permission).isEmpty())
        assertTrue(ledger.isOptedIn(permission))
        assertTrue(ledger.markMissingMoment("stone arch", permission))
        ledger.clearAll()
        assertNull(store.bytes)
        assertFalse(ledger.isOptedIn(permission))
        assertFalse(ledger.markMissingMoment("stone arch", permission))
        assertTrue(ledger.optIn(permission))
        ledger.optOut()
        assertNull(store.bytes)
        assertFalse(ledger.isOptedIn(permission))
    }

    @Test fun invalidQueriesIdsAndCorruptOrOversizedPayloadFailClosed() {
        val store = MemoryStore()
        val ledger = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertTrue(ledger.optIn(permission))
        assertFalse(ledger.markMissingMoment("", permission))
        assertFalse(ledger.markMissingMoment("   ", permission))
        assertFalse(ledger.markMissingMoment("bad\u0000query", permission))
        assertFalse(ledger.markMissingMoment("\uD800", permission))
        assertFalse(ledger.markMissingMoment("x".repeat(SceneFeedbackLedger.MAX_QUERY_LENGTH + 1), permission))
        assertFalse(ledger.markIncorrectHit("valid", Long.MIN_VALUE, permission))
        assertTrue(ledger.feedback(permission).isEmpty())

        store.bytes = byteArrayOf(0, 1, 2, 3)
        val corrupt = SceneFeedbackLedger(store) { 10_000_000_000L }
        assertTrue(corrupt.feedback(permission).isEmpty())
        assertNull(store.bytes)
        assertFalse(corrupt.isOptedIn(permission))

        store.bytes = ByteArray(100_000) { 5 }
        assertTrue(SceneFeedbackLedger(store) { 10_000_000_000L }.feedback(permission).isEmpty())
        assertNull(store.bytes)
        var invalidEpochRejected = false
        try {
            SceneFeedbackLedger.Permission(true, -1L)
        } catch (_: IllegalArgumentException) {
            invalidEpochRejected = true
        }
        assertTrue(invalidEpochRejected)
    }

    @Test fun atomicFileAdapterPersistsAcrossInstancesAndErasesTempArtifacts() {
        val directory = Files.createTempDirectory("nls-feedback-jvm").toFile()
        try {
            val file = File(directory, "private-feedback.bin")
            val storage = FileSceneFeedbackPersistence(file)
            val ledger = SceneFeedbackLedger(storage) { 10_000_000_000L }
            assertTrue(ledger.optIn(permission))
            assertTrue(ledger.markMissingMoment("licensed synthetic scene", permission))
            assertTrue(file.exists())
            assertFalse(File(directory, "private-feedback.bin.tmp").exists())
            val reopened = SceneFeedbackLedger(FileSceneFeedbackPersistence(file)) { 10_000_000_000L }
            assertEquals(ledger.feedback(permission), reopened.feedback(permission))
            reopened.optOut()
            assertFalse(file.exists())
            assertTrue(SceneFeedbackLedger(FileSceneFeedbackPersistence(file)) {
                10_000_000_000L
            }.feedback(permission).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
