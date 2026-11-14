package nl.atlas.gallery.featurepeople.split

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * SPLIT-0 (#105) on synthetic identities only: random 128-d unit identities
 * (two of them score about 0 like centred SFace strangers), each face its
 * identity plus pose and noise. No real face vector is involved.
 */
class SplitProposerTest {
    private val model = "synthetic-128"
    private var nextId = 1

    private fun id() = NativeFaceObservationId("%032x".format(nextId++))

    private fun Random.gaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12); val v = nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * Math.PI * v)
    }

    private fun unit(rng: Random): DoubleArray {
        val v = DoubleArray(DIM) { rng.gaussian() }
        val n = sqrt(v.sumOf { it * it })
        return DoubleArray(DIM) { v[it] / n }
    }

    /** Unit vector at cosine [c] to unit [a], in a random direction. */
    private fun near(a: DoubleArray, c: Double, rng: Random): DoubleArray {
        val r = unit(rng)
        val proj = r.indices.sumOf { r[it] * a[it] }
        val o = DoubleArray(DIM) { r[it] - proj * a[it] }
        val on = sqrt(o.sumOf { it * it })
        val s = sqrt(1 - c * c)
        return DoubleArray(DIM) { c * a[it] + s * o[it] / on }
    }

    private class Person(val identity: DoubleArray, val poses: List<DoubleArray>)

    private fun person(rng: Random, poses: Int = 3, identity: DoubleArray = unit(rng)) =
        Person(identity, List(poses) { near(identity, 0.72, rng) })

    /** A face of [p]: a pose of the person, then per-face noise. */
    private fun face(p: Person, rng: Random, lo: Double = 0.55, hi: Double = 0.85): FloatArray {
        val pose = p.poses[rng.nextInt(p.poses.size)]
        val v = near(pose, lo + rng.nextDouble() * (hi - lo), rng)
        return FloatArray(DIM) { v[it].toFloat() }
    }

    private data class Labelled(val face: SplitFace, val truth: Int)

    private fun profile(
        rng: Random, people: List<Person>, counts: List<Int>, confirmed: (Int, Int) -> PersonKey? = { _, _ -> null }
    ): List<Labelled> {
        val out = ArrayList<Labelled>()
        var t = 1_700_000_000_000L
        people.forEachIndexed { who, p ->
            repeat(counts[who]) { j ->
                t += 3_600_000L * (1 + rng.nextInt(48))
                out += Labelled(SplitFace(FaceEmbedding(id(), model, face(p, rng)), confirmed(who, j), t), who)
            }
        }
        return out.shuffled(rng)
    }

    private fun propose(faces: List<Labelled>, facts: ClusterReviewFacts = ClusterReviewFacts.NONE) =
        SplitProposer.propose(SplitRequest(faces.map { it.face }, facts, crowdWeight = 1f))

    /** Share of placed faces that sit in their group's majority identity. */
    private fun purity(p: SplitProposal, faces: List<Labelled>): Double {
        val truth = faces.associate { it.face.embedding.observation to it.truth }
        var right = 0; var all = 0
        for (g in p.groups) {
            val counts = g.members.groupingBy { truth[it]!! }.eachCount()
            right += counts.values.max(); all += g.members.size
        }
        return if (all == 0) 1.0 else right.toDouble() / all
    }

    @Test fun twoMergedPeopleSplitIntoTwo() {
        val rng = Random(1)
        val faces = profile(rng, listOf(person(rng), person(rng)), listOf(300, 200))
        val p = propose(faces)
        assertEquals(2, p.k)
        assertTrue(p.isSplit)
        assertTrue("purity ${purity(p, faces)}", purity(p, faces) >= 0.99)
        assertTrue("unsure ${p.unsure.size}", p.unsure.size <= faces.size / 20)
        assertTrue(p.groups[0].members.size in 280..300 && p.groups[1].members.size in 185..200)
        assertTrue(p.separation < SplitProposer.JOIN_FLOOR)
    }

    @Test fun threeMergedPeopleSplitIntoThree() {
        val rng = Random(2)
        val faces = profile(rng, List(3) { person(rng) }, listOf(220, 140, 60))
        val p = propose(faces)
        assertEquals(3, p.k)
        assertTrue(purity(p, faces) >= 0.99)
    }

    @Test fun cleanProfileWithManyPosesIsNoSplit() {
        for (seed in 10..14) {
            val rng = Random(seed)
            val faces = profile(rng, listOf(person(rng, poses = 6)), listOf(500))
            val p = propose(faces)
            assertEquals("seed $seed", 1, p.k)
            assertFalse(p.isSplit)
            assertTrue("seed $seed unsure ${p.unsure.size}", p.unsure.size <= 10)
        }
    }

    @Test fun strangersInACleanProfileGoToUnsure() {
        val rng = Random(3)
        val clean = profile(rng, listOf(person(rng)), listOf(300))
        val strangers = List(5) { Labelled(SplitFace(FaceEmbedding(id(), model, face(person(rng), rng))), 1) }
        val p = propose(clean + strangers)
        assertEquals(1, p.k)
        val unsure = p.unsure.map { it.observation }.toSet()
        for (s in strangers) assertTrue(s.face.embedding.observation in unsure)
        assertTrue(p.unsure.filter { it.observation in strangers.map { s -> s.face.embedding.observation } }
            .all { it.reason == UnsureReason.OUTLIER })
    }

    @Test fun lookAlikesAreNeverSplitWrongly() {
        for (seed in 20..24) {
            val rng = Random(seed)
            val a = person(rng)
            val b = person(rng, identity = near(a.identity, 0.45, rng))
            val faces = profile(rng, listOf(a, b), listOf(250, 250))
            val p = propose(faces)
            assertTrue("seed $seed purity ${purity(p, faces)} k=${p.k}", p.k == 1 || purity(p, faces) >= 0.97)
        }
    }

    @Test fun cannotLinkAndSharedPicturesNeverShareAGroup() {
        val rng = Random(4)
        val a = person(rng)
        val b = person(rng, identity = near(a.identity, 0.75, rng)) // so close they would stay one group
        val faces = profile(rng, listOf(a, b), listOf(150, 150)).toMutableList()
        val aFaces = faces.filter { it.truth == 0 }; val bFaces = faces.filter { it.truth == 1 }
        val pairs = (0 until 10).map { FacePair.of(aFaces[it].face.embedding.observation, bFaces[it].face.embedding.observation) }.toSet()
        // Two more faces in one picture.
        val x = SplitFace(FaceEmbedding(id(), model, face(a, rng)), media = 42L)
        val y = SplitFace(FaceEmbedding(id(), model, face(a, rng)), media = 42L)
        faces += Labelled(x, 0); faces += Labelled(y, 0)
        val p = propose(faces, ClusterReviewFacts(cannotLink = pairs))
        for (g in p.groups) {
            val set = g.members.toSet()
            for (pair in pairs) assertFalse(pair.first in set && pair.second in set)
            assertFalse(x.embedding.observation in set && y.embedding.observation in set)
        }
    }

    @Test fun confirmedNameStaysWithItsPersonAndIsNeverTransferred() {
        val rng = Random(5)
        val name = PersonKey("person-a")
        val faces = profile(rng, listOf(person(rng), person(rng)), listOf(300, 200)) { who, j ->
            if (who == 0 && j < 120) name else null
        }
        val p = propose(faces)
        assertEquals(2, p.k)
        val truth = faces.associate { it.face.embedding.observation to it.truth }
        for (g in p.groups) {
            val majority = g.members.groupingBy { truth[it]!! }.eachCount().maxBy { it.value }.key
            if (majority == 0) { assertEquals(name, g.keepsName); assertEquals(120, g.confirmedCount) }
            else { assertNull(g.keepsName); assertEquals(0, g.confirmedCount) }
        }
    }

    @Test fun nameSpreadOverTwoGroupsStaysWithNeither() {
        val rng = Random(6)
        val name = PersonKey("person-a")
        // The owner confirmed faces of both people under one name.
        val faces = profile(rng, listOf(person(rng), person(rng)), listOf(250, 250)) { _, j ->
            if (j < 100) name else null
        }
        val p = propose(faces)
        assertEquals(2, p.k)
        assertTrue(p.groups.all { it.keepsName == null })
        assertTrue(p.groups.all { it.confirmedPeople[name] in 90..110 })
    }

    @Test fun faceRejectedForTheKeptNameGoesToUnsure() {
        val rng = Random(7)
        val name = PersonKey("person-a")
        val faces = profile(rng, listOf(person(rng), person(rng)), listOf(300, 200)) { who, j ->
            if (who == 0 && j < 150) name else null
        }
        val rejected = faces.first { it.truth == 0 && it.face.confirmedAs == null }.face.embedding.observation
        val p = propose(faces, ClusterReviewFacts(rejectedPeople = setOf(RejectedPerson(rejected, name))))
        assertTrue(p.groups.none { rejected in it.members })
        assertEquals(UnsureReason.REJECTED_FOR_NAME, p.unsure.single { it.observation == rejected }.reason)
    }

    @Test fun resultDoesNotDependOnInputOrder() {
        val rng = Random(8)
        val faces = profile(rng, List(3) { person(rng) }, listOf(200, 120, 40))
        val a = propose(faces)
        val b = propose(faces.shuffled(Random(99)))
        assertEquals(a, b)
    }

    @Test fun groupsCarryDiverseExemplarsAndConfidences() {
        val rng = Random(9)
        val faces = profile(rng, listOf(person(rng), person(rng)), listOf(200, 100))
        val p = propose(faces)
        for (g in p.groups) {
            assertEquals(SplitProposer.EXEMPLARS, g.exemplars.size)
            assertEquals(g.exemplars.size, g.exemplars.toSet().size)
            assertTrue(g.members.containsAll(g.exemplars))
            assertEquals(g.members.toSet(), g.confidence.keys)
            assertTrue(g.confidence.values.all { it in 0f..1f })
            assertTrue(g.members == g.members.sortedBy { it.localHex })
        }
        assertTrue(p.groups[0].members.size >= p.groups[1].members.size)
    }

    @Test fun emptyAndTinyInputsAreSafe() {
        assertEquals(0, SplitProposer.propose(SplitRequest(emptyList())).k)
        val rng = Random(10)
        val two = profile(rng, listOf(person(rng)), listOf(2))
        val p = propose(two)
        assertTrue(p.k <= 1)
    }

    /**
     * Aggregate numbers over many synthetic profiles (the licensed-face bench
     * is in tools/people_bench). Gates are loose; the printed line is the record.
     */
    @Test fun syntheticBenchAggregates() {
        var kRight = 0; var runs = 0; var falseSplits = 0; var controls = 0
        var puritySum = 0.0; var unsure = 0; var faceCount = 0
        for (seed in 100 until 130) {
            val rng = Random(seed)
            val people = seed % 3 // 0 = clean control, 1 = two merged, 2 = three merged
            val faces = if (people == 0) profile(rng, listOf(person(rng, poses = 5)), listOf(150 + rng.nextInt(400)))
            else profile(rng, List(people + 1) { person(rng) }, List(people + 1) { 40 + rng.nextInt(300) })
            val p = propose(faces)
            runs++
            if (people == 0) { controls++; if (p.isSplit) falseSplits++ }
            if (p.k == people + 1) kRight++
            puritySum += purity(p, faces); unsure += p.unsure.size; faceCount += faces.size
        }
        println("SPLIT_SYNTH runs=$runs k_accuracy=${"%.3f".format(kRight.toDouble() / runs)} " +
            "purity=${"%.4f".format(puritySum / runs)} unsure_share=${"%.4f".format(unsure.toDouble() / faceCount)} " +
            "false_split=$falseSplits/$controls")
        assertEquals(0, falseSplits)
        assertTrue(kRight >= runs * 0.9)
    }

    /** JVM timing at the issue's bound: 5,000 faces of three people. */
    @Test fun fiveThousandFacesStayBounded() {
        val rng = Random(11)
        val faces = profile(rng, List(3) { person(rng) }, listOf(2500, 1700, 800))
        propose(faces.take(600)) // warm up
        val t0 = System.nanoTime()
        val p = propose(faces)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("SPLIT_BENCH faces=${faces.size} k=${p.k} ms=$ms purity=${"%.4f".format(purity(p, faces))}")
        assertEquals(3, p.k)
        assertTrue("took $ms ms", ms < 10_000)
    }

    private companion object { const val DIM = 128 }
}
