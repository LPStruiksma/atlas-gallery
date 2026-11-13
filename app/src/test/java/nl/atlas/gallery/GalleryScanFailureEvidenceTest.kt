package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** No Android provider, user files, filenames or MediaStore access. */
class GalleryScanFailureEvidenceTest {
    private fun note(cause: Exception, changed: Boolean = false,
                     grantSame: Boolean? = true, millis: Long = 31_400L) =
        galleryScanFailureEvidence(cause, changed, grantSame,
            latestRequest = true, explicitlyRevoked = false,
            elapsedMilliseconds = millis)

    @Test fun knownCategoriesAreFixedAndNeverUseProviderExceptionMessages() {
        assertEquals(GalleryScanFailureCode.SNAPSHOT_CHANGED,
            note(GalleryProviderSnapshotChanged()).code)
        assertEquals(GalleryScanFailureCode.VERSION_UNAVAILABLE,
            note(GalleryProviderVersionUnavailable()).code)
        assertEquals(GalleryScanFailureCode.SECURITY,
            note(SecurityException("private")).code)
        assertEquals(GalleryScanFailureCode.PROVIDER_STATE,
            note(IllegalStateException("private")).code)
        assertEquals(GalleryScanFailureCode.INVALID_ROW,
            note(IllegalArgumentException("private")).code)
        assertEquals(GalleryScanFailureCode.IO,
            note(java.io.IOException("private")).code)
        assertEquals(GalleryScanFailureCode.UNKNOWN,
            note(RuntimeException("private")).code)
    }

    @Test fun knownProviderSnapshotChurnIsDistinctFromOtherIllegalState() {
        val churn = note(GalleryProviderSnapshotChanged(), changed = true)
            .safeLogLine(9)
        assertTrue(churn.contains("code=SNAPSHOT_CHANGED"))
        assertTrue(churn.contains("grant=SAME"))
        assertFalse(churn.contains("Media provider changed"))
        val other = note(IllegalStateException("provider private photo"))
            .safeLogLine(10)
        assertTrue(other.contains("code=PROVIDER_STATE"))
        assertFalse(other.contains("provider private photo"))
    }

    @Test fun maliciousThrowableCannotSmuggleMediaIdentityIntoLogs() {
        val exception = object : RuntimeException(
            "content://media/external/images/media/9913 /storage/emulated/0/DCIM/PRIVATE.jpg"
        ) {
            override fun toString(): String = error("Throwable.toString must never run")
        }
        val line = note(exception, changed = true).safeLogLine(7L)
        assertTrue(line.contains("code=UNKNOWN"))
        assertTrue(line.contains("observer_changed=true"))
        for (privatePart in listOf("content://", "DCIM", "PRIVATE", "/storage/", "9913"))
            assertFalse(line.contains(privatePart))
        assertFalse(line.contains(exception.javaClass.name))
    }

    @Test fun actualGrantAndProviderObserverChangesAreIndependent() {
        val e = IllegalStateException("private")
        val matched = note(e, changed = true, grantSame = true).safeLogLine(3)
        assertTrue(matched.contains("observer_changed=true grant=SAME"))
        val rejected = note(e, changed = false, grantSame = false).safeLogLine(4)
        assertTrue(rejected.contains("observer_changed=false grant=DIFFERENT"))
        assertTrue(note(e, grantSame = null).safeLogLine(5).contains("grant=UNKNOWN"))
    }

    @Test fun revokedAndSupersededFailuresRemainDistinguishable() {
        val e = galleryScanFailureEvidence(RuntimeException("secret"),
            observerChanged = true, grantAtStartStillMatches = false,
            latestRequest = false, explicitlyRevoked = true,
            elapsedMilliseconds = 120L)
        assertEquals("event=failed ticket=2 code=UNKNOWN observer_changed=true " +
            "grant=DIFFERENT latest=false revoked=true elapsed_s=0", e.safeLogLine(2))
    }

    @Test fun timingIsBoundedAndSuccessfulPublicationCanBeCorrelated() {
        assertEquals(0L, galleryScanElapsedSeconds(-1))
        assertEquals(0L, galleryScanElapsedSeconds(999))
        assertEquals(21L, galleryScanElapsedSeconds(21_999))
        assertEquals(600L, galleryScanElapsedSeconds(Long.MAX_VALUE))
        assertEquals("event=published ticket=8 elapsed_s=31",
            galleryScanPublicationLogLine(8, 31_400))
        assertThrows(IllegalArgumentException::class.java) {
            galleryScanPublicationLogLine(0, 1)
        }
    }

    @Test fun repeatedSyntheticFailuresRemainConstantSizeWithoutMedia() {
        val e = RuntimeException("content://media/PRIVATE.jpg")
        repeat(150_000) {
            val line = note(e, changed = it % 2 == 0).safeLogLine(1)
            assertTrue(line.length < 150)
            assertFalse(line.contains("content://"))
        }
    }
}
