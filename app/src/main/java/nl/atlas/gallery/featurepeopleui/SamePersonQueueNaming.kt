package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.PersonKey

/**
 * A name editor belongs to exactly the question and authorized snapshot
 * from which it opened. A change of generation requires reopening, even
 * if the display name or number of members happens to be unchanged.
 */
internal data class QueueNameRequest(
    val question: SamePersonQueueItem,
    val target: PersonPageTarget,
    val epoch: String,
    val generation: String,
    val scope: String,
    val fingerprint: String,
    val facts: PersonPageFacts,
    val samples: List<NativeFaceObservationId>
) {
    fun current(state: PeopleClusterReviewState?, currentScope: String?): Boolean =
        state != null && state.permissionEpoch == epoch &&
            state.sourceGeneration == generation && currentScope == scope &&
            samePersonSkipFingerprint(state, question, scope) == fingerprint &&
            personPageFacts(state, target) == facts

    fun suggestions(state: PeopleClusterReviewState?, currentScope: String?,
                    typed: String): List<NativePersonAlbum> {
        if (!current(state, currentScope)) return emptyList()
        return nameSuggestions(state, target, typed, Int.MAX_VALUE)
            .filter { it.person !in requireNotNull(state).hidden }
    }

    fun naming(state: PeopleClusterReviewState?, currentScope: String?,
               typed: String): PersonNaming {
        if (!current(state, currentScope)) return PersonNaming.Invalid
        return when (val result = personNaming(state, target, typed)) {
            // Do not reveal a hidden person in the ordinary queue, including
            // via an exact typed collision. Do not create a duplicate name.
            is PersonNaming.OfferMerge ->
                if (result.into.person in requireNotNull(state).hidden)
                    PersonNaming.Invalid else result
            else -> result
        }
    }

    fun confirmedAction(state: PeopleClusterReviewState?, currentScope: String?,
                        typed: String, into: NativePersonAlbum): PeopleReviewAction? {
        val offer = naming(state, currentScope, typed) as? PersonNaming.OfferMerge
            ?: return null
        if (offer.into != into) return null
        return mergeAction(target, into.person)
    }

    fun pairAfter(action: PeopleReviewAction): Set<PersonPageTarget> =
        setOf(question.subject, PersonPageTarget.Unnamed(question.other)).map {
            if (it == target) targetAfter(target, action) else it
        }.toSet()
}

internal fun captureQueueName(
    state: PeopleClusterReviewState?, question: SamePersonQueueItem,
    subject: Boolean, scope: String?
): QueueNameRequest? {
    if (state == null || scope == null || !samePersonQueueStillAsks(state, question)) return null
    val fingerprint = samePersonSkipFingerprint(state, question, scope) ?: return null
    val target = if (subject) question.subject else PersonPageTarget.Unnamed(question.other)
    val facts = personPageFacts(state, target)?.takeUnless { it.hidden } ?: return null
    val samples = (if (subject) question.subjectSamples else question.otherSamples)
        .take(2).filter { it in facts.faces }
    if (samples.isEmpty()) return null
    return QueueNameRequest(question, target, state.permissionEpoch,
        state.sourceGeneration, scope, fingerprint, facts, samples)
}

internal fun SamePersonQueueItem.matchesNamingPair(pair: Set<PersonPageTarget>): Boolean =
    setOf(subject, PersonPageTarget.Unnamed(other)) == pair
