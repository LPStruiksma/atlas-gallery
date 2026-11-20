package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.MixedGroupMarks
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster
import nl.atlas.gallery.featurepeople.identity.PersonHypothesis
import nl.atlas.gallery.featurepeople.identity.SamePersonHypotheses
import nl.atlas.gallery.featurepeople.identity.SamePersonHypothesis

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
 * HUB-1B (#211): a question one of whose sides is marked "not one person"
 * ([MixedGroupMarks.marked]) is no question at all: it is left out of both
 * the items and the [SamePersonQueue.open] count, and every other question
 * stays as it was.
 *
 * Reads every unnamed group's vectors; call it off the main thread. The
 * screen reads the same list through [SamePersonQueueCursor], which stops
 * as soon as it has the next few questions.
 */
fun samePersonQueue(
    state: PeopleClusterReviewState?,
    skipped: Set<String> = emptySet(),
    limit: Int = SAME_PERSON_QUEUE_LIMIT,
    samples: Int = SAME_PERSON_QUEUE_SAMPLES
): SamePersonQueue {
    require(limit in 1..10_000 && samples in 1..16) { "Invalid queue size" }
    if (state == null) return SamePersonQueue(emptyList(), 0)
    val cursor = SamePersonQueueCursor(state, samples = samples)
    while (!cursor.done) cursor.step()
    val all = cursor.items
    return SamePersonQueue(all.asSequence().filter { it.key !in skipped }.take(limit).toList(),
        all.size)
}

/**
 * #580 PERF: the [samePersonQueue] list of one [state], found in asking order
 * one step at a time. The list is ordered by the size of the unnamed group
 * asked about, and every question about one group is scored when that group
 * is reached, so the questions about the largest groups are known after a
 * few small steps instead of after scoring every pair of the library
 * (minutes on a phone with thousands of groups).
 *
 * While reading, every question that passes the engine's floors is listed.
 * Once [done], the list is cut exactly as [SamePersonHypotheses.of] and
 * [SamePersonHypotheses.forPeople] cut it (the strongest
 * [unnamedLimit] pairs of unnamed groups and [namedLimit] questions about
 * named people), so the
 * finished list is the one the whole-library pass gave.
 *
 * [step] reads the next run of equally sized groups and is all-or-nothing:
 * a step cancelled through its checkpoint leaves the cursor as it was.
 * Steps are serialized; [items] and [done] may be read from any thread.
 */
class SamePersonQueueCursor(
    val state: PeopleClusterReviewState,
    private val samples: Int = SAME_PERSON_QUEUE_SAMPLES,
    private val unnamedLimit: Int = SamePersonHypotheses.MAX_GROUPS,
    private val namedLimit: Int = SAME_PERSON_QUEUE_NAMED_LIMIT
) {
    init { require(samples in 1..16 && unnamedLimit > 0 && namedLimit > 0) { "Invalid queue size" } }

    private val clusters = state.clusters
    private val groups = clusters.unknownClusters
    /** HUB-1B (#211): groups marked "not one person" are asked about by no question. */
    private val mixed: Set<FaceClusterId> = MixedGroupMarks.marked(clusters)
    private var ready = false
    private var pairs: SamePersonHypotheses.PairIndex? = null
    private var people: SamePersonHypotheses.PeopleIndex? = null
    private var confirmed: Map<PersonKey, List<NativeFaceObservationId>> = emptyMap()
    private var byId: Map<FaceClusterId, UnknownFaceCluster> = emptyMap()
    @Volatile private var position = 0
    @Volatile private var found: List<SamePersonQueueItem> = emptyList()
    private val scored = ArrayList<Scored>()

    private class Scored(
        val item: SamePersonQueueItem,
        val strength: Float,
        val named: PersonHypothesis?,
        val unnamed: SamePersonHypothesis?
    )

    /** The questions found so far, in asking order. */
    val items: List<SamePersonQueueItem> get() = found

    /** True once every group has been read: [items] is then the whole list. */
    val done: Boolean get() = position >= groups.size

    private fun sample(faces: List<NativeFaceObservationId>): List<NativeFaceObservationId> {
        if (faces.size <= samples) return faces
        // Evenly spread, so the samples are not all from one burst of photos.
        return List(samples) { faces[(it.toLong() * faces.size / samples).toInt()] }
    }

    private fun prepare() {
        if (ready) return
        val asking = state.names.keys.filterTo(HashSet()) { it !in state.hidden }
        if (asking.isNotEmpty()) {
            people = SamePersonHypotheses.PeopleIndex.of(clusters, asking)
            confirmed = clusters.reviews.confirmed.entries.filter { it.key in clusters }
                .groupBy({ it.value }, { it.key }).mapValues { (_, v) -> v.sortedBy { it.localHex } }
        }
        pairs = SamePersonHypotheses.PairIndex.of(clusters)
        if (pairs != null) byId = groups.associateBy { it.id }
        ready = true
    }

    /** Reads the next run of equally sized groups; [checkpoint] may throw to cancel. */
    @Synchronized fun step(checkpoint: () -> Unit = {}) {
        if (done) return
        prepare()
        val bucket = ArrayList<Scored>()
        val named = ArrayList<PersonHypothesis>()
        val unnamed = ArrayList<SamePersonHypothesis>()
        var at = position
        val size = groups[at].members.size
        while (at < groups.size && groups[at].members.size == size) {
            checkpoint()
            val group = groups[at]
            if (group.id in mixed) { at++; continue }
            people?.let { index ->
                named.clear()
                index.forGroup(group, named)
                for (q in named) {
                    val own = confirmed[q.person] ?: continue
                    bucket += Scored(SamePersonQueueItem(PersonPageTarget.Named(q.person),
                        state.names[q.person], own.size, sample(own),
                        group.id, size, sample(group.members)), q.strength, q, null)
                }
            }
            pairs?.let { index ->
                if (at < index.size) {
                    unnamed.clear()
                    index.pairsWithSecond(at, unnamed)
                    for (q in unnamed) {
                        if (q.first in mixed) continue
                        val first = byId[q.first] ?: continue
                        bucket += Scored(SamePersonQueueItem(PersonPageTarget.Unnamed(q.first),
                            null, first.members.size, sample(first.members),
                            group.id, size, sample(group.members)), q.strength, null, q)
                    }
                }
            }
            at++
        }
        bucket.sortWith(compareByDescending<Scored> { it.strength }.thenBy { it.item.key })
        scored += bucket
        if (at >= groups.size) cut()
        found = scored.map { it.item }
        position = at
    }

    /** The whole-library pass's limits, applied with its own ordering. */
    private fun cut() {
        val unnamed = scored.mapNotNull { it.unnamed }
        val named = scored.mapNotNull { it.named }
        val keepUnnamed: Set<SamePersonHypothesis>? =
            if (unnamed.size <= unnamedLimit) null
            else unnamed.sortedWith(compareByDescending<SamePersonHypothesis> { it.strength }
                .thenByDescending { it.firstFaces + it.secondFaces }
                .thenBy { it.first.anchor.localHex }.thenBy { it.second.anchor.localHex })
                .take(unnamedLimit).toHashSet()
        val keepNamed: Set<PersonHypothesis>? =
            if (named.size <= namedLimit) null
            else named.sortedWith(compareByDescending<PersonHypothesis> { it.strength }
                .thenByDescending { it.groupFaces }
                .thenBy { it.person.value }.thenBy { it.group.anchor.localHex })
                .take(namedLimit).toHashSet()
        if (keepUnnamed == null && keepNamed == null) return
        scored.retainAll { entry ->
            entry.unnamed?.let { keepUnnamed == null || it in keepUnnamed }
                ?: entry.named?.let { keepNamed == null || it in keepNamed } ?: true
        }
    }
}

/**
 * HUB-1B (#211): "Not one person" for the [subject] side, the [other] side
 * or both sides of [item], as one review action. Null when no unnamed side
 * is chosen: a named person is never marked mixed.
 */
fun samePersonQueueMixed(
    item: SamePersonQueueItem, subject: Boolean, other: Boolean
): PeopleReviewAction.MarkMixedGroups? {
    val groups = LinkedHashSet<FaceClusterId>()
    val subjectSide = item.subject
    if (subject && subjectSide is PersonPageTarget.Unnamed) groups += subjectSide.cluster
    if (other) groups += item.other
    return if (groups.isEmpty()) null else PeopleReviewAction.MarkMixedGroups(groups)
}

/** The owner's answer to [item]: the same action its person page would send. */
fun samePersonQueueAnswer(item: SamePersonQueueItem, same: Boolean): PeopleReviewAction =
    samePersonAnswer(item.subject,
        SamePersonQuestion(item.other, item.otherFaces, item.other.anchor), same)

const val SAME_PERSON_QUEUE_LIMIT = 50
const val SAME_PERSON_QUEUE_SAMPLES = 4
/** Questions about named people kept in the finished list (the old forPeople limit). */
const val SAME_PERSON_QUEUE_NAMED_LIMIT = 10_000

/**
 * True while [live] still asks [item] exactly as it was shown: both sides
 * exist with the same number of faces and a named side is still named and
 * shown. The queue screen sends an answer only then, so an answer tapped
 * from an older list never acts on a group that changed meanwhile.
 */
fun samePersonQueueStillAsks(live: PeopleClusterReviewState, item: SamePersonQueueItem): Boolean {
    val sizes = live.clusters.unknownClusters.associate { it.id to it.members.size }
    if (sizes[item.other] != item.otherFaces) return false
    return when (val subject = item.subject) {
        is PersonPageTarget.Named -> subject.person in live.names && subject.person !in live.hidden
        is PersonPageTarget.Unnamed -> sizes[subject.cluster] == item.subjectFaces
    }
}
