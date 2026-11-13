package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android storage, permissions, actual photos or MediaStore access. */
class GalleryMediaScanTest {
    @Test fun successfulReadKeepsAllRecords() {
        val result = mutableListOf("existing")
        appendMediaTypeOrRollbackOnDenial(result) { result.addAll(listOf("new1", "new2")) }
        assertEquals(listOf("existing", "new1", "new2"), result)
    }

    @Test fun deniedTypeAfterPartialCursorRowsDoesNotLeakThem() {
        val result = mutableListOf("completed-video1", "completed-video2")
        appendMediaTypeOrRollbackOnDenial(result) {
            result.add("unauthorized-row1")
            result.add("unauthorized-row2")
            throw SecurityException("fictional denial")
        }
        assertEquals(listOf("completed-video1", "completed-video2"), result)
    }

    @Test fun deniedFirstTypePreservesSubsequentPermittedType() {
        val result = mutableListOf<String>()
        appendMediaTypeOrRollbackOnDenial(result) {
            result.add("partial-image")
            throw SecurityException("fictional image denial")
        }
        appendMediaTypeOrRollbackOnDenial(result) {
            result.add("allowed-video")
        }
        assertEquals(listOf("allowed-video"), result)
    }

    @Test fun completelyDeniedEmptyTypeLeavesPriorRecords() {
        val result = mutableListOf("prior")
        appendMediaTypeOrRollbackOnDenial(result) {
            throw SecurityException("fictional image denial")
        }
        assertEquals(listOf("prior"), result)
    }

    @Test fun nonPermissionFailurePropagatesWithoutPublishing() {
        val result = mutableListOf<String>()
        var thrown = false
        try {
            appendMediaTypeOrRollbackOnDenial(result) {
                result.add("partial")
                throw IllegalStateException("fictional cursor failure")
            }
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
