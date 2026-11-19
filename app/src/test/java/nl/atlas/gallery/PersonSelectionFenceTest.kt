package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #404: an old person selection must be non-actionable in the first changed frame. */
class PersonSelectionFenceTest {
    private val keys = setOf("content://synthetic/photo-a", "content://synthetic/photo-b")
    private val fence = listOf("grant-17", "people-2", "person-a")

    @Test fun currentGrantPeopleAndPersonIdentityKeepSelectionActionable() {
        assertEquals(keys, validatedPersonSelection(keys, fence, fence.toList()))
    }

    @Test fun changedOrUnknownIdentityHidesSelectionImmediately() {
        assertNull(validatedPersonSelection(keys, fence,
            listOf("grant-18", "people-2", "person-a")))
        assertNull(validatedPersonSelection(keys, fence,
            listOf("grant-17", "people-3", "person-a")))
        assertNull(validatedPersonSelection(keys, fence,
            listOf("grant-17", "people-2", "person-b")))
        assertNull(validatedPersonSelection(keys, fence, null))
        assertNull(validatedPersonSelection(keys, null, fence))
    }

    @Test fun configurationSnapshotIsScopedToTheSamePersonPageOpening() {
        val encoded = fence + keys.sorted()
        val snapshot = PersonSelectionRotationSnapshot(opening = 17L, encoded = encoded)
        assertEquals(encoded, snapshot.forOpening(17L))
        assertEquals(emptyList<String>(), snapshot.forOpening(18L))
    }

    @Test fun oversizedLiveSelectionClearsEarlierSmallerRotationSnapshot() {
        val small = (1..3).map { "content://synthetic/photo-$it" }.toSet()
        val initial = encodePersonSelectionForRotation(small, fence, fence)
        assertEquals(fence + small.sorted(), initial)
        val selectedAll = (1..501).map { "content://synthetic/photo-$it" }.toSet()
        val afterSelectAll = encodePersonSelectionForRotation(selectedAll, fence, fence)
        assertEquals(emptyList<String>(), afterSelectAll)
        val rotated = PersonSelectionRotationSnapshot(17L, afterSelectAll)
        assertEquals(emptyList<String>(), rotated.forOpening(17L))
        assertEquals(emptyList<String>(),
            encodePersonSelectionForRotation(small + "", fence, fence))
        assertEquals(emptyList<String>(),
            encodePersonSelectionForRotation(small, fence, listOf("other-grant")))
    }

    @Test fun restoredPersonKeysKeepOnlyVisibleMembersOfTheSameIdentity() {
        assertEquals(keys, eligiblePersonSelection(keys, keys))
        assertEquals(setOf("content://synthetic/photo-a"),
            eligiblePersonSelection(keys, setOf("content://synthetic/photo-a")))
        assertNull(eligiblePersonSelection(keys, setOf("content://synthetic/photo-c")))
        assertNull(eligiblePersonSelection(emptySet(), keys))
    }
}
