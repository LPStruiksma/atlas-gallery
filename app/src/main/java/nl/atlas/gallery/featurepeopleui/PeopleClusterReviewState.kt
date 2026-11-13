package nl.atlas.gallery.featurepeopleui

import java.util.Locale
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.identity.ReviewTally
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster

/**
 * Human-authored identity decisions only. FaceClusterer supplies UNKNOWN groups
 * and optional suggestions; neither a suggestion nor a similarity score is an
 * identity assignment. This reducer does no I/O and never modifies image data.
 *
 * The caller persists the new review facts, names and undo journal atomically,
 * and supplies a fresh committed grant/source revision after a change. Until a
 * backend does that, the UI must remain read-only. A new scan or grant invalidates
 * the in-memory undo journal rather than replaying stale face identifiers.
 */
data class PeopleClusterReviewState(
    val permissionEpoch: String,
    val sourceGeneration: String,
    val clusters: FaceClusterState,
    val names: Map<PersonKey, String> = emptyMap(),
    val undoHistory: List<PeopleReviewCheckpoint> = emptyList(),
    /**
     * Named people the user hid from People (#551, Samsung "Hide this
     * person"). Their photos and facts are untouched; People ⋮ → Hidden
     * lists them and can show them again.
     */
    val hidden: Set<PersonKey> = emptySet()
) {
    init {
        require(permissionEpoch.isNotBlank() && sourceGeneration.isNotBlank())
        require(names.values.all(::validReviewedName))
        require(names.keys.containsAll(hidden)) { "A hidden person must be named" }
    }
}

/**
 * Home's compact immutable read model. Unlike [PeopleClusterReviewState], this
 * contains no embedding vectors or unknown clusters. The store supplies only
 * names that own a processed, current-model face.
 */
data class PeopleHomeSummary(
    val permissionEpoch: String,
    val sourceGeneration: String,
    val people: Map<PersonKey, PeopleHomePerson>,
    /** #579: unnamed groups of 3+ faces, largest first. */
    val unnamed: List<PeopleHomeGroup> = emptyList()
) {
    init {
        require(permissionEpoch.isNotBlank() && sourceGeneration.isNotBlank())
    }
}

/** #579: an unnamed group on People; [anchorFaceUid] is opaque and also its cover. */
data class PeopleHomeGroup(val anchorFaceUid: String, val faceCount: Int)

data class PeopleHomePerson(
    val name: String,
    val faceCount: Int,
    /** Opaque observation identifier, not an image URI or file path. */
    val coverFaceUid: String
)

/** Same visibility and sorting rules as [peopleAlbumsForGrant], without vectors. */
fun peopleHomeAlbumsForGrant(
    state: PeopleHomeSummary?,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?
): List<NativePersonSearchEntry> {
    if (state == null || state.permissionEpoch != currentPermissionEpoch ||
        state.sourceGeneration != currentSourceGeneration ||
        currentPermissionEpoch.isNullOrBlank() || currentSourceGeneration.isNullOrBlank()
    ) return emptyList()
    return state.people.map { (person, entry) ->
        NativePersonSearchEntry(person, entry.name, entry.faceCount)
    }.sortedWith(compareBy<NativePersonSearchEntry> { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.person.value })
}

fun peopleHomeSearchForGrant(
    state: PeopleHomeSummary?,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?,
    query: String
): List<NativePersonSearchEntry> {
    val search = query.trim().lowercase(Locale.ROOT)
    if (search.length > 80 || search.any { Character.isISOControl(it) }) return emptyList()
    return peopleHomeAlbumsForGrant(state, currentPermissionEpoch, currentSourceGeneration)
        .asSequence().filter { search.isEmpty() || it.name.lowercase(Locale.ROOT).contains(search) }
        .take(40).toList()
}

/** Previous *complete* immutable cluster snapshot, not an inverse similarity guess. */
data class PeopleReviewCheckpoint(
    val clusters: FaceClusterState,
    val names: Map<PersonKey, String>,
    val hidden: Set<PersonKey> = emptySet(),
    /** #580: what the tap that left this checkpoint counted as, for Undo to take back. */
    val tally: ReviewTally? = null
)

sealed interface PeopleReviewAction {
    /** Name an unknown group, creating a caller-chosen unique person key. */
    data class NameCluster(
        val cluster: FaceClusterId, val person: PersonKey, val name: String
    ) : PeopleReviewAction

    /** Explicitly attach one entire unknown group to an existing named person. */
    data class AssignCluster(
        val cluster: FaceClusterId, val person: PersonKey
    ) : PeopleReviewAction

    /** Merge two *confirmed* identities; source label disappears until Undo. */
    data class MergePeople(
        val source: PersonKey, val target: PersonKey
    ) : PeopleReviewAction

    /** Mark a proper subset of an unknown group as "not the same person". */
    data class SplitUnknownCluster(
        val cluster: FaceClusterId,
        val selected: Set<NativeFaceObservationId>
    ) : PeopleReviewAction

    /**
     * Remove faces from a named person: confirmed ones (at least one must
     * stay) and, #580, faces the engine added on its own.
     */
    data class SplitPerson(
        val person: PersonKey,
        val selected: Set<NativeFaceObservationId>
    ) : PeopleReviewAction

    /** Move one observation to a named person, never from similarity alone. */
    data class ReassignFace(
        val face: NativeFaceObservationId, val target: PersonKey
    ) : PeopleReviewAction

    /** Take one observation out of its person (confirmed or added by the engine), returning it to Unknown. */
    data class UnassignFace(val face: NativeFaceObservationId) : PeopleReviewAction

    /** An explicit "not this person" decision about a suggested identity. */
    data class RejectSuggestion(
        val cluster: FaceClusterId, val person: PersonKey
    ) : PeopleReviewAction

    /**
     * #580 "Same person?" answered yes for two unnamed groups: they become
     * one group and stay one, also after a regroup. Refused when a "not the
     * same person" fact stands between any two of their faces.
     */
    data class JoinUnknownClusters(
        val first: FaceClusterId, val second: FaceClusterId
    ) : PeopleReviewAction

    /** "Same person?" answered no: never grouped together, never asked again. */
    data class SeparateUnknownClusters(
        val first: FaceClusterId, val second: FaceClusterId
    ) : PeopleReviewAction

    /**
     * #580 "Same person?" on a named person's page answered no for an unnamed
     * group: none of its faces is offered for that person again.
     */
    data class SeparateClusterFromPerson(
        val cluster: FaceClusterId, val person: PersonKey
    ) : PeopleReviewAction

    /** Give a confirmed person another name (Samsung "Edit name"). */
    data class RenamePerson(val person: PersonKey, val name: String) : PeopleReviewAction

    /** Hide a named person from People, or show them again (#551). */
    data class SetPersonHidden(val person: PersonKey, val hidden: Boolean) : PeopleReviewAction

    data object Undo : PeopleReviewAction
}

private fun validReviewedName(value: String): Boolean {
    val cleaned = value.trim()
    return cleaned.isNotEmpty() && cleaned.length <= 80 &&
        cleaned == value && value.none { Character.isISOControl(it) }
}

/** No stale callback may use a different page object, grant, or source version. */
fun currentPeopleReviewActionOrNull(
    rendered: PeopleClusterReviewState?,
    action: PeopleReviewAction,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?,
    liveAtAction: PeopleClusterReviewState?
): PeopleReviewAction? =
    if (rendered != null && liveAtAction === rendered &&
        !currentPermissionEpoch.isNullOrBlank() &&
        !currentSourceGeneration.isNullOrBlank() &&
        rendered.permissionEpoch == currentPermissionEpoch &&
        rendered.sourceGeneration == currentSourceGeneration
    ) action else null

/**
 * Command envelope for a storage owner: source equality MUST be rechecked
 * when the transaction starts, not just when the Compose button was tapped.
 */
data class PeopleClusterReviewCommand(
    val expectedState: PeopleClusterReviewState,
    val action: PeopleReviewAction
)

fun applyCurrentPeopleReviewCommand(
    liveState: PeopleClusterReviewState?,
    command: PeopleClusterReviewCommand
): PeopleClusterReviewState? =
    if (liveState === command.expectedState) reviewPeopleCluster(liveState, command.action)
    else null

private fun PeopleClusterReviewState.unknown(id: FaceClusterId): UnknownFaceCluster? =
    clusters.unknownClusters.firstOrNull { it.id == id }

private fun cannotJoin(
    combined: Set<NativeFaceObservationId>,
    facts: ClusterReviewFacts
): Boolean = facts.cannotLink.any { it.first in combined && it.second in combined }

/** No partial write: invalid, ambiguous, stale or contradictory actions return null. */
fun reviewPeopleCluster(
    state: PeopleClusterReviewState,
    action: PeopleReviewAction
): PeopleClusterReviewState? {
    if (action == PeopleReviewAction.Undo) {
        val previous = state.undoHistory.lastOrNull() ?: return null
        return state.copy(clusters = previous.clusters, names = previous.names,
            hidden = previous.hidden, undoHistory = state.undoHistory.dropLast(1))
    }

    val prior = state.clusters.reviews
    val confirmed = prior.confirmed.toMutableMap()
    val cannotLink = prior.cannotLink.toMutableSet()
    val rejected = prior.rejectedPeople.toMutableSet()
    val same = prior.sameUnknown.toMutableSet()
    val names = state.names.toMutableMap()
    val hidden = state.hidden.toMutableSet()

    fun addBarriers(
        selected: Set<NativeFaceObservationId>,
        rest: Collection<NativeFaceObservationId>
    ): Boolean {
        // Bound a potentially quadratic user split before allocating the pairs.
        if (selected.size.toLong() * rest.size > ClusterReviewFacts.MAX_FACTS -
            cannotLink.size.toLong()) return false
        for (a in selected) for (b in rest) cannotLink.add(FacePair.of(a, b))
        return cannotLink.size <= ClusterReviewFacts.MAX_FACTS
    }

    when (action) {
        is PeopleReviewAction.NameCluster -> {
            val cluster = state.unknown(action.cluster) ?: return null
            val name = action.name.trim()
            if (!validReviewedName(name) || action.person in names ||
                action.person in confirmed.values) return null
            if (cluster.members.any { it in confirmed }) return null
            names[action.person] = name
            for (f in cluster.members) confirmed[f] = action.person
        }
        is PeopleReviewAction.AssignCluster -> {
            val cluster = state.unknown(action.cluster) ?: return null
            if (action.person !in names) return null
            val combined = (cluster.members +
                confirmed.filterValues { it == action.person }.keys).toSet()
            if (cannotJoin(combined, prior)) return null
            for (f in cluster.members) {
                confirmed[f] = action.person
                rejected.remove(RejectedPerson(f, action.person))
            }
        }
        is PeopleReviewAction.MergePeople -> {
            if (action.source == action.target || action.source !in names ||
                action.target !in names) return null
            val combined = confirmed.filterValues {
                it == action.source || it == action.target
            }.keys
            if (cannotJoin(combined, prior)) return null
            for ((f, p) in confirmed.toMap()) {
                if (p == action.source) confirmed[f] = action.target
            }
            names.remove(action.source)
            // The merged person keeps the target's visibility (#551).
            hidden.remove(action.source)
        }
        is PeopleReviewAction.SplitUnknownCluster -> {
            val cluster = state.unknown(action.cluster) ?: return null
            val selected = action.selected
            if (selected.isEmpty() || selected.size >= cluster.members.size ||
                !cluster.members.containsAll(selected)) return null
            if (!addBarriers(selected, cluster.members.filterNot { it in selected })) return null
        }
        is PeopleReviewAction.SplitPerson -> {
            if (action.person !in names) return null
            val owned = confirmed.filterValues { it == action.person }.keys
            // #580: faces the engine added on its own are shown with the
            // person and can be taken out like any other; they hold no
            // confirmation to drop, only a "not this person" to store.
            val added = state.clusters.matched.filterValues { it.person == action.person }.keys
            val ownedOut = action.selected.filterTo(HashSet()) { it in owned }
            if (action.selected.isEmpty() || ownedOut.size >= owned.size ||
                !action.selected.all { it in owned || it in added }) return null
            if (!addBarriers(ownedOut, owned.filterNot { it in ownedOut })) return null
            for (f in action.selected) {
                confirmed.remove(f)
                rejected.add(RejectedPerson(f, action.person))
            }
        }
        is PeopleReviewAction.ReassignFace -> {
            if (action.target !in names || action.face !in state.clusters ||
                confirmed[action.face] == action.target) return null
            val combined = confirmed.filterValues { it == action.target }.keys +
                action.face
            if (cannotJoin(combined.toSet(), prior)) return null
            confirmed[action.face] = action.target
            rejected.remove(RejectedPerson(action.face, action.target))
        }
        is PeopleReviewAction.UnassignFace -> {
            val source = confirmed.remove(action.face)
                ?: state.clusters.matched[action.face]?.person ?: return null
            rejected.add(RejectedPerson(action.face, source))
        }
        is PeopleReviewAction.RejectSuggestion -> {
            val cluster = state.unknown(action.cluster) ?: return null
            if (cluster.suggestion?.person != action.person) return null
            for (f in cluster.members) rejected.add(RejectedPerson(f, action.person))
        }
        is PeopleReviewAction.JoinUnknownClusters -> {
            val a = state.unknown(action.first) ?: return null
            val b = state.unknown(action.second) ?: return null
            if (a.id == b.id || same.size >= ClusterReviewFacts.MAX_FACTS) return null
            if (cannotJoin((a.members + b.members).toSet(), prior)) return null
            same.add(FacePair.of(a.id.anchor, b.id.anchor))
        }
        is PeopleReviewAction.SeparateUnknownClusters -> {
            val a = state.unknown(action.first) ?: return null
            val b = state.unknown(action.second) ?: return null
            if (a.id == b.id || cannotLink.size >= ClusterReviewFacts.MAX_FACTS) return null
            // One pair is enough: a fact between any two faces keeps their groups apart.
            cannotLink.add(FacePair.of(a.id.anchor, b.id.anchor))
            val inA = a.members.toSet()
            val inB = b.members.toSet()
            same.removeAll { (it.first in inA && it.second in inB) ||
                (it.first in inB && it.second in inA) }
        }
        is PeopleReviewAction.SeparateClusterFromPerson -> {
            val cluster = state.unknown(action.cluster) ?: return null
            if (action.person !in names) return null
            for (f in cluster.members) rejected.add(RejectedPerson(f, action.person))
        }
        is PeopleReviewAction.RenamePerson -> {
            val name = action.name.trim()
            if (action.person !in names || !validReviewedName(name) ||
                names[action.person] == name) return null
            names[action.person] = name
        }
        is PeopleReviewAction.SetPersonHidden -> {
            if (action.person !in names) return null
            if (action.hidden) { if (!hidden.add(action.person)) return null }
            else if (!hidden.remove(action.person)) return null
        }
        PeopleReviewAction.Undo -> return null // handled before mutation
    }
    if (confirmed.size > ClusterReviewFacts.MAX_FACTS ||
        rejected.size > ClusterReviewFacts.MAX_FACTS) return null
    val newFacts = ClusterReviewFacts(
        confirmed = confirmed.toMap(), cannotLink = cannotLink.toSet(),
        rejectedPeople = rejected.toSet(), sameUnknown = same.toSet()
    )
    if (newFacts == prior && names == state.names && hidden == state.hidden) return null
    // Names and visibility alone never re-cluster (and keep the same object).
    val updated = if (newFacts == prior) state.clusters
    // This re-clusters only unknowns; confirmed faces are explicitly excluded
    // by FaceClusterer and may never be silently relabelled.
    else FaceClusterer.update(state.clusters, reviews = newFacts)
    return state.copy(clusters = updated, names = names.toMap(), hidden = hidden.toSet(),
        undoHistory = state.undoHistory +
            PeopleReviewCheckpoint(state.clusters, state.names, state.hidden,
                reviewTally(state, action)))
}

/** Metadata only: host must resolve observation IDs against its CURRENT grant. */
data class NativePersonAlbum(
    val person: PersonKey,
    val name: String,
    val observations: List<NativeFaceObservationId>,
    /** Only keys supplied from the already-authorized visible media inventory. */
    val mediaKeys: List<String>
)

/** Safe People search entry; not a face-recognition result or an auto-assignment. */
data class NativePersonSearchEntry(
    val person: PersonKey,
    val name: String,
    val accessiblePhotos: Int
)

/**
 * The named people People shows. Hidden people (#551) are left out unless
 * [includeHidden]; [hiddenOnly] lists just them (People ⋮ → Hidden).
 */
fun peopleAlbumsForGrant(
    state: PeopleClusterReviewState?,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?,
    authorizedMediaByFace: Map<NativeFaceObservationId, String> = emptyMap(),
    includeHidden: Boolean = false,
    hiddenOnly: Boolean = false
): List<NativePersonAlbum> {
    if (state == null || state.permissionEpoch != currentPermissionEpoch ||
        state.sourceGeneration != currentSourceGeneration ||
        currentPermissionEpoch.isNullOrBlank() || currentSourceGeneration.isNullOrBlank()
    ) return emptyList()
    val byPerson = state.clusters.reviews.confirmed.entries.groupBy({ it.value }, { it.key })
    val addedTo = state.clusters.matched.entries.groupBy({ it.value.person }, { it.key })
    return state.names.mapNotNull { (person, name) ->
        val isHidden = person in state.hidden
        if (hiddenOnly && !isHidden || !hiddenOnly && !includeHidden && isHidden)
            return@mapNotNull null
        // Confirmed faces first (the cover is one of them), then the faces
        // the engine added on its own (#580).
        val faces = (byPerson[person]?.sortedBy { it.localHex } ?: return@mapNotNull null) +
            addedTo[person].orEmpty().sortedBy { it.localHex }
        NativePersonAlbum(person, name, faces,
            faces.mapNotNull { authorizedMediaByFace[it] }.distinct())
    }.sortedWith(compareBy<NativePersonAlbum> { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.person.value })
}

fun peopleSearchForGrant(
    state: PeopleClusterReviewState?,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?,
    query: String,
    authorizedMediaByFace: Map<NativeFaceObservationId, String> = emptyMap()
): List<NativePersonSearchEntry> {
    val search = query.trim().lowercase(Locale.ROOT)
    if (search.length > 80 || search.any { Character.isISOControl(it) }) return emptyList()
    // Search still finds a hidden person by name, like Samsung's search.
    return peopleAlbumsForGrant(state, currentPermissionEpoch,
        currentSourceGeneration, authorizedMediaByFace, includeHidden = true)
        .asSequence().filter { search.isEmpty() ||
            it.name.lowercase(Locale.ROOT).contains(search) }
        .take(40)
        .map { NativePersonSearchEntry(it.person, it.name, it.mediaKeys.size) }
        .toList()
}
