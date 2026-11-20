package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepersistence.PeopleReviewRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HUB-1A (#210): the durable "not one person" fact. Synthetic opaque face
 * ids only; no vectors, media or names.
 */
class MixedGroupTest {
    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
    private val group = listOf(id(7), id(3), id(9))

    @Test fun fingerprintIsStableOrderIndependentAndBounded() {
        val fact = MixedGroup.of(group)
        assertEquals(id(3), fact.anchor) // the smallest member, as People names a group
        assertEquals(fact, MixedGroup.of(group.reversed()))
        assertEquals(fact, MixedGroup.of(group.toSet()))
        assertEquals(fact.fingerprint, MixedGroup.fingerprint(listOf(id(9), id(3), id(7))))
        assertTrue(MixedGroup.FINGERPRINT.matches(fact.fingerprint))
        assertEquals(64, fact.fingerprint.length)
        // Pinned value: a change to the fingerprint rule must be deliberate,
        // because it orphans every stored and backed-up fact.
        assertEquals(PINNED, MixedGroup.fingerprint(listOf(id(1), id(2))))
    }

    @Test fun anyMembershipChangeGivesAnotherFingerprint() {
        val fact = MixedGroup.of(group)
        assertTrue(fact.matches(group.shuffled()))
        assertFalse(fact.matches(group + id(11)))   // a face joined
        assertFalse(fact.matches(group.drop(1)))     // a face left (also a new anchor)
        assertFalse(fact.matches(listOf(id(3), id(7), id(10)))) // same anchor, other member
        assertNotEquals(fact.fingerprint, MixedGroup.fingerprint(listOf(id(3), id(7), id(10))))
        assertFalse(fact.matches(listOf(id(3), id(3), id(7)))) // duplicates never match
    }

    @Test fun malformedFactsAndGroupsAreRefused() {
        val good = MixedGroup.of(group).fingerprint
        for (bad in listOf("", good.uppercase(), good.dropLast(1), good + "0",
            "g" + good.drop(1), " " + good.drop(1))) {
            refused { MixedGroup(id(1), bad) }
        }
        refused { MixedGroup.of(emptyList()) }
        refused { MixedGroup.of(listOf(id(1))) } // one face cannot mix two people
        refused { MixedGroup.of(listOf(id(1), id(1))) }
        refused { MixedGroup.fingerprint(listOf(id(2), id(1), id(2))) }
    }

    @Test fun storedRowsRoundTripWithoutTouchingOtherFacts() {
        val facts = ClusterReviewFacts(
            confirmed = mapOf(id(1) to PersonKey("p")),
            cannotLink = setOf(FacePair.of(id(1), id(2))),
            rejectedPeople = setOf(RejectedPerson(id(2), PersonKey("p"))),
            sameUnknown = setOf(FacePair.of(id(4), id(5))),
            mixedGroups = setOf(MixedGroup.of(group), MixedGroup.of(listOf(id(3), id(7))))
        )
        val rows = PeopleReviewRecords.rows(facts)
        assertEquals(setOf(id(3).localHex to MixedGroup.of(group).fingerprint,
            id(3).localHex to MixedGroup.of(listOf(id(3), id(7))).fingerprint), rows.mixedGroups)
        assertEquals(facts, PeopleReviewRecords.facts(rows))
        // Facts without mixed groups map exactly as before.
        val legacy = facts.copy(mixedGroups = emptySet())
        assertEquals(PeopleReviewRecords.Rows(rows.confirmed, rows.cannotLink, rows.rejected,
            rows.sameUnknown), PeopleReviewRecords.rows(legacy))
        assertEquals(legacy, PeopleReviewRecords.facts(PeopleReviewRecords.rows(legacy)))
    }

    private fun refused(block: () -> Unit) {
        try { block(); fail("Expected a refused mixed-group fact") }
        catch (_: IllegalArgumentException) { }
    }

    private companion object {
        const val PINNED = "8e4c64f138002cfc3fdad3d2bbdd9709501a7d0871f17fd4648b706c3f69dff0"
    }
}
