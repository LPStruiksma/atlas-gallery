package nl.atlas.gallery.featurepeople.splitdraft

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.split.SplitProposal
import java.util.Collections

/**
 * Identity of a *proposed* group, not a persisted person or a current member.
 * Anchored to the smallest original observation ID; moving that observation
 * does not rename the group. A new proposal starts a new draft.
 */
@JvmInline
value class SplitDraftGroupId(val anchor: NativeFaceObservationId)

/** Locations supported by one adjustment. No synthetic "new group" is created here. */
sealed interface SplitDraftLocation {
    data class Group(val id: SplitDraftGroupId) : SplitDraftLocation
    data object Unsure : SplitDraftLocation
}

/**
 * Every user action carries the revision observed by its UI. The Move source is
 * also checked, so delayed drags/selections cannot silently move a different face.
 * One Move handles both a single face and a multi-selection, atomically.
 */
sealed interface SplitDraftAction {
    data class Move(
        val faces: List<NativeFaceObservationId>,
        val from: SplitDraftLocation,
        val to: SplitDraftLocation,
        val expectedRevision: Long
    ) : SplitDraftAction

    /** Local acknowledgement only. Persisting an accepted split belongs to SPLIT-2A. */
    data class Confirm(val expectedRevision: Long) : SplitDraftAction
    /** Restore the proposal without changing any underlying review fact. */
    data class Cancel(val expectedRevision: Long) : SplitDraftAction
}

enum class SplitDraftStatus {
    /** Exactly the proposal, and no explicit user action has changed membership. */
    UNTOUCHED,
    /** The owner moved one or more faces (possibly back to the start). */
    ADJUSTED,
    /** Explicit local confirmation; not persisted or accepted into People. */
    CONFIRMED
}

/**
 * [sourceIndex] indexes SplitProposal.groups, for retrieving original exemplars,
 * names and confidence in a preview. Those metrics MUST NOT be treated as
 * recomputed after a move; [members] is the authoritative draft partition.
 */
data class SplitDraftGroup(
    val id: SplitDraftGroupId,
    val sourceIndex: Int,
    val members: List<NativeFaceObservationId>
)

/**
 * Pure, copy-on-write split adjustment. Every exposed list is a defensive,
 * unmodifiable snapshot. No store, model vectors, media or mutable review facts.
 *
 * An empty proposed group is retained under its original ID while editing,
 * but [canConfirm] refuses an incomplete partition. k < 2 is always a manual
 * split fallback, even if the owner moved faces into Unsure.
 *
 * A revision fences actions only within this draft. The integration host must
 * also fence its own source/grant generation on Accept (#172/#233).
 */
class SplitDraftState private constructor(
    val groups: List<SplitDraftGroup>,
    val unsure: List<NativeFaceObservationId>,
    val revision: Long,
    val hasExplicitMoves: Boolean,
    val isConfirmed: Boolean,
    private val initialGroups: List<SplitDraftGroup>,
    private val initialUnsure: List<NativeFaceObservationId>
) {
    val status: SplitDraftStatus
        get() = when {
            isConfirmed -> SplitDraftStatus.CONFIRMED
            hasExplicitMoves -> SplitDraftStatus.ADJUSTED
            else -> SplitDraftStatus.UNTOUCHED
        }

    val differsFromProposal: Boolean
        get() = groups != initialGroups || unsure != initialUnsure

    /** A one-group/no-group proposer result is not an automatic split. */
    val needsManualSplit: Boolean get() = initialGroups.size < 2

    /** Only a local UI gate; it says nothing about permission, source or Undo. */
    val canConfirm: Boolean
        get() = !needsManualSplit && groups.all { it.members.isNotEmpty() }

    /** Return null for a foreign face; never infer a target from a stale index. */
    fun locationOf(face: NativeFaceObservationId): SplitDraftLocation? {
        for (group in groups) if (face in group.members) {
            return SplitDraftLocation.Group(group.id)
        }
        return SplitDraftLocation.Unsure.takeIf { face in unsure }
    }

    fun reduce(action: SplitDraftAction): SplitDraftState {
        val observed = when (action) {
            is SplitDraftAction.Move -> action.expectedRevision
            is SplitDraftAction.Confirm -> action.expectedRevision
            is SplitDraftAction.Cancel -> action.expectedRevision
        }
        if (observed != revision || revision == Long.MAX_VALUE) return this

        return when (action) {
            is SplitDraftAction.Move -> move(action)
            is SplitDraftAction.Confirm -> {
                if (!canConfirm || isConfirmed) this
                else next(groups, unsure, hasExplicitMoves, confirmed = true)
            }
            is SplitDraftAction.Cancel -> {
                if (!hasExplicitMoves && !isConfirmed && !differsFromProposal) this
                else next(initialGroups, initialUnsure, moved = false, confirmed = false)
            }
        }
    }

    private fun move(action: SplitDraftAction.Move): SplitDraftState {
        if (action.from == action.to || action.faces.isEmpty()) return this
        val source = membersAt(action.from) ?: return this
        if (membersAt(action.to) == null) return this

        // Reject duplicates and unknown/wrong-source faces as one all-or-nothing action.
        val picked = action.faces.toHashSet()
        if (picked.size != action.faces.size || !source.toHashSet().containsAll(picked)) return this

        val updatedGroups = groups.map { group ->
            var result: List<NativeFaceObservationId> = group.members
            if (action.from == SplitDraftLocation.Group(group.id)) {
                result = result.filterNot { it in picked }
            }
            if (action.to == SplitDraftLocation.Group(group.id)) {
                result = result + picked
            }
            if (result === group.members) group
            else group.copy(members = sortedSnapshot(result))
        }
        var updatedUnsure: List<NativeFaceObservationId> = unsure
        if (action.from == SplitDraftLocation.Unsure) {
            updatedUnsure = updatedUnsure.filterNot { it in picked }
        }
        if (action.to == SplitDraftLocation.Unsure) {
            updatedUnsure = updatedUnsure + picked
        }
        if (updatedUnsure !== unsure) updatedUnsure = sortedSnapshot(updatedUnsure)
        return next(immutableSnapshot(updatedGroups), updatedUnsure, moved = true, confirmed = false)
    }

    private fun membersAt(location: SplitDraftLocation): List<NativeFaceObservationId>? =
        when (location) {
            is SplitDraftLocation.Group -> groups.firstOrNull { it.id == location.id }?.members
            SplitDraftLocation.Unsure -> unsure
        }

    private fun next(
        newGroups: List<SplitDraftGroup>,
        newUnsure: List<NativeFaceObservationId>,
        moved: Boolean,
        confirmed: Boolean
    ) = SplitDraftState(
        newGroups, newUnsure, revision + 1, moved, confirmed, initialGroups, initialUnsure
    )

    companion object {
        private const val MAX_DRAFT_FACES = 50_000

        /**
         * Fail closed on a malformed proposal: every face occurs in exactly
         * one group or Unsure, and each group has an original anchor.
         * All member/order snapshots and generated IDs are deterministic.
         */
        fun fromProposal(proposal: SplitProposal): SplitDraftState {
            val seen = HashSet<NativeFaceObservationId>()
            val groups = proposal.groups.mapIndexed { index, source ->
                require(source.members.isNotEmpty()) { "Proposed group must have a member" }
                val members = sortedSnapshot(source.members)
                require(members.all(seen::add)) { "Duplicate proposed face" }
                SplitDraftGroup(SplitDraftGroupId(members.first()), index, members)
            }
            val unsure = sortedSnapshot(proposal.unsure.map { it.observation })
            require(unsure.all(seen::add)) { "Duplicate face in Unsure or a group" }
            require(seen.size <= MAX_DRAFT_FACES) { "Draft has too many faces" }
            require(proposal.unsure.all {
                it.nearestGroup == null || it.nearestGroup in proposal.groups.indices
            }) { "Unsure nearest group is invalid" }
            val initialGroups = immutableSnapshot(groups)
            return SplitDraftState(
                initialGroups, unsure, revision = 0L, hasExplicitMoves = false,
                isConfirmed = false, initialGroups = initialGroups, initialUnsure = unsure
            )
        }
    }
}

/** Small integration entry point for #172; both methods are deterministic and side-effect free. */
object SplitDraftReducer {
    fun begin(proposal: SplitProposal): SplitDraftState = SplitDraftState.fromProposal(proposal)
    fun apply(draft: SplitDraftState, action: SplitDraftAction): SplitDraftState = draft.reduce(action)
}

private fun <T> immutableSnapshot(items: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(items))

private fun sortedSnapshot(ids: List<NativeFaceObservationId>): List<NativeFaceObservationId> =
    immutableSnapshot(ids.sortedBy { it.localHex })
