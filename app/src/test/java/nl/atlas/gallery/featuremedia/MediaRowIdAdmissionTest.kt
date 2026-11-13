package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

class MediaRowIdAdmissionTest {
    @Test fun positiveProviderIdsArePreservedExactlyAcross64BitRange() {
        for (id in listOf(1L, 42L, Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong() + 1L, Long.MAX_VALUE)) {
            assertEquals(id, requireMediaStoreItemId(id))
        }
    }

    @Test fun nullConvertedToZeroOrNegativeProviderIdsAbortTheWholeScan() {
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) {
            try {
                requireMediaStoreItemId(id)
                fail("Unaddressable provider row reached media catalogue")
            } catch (expected: IllegalStateException) {
                assertEquals("Media provider returned an invalid item identifier",
                    expected.message)
            }
        }
    }

    @Test fun largeSyntheticInventoryFailsOnTailCorruptionWithoutPartialResult() {
        val rows = (1L..150_000L).toMutableList()
        val allIds = rows.map(::requireMediaStoreItemId)
        assertEquals(150_000, allIds.size)
        assertEquals(150_000L, allIds.last())
        rows[149_999] = 0L
        try {
            rows.map(::requireMediaStoreItemId)
            fail("Partial snapshot should not escape after invalid last record")
        } catch (expected: IllegalStateException) {
            assertEquals("Media provider returned an invalid item identifier",
                expected.message)
        }
    }
}
