package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Fictional exception payloads only. No real V4 CSV, URI or device storage. */
class V4DocumentErrorMessageTest {
    private val secret = "PRIVATE_2026.csv content://com.example.documents/tree/42 " +
        "/storage/emulated/0/DCIM/private.jpg"

    @Test fun everyDocumentOperationAndFailureCategoryNeverDisclosesPayload() {
        val failures = listOf(
            SecurityException("Denied $secret"),
            IllegalArgumentException("CSV row '$secret'"),
            java.io.FileNotFoundException("Missing $secret"),
            IllegalStateException("SQLite: $secret"),
            RuntimeException("Provider: $secret"),
            RuntimeException("outer", RuntimeException("inner $secret"))
        )
        for (action in V4DocumentOperation.entries) {
            for (failure in failures) {
                val message = v4DocumentFailureMessage(action, failure)
                assertFalse(message.contains("PRIVATE_2026"))
                assertFalse(message.contains("content://"))
                assertFalse(message.contains("/storage/"))
                assertFalse(message.contains("SQLite"))
                assertFalse(message.contains("42"))
                assertTrue(message.contains("V4 export"))
                assertTrue(message.contains("again"))
            }
        }
    }

    @Test fun specificAccessCsvAndReadFailuresRemainActionable() {
        val cannotLink = v4DocumentFailureMessage(
            V4DocumentOperation.LINK, SecurityException(secret))
        val cannotImport = v4DocumentFailureMessage(
            V4DocumentOperation.IMPORT, SecurityException(secret))
        assertNotEquals(cannotLink, cannotImport)
        assertTrue(cannotLink.contains("Select it again"))
        assertTrue(cannotImport.contains("Check file access"))
        assertTrue(v4DocumentFailureMessage(V4DocumentOperation.IMPORT,
            IllegalArgumentException(secret)).contains("invalid or incomplete"))
        assertTrue(v4DocumentFailureMessage(V4DocumentOperation.IMPORT,
            java.io.IOException(secret)).contains("Couldn't read"))
    }

    @Test fun unknownFailuresDoNotExposeNestedPrivateDetails() {
        val failure = IllegalStateException("private document", RuntimeException(secret))
        assertEquals("Couldn't link the V4 export. Try again.",
            v4DocumentFailureMessage(V4DocumentOperation.LINK, failure))
        assertEquals("Couldn't import the V4 export. Try again.",
            v4DocumentFailureMessage(V4DocumentOperation.IMPORT, failure))
    }
}
