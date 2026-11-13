package nl.atlas.gallery.featureface.search

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** #571 on synthetic vectors only; no real photos or faces. */
class PersonMatchSuggesterTest {
    private val model = SFaceModel.EMBEDDER_VERSION
    private val dim = SFaceModel.DIMENSION
    private val rng = Random(571)
    private val alice = PersonKey("person-alice")
    private val bob = PersonKey("person-bob")

    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
    private fun cluster(n: Int) = FaceClusterId(id(n))

    private fun gaussian(): Float {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }
    private fun unit(v: FloatArray) = SFaceModel.l2Normalize(v)
    private fun random() = unit(FloatArray(dim) { gaussian() })
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    /** A unit vector whose cosine to unit [base] is exactly [c]. */
    private fun at(base: FloatArray, c: Float): FloatArray {
        val r = random()
        val p = dot(r, base)
        val orth = unit(FloatArray(dim) { r[it] - p * base[it] })
        val s = sqrt(1f - c * c)
        return unit(FloatArray(dim) { c * base[it] + s * orth[it] })
    }

    private class Row(val face: Int, val vector: FloatArray, val anchor: Int? = null,
                      val person: PersonKey? = null)

    private fun inputs(rows: List<Row>, facts: ClusterReviewFacts = ClusterReviewFacts.NONE) =
        PersonMatchInputs.Builder("people-review:7", model, facts).apply {
            for (r in rows) add(id(r.face), r.vector, r.anchor?.let { id(it) }, r.person)
        }.build()

    /** Five confirmed faces of [person] around [identity], uids from [first]. */
    private fun confirmed(person: PersonKey, identity: FloatArray, first: Int) =
        (0 until 5).map { Row(first + it, at(identity, 0.97f), person = person) }

    private fun PersonMatchSuggestions.of(p: PersonKey) = byPerson[p].orEmpty()
    private fun PersonMatchSuggestions.clustersOf(p: PersonKey) = of(p).map { it.cluster }

    @Test fun ranksClustersByMatchAndTiersThem() {
        val a = random()
        val rows = confirmed(alice, a, 1000) + listOf(
            Row(1, at(a, 0.75f), anchor = 1),   // same person
            Row(2, at(a, 0.43f), anchor = 2),   // probable
            Row(3, at(a, 0.31f), anchor = 3),   // past the centroid gate, under PROBABLE
            Row(4, random(), anchor = 4),       // unrelated
            Row(5, at(a, 0.62f), anchor = 5))   // same person, weaker than 1
        val out = PersonMatchSuggester.suggest(inputs(rows))
        assertEquals(listOf(cluster(1), cluster(5), cluster(2)), out.clustersOf(alice))
        val tiers = out.of(alice).map { it.tier }
        assertEquals(listOf(PersonMatchTier.SAME_PERSON, PersonMatchTier.SAME_PERSON,
            PersonMatchTier.PROBABLE), tiers)
        assertTrue(out.of(alice).zipWithNext().all { (x, y) -> x.score >= y.score })
        for (s in out.of(alice)) {
            assertEquals((s.centroidSimilarity + s.topKMean) / 2f, s.score, 1e-6f)
            assertFalse(s.ambiguous)
        }
        assertEquals("people-review:7", out.generation)
        assertFalse("bands are not calibrated yet", out.bandsCalibrated)
        assertTrue(out.truncated.isEmpty())
    }

    @Test fun multiFaceClusterUsesItsCentroid() {
        val a = random()
        // Each member alone is only probable; together their centroid is closer.
        val rows = confirmed(alice, a, 1000) +
            (1..6).map { Row(it, at(a, 0.45f), anchor = 1) }
        val s = PersonMatchSuggester.suggest(inputs(rows)).of(alice).single()
        assertEquals(6, s.faceCount)
        assertEquals(PersonMatchTier.SAME_PERSON, s.tier)
    }

    @Test fun rejectedPairNeverComesBack() {
        val a = random()
        val rows = confirmed(alice, a, 1000) + listOf(
            Row(1, at(a, 0.8f), anchor = 1), Row(2, at(a, 0.8f), anchor = 1),
            Row(3, at(a, 0.7f), anchor = 3))
        val facts = ClusterReviewFacts(
            confirmed = rows.filter { it.person != null }.associate { id(it.face) to it.person!! },
            rejectedPeople = setOf(RejectedPerson(id(2), alice)))
        val out = PersonMatchSuggester.suggest(inputs(rows, facts))
        assertEquals("one rejected member vetoes its whole cluster for that person",
            listOf(cluster(3)), out.clustersOf(alice))
    }

    @Test fun rejectionForSomeoneElseDoesNotVeto() {
        val a = random()
        val rows = confirmed(alice, a, 1000) + Row(1, at(a, 0.8f), anchor = 1)
        val facts = ClusterReviewFacts(rejectedPeople = setOf(RejectedPerson(id(1), bob)))
        assertEquals(listOf(cluster(1)), PersonMatchSuggester.suggest(inputs(rows, facts)).clustersOf(alice))
    }

    @Test fun cannotLinkToAConfirmedFaceVetoes() {
        val a = random()
        val b = at(a, 0.2f)
        val rows = confirmed(alice, a, 1000) + confirmed(bob, b, 2000) + listOf(
            Row(1, at(a, 0.8f), anchor = 1), Row(2, at(a, 0.8f), anchor = 1),
            Row(3, at(a, 0.7f), anchor = 3))
        // Member 2 was split from one of alice's confirmed faces; member 3 from one of bob's.
        val facts = ClusterReviewFacts(cannotLink = setOf(
            FacePair.of(id(2), id(1003)), FacePair.of(id(3), id(2001))))
        val out = PersonMatchSuggester.suggest(inputs(rows, facts))
        assertEquals(listOf(cluster(3)), out.clustersOf(alice))
        assertTrue(cluster(3) !in out.clustersOf(bob))
    }

    @Test fun cannotLinkBetweenUnknownFacesDoesNotVeto() {
        val a = random()
        val rows = confirmed(alice, a, 1000) + listOf(
            Row(1, at(a, 0.8f), anchor = 1), Row(2, random(), anchor = 2))
        val facts = ClusterReviewFacts(cannotLink = setOf(FacePair.of(id(1), id(2))))
        assertEquals(listOf(cluster(1)), PersonMatchSuggester.suggest(inputs(rows, facts)).clustersOf(alice))
    }

    @Test fun closePeopleMarkSharedClustersAmbiguous() {
        val a = random()
        val b = at(a, 0.9f)
        val rows = confirmed(alice, a, 1000) + confirmed(bob, b, 2000) +
            Row(1, at(a, 0.7f), anchor = 1)
        val out = PersonMatchSuggester.suggest(inputs(rows))
        val forAlice = out.of(alice).single()
        val forBob = out.of(bob).single()
        if (kotlin.math.abs(forAlice.score - forBob.score) <= FaceQueryBands.PERSON_MARGIN) {
            assertTrue(forAlice.ambiguous && forBob.ambiguous)
        }
        // A clearly separate cluster is never ambiguous.
        val c = at(random(), 0f)
        val far = confirmed(alice, a, 1000) + confirmed(bob, c, 2000) + Row(1, at(a, 0.8f), anchor = 1)
        assertFalse(PersonMatchSuggester.suggest(inputs(far)).of(alice).single().ambiguous)
    }

    @Test fun boundedPerPersonAndFlagsTruncation() {
        val a = random()
        val rows = confirmed(alice, a, 10_000) + (1..80).map { Row(it, at(a, 0.6f), anchor = it) }
        val out = PersonMatchSuggester.suggest(inputs(rows))
        assertEquals(PersonMatchSuggester.MAX_PER_PERSON, out.of(alice).size)
        assertEquals(setOf(alice), out.truncated)
        val small = PersonMatchSuggester.suggest(inputs(rows), perPersonLimit = 3)
        assertEquals(out.of(alice).take(3), small.of(alice))
        assertThrows(IllegalArgumentException::class.java) {
            PersonMatchSuggester.suggest(inputs(rows), perPersonLimit = 51)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonMatchSuggester.suggest(inputs(rows), perPersonLimit = 0)
        }
    }

    @Test fun orderIsStableAndIndependentOfInputOrder() {
        val a = random()
        val v = at(a, 0.6f)
        val rows = confirmed(alice, a, 1000) + listOf(
            // Identical vectors: equal scores, so size then anchor decide.
            Row(9, v.copyOf(), anchor = 9),
            Row(4, v.copyOf(), anchor = 4),
            Row(7, v.copyOf(), anchor = 7), Row(8, v.copyOf(), anchor = 7)) +
            (20..40).map { Row(it, at(a, 0.4f + 0.01f * (it - 20)), anchor = it) }
        val first = PersonMatchSuggester.suggest(inputs(rows))
        val tied = first.clustersOf(alice).filter { it in setOf(cluster(4), cluster(7), cluster(9)) }
        assertEquals(listOf(cluster(7), cluster(4), cluster(9)), tied)
        repeat(5) {
            val again = PersonMatchSuggester.suggest(inputs(rows.shuffled(Random(it))))
            assertEquals(first.of(alice), again.of(alice))
        }
    }

    @Test fun confirmedFaceNeverCountsAsAnUnknownMember() {
        val a = random()
        val rows = confirmed(alice, a, 1000)
        val built = PersonMatchInputs.Builder("people-review:1", model, ClusterReviewFacts.NONE).apply {
            for (r in rows) add(id(r.face), r.vector, id(1), r.person)
            add(id(1), at(a, 0.8f), id(1), null)
        }.build()
        assertEquals(1, built.clusters.getValue(cluster(1)).size)
        assertEquals(5, built.confirmed.getValue(alice).size)
        assertEquals(6, built.faceCount)
        assertThrows(IllegalArgumentException::class.java) {
            PersonMatchInputs.Builder("g", model, ClusterReviewFacts.NONE)
                .add(id(1), random(), id(1), null).add(id(1), random(), id(1), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonMatchInputs.Builder("g", model, ClusterReviewFacts.NONE).add(id(1), random(), null, null)
        }
    }

    @Test fun nothingToSuggestWithoutPeopleOrClusters() {
        val a = random()
        assertTrue(PersonMatchSuggester.suggest(inputs(confirmed(alice, a, 1000))).byPerson.isEmpty())
        assertTrue(PersonMatchSuggester.suggest(inputs(listOf(Row(1, a, anchor = 1)))).byPerson.isEmpty())
        assertNull(PersonMatchSuggester.suggest(inputs(confirmed(alice, a, 1000) +
            Row(1, random(), anchor = 1))).byPerson[alice])
    }

    /**
     * Synthetic 100k faces: 40 people with 1,000 confirmed faces each, 40 x 25
     * planted 3-face clusters near them, and ~57k unrelated faces in 1-3 face
     * clusters. Reports build and suggest wall-clock time on this JVM (line
     * `P11 suggest:`), fails if a planted cluster is missed or a run is very
     * slow. The #571 target is under 1 s on the cloud container.
     */
    @Test fun suggestsOver100kFacesQuickly() {
        val people = 40
        val perPerson = 1_000
        val plantedPerPerson = 25
        val total = 100_000
        val identities = List(people) { random() }
        val buildStart = System.nanoTime()
        val builder = PersonMatchInputs.Builder("people-review:1", model, ClusterReviewFacts.NONE)
        var uid = 1
        val planted = HashMap<PersonKey, MutableSet<FaceClusterId>>()
        for (p in 0 until people) {
            val key = PersonKey("person-$p")
            repeat(perPerson) { builder.add(id(uid++), at(identities[p], 0.75f), null, key) }
            repeat(plantedPerPerson) {
                val anchor = id(uid)
                planted.getOrPut(key) { HashSet() } += FaceClusterId(anchor)
                repeat(3) { builder.add(id(uid++), at(identities[p], 0.7f), anchor, null) }
            }
        }
        while (uid <= total) {
            val anchor = id(uid)
            val size = 1 + uid % 3
            repeat(size) { if (uid <= total) builder.add(id(uid++), random(), anchor, null) }
        }
        val inputs = builder.build()
        val buildMs = (System.nanoTime() - buildStart) / 1e6
        assertEquals(total, inputs.faceCount)

        PersonMatchSuggester.suggest(inputs) // warm-up
        val runs = 5
        val times = DoubleArray(runs)
        var out: PersonMatchSuggestions? = null
        for (r in 0 until runs) {
            val t = System.nanoTime()
            out = PersonMatchSuggester.suggest(inputs)
            times[r] = (System.nanoTime() - t) / 1e6
        }
        times.sort()
        val res = out!!
        val suggested = res.byPerson.values.sumOf { it.size }
        println("P11 suggest: faces=$total people=$people clusters=${inputs.clusters.size} " +
            "build_ms=%.0f suggest_ms_median=%.1f suggest_ms_min=%.1f suggest_ms_max=%.1f suggested=%d"
                .format(buildMs, times[runs / 2], times[0], times[runs - 1], suggested))
        for ((key, clusters) in planted) {
            assertTrue("planted clusters of $key", res.clustersOf(key).toSet().containsAll(clusters))
        }
        assertTrue("suggest median ${times[runs / 2]} ms", times[runs / 2] < 3_000.0)
    }
}
