package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster
import nl.atlas.gallery.featurepeople.identity.SamePersonHypotheses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * #580 PERF: the "Same person?" list read one step at a time. Synthetic
 * vectors only (a shared direction, as the shipped model's vectors have),
 * so many pairs pass the floors and the whole-library limits matter.
 */
class SamePersonQueueCursorTest {
    private val dim = 32
    private val rng = Random(119)
    private var nextId = 1
    private val faces = HashMap<NativeFaceObservationId, FaceEmbedding>()
    private val common = FloatArray(dim) { g() }

    private fun g() = (rng.nextDouble() * 2 - 1).toFloat()
    private fun look(): FloatArray {
        val own = FloatArray(dim) { g() }
        return FloatArray(dim) { 0.55f * common[it] + own[it] }
    }
    private fun face(look: FloatArray): NativeFaceObservationId {
        val id = NativeFaceObservationId("%032x".format(nextId))
        faces[id] = FaceEmbedding(id, "m", FloatArray(dim) { look[it] + 0.35f * g() }, 0f,
            nextId++.toLong())
        return id
    }
    private fun group(look: FloatArray, size: Int): UnknownFaceCluster {
        val members = List(size) { face(look) }.sortedBy { it.localHex }
        return UnknownFaceCluster(FaceClusterId(members.first()), members, null)
    }

    /** Six named people, 70 unnamed groups (several looks of the people), pairs and singles. */
    private fun state(hidden: Set<PersonKey> = emptySet()): PeopleClusterReviewState {
        val confirmed = HashMap<NativeFaceObservationId, PersonKey>()
        val names = HashMap<PersonKey, String>()
        val looks = List(6) { look() }
        for ((p, l) in looks.withIndex()) {
            val key = PersonKey("native-p$p")
            names[key] = "Person $p"
            repeat(12) { confirmed[face(l)] = key }
        }
        val groups = ArrayList<UnknownFaceCluster>()
        for (i in 0 until 70) {
            val size = maxOf(3, 40 / (i + 1) + 3 - i % 2)
            val l = if (i % 4 == 0) looks[i % 6].let { b -> FloatArray(dim) { b[it] + 0.3f * g() } }
                else look()
            groups += group(l, size)
        }
        repeat(30) { groups += group(look(), 2) }
        repeat(40) { groups += group(look(), 1) }
        val sorted = groups.sortedWith(compareByDescending<UnknownFaceCluster> { it.members.size }
            .thenBy { it.id.anchor.localHex })
        val clusters = FaceClusterState("m", dim, ClusterPolicy.SFACE_MEASURED.copy(metric = null),
            faces, sorted, ClusterReviewFacts(confirmed = confirmed))
        return PeopleClusterReviewState("epoch", "generation", clusters, names, hidden = hidden)
    }

    /** The whole-library pass as it was before the cursor (#580 queue, PR #646). */
    private fun reference(state: PeopleClusterReviewState, unnamedLimit: Int, namedLimit: Int):
        List<SamePersonQueueItem> {
        val clusters = state.clusters
        val members = clusters.unknownClusters.associate { it.id to it.members }
        fun sample(f: List<NativeFaceObservationId>) = if (f.size <= 4) f
            else List(4) { f[(it.toLong() * f.size / 4).toInt()] }
        val all = ArrayList<Pair<SamePersonQueueItem, Float>>()
        val asking = state.names.keys.filterTo(HashSet()) { it !in state.hidden }
        if (asking.isNotEmpty()) {
            val confirmed = clusters.reviews.confirmed.entries.filter { it.key in clusters }
                .groupBy({ it.value }, { it.key })
            for (q in SamePersonHypotheses.forPeople(clusters, asking, limit = namedLimit)) {
                val own = confirmed[q.person]?.sortedBy { it.localHex } ?: continue
                val group = members[q.group] ?: continue
                all += SamePersonQueueItem(PersonPageTarget.Named(q.person), state.names[q.person],
                    own.size, sample(own), q.group, group.size, sample(group)) to q.strength
            }
        }
        for (q in SamePersonHypotheses.of(clusters, limit = unnamedLimit)) {
            val first = members[q.first] ?: continue
            val second = members[q.second] ?: continue
            all += SamePersonQueueItem(PersonPageTarget.Unnamed(q.first), null, first.size,
                sample(first), q.second, second.size, sample(second)) to q.strength
        }
        return all.sortedWith(compareByDescending<Pair<SamePersonQueueItem, Float>> { it.first.otherFaces }
            .thenByDescending { it.second }.thenBy { it.first.key }).map { it.first }
    }

    private fun SamePersonQueueCursor.readAll(): SamePersonQueueCursor {
        while (!done) step()
        return this
    }

    @Test fun theFinishedListIsTheWholeLibraryPassAndEarlyStepsAreItsPrefix() {
        val s = state()
        val expected = reference(s, SamePersonHypotheses.MAX_GROUPS, SAME_PERSON_QUEUE_NAMED_LIMIT)
        assertTrue("enough questions to matter, found ${expected.size}", expected.size > 40)
        assertTrue(expected.any { it.subject is PersonPageTarget.Named })
        assertTrue(expected.any { it.subject is PersonPageTarget.Unnamed })
        val cursor = SamePersonQueueCursor(s)
        var steps = 0
        while (cursor.items.isEmpty()) { cursor.step(); steps++ }
        // The first question is known after a few steps, and it is the first one asked.
        assertTrue("first question after $steps steps", steps <= 5)
        assertEquals(expected.first(), cursor.items.first())
        while (!cursor.done) {
            assertEquals(expected.subList(0, cursor.items.size), cursor.items)
            cursor.step()
        }
        assertEquals(expected, cursor.items)
        assertEquals(SamePersonQueue(expected.take(SAME_PERSON_QUEUE_LIMIT), expected.size),
            samePersonQueue(s))
    }

    @Test fun theFinishedListKeepsTheWholeLibraryLimits() {
        val s = state()
        val limited = SamePersonQueueCursor(s, unnamedLimit = 7, namedLimit = 5).readAll().items
        assertEquals(reference(s, unnamedLimit = 7, namedLimit = 5), limited)
        assertEquals(7, limited.count { it.subject is PersonPageTarget.Unnamed })
        assertEquals(5, limited.count { it.subject is PersonPageTarget.Named })
    }

    @Test fun hiddenPeopleAskNothing() {
        val all = state()
        val first = all.names.keys.first()
        val s = all.copy(hidden = setOf(first))
        val items = SamePersonQueueCursor(s).readAll().items
        assertTrue(items.none { (it.subject as? PersonPageTarget.Named)?.person == first })
        assertEquals(reference(s, SamePersonHypotheses.MAX_GROUPS, SAME_PERSON_QUEUE_NAMED_LIMIT), items)
    }

    @Test fun aCancelledStepLeavesTheCursorAsItWas() {
        val s = state()
        val cursor = SamePersonQueueCursor(s)
        cursor.step()
        val before = cursor.items
        // Cancel inside a step that reads several equally sized groups.
        while (!cursor.done) {
            var calls = 0
            val partial = try {
                cursor.step { if (++calls == 2) throw IllegalStateException("cancelled") }
                false
            } catch (_: IllegalStateException) { true }
            if (partial) break
        }
        assertFalse(cursor.done)
        assertTrue(cursor.items.size >= before.size)
        val kept = cursor.items
        cursor.readAll()
        val fresh = SamePersonQueueCursor(s).readAll()
        assertEquals(fresh.items, cursor.items)
        assertEquals(kept, cursor.items.subList(0, kept.size))
    }

    @Test fun aQuestionIsStillAskedOnlyWhileBothSidesAreUnchanged() {
        val s = state()
        val items = SamePersonQueueCursor(s).readAll().items
        val unnamed = items.first { it.subject is PersonPageTarget.Unnamed }
        val named = items.first { it.subject is PersonPageTarget.Named }
        assertTrue(samePersonQueueStillAsks(s, unnamed))
        assertTrue(samePersonQueueStillAsks(s, named))
        // A group that grew or shrank is a different question now.
        assertFalse(samePersonQueueStillAsks(s, unnamed.copy(otherFaces = unnamed.otherFaces + 1)))
        assertFalse(samePersonQueueStillAsks(s, unnamed.copy(subjectFaces = unnamed.subjectFaces - 1)))
        // A hidden or unnamed person asks nothing.
        val person = (named.subject as PersonPageTarget.Named).person
        assertFalse(samePersonQueueStillAsks(s.copy(hidden = setOf(person)), named))
        assertFalse(samePersonQueueStillAsks(s.copy(names = s.names - person), named))
    }
}
