package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryScanErrorMessageTest {
    private val secret = "DCIM/PRIVATE_2025.jpg content://media/external_primary/images/media/4242"

    @Test fun privacyBearingProviderExceptionsAreNeverReflectedToUi() {
        for (exception in listOf(
            IllegalStateException("Missing volume: $secret"),
            SecurityException("Denied URI $secret"),
            IllegalArgumentException("Bad cursor for $secret"),
            java.io.IOException("Disk IO failed: $secret"),
            RuntimeException("Provider threw $secret")
        )) {
            val message = galleryScanFailureMessage(exception)
            assertFalse(message.contains("DCIM"))
            assertFalse(message.contains("content://"))
            assertFalse(message.contains("4242"))
            assertFalse(message.contains("PRIVATE"))
            assertTrue(message.contains("refresh", ignoreCase = true))
        }
    }

    @Test fun permissionInterruptedGivesActionableCategoryWithoutCauseDetails() {
        val message = galleryScanFailureMessage(
            SecurityException("private storage volume at /storage/SDCARD/secret"))
        assertTrue(message.contains("permission", ignoreCase = true))
        assertFalse(message.contains("/storage/"))
    }

    @Test fun transientProviderStateAndUnknownFailureAreDistinguished() {
        val changed = galleryScanFailureMessage(
            IllegalStateException("Media provider changed during catalogue scan"))
        val unknown = galleryScanFailureMessage(
            RuntimeException("SQLite exception from named photo"))
        assertNotEquals(changed, unknown)
        assertTrue(changed.contains("changed", ignoreCase = true))
        assertFalse(unknown.contains("SQLite"))
    }
}
