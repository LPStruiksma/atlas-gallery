package nl.atlas.gallery.featurepeopleui

import java.util.Locale
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.identity.SamePersonHypotheses

/**
 * #551: the pure half of the Samsung-style person page. It maps what the user
 * does on the page (Edit name, Merge with…, Remove from this person, Hide
 * this person) onto the existing identity-review actions, so every write still
 * goes through [reviewPeopleCluster] and FaceIndexStore's review commit. No I/O.
 */
sealed interface PersonPageTarget {
    /** A confirmed, named person. */
    data class Named(val person: PersonKey) : PersonPageTarget

    /** An unknown group of faces: Samsung's unnamed person ("Add name"). */
    data class Unnamed(val cluster: FaceClusterId) : PersonPageTarget
}

/** What the page shows for its target in one live review snapshot. */
data class PersonPageFacts(
    val target: PersonPageTarget,
    /** Null for an unnamed person: the header reads "Add name". */
    val name: String?,
    /** The faces whose photos the page lists, in a stable order. */
    val faces: List<NativeFaceObservationId>,
    val hidden: Boolean,
    /**
     * #580: how many of [faces] (the last ones) the engine added on its own.
     * They can be removed like the others; at least one confirmed face stays.
     */
    val addedFaces: Int = 0
) {
    /** The header portrait: the first face, as on the People grid. */
    val coverFace: NativeFaceObservationId? get() = faces.firstOrNull()
}

/**
 * The page's facts, or null once the target is gone from [state] (merged
 * away, unnamed by Undo, all faces removed, or an unknown group re-clustered).
 */
fun personPageFacts(state: PeopleClusterReviewState?, target: PersonPageTarget): PersonPageFacts? {
    if (state == null) return null
    return when (target) {
        is PersonPageTarget.Named -> {
            val name = state.names[target.person] ?: return null
            val faces = state.clusters.reviews.confirmed.entries
                .filter { it.value == target.person && it.key in state.clusters }
                .map { it.key }.sortedBy { it.localHex }
            val added = state.clusters.matched.entries.filter { it.value.person == target.person }
                .map { it.key }.sortedBy { it.localHex }
            if (faces.isEmpty()) null
            else PersonPageFacts(target, name, faces + added, target.person in state.hidden, added.size)
        }
        is PersonPageTarget.Unnamed -> {
            val cluster = state.clusters.unknownClusters.firstOrNull { it.id == target.cluster }
                ?: return null
            PersonPageFacts(target, null, cluster.members.sortedBy { it.localHex }, false)
        }
    }
}

/** Other named people the page may merge into, by name (hidden ones too). */
fun mergeCandidates(state: PeopleClusterReviewState?, target: PersonPageTarget): List<NativePersonAlbum> {
    if (state == null) return emptyList()
    val self = (target as? PersonPageTarget.Named)?.person
    return peopleAlbumsForGrant(state, state.permissionEpoch, state.sourceGeneration,
        includeHidden = true).filter { it.person != self }
}

/**
 * Names to suggest while typing: other people whose name starts with, then
 * contains, [typed] (case-insensitive), at most [limit]. Empty for blank input.
 */
fun nameSuggestions(
    state: PeopleClusterReviewState?, target: PersonPageTarget, typed: String, limit: Int = 5
): List<NativePersonAlbum> {
    val query = typed.trim().lowercase(Locale.ROOT)
    if (query.isEmpty()) return emptyList()
    val candidates = mergeCandidates(state, target)
    val starts = candidates.filter { it.name.lowercase(Locale.ROOT).startsWith(query) }
    val contains = candidates.filter {
        it !in starts && it.name.lowercase(Locale.ROOT).contains(query)
    }
    return (starts + contains).take(limit)
}

/** The outcome of saving a typed name. */
sealed interface PersonNaming {
    /** Write this action (rename, or name an unknown group). */
    data class Apply(val action: PeopleReviewAction) : PersonNaming

    /** The name belongs to someone else: Samsung offers to merge into them. */
    data class OfferMerge(val into: NativePersonAlbum) : PersonNaming

    /** Nothing to do: blank, too long, or the same name. */
    data object Invalid : PersonNaming
}

/**
 * Saving a typed name. A name another person already has (ignoring case)
 * becomes a merge offer, never a second person with the same name.
 */
fun personNaming(
    state: PeopleClusterReviewState?, target: PersonPageTarget, typed: String
): PersonNaming {
    if (state == null) return PersonNaming.Invalid
    val name = typed.trim()
    if (name.isEmpty() || name.length > 80 || name.any { Character.isISOControl(it) })
        return PersonNaming.Invalid
    val existing = mergeCandidates(state, target).firstOrNull {
        it.name.equals(name, ignoreCase = true)
    }
    if (existing != null) return PersonNaming.OfferMerge(existing)
    return when (target) {
        is PersonPageTarget.Named ->
            if (state.names[target.person] == name) PersonNaming.Invalid
            else PersonNaming.Apply(PeopleReviewAction.RenamePerson(target.person, name))
        is PersonPageTarget.Unnamed ->
            PersonNaming.Apply(PeopleReviewAction.NameCluster(target.cluster,
                newPersonKey(state, target.cluster), name))
    }
}

/**
 * A fresh key for naming [cluster]: "native-<anchor>" as the review screen
 * mints it, with a numeric suffix when that key is already taken.
 */
fun newPersonKey(state: PeopleClusterReviewState, cluster: FaceClusterId): PersonKey {
    val used = state.names.keys + state.clusters.reviews.confirmed.values
    val base = "native-" + cluster.anchor.localHex
    if (PersonKey(base) !in used) return PersonKey(base)
    var n = 2
    while (PersonKey("$base-$n") in used) n++
    return PersonKey("$base-$n")
}

/** Merge the page's person into [into]: the page then shows [into]. */
fun mergeAction(target: PersonPageTarget, into: PersonKey): PeopleReviewAction? = when (target) {
    is PersonPageTarget.Named ->
        if (target.person == into) null else PeopleReviewAction.MergePeople(target.person, into)
    is PersonPageTarget.Unnamed -> PeopleReviewAction.AssignCluster(target.cluster, into)
}

/**
 * "Remove from this person" for the selected photos: every face of the page's
 * person in those photos leaves the person and is kept apart from the rest
 * (cannot-link), so it does not come back. Null when nothing or everything is
 * selected: removing every photo is not a removal Samsung offers either.
 */
fun removeAction(
    facts: PersonPageFacts,
    facesByUri: Map<String, Set<NativeFaceObservationId>>,
    selectedUris: Set<String>
): PeopleReviewAction? {
    val own = facts.faces.toHashSet()
    val selected = selectedUris.flatMapTo(HashSet()) { facesByUri[it].orEmpty() }
        .filterTo(HashSet()) { it in own }
    if (selected.isEmpty() || selected.size >= own.size) return null
    // A named person keeps at least one face a human confirmed.
    val confirmed = facts.faces.size - facts.addedFaces
    if (facts.addedFaces > 0 && facts.faces.take(confirmed).all { it in selected }) return null
    return when (val target = facts.target) {
        is PersonPageTarget.Named -> PeopleReviewAction.SplitPerson(target.person, selected)
        is PersonPageTarget.Unnamed -> PeopleReviewAction.SplitUnknownCluster(target.cluster, selected)
    }
}

/** Where the page goes after [action] committed: the surviving person, if it moved. */
fun targetAfter(target: PersonPageTarget, action: PeopleReviewAction): PersonPageTarget =
    when (action) {
        is PeopleReviewAction.MergePeople -> PersonPageTarget.Named(action.target)
        is PeopleReviewAction.AssignCluster -> PersonPageTarget.Named(action.person)
        is PeopleReviewAction.NameCluster -> PersonPageTarget.Named(action.person)
        // The joined group is named by its smallest face, which is one of the two anchors.
        is PeopleReviewAction.JoinUnknownClusters -> PersonPageTarget.Unnamed(
            if (action.first.anchor.localHex <= action.second.anchor.localHex) action.first
            else action.second)
        else -> target
    }

/**
 * #580 "Same person?" on a person's page: an unnamed group the engine thinks
 * may be the same person. On a named person's page this is how later photos
 * of that person arrive: grouping never adds a face to a named person on its
 * own. Only ever a question; nothing is joined until the user answers.
 */
data class SamePersonQuestion(
    val other: FaceClusterId,
    val otherFaces: Int,
    /** The other group's portrait, as on the People grid. */
    val otherCover: NativeFaceObservationId
)

/**
 * The strongest open question for [target], or null: no candidate, or every
 * candidate already answered. Reads every unnamed group's vectors (about
 * 0.2 s for 14,000 faces on a desktop); call it off the main thread.
 */
fun samePersonQuestion(
    state: PeopleClusterReviewState?, target: PersonPageTarget
): SamePersonQuestion? {
    if (state == null) return null
    if (target is PersonPageTarget.Named) {
        if (target.person !in state.names) return null
        val asked = SamePersonHypotheses.forPeople(state.clusters, setOf(target.person), limit = 1)
            .firstOrNull() ?: return null
        return SamePersonQuestion(asked.group, asked.groupFaces, asked.group.anchor)
    }
    val own = (target as PersonPageTarget.Unnamed).cluster
    val asked = SamePersonHypotheses.of(state.clusters, limit = SamePersonHypotheses.MAX_GROUPS)
        .firstOrNull { it.first == own || it.second == own } ?: return null
    val other = if (asked.first == own) asked.second else asked.first
    val faces = if (asked.first == own) asked.secondFaces else asked.firstFaces
    return SamePersonQuestion(other, faces, other.anchor)
}

/** The user's answer to [question] on [target]'s page. */
fun samePersonAnswer(
    target: PersonPageTarget, question: SamePersonQuestion, same: Boolean
): PeopleReviewAction = when (target) {
    is PersonPageTarget.Unnamed ->
        if (same) PeopleReviewAction.JoinUnknownClusters(target.cluster, question.other)
        else PeopleReviewAction.SeparateUnknownClusters(target.cluster, question.other)
    is PersonPageTarget.Named ->
        if (same) PeopleReviewAction.AssignCluster(question.other, target.person)
        else PeopleReviewAction.SeparateClusterFromPerson(question.other, target.person)
}

/** The header's count line, as Samsung writes it under an album. */
fun personPageCount(photos: Int, videos: Int): String = when {
    videos == 0 -> if (photos == 1) "1 photo" else "%,d photos".format(Locale.US, photos)
    photos == 0 -> if (videos == 1) "1 video" else "%,d videos".format(Locale.US, videos)
    else -> "%,d items".format(Locale.US, photos + videos)
}
