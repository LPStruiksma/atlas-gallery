package nl.atlas.gallery.featurepeople.cluster

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepersistence.ClusterPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #580: grouping keeps unnamed faces that are a named person by the measured
 * rule in [FaceClusterState.matched] ([ClusterPolicy.personMatchFloor]).
 * Synthetic vectors only: faces sit tight around a person's direction.
 */
class FaceClustererPersonMatchTest {
    private val model = "m"
    private val dim = 64
    private val off = ClusterPolicy.SFACE_MEASURED.copy(metric = null, personMatchFloor = 0f)
    private val on = off.copy(personMatchFloor = 0.45f)
    private val ada = PersonKey("ada")
    private val bo = PersonKey("bo")
    private var nextId = 1
    private var nextFrame = 1L
    private var rng = Random(580)

    private fun gaussian(): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * rng.nextDouble())
    }
    private fun unit(): DoubleArray {
        val v = DoubleArray(dim) { gaussian() }
        val n = sqrt(v.sumOf { it * it })
        return DoubleArray(dim) { v[it] / n }
    }
    private fun mix(a: DoubleArray, s: Double): DoubleArray {
        val b = unit()
        val along = (0 until dim).sumOf { a[it] * b[it] }
        val o = DoubleArray(dim) { b[it] - along * a[it] }
        val n = sqrt(o.sumOf { it * it })
        return DoubleArray(dim) { s * a[it] + sqrt(1 - s * s) * o[it] / n }
    }
    private fun face(look: DoubleArray, frame: Long = nextFrame++, closeness: Double = 0.95): FaceEmbedding {
        val f = mix(look, closeness)
        return FaceEmbedding(NativeFaceObservationId("%032x".format(nextId++)), model,
            FloatArray(dim) { f[it].toFloat() }, 0f, frame)
    }
    private fun ids(faces: List<FaceEmbedding>) = faces.map { it.observation }.toSet()
    private fun named(vararg people: Pair<PersonKey, List<FaceEmbedding>>) =
        people.flatMap { (key, faces) -> faces.map { it.observation to key } }.toMap()

    /** Every face is confirmed, matched or in exactly one unknown cluster; matched equals the rule read from nothing. */
    private fun check(state: FaceClusterState) {
        val inClusters = state.unknownClusters.flatMap { it.members }
        assertEquals("a face in two clusters", inClusters.size, inClusters.toSet().size)
        val confirmed = state.reviews.confirmed.keys.filter { it in state }
        assertTrue(inClusters.none { it in state.matched || it in state.reviews.confirmed })
        assertTrue(state.matched.keys.none { it in state.reviews.confirmed })
        assertEquals(state.faceCount, inClusters.size + confirmed.size + state.matched.size)
        val floor = state.policy.personMatchFloor
        assertEquals(if (floor > 0f) FaceClusterer.personMatches(state, floor) else emptyMap(), state.matched)
        assertEquals(state.faceCount - confirmed.size, ClusterPublication.membership(state).size)
        for (m in state.matched.keys) assertNull(state.clusterOf(m))
    }

    @Test fun laterFacesOfANamedPersonAreMatchedAndLeaveTheUnknownGroups() {
        val a = unit()
        val known = List(4) { face(a) }
        val strangers = List(4) { face(unit()) }
        val start = FaceClusterer.update(FaceClusterState.empty(model, dim, on), add = known + strangers)
        assertTrue(start.matched.isEmpty())
        // The owner names the group; later photos arrive in two publications.
        val namedState = FaceClusterer.update(start, reviews = ClusterReviewFacts(confirmed = named(ada to known)))
        check(namedState)
        val later = List(3) { face(a) }
        val more = List(2) { face(a) } + face(unit())
        val one = FaceClusterer.update(namedState, add = later)
        check(one)
        assertEquals(ids(later), one.matched.keys)
        val two = FaceClusterer.update(one, add = more)
        check(two)
        assertEquals(ids(later + more.take(2)), two.matched.keys)
        assertTrue(two.matched.values.all { it.person == ada && it.mean >= 0.45f })
        // The unknown groups hold the strangers only, each alone.
        assertEquals(List(5) { 1 }, two.unknownClusters.map { it.members.size })
        // The same faces without the switch: the later faces form an unnamed group.
        val plain = FaceClusterer.update(FaceClusterer.update(
            FaceClusterState.empty(model, dim, off), add = known + strangers + later + more),
            reviews = ClusterReviewFacts(confirmed = named(ada to known)))
        assertTrue(plain.matched.isEmpty())
        assertEquals(5, plain.unknownClusters.first().members.size)
    }

    @Test fun aHumanNoPutsAMatchedFaceBackAmongTheUnnamed() {
        val a = unit()
        val known = List(4) { face(a) }
        val later = List(3) { face(a) }
        val confirmed = named(ada to known)
        val state = FaceClusterer.update(FaceClusterState.empty(model, dim, on), add = known + later,
            reviews = ClusterReviewFacts(confirmed = confirmed))
        assertEquals(ids(later), state.matched.keys)
        val no = FaceClusterer.update(state, reviews = ClusterReviewFacts(confirmed = confirmed,
            rejectedPeople = setOf(RejectedPerson(later[0].observation, ada))))
        check(no)
        assertEquals(ids(later.drop(1)), no.matched.keys)
        assertEquals(listOf(listOf(later[0].observation)), no.unknownClusters.map { it.members })
        // Taking the person below three examples ends every match.
        val few = FaceClusterer.update(no, reviews = ClusterReviewFacts(confirmed = named(ada to known.take(2))))
        check(few)
        assertTrue(few.matched.isEmpty())
    }

    @Test fun aBetterFaceOfTheSamePictureTakesTheMatchWhenItArrivesLater() {
        val a = unit()
        val known = List(4) { face(a) }
        val shared = nextFrame++
        val weak = face(a, frame = shared, closeness = 0.90)
        val state = FaceClusterer.update(FaceClusterState.empty(model, dim, on), add = known + weak,
            reviews = ClusterReviewFacts(confirmed = named(ada to known)))
        assertEquals(setOf(weak.observation), state.matched.keys)
        val strong = face(a, frame = shared, closeness = 0.98)
        val next = FaceClusterer.update(state, add = listOf(strong))
        check(next)
        assertEquals(setOf(strong.observation), next.matched.keys)
        assertEquals(listOf(listOf(weak.observation)), next.unknownClusters.map { it.members })
        // Removing the winner lets the other face match again.
        val back = FaceClusterer.update(next, remove = listOf(strong.observation))
        check(back)
        assertEquals(setOf(weak.observation), back.matched.keys)
    }

    @Test fun storedRowsRestoreToTheSameState() {
        val a = unit(); val b = unit()
        val adaFaces = List(4) { face(a) }
        val boFaces = List(3) { face(b) }
        val rest = List(3) { face(a) } + List(2) { face(b) } + List(6) { face(unit()) } + List(3) { face(unit()) }
        val state = FaceClusterer.update(FaceClusterState.empty(model, dim, on), add = adaFaces + boFaces + rest,
            reviews = ClusterReviewFacts(confirmed = named(ada to adaFaces, bo to boFaces)))
        check(state)
        assertEquals(5, state.matched.size)
        val rows = ClusterPublication.membership(state)
        for (m in state.matched.keys) assertEquals(m.localHex, rows[m.localHex])
        val groups = rows.entries.groupBy({ it.value }, { NativeFaceObservationId(it.key) }).values
        val restored = FaceClusterer.restore(model, on, adaFaces + boFaces + rest, groups, state.reviews)
        check(restored)
        assertEquals(state.matched, restored.matched)
        assertEquals(state.unknownClusters.map { it.members }, restored.unknownClusters.map { it.members })
        assertEquals(rows, ClusterPublication.membership(restored))
    }

    @Test fun theSwitchIsPartOfTheFingerprintOnlyWhenOn() {
        assertEquals(ClusterPolicy.SFACE_MEASURED.copy(personMatchFloor = 0f).fingerprint, off.copy(metric = FaceMetric.SFACE).fingerprint)
        assertFalse(";p=" in off.fingerprint)
        assertTrue(on.fingerprint.endsWith(";p=0.45"))
        assertEquals(off.fingerprint + ";p=0.45", on.fingerprint)
    }

    @Test fun anyHistoryOfScansAndAnswersKeepsMatchedEqualToTheRuleReadFromNothing() {
        var sawMatches = 0
        var sawPeople = 0
        for (seed in 1..12) {
            rng = Random(seed); nextId = 1; nextFrame = 1L
            val looks = List(5) { unit() }.toMutableList()
            looks += mix(looks[0], 0.6) // a relative of the first person
            val people = listOf(ada, bo, PersonKey("cy"))
            var state = FaceClusterState.empty(model, dim, on)
            val present = ArrayList<FaceEmbedding>()
            var peak = 0
            repeat(60) { step ->
                when (rng.nextInt(6)) {
                    0, 1, 2 -> { // a publication: a few faces, sometimes two of one picture
                        val batch = ArrayList<FaceEmbedding>()
                        repeat(1 + rng.nextInt(6)) {
                            val look = if (rng.nextInt(4) == 0) unit() else looks[rng.nextInt(looks.size)]
                            val frame = if (batch.isNotEmpty() && rng.nextInt(4) == 0) batch.last().frame
                                else if (present.isNotEmpty() && rng.nextInt(6) == 0) present[rng.nextInt(present.size)].frame
                                else nextFrame++
                            batch += face(look, frame, closeness = 0.75 + 0.23 * rng.nextDouble())
                        }
                        present += batch
                        state = FaceClusterer.update(state, add = batch)
                    }
                    3 -> if (present.isNotEmpty()) { // the owner confirms some faces as a person, or takes some out
                        val confirmed = state.reviews.confirmed.toMutableMap()
                        val rejected = state.reviews.rejectedPeople.toMutableSet()
                        val group = state.unknownClusters.firstOrNull { it.members.size >= 3 }
                        if (group != null && rng.nextBoolean()) {
                            // Naming: a whole unnamed group becomes (part of) a person.
                            val p = people[rng.nextInt(people.size)]
                            for (f in group.members) { confirmed[f] = p; rejected -= RejectedPerson(f, p) }
                        } else repeat(1 + rng.nextInt(5)) {
                            val f = present[rng.nextInt(present.size)].observation
                            if (rng.nextInt(4) == 0) confirmed.remove(f)?.let { rejected += RejectedPerson(f, it) }
                            else { val p = people[rng.nextInt(people.size)]; confirmed[f] = p; rejected -= RejectedPerson(f, p) }
                        }
                        state = FaceClusterer.update(state, reviews = state.reviews.copy(
                            confirmed = confirmed, rejectedPeople = rejected))
                    }
                    4 -> if (state.matched.isNotEmpty() || present.isNotEmpty()) { // "not this person" or "not the same person"
                        val f = (state.matched.keys.toList().ifEmpty { present.map { it.observation } }).let { it[rng.nextInt(it.size)] }
                        val rejected = state.reviews.rejectedPeople.toMutableSet()
                        val cannot = state.reviews.cannotLink.toMutableSet()
                        val owner = state.matched[f]?.person ?: people[rng.nextInt(people.size)]
                        if (rng.nextBoolean()) rejected += RejectedPerson(f, owner)
                        else state.reviews.confirmed.entries.firstOrNull { it.value == owner && it.key != f }
                            ?.let { cannot += FacePair.of(f, it.key) }
                        state = FaceClusterer.update(state, reviews = state.reviews.copy(
                            rejectedPeople = rejected, cannotLink = cannot))
                    }
                    else -> if (present.size > 3) { // photos deleted
                        val gone = List(1 + rng.nextInt(3)) { present[rng.nextInt(present.size)] }.toSet()
                        present.removeAll(gone)
                        val left = gone.map { it.observation }.toSet()
                        // The store drops the facts of a face that is gone.
                        val r = state.reviews
                        state = FaceClusterer.update(state, remove = left, reviews = if (rng.nextBoolean()) r else r.copy(
                            confirmed = r.confirmed - left))
                    }
                }
                peak = maxOf(peak, state.matched.size)
                try { check(state) } catch (e: AssertionError) {
                    throw AssertionError("seed $seed step $step: ${e.message}", e)
                }
            }
            sawMatches += peak
            sawPeople += state.reviews.confirmed.values.groupingBy { it }.eachCount().count { it.value >= 3 }
        }
        // The histories must exercise the rule, not pass on empty results.
        assertTrue("matches seen: $sawMatches", sawMatches >= 30)
        assertTrue("people with examples at the end: $sawPeople", sawPeople >= 12)
    }
}
