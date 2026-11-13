package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.identity.SamePersonHypotheses

/**
 * #580: one open "Same person?" question of the whole library. [subject] is
 * the larger side (a named person or an unnamed group), [other] the unnamed
 * group that may be the same person. The sample faces are what the screen
 * shows of each side: one portrait is often not enough to tell.
 */
data class SamePersonQueueItem(
    val subject: PersonPageTarget,
    /** Null for an unnamed group. */
    val subjectName: String?,
    val subjectFaces: Int,
    val subjectSamples: List<NativeFaceObservationId>,
    val other: FaceClusterId,
    val otherFaces: Int,
    val otherSamples: List<NativeFaceObservationId>
) {
    /** Stable for one question while neither side changes: what Skip remembers. */
    val key: String get() = when (subject) {
        is PersonPageTarget.Named -> "n:" + subject.person.value
        is PersonPageTarget.Unnamed -> "u:" + subject.cluster.anchor.localHex
    } + "|" + other.anchor.localHex
}

/** [items] in asking order and how many questions are [open] in all (skipped ones included). */
data class SamePersonQueue(val items: List<SamePersonQueueItem>, val open: Int)

/**
 * #580: every open "Same person?" question in one list, so the owner can
 * answer them in a row instead of opening each person's page. The questions
 * are the ones the pages ask ([samePersonQuestion]): hidden people ask
 * nothing. Order: the answer that would move the most faces first, then the
 * engine's own strength, so the largest loose pieces go first. [skipped]
 * questions ([SamePersonQueueItem.key]) are left out of [SamePersonQueue.items].
 *
 * Reads every unnamed group's vectors; call it off the main thread.
 */
fun samePersonQueue(
    state: PeopleClusterReviewState?,
    skipped: Set<String> = emptySet(),
    limit: Int = SAME_PERSON_QUEUE_LIMIT,
    samples: Int = SAME_PERSON_QUEUE_SAMPLES
): SamePersonQueue {
    require(limit in 1..10_000 && samples in 1..16) { "Invalid queue size" }
    if (state == null) return SamePersonQueue(emptyList(), 0)
    val clusters = state.clusters
    val members = clusters.unknownClusters.associate { it.id to it.members }
    fun sample(faces: List<NativeFaceObservationId>): List<NativeFaceObservationId> {
        if (faces.size <= samples) return faces
        // Evenly spread, so the samples are not all from one burst of photos.
        return List(samples) { faces[(it.toLong() * faces.size / samples).toInt()] }
    }
    class Scored(val item: SamePersonQueueItem, val strength: Float)
    val all = ArrayList<Scored>()
    val asking = state.names.keys.filterTo(HashSet()) { it !in state.hidden }
    if (asking.isNotEmpty()) {
        val confirmed = clusters.reviews.confirmed.entries.filter { it.key in clusters }
            .groupBy({ it.value }, { it.key })
        for (q in SamePersonHypotheses.forPeople(clusters, asking, limit = 10_000)) {
            val own = confirmed[q.person]?.sortedBy { it.localHex } ?: continue
            val group = members[q.group] ?: continue
            all += Scored(SamePersonQueueItem(PersonPageTarget.Named(q.person),
                state.names[q.person], own.size, sample(own),
                q.group, group.size, sample(group)), q.strength)
        }
    }
    for (q in SamePersonHypotheses.of(clusters, limit = SamePersonHypotheses.MAX_GROUPS)) {
        val first = members[q.first] ?: continue
        val second = members[q.second] ?: continue
        all += Scored(SamePersonQueueItem(PersonPageTarget.Unnamed(q.first), null,
            first.size, sample(first), q.second, second.size, sample(second)), q.strength)
    }
    val ordered = all.sortedWith(compareByDescending<Scored> { it.item.otherFaces }
        .thenByDescending { it.strength }.thenBy { it.item.key })
    return SamePersonQueue(
        ordered.asSequence().map { it.item }.filter { it.key !in skipped }.take(limit).toList(),
        ordered.size)
}

/** The owner's answer to [item]: the same action its person page would send. */
fun samePersonQueueAnswer(item: SamePersonQueueItem, same: Boolean): PeopleReviewAction =
    samePersonAnswer(item.subject,
        SamePersonQuestion(item.other, item.otherFaces, item.other.anchor), same)

const val SAME_PERSON_QUEUE_LIMIT = 50
const val SAME_PERSON_QUEUE_SAMPLES = 4
