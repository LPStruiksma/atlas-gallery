package nl.atlas.gallery.featurepeople.identity

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.PersonKey

/**
 * #580: an unnamed face the engine is sure belongs to a named [person].
 * [mean] is the crowd-corrected mean cosine to the person's confirmed faces.
 */
data class NamedPersonMatch(
    val face: NativeFaceObservationId,
    val person: PersonKey,
    val mean: Float
)

/**
 * #580: which unnamed faces are, by the measured rule, a named person.
 *
 * Without this every later photo of a named person waits for a human answer.
 * The owner chose (2026-11-12) that a face the engine is sure about shows
 * under the person without one. Grouping applies the rule when
 * [nl.atlas.gallery.featurepeople.cluster.ClusterPolicy.personMatchFloor] is
 * set and keeps the result in [FaceClusterState.matched]; this object reads
 * the same rule from nothing, for the bench and as the reference the tests
 * compare grouping's incremental result with. It stores and changes nothing.
 *
 * A face matches a person when all of these hold:
 * - the person has at least [MIN_EXAMPLES] confirmed faces;
 * - the face's mean corrected cosine to those faces is [MEAN_FLOOR] or more
 *   (docs/people/PEOPLE_BENCH.md, "Adding to a named person without asking":
 *   none of 1,081 such faces was flagged wrong on two library sets split by
 *   time; the grouping cut of 0.35 was too loose);
 * - no other named person reaches the floor for it: two people who both fit
 *   (relatives) is a question for a human, not a match;
 * - no human answer keeps it away: it was not taken out of that person or
 *   refused for them, and has no "not the same person" fact against one of
 *   the person's faces;
 * - the person has no confirmed face in the same picture, and of several
 *   unnamed faces of one picture only the best one matches the person.
 *
 * Only confirmed faces are examples. A matched face never counts for the
 * next one, so one wrong match cannot pull in another, and taking a face out
 * (a stored "not this person") ends its match for good.
 *
 * Deterministic for the same state. Work grows with unnamed faces times
 * named people; call it off the main thread.
 */
object NamedPersonMatches {
    const val MIN_EXAMPLES = 3
    const val MEAN_FLOOR = 0.45f

    /**
     * Every unnamed face of [state] that matches a named person at [floor],
     * whether or not grouping already keeps it in [FaceClusterState.matched].
     * [people] null reads every named person; every named person competes
     * either way. Sorted by person, then best match first.
     */
    fun of(
        state: FaceClusterState, people: Set<PersonKey>? = null, floor: Float = MEAN_FLOOR
    ): List<NamedPersonMatch> {
        require(floor > 0f && floor <= 1f) { "Invalid floor" }
        return FaceClusterer.personMatches(state, floor).asSequence()
            .filter { people == null || it.value.person in people }
            .map { NamedPersonMatch(it.key, it.value.person, it.value.mean) }
            .sortedWith(compareBy<NamedPersonMatch> { it.person.value }
                .thenByDescending { it.mean }.thenBy { it.face.localHex })
            .toList()
    }
}
