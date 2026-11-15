package nl.atlas.gallery.featurepeople.splitplan

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Synthetic opaque IDs only; no actual faces, embeddings, names or media. */
class SplitAcceptancePlannerTest {
    private val a = PersonKey("synthetic-owner-a")
    private val b = PersonKey("synthetic-owner-b")

    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
    private fun pair(left: Int, right: Int) = FacePair.of(id(left), id(right))
    private fun group(key: String, vararg memberNumbers: Int,
                      exemplars: List<Int> = memberNumbers.take(2)) =
        SplitAcceptanceGroup(key, memberNumbers.map(::id), exemplars.map(::id))

    private fun snapshot(
        groups: List<SplitAcceptanceGroup> =
            listOf(group("one", 1, 2), group("two", 3, 4)),
        sourcePerson: PersonKey? = null,
        unsure: List<NativeFaceObservationId> = emptyList(),
        confirmed: Map<NativeFaceObservationId, PersonKey> = emptyMap(),
        cannotLink: Set<FacePair> = emptySet(),
        sameUnknown: Set<FacePair> = emptySet(),
        rejected: Set<RejectedPerson> = emptySet(),
        counts: SplitFactCounts = SplitFactCounts(
            confirmed.size.toLong(), cannotLink.size.toLong(),
            rejected.size.toLong(), sameUnknown.size.toLong()
        ),
        approved: Boolean = true,
        revision: String = "synthetic-review-generation"
    ) = SplitAcceptanceSnapshot(revision, sourcePerson, groups, unsure, confirmed,
        cannotLink, sameUnknown, rejected, counts, approved)

    private fun plan(input: SplitAcceptanceSnapshot): SplitAcceptancePlan =
        (SplitAcceptancePlanner.plan(input) as SplitPlanResult.Ready).plan

    private fun rejected(input: SplitAcceptanceSnapshot, reason: SplitPlanRejection) {
        assertEquals(SplitPlanResult.Rejected(reason), SplitAcceptancePlanner.plan(input))
    }

    @Test fun previewCancelAndOneGroupNeverProduceAcceptablePlans() {
        val initial = snapshot()
        rejected(initial.copy(explicitlyApproved = false), SplitPlanRejection.NOT_APPROVED)
        rejected(initial.copy(groups = initial.groups.take(1)), SplitPlanRejection.NO_CLEAR_SPLIT)
        rejected(initial.copy(groups = emptyList()), SplitPlanRejection.NO_CLEAR_SPLIT)
        rejected(initial.copy(sourceRevision = ""), SplitPlanRejection.STALE_OR_INVALID_SOURCE)
        rejected(initial.copy(sourceRevision = "bad\u0000revision"),
            SplitPlanRejection.STALE_OR_INVALID_SOURCE)
    }

    @Test fun unnamedTwoGroupsProduceOnlyGroupIntentsAndBoundedBarriers() {
        val original = snapshot(unsure = listOf(id(5), id(6)))
        val output = plan(original)
        assertTrue(output.keptNames.isEmpty())
        assertEquals(listOf("one", "two"), output.unnamedGroups.map { it.groupKey })
        assertEquals(listOf(id(5), id(6)), output.unsureStayWithSource)
        assertTrue(output.releaseConfirmations.isEmpty())
        assertEquals(setOf(pair(1, 3), pair(1, 4), pair(2, 3), pair(2, 4)),
            output.newCannotLinks.toSet())
        assertEquals("synthetic-review-generation", output.sourceRevision)
        assertEquals(null, output.sourcePerson)
    }

    @Test fun namedMajorityKeepsExistingOwnerAndExplicitlyReleasesOutlier() {
        val snapshot = snapshot(
            groups = listOf(group("keep", 1, 2, 3), group("new", 4, 5)),
            sourcePerson = a,
            unsure = listOf(id(6)),
            confirmed = (1..4).associate { id(it) to a }
        )
        val output = plan(snapshot)
        assertEquals(listOf(SplitKeptName("keep", a)), output.keptNames)
        assertEquals(listOf(SplitUnnamedGroup("new", listOf(id(4), id(5)))),
            output.unnamedGroups)
        assertEquals(listOf(SplitReleaseConfirmation(id(4), a)), output.releaseConfirmations)
        assertEquals(listOf(id(6)), output.unsureStayWithSource)
        assertEquals(4, output.newCannotLinks.size)
    }

    @Test fun threeGroupsCanPreserveDifferentPreviouslyConfirmedOwners() {
        val confirmed = ((1..4).associate { id(it) to a } +
            (7..9).associate { id(it) to b })
        val output = plan(snapshot(
            groups = listOf(group("b", 7, 8, 9),
                group("drop", 4, 5, 6), group("a", 1, 2, 3)),
            sourcePerson = a, confirmed = confirmed
        ))
        assertEquals(listOf(SplitKeptName("a", a), SplitKeptName("b", b)),
            output.keptNames)
        assertEquals(listOf("drop"), output.unnamedGroups.map { it.groupKey })
        assertEquals(listOf(SplitReleaseConfirmation(id(4), a)), output.releaseConfirmations)
        assertEquals(12, output.newCannotLinks.size)
    }

    @Test fun perOwnerTieAndUnsureVotesCannotTransferOrLoseName() {
        rejected(snapshot(
            groups = listOf(group("one", 1), group("two", 2)),
            sourcePerson = a, confirmed = mapOf(id(1) to a, id(2) to a)
        ), SplitPlanRejection.UNSAFE_NAME_RETENTION)

        // Unsure's confirmed source face still counts against the 2/3 majority.
        rejected(snapshot(
            groups = listOf(group("one", 1, 2), group("two", 3, 4)),
            unsure = listOf(id(5)), sourcePerson = a,
            confirmed = (1..5).associate { id(it) to a }
        ), SplitPlanRejection.UNSAFE_NAME_RETENTION)

        rejected(snapshot(
            groups = listOf(group("one", 1, 2), group("two", 3, 4)),
            sourcePerson = a
        ), SplitPlanRejection.UNSAFE_NAME_RETENTION)
    }

    @Test fun groupNameClaimTieIsAmbiguousAndMustReject() {
        rejected(snapshot(
            groups = listOf(group("one", 1, 2, 3, 4), group("two", 5)),
            confirmed = mapOf(id(1) to a, id(2) to a, id(3) to b, id(4) to b)
        ), SplitPlanRejection.AMBIGUOUS_NAME)
    }

    @Test fun unnamedSourceCannotEmptyAnyConfirmedExistingPerson() {
        rejected(snapshot(
            groups = listOf(group("one", 1), group("two", 2)),
            confirmed = mapOf(id(1) to a, id(2) to a)
        ), SplitPlanRejection.CONFIRMED_PERSON_EMPTIED)
    }

    @Test fun noImplicitSwitchOfConflictingConfirmedOwnerInsideNamedGroup() {
        rejected(snapshot(
            groups = listOf(group("one", 1, 2, 3, 4), group("two", 5, 6)),
            sourcePerson = a,
            confirmed = mapOf(id(1) to a, id(2) to a, id(3) to a,
                id(4) to b, id(5) to b)
        ), SplitPlanRejection.CROSS_OWNER_GROUP)
    }

    @Test fun existingRejectionOfKeptNameIsNotSilentlyOverridden() {
        rejected(snapshot(
            groups = listOf(group("keep", 1, 2, 3, 4), group("new", 5)),
            sourcePerson = a,
            confirmed = mapOf(id(1) to a, id(2) to a, id(3) to a),
            rejected = setOf(RejectedPerson(id(4), a))
        ), SplitPlanRejection.REJECTED_NAMED_MEMBER)
    }

    @Test fun transitiveSameUnknownThroughUnsureCannotContradictSplit() {
        rejected(snapshot(
            groups = listOf(group("one", 1), group("two", 2)),
            unsure = listOf(id(3)),
            sameUnknown = setOf(pair(1, 3), pair(2, 3))
        ), SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)
    }

    @Test fun withinGroupBarrierOrConflictingExistingHumanFactsReject() {
        rejected(snapshot(
            groups = listOf(group("one", 1, 2), group("two", 3)),
            cannotLink = setOf(pair(1, 2))
        ), SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)
        rejected(snapshot(
            groups = listOf(group("one", 1), group("two", 2)),
            cannotLink = setOf(pair(1, 2)),
            sameUnknown = setOf(pair(1, 2))
        ), SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)
        rejected(snapshot(
            groups = listOf(group("one", 1, 2), group("two", 3)),
            confirmed = mapOf(id(1) to a, id(2) to a),
            rejected = setOf(RejectedPerson(id(1), a))
        ), SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)
    }

    @Test fun preexistingCrossGroupBarrierIsReusedWithoutSpendingFactBudget() {
        val known = pair(1, 2)
        val output = plan(snapshot(
            groups = listOf(group("one", 1), group("two", 2)),
            cannotLink = setOf(known),
            counts = SplitFactCounts(0L, ClusterReviewFacts.MAX_FACTS.toLong(), 0L, 0L)
        ))
        assertTrue(output.newCannotLinks.isEmpty())
        assertEquals(ClusterReviewFacts.MAX_FACTS.toLong(), output.baselineFactCounts.cannotLink)
    }

    @Test fun cannotLinkAndRejectionCeilingsFailClosedWithoutOverflow() {
        rejected(snapshot(
            counts = SplitFactCounts(0L, ClusterReviewFacts.MAX_FACTS.toLong(), 0L, 0L)
        ), SplitPlanRejection.FACT_LIMIT_EXCEEDED)

        rejected(snapshot(
            groups = listOf(group("keep", 1, 2, 3), group("new", 4)),
            sourcePerson = a, confirmed = (1..4).associate { id(it) to a },
            counts = SplitFactCounts(4L, 0L, ClusterReviewFacts.MAX_FACTS.toLong(), 0L)
        ), SplitPlanRejection.FACT_LIMIT_EXCEEDED)

        rejected(snapshot(
            counts = SplitFactCounts(0L, ClusterReviewFacts.MAX_FACTS.toLong() + 1L, 0L, 0L)
        ), SplitPlanRejection.INVALID_FACT_COUNTS)
        rejected(snapshot(
            counts = SplitFactCounts(-1L, 0L, 0L, 0L)
        ), SplitPlanRejection.INVALID_FACT_COUNTS)
        rejected(snapshot(
            cannotLink = setOf(pair(1, 3)),
            counts = SplitFactCounts(0L, 0L, 0L, 0L)
        ), SplitPlanRejection.INVALID_FACT_COUNTS)
    }

    @Test fun badPartitionMissingExemplarsForeignFactsAndDuplicateMembershipReject() {
        rejected(snapshot(
            groups = listOf(group("a", 1, 2), group("a", 3, 4))
        ), SplitPlanRejection.INVALID_PARTITION)
        rejected(snapshot(
            groups = listOf(group("a", 1, 2), group("b", 2, 3))
        ), SplitPlanRejection.DUPLICATE_FACE)
        rejected(snapshot(
            unsure = listOf(id(1))
        ), SplitPlanRejection.DUPLICATE_FACE)
        rejected(snapshot(
            groups = listOf(group("a", 1, exemplars = listOf(99)), group("b", 2))
        ), SplitPlanRejection.INVALID_EXEMPLARS)
        rejected(snapshot(
            cannotLink = setOf(pair(1, 99))
        ), SplitPlanRejection.FOREIGN_REVIEW_FACT)
        rejected(snapshot(
            confirmed = mapOf(id(99) to a)
        ), SplitPlanRejection.FOREIGN_REVIEW_FACT)
        rejected(snapshot(
            groups = List(SplitAcceptancePlanner.MAX_GROUPS + 1) {
                group("group-" + it, it + 1)
            }
        ), SplitPlanRejection.TOO_MANY_GROUPS)
    }

    @Test fun permutationAndInputMutationDoNotChangeReturnedPlan() {
        val members = mutableListOf(id(2), id(1))
        val original = snapshot(
            groups = listOf(
                SplitAcceptanceGroup("two", mutableListOf(id(3), id(4)),
                    mutableListOf(id(4), id(3))),
                SplitAcceptanceGroup("one", members, mutableListOf(id(2), id(1)))
            ),
            unsure = mutableListOf(id(6), id(5))
        )
        val first = plan(original)
        val second = plan(snapshot(unsure = listOf(id(5), id(6))))
        assertEquals(second, first)
        members.add(id(99))
        assertEquals(listOf(id(1), id(2)),
            first.unnamedGroups.first().members)
        try {
            @Suppress("UNCHECKED_CAST")
            (first.unnamedGroups.first().members as MutableList<NativeFaceObservationId>).add(id(99))
            fail("The plan must own unmodifiable membership")
        } catch (expected: UnsupportedOperationException) {
            assertEquals(2, first.unnamedGroups.first().members.size)
        }
    }

    @Test fun fiveThousandSyntheticFacesStayBoundedAndPartitioned() {
        val left = (1..2_500).map(::id)
        val right = (2_501..5_000).map(::id)
        val output = plan(snapshot(groups = listOf(
            SplitAcceptanceGroup("a", left, left.take(2)),
            SplitAcceptanceGroup("b", right, right.take(2))
        )))
        assertEquals(5_000, output.unnamedGroups.sumOf { it.members.size })
        assertEquals(4, output.newCannotLinks.size)
        assertFalse(output.newCannotLinks.any { it.first == it.second })
    }
}
