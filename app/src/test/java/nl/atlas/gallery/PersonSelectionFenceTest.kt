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

    @Test fun restoredPersonKeysKeepOnlyVisibleMembersOfTheSameIdentity() {
        assertEquals(keys, eligiblePersonSelection(keys, keys))
        assertEquals(setOf("content://synthetic/photo-a"),
            eligiblePersonSelection(keys, setOf("content://synthetic/photo-a")))
        assertNull(eligiblePersonSelection(keys, setOf("content://synthetic/photo-c")))
        assertNull(eligiblePersonSelection(emptySet(), keys))
    }
}
