package nl.atlas.gallery.featurepeople.splitdraft

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.split.ProposedGroup
import nl.atlas.gallery.featurepeople.split.SplitProposal
import nl.atlas.gallery.featurepeople.split.UnsureFace
import nl.atlas.gallery.featurepeople.split.UnsureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPLIT-1B synthetic observation IDs only: no media, real people or embeddings. */
class SplitDraftReducerTest {
    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))

    private fun group(vararg members: Int) = group(members.map(::id))
    private fun group(members: List<NativeFaceObservationId>) = ProposedGroup(
        exemplars = members.take(1),
        members = members,
        confirmedCount = 0,
        confirmedPeople = emptyMap(),
        keepsName = null,
        cohesion = 0.85f,
        confidence = members.associateWith { 0.9f }
    )

    private fun unsure(n: Int, nearest: Int? = 0) = UnsureFace(
        observation = id(n),
        nearestGroup = nearest,
        confidence = 0.3f,
        reason = UnsureReason.LOW_MARGIN
    )

    private fun proposal2() = SplitProposal(
        listOf(group(3, 1, 2), group(6, 5, 4)),
        listOf(unsure(8), unsure(7, 1)),
        0.05f
    )

    private fun proposal3() = SplitProposal(
        listOf(group(2, 1), group(4, 3), group(6, 5)),
        listOf(unsure(8), unsure(7, 2)),
        0.04f
    )

    private fun at(g: SplitDraftGroup) = SplitDraftLocation.Group(g.id)

    private fun move(
        state: SplitDraftState,
        faces: List<Int>,
        from: SplitDraftLocation,
        to: SplitDraftLocation,
        revision: Long = state.revision
    ) = SplitDraftReducer.apply(
        state, SplitDraftAction.Move(faces.map(::id), from, to, revision)
    )

    private fun allFaces(state: SplitDraftState): List<NativeFaceObservationId> =
        state.groups.flatMap { it.members } + state.unsure

    private fun assertPartition(state: SplitDraftState, expected: Set<NativeFaceObservationId>) {
        val all = allFaces(state)
        assertEquals(expected.size, all.size)
        assertEquals(expected, all.toSet())
    }

    @Test fun startsAsAnUntouchedTwoGroupPartitionWithStableIds() {
        val p = proposal2()
        val a = SplitDraftReducer.begin(p)
        val b = SplitDraftReducer.begin(p)
        assertEquals(SplitDraftStatus.UNTOUCHED, a.status)
        assertEquals(0L, a.revision)
        assertFalse(a.hasExplicitMoves)
        assertFalse(a.differsFromProposal)
        assertFalse(a.isConfirmed)
        assertTrue(a.canConfirm)
        assertFalse(a.needsManualSplit)
        assertEquals(a.groups.map { it.id }, b.groups.map { it.id })
        assertEquals(listOf(id(1), id(2), id(3)), a.groups[0].members)
        assertEquals(listOf(id(7), id(8)), a.unsure)
        assertEquals(0, a.groups[0].sourceIndex)
        assertEquals(1, a.groups[1].sourceIndex)
        assertPartition(a, (1..8).map(::id).toSet())
        assertNull(a.locationOf(id(999)))
    }

    @Test fun movesAnAnchorWithoutRenamingItsGroup() {
        val initial = SplitDraftReducer.begin(proposal2())
        val first = at(initial.groups[0])
        val second = at(initial.groups[1])
        val moved = move(initial, listOf(1), first, second)
        assertNotSame(initial, moved)
        assertEquals(1L, moved.revision)
        assertEquals(initial.groups.map { it.id }, moved.groups.map { it.id })
        assertEquals(listOf(id(2), id(3)), moved.groups[0].members)
        assertEquals(listOf(id(1), id(4), id(5), id(6)), moved.groups[1].members)
        assertEquals(SplitDraftStatus.ADJUSTED, moved.status)
        assertTrue(moved.differsFromProposal)
        assertEquals(second, moved.locationOf(id(1)))
        assertEquals(first, initial.locationOf(id(1)))
        assertPartition(moved, (1..8).map(::id).toSet())
    }

    @Test fun threeGroupsAndMultiSelectionMoveInBothDirectionsViaUnsure() {
        val original = SplitDraftReducer.begin(proposal3())
        val a = at(original.groups[0])
        val b = at(original.groups[1])
        val c = at(original.groups[2])
        val toThird = move(original, listOf(1, 2), a, c)
        // Empty groups are kept for stable view identity; not valid to confirm.
        assertTrue(toThird.groups[0].members.isEmpty())
        assertEquals(original.groups[0].id, toThird.groups[0].id)
        assertFalse(toThird.canConfirm)
        val toUnsure = move(toThird, listOf(3, 4), b, SplitDraftLocation.Unsure)
        val fromUnsure = move(toUnsure, listOf(7, 3, 4), SplitDraftLocation.Unsure, a)
        assertTrue(fromUnsure.canConfirm)
        assertEquals(listOf(id(3), id(4), id(7)), fromUnsure.groups[0].members)
        assertEquals(listOf(id(8)), fromUnsure.unsure)
        assertEquals(listOf(id(1), id(2), id(5), id(6)), fromUnsure.groups[2].members)
        assertEquals(original.groups.map { it.id }, fromUnsure.groups.map { it.id })
        assertPartition(fromUnsure, (1..8).map(::id).toSet())
    }

    @Test fun foreignDuplicateStaleAndWrongSourceMovesAreAtomicNoOps() {
        val initial = SplitDraftReducer.begin(proposal2())
        val first = at(initial.groups[0])
        val second = at(initial.groups[1])
        assertSame(initial, move(initial, emptyList(), first, second))
        assertSame(initial, move(initial, listOf(1, 1), first, second))
        assertSame(initial, move(initial, listOf(1, 999), first, second))
        assertSame(initial, move(initial, listOf(1, 4), first, second))
        assertSame(initial, move(initial, listOf(7), first, second))
        assertSame(initial, move(initial, listOf(1), first, first))
        assertSame(
            initial,
            move(initial, listOf(1), first, SplitDraftLocation.Group(SplitDraftGroupId(id(999))))
        )
        assertSame(
            initial,
            move(initial, listOf(1), SplitDraftLocation.Group(SplitDraftGroupId(id(999))), second)
        )
        assertSame(initial, move(initial, listOf(1), first, second, revision = 100L))
        val moved = move(initial, listOf(1), first, second)
        assertSame(moved, move(moved, listOf(2), first, second, revision = initial.revision))
        assertSame(moved, move(moved, listOf(1), first, second))
        assertSame(moved, move(moved, listOf(7), SplitDraftLocation.Unsure, SplitDraftLocation.Unsure))
        assertPartition(moved, (1..8).map(::id).toSet())
    }

    @Test fun aRoundTripIsStillAnExplicitUserAdjustmentUntilCancel() {
        val initial = SplitDraftReducer.begin(proposal2())
        val first = at(initial.groups[0])
        val second = at(initial.groups[1])
        val forward = move(initial, listOf(1), first, second)
        val back = move(forward, listOf(1), second, first)
        assertFalse(back.differsFromProposal)
        assertTrue(back.hasExplicitMoves)
        assertEquals(SplitDraftStatus.ADJUSTED, back.status)
        assertEquals(initial.groups, back.groups)
        assertEquals(initial.unsure, back.unsure)
    }

    @Test fun confirmIsExplicitLocalOnlyAndAnyNewMoveClearsIt() {
        val original = SplitDraftReducer.begin(proposal2())
        assertEquals(SplitDraftStatus.UNTOUCHED, original.status)
        val confirmed = SplitDraftReducer.apply(original, SplitDraftAction.Confirm(0L))
        assertEquals(SplitDraftStatus.CONFIRMED, confirmed.status)
        assertTrue(confirmed.isConfirmed)
        assertFalse(confirmed.hasExplicitMoves)
        assertFalse(confirmed.differsFromProposal)
        assertEquals(1L, confirmed.revision)
        assertSame(confirmed, SplitDraftReducer.apply(confirmed, SplitDraftAction.Confirm(1L)))
        assertSame(confirmed, SplitDraftReducer.apply(confirmed, SplitDraftAction.Cancel(0L)))
        val adjusted = move(confirmed, listOf(7), SplitDraftLocation.Unsure, at(confirmed.groups[0]))
        assertFalse(adjusted.isConfirmed)
        assertTrue(adjusted.hasExplicitMoves)
        assertEquals(SplitDraftStatus.ADJUSTED, adjusted.status)
        assertEquals(2L, adjusted.revision)
    }

    @Test fun cancelReturnsOriginalDraftNotMutatingInputAndFencesPriorEvents() {
        val p = proposal2()
        val original = SplitDraftReducer.begin(p)
        val moved = move(original, listOf(7, 8), SplitDraftLocation.Unsure, at(original.groups[1]))
        val confirmed = SplitDraftReducer.apply(moved, SplitDraftAction.Confirm(moved.revision))
        val reset = SplitDraftReducer.apply(confirmed, SplitDraftAction.Cancel(confirmed.revision))
        assertEquals(SplitDraftStatus.UNTOUCHED, reset.status)
        assertFalse(reset.differsFromProposal)
        assertFalse(reset.hasExplicitMoves)
        assertFalse(reset.isConfirmed)
        assertEquals(original.groups, reset.groups)
        assertEquals(original.unsure, reset.unsure)
        assertEquals(confirmed.revision + 1L, reset.revision)
        assertSame(reset, move(reset, listOf(1), at(reset.groups[0]), at(reset.groups[1]), 0L))
        assertEquals(listOf(id(3), id(1), id(2)), p.groups[0].members)
        assertEquals(listOf(id(8), id(7)), p.unsure.map { it.observation })
        assertPartition(reset, (1..8).map(::id).toSet())
    }

    @Test fun oneOrZeroGroupsNeedsManualSplitNotAutomaticAcceptance() {
        val single = SplitDraftReducer.begin(SplitProposal(listOf(group(1, 2)), listOf(unsure(3)), 0f))
        assertTrue(single.needsManualSplit)
        assertFalse(single.canConfirm)
        assertSame(single, SplitDraftReducer.apply(single, SplitDraftAction.Confirm(0L)))
        val adjusted = move(single, listOf(2), at(single.groups[0]), SplitDraftLocation.Unsure)
        assertTrue(adjusted.needsManualSplit)
        assertFalse(adjusted.canConfirm)
        assertSame(adjusted, SplitDraftReducer.apply(adjusted, SplitDraftAction.Confirm(1L)))
        val noGroup = SplitDraftReducer.begin(SplitProposal(emptyList(), listOf(unsure(7, null)), 0f))
        assertTrue(noGroup.needsManualSplit)
        assertFalse(noGroup.canConfirm)
        assertEquals(SplitDraftLocation.Unsure, noGroup.locationOf(id(7)))
    }

    @Test fun rejectsMalformedInputsInsteadOfLosingOrDuplicatingFaces() {
        fun invalid(block: () -> SplitDraftState) {
            try {
                block()
                throw AssertionError("Expected invalid draft proposal")
            } catch (_: IllegalArgumentException) {
                // No partial state returned.
            }
        }
        invalid { SplitDraftReducer.begin(SplitProposal(listOf(group(1), group(1)), emptyList(), 0f)) }
        invalid { SplitDraftReducer.begin(SplitProposal(listOf(group(1, 1)), emptyList(), 0f)) }
        invalid { SplitDraftReducer.begin(SplitProposal(listOf(group(1)), listOf(unsure(1)), 0f)) }
        invalid { SplitDraftReducer.begin(SplitProposal(listOf(group(emptyList())), emptyList(), 0f)) }
        invalid { SplitDraftReducer.begin(SplitProposal(listOf(group(1)), listOf(unsure(2, 9)), 0f)) }
    }

    @Test fun callerListsCannotMutateSnapshotsOrChangeIdsAcrossRecomposition() {
        val mutable = mutableListOf(id(3), id(1), id(2))
        val p = SplitProposal(listOf(group(mutable), group(4, 5)), listOf(unsure(6)), 0.1f)
        val first = SplitDraftReducer.begin(p)
        val again = SplitDraftReducer.begin(
            SplitProposal(listOf(group(2, 3, 1), group(5, 4)), listOf(unsure(6)), 0.1f)
        )
        mutable.clear()
        assertEquals(listOf(id(1), id(2), id(3)), first.groups[0].members)
        assertEquals(first.groups.map { it.id }, again.groups.map { it.id })
        assertEquals(first.groups, again.groups)
        try {
            @Suppress("UNCHECKED_CAST")
            (first.groups[0].members as MutableList<NativeFaceObservationId>).clear()
            throw AssertionError("Snapshot should be unmodifiable")
        } catch (_: UnsupportedOperationException) {
            assertEquals(3, first.groups[0].members.size)
        }
    }

    @Test fun fiveThousandFacesStayPartitionedAcrossOneThousandFaceMove() {
        val p = SplitProposal(
            listOf(group((1..2_500).map(::id)), group((2_501..5_000).map(::id))),
            emptyList(),
            0.08f
        )
        val initial = SplitDraftReducer.begin(p)
        val toUnsure = SplitDraftReducer.apply(
            initial,
            SplitDraftAction.Move(
                (1..1_000).map(::id),
                at(initial.groups[0]),
                SplitDraftLocation.Unsure,
                initial.revision
            )
        )
        assertEquals(1_500, toUnsure.groups[0].members.size)
        assertEquals(2_500, toUnsure.groups[1].members.size)
        assertEquals(1_000, toUnsure.unsure.size)
        assertTrue(toUnsure.canConfirm)
        assertEquals(initial.groups.map { it.id }, toUnsure.groups.map { it.id })
        assertPartition(toUnsure, (1..5_000).map(::id).toSet())
        val reset = SplitDraftReducer.apply(toUnsure, SplitDraftAction.Cancel(toUnsure.revision))
        assertEquals(5_000, allFaces(reset).size)
        assertEquals(initial.groups, reset.groups)
    }
}
