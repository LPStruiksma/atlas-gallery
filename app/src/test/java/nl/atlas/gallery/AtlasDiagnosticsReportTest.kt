package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #45: adversarial values must never leave the device in a diagnostics export. */
class AtlasDiagnosticsReportTest {
    @Test fun injectedNamesPathsUrisAndArbitraryCodesAreAbsent() {
        val text = formatAtlasDiagnostics(AtlasDiagnosticsSnapshot(
            version = "0.3 Jane Doe content://media/42",
            deviceModel = "Jane's S24 /storage/emulated/0/DCIM",
            photoAccess = "content://Jane/private.jpg",
            peoplePhase = "Jane Doe",
            lastCrash = "ATLAS last crash\nException: java.lang.IllegalStateException\n" +
                "Message: /sdcard/DCIM/Jane-Doe.jpg content://secret\n  at com.example.Jane:2",
            logLines = listOf(
                "ATLAS_SCAN rows=5 outcome=SNAPSHOT_CHANGED",
                "ATLAS_SCAN rows=5 name=Jane",
                "ATLAS_SCAN rows=5 uri=content://media/private",
                "ATLAS_SCAN rows=5 outcome=JANE",
                "ATLAS_SCAN rows=5 folder=/sdcard/DCIM"
            )
        ))
        for (secret in listOf("Jane", "Doe", "/sdcard", "content://", "DCIM",
            "com.example", "/storage", "secret")) {
            assertFalse("Leaked $secret", text.contains(secret, ignoreCase = true))
        }
        assertTrue(text.contains("Version: unknown"))
        assertTrue(text.contains("Device model: unknown"))
        assertTrue(text.contains("Crash exception: java.lang.IllegalStateException"))
        assertTrue(text.contains("ATLAS_SCAN rows=5 outcome=SNAPSHOT_CHANGED"))
        assertEquals(1, text.lines().count { it.startsWith("Log: ATLAS_") })
    }

    @Test fun countersAreBoundedAndLogIsStrictlyAllowListed() {
        val text = formatAtlasDiagnostics(AtlasDiagnosticsSnapshot(
            images = 12, videos = 3, volumes = 2, folders = 6,
            peopleEligible = 20, peopleProcessed = 11,
            logLines = List(600) { "ATLAS_SCAN rows=$it outcome=SUCCESS" },
            scans = List(23) { AtlasScanDiagnostic(30, 12, null, 1_024, 4_096, "SUCCESS") }
        ))
        assertTrue(text.contains("Images: 12"))
        assertTrue(text.contains("People processed: 11"))
        assertEquals(20, text.lines().count { it.startsWith("Scan: ") })
        assertEquals(500, text.lines().count { it.startsWith("Log: ATLAS_") })
        assertFalse(text.contains("rows=0 outcome=SUCCESS")) // last 500, not first 500
        assertTrue(text.contains("rows=599 outcome=SUCCESS"))
    }

    @Test fun scannerFailuresUseTypedCodesAndRealFreshScanReuse() {
        assertEquals("SNAPSHOT_CHANGED",
            atlasScanFailureOutcome(GalleryProviderSnapshotChanged()))
        assertEquals("OUT_OF_MEMORY", atlasScanFailureOutcome(OutOfMemoryError("/DCIM/secret")))
        assertEquals("OUT_OF_MEMORY", atlasScanFailureOutcome(GalleryScanOutOfMemory()))
        assertEquals("PERMISSION_DENIED",
            atlasScanFailureOutcome(SecurityException("content://secret")))
        assertEquals("CANCELLED",
            atlasScanFailureOutcome(java.util.concurrent.CancellationException("secret")))
        assertEquals("FAILED", atlasScanFailureOutcome(IllegalStateException("secret")))
        AtlasDiagnosticsRing.scan(25, 42, "SUCCESS", reused = 0)
        val last = AtlasDiagnosticsRing.scans().last()
        assertEquals(42, last.rows)
        assertEquals(0, last.reused)
        assertEquals("SUCCESS", last.outcome)
        assertTrue(formatAtlasDiagnostics(AtlasDiagnosticsSnapshot(scans = listOf(last)))
            .contains("rows=42 reused=0"))
    }

    @Test fun populatedPeopleSlotsRemainNumbersAndBooleans() {
        val text = formatAtlasDiagnostics(AtlasDiagnosticsSnapshot(
            peopleDirty = true, peoplePending = 8, peopleOptIn = true,
            lastPublicationMs = 1_799_900_123_000L, publicationDurationMs = 754,
            visualModelPresent = true, visualModelVerified = false
        ))
        assertTrue(text.contains("People dirty: yes"))
        assertTrue(text.contains("People pending: 8"))
        assertTrue(text.contains("People opted in: yes"))
        assertTrue(text.contains("Last People publication epoch ms: 1799900123000"))
        assertTrue(text.contains("Last People publication duration ms: 754"))
        assertTrue(text.contains("Visual model verified: no"))
    }

    @Test fun unknownOrUnmeasuredFieldsAreExplicitNotInvented() {
        val text = formatAtlasDiagnostics(AtlasDiagnosticsSnapshot(
            peoplePhase = "QUEUED", photoAccess = "SELECTED"))
        assertTrue(text.contains("Photo access: SELECTED"))
        assertTrue(text.contains("People phase: QUEUED"))
        assertTrue(text.contains("Scan history: unavailable"))
        assertTrue(text.contains("People clusters: unavailable"))
        assertTrue(text.contains("Background visual: unavailable"))
    }
}
