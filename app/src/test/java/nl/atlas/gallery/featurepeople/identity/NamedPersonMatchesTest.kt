package nl.atlas.gallery.featurepeople.identity

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * #580: unnamed faces that are, by the measured rule, a named person.
 * Synthetic vectors only: faces sit tight around a person's direction
 * (cosine about 0.9 to each other), strangers are unrelated directions.
 */
class NamedPersonMatchesTest {
    private val model = "m"
    private val dim = 128
    // The rule read from nothing: grouping itself applies none here.
    private val policy = ClusterPolicy.SFACE_MEASURED.copy(metric = null, personMatchFloor = 0f)
    private val ada = PersonKey("ada")
    private val bo = PersonKey("bo")
    private var nextId = 1
    private var nextFrame = 1L
    private val rng = Random(580)

    private fun Random.gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
    private fun unit(): DoubleArray {
        val v = DoubleArray(dim) { rng.gaussian() }
        val n = sqrt(v.sumOf { it * it })
        return DoubleArray(dim) { v[it] / n }
    }
    /** A direction at exactly cosine [s] to [a]. */
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
    private fun state(faces: List<FaceEmbedding>, reviews: ClusterReviewFacts): FaceClusterState =
        FaceClusterer.update(FaceClusterState.empty(model, dim, policy), add = faces, reviews = reviews)
    private fun named(vararg people: Pair<PersonKey, List<FaceEmbedding>>) =
        people.flatMap { (key, faces) -> faces.map { it.observation to key } }.toMap()

    @Test fun laterFacesOfANamedPersonMatchAndStrangersDoNot() {
        val a = unit()
        val known = List(4) { face(a) }
        val later = List(3) { face(a) }
        val strangers = List(5) { face(unit()) } + List(3) { face(unit()) }
        val s = state(known + later + strangers, ClusterReviewFacts(confirmed = named(ada to known)))
        val before = s.unknownClusters
        val matches = NamedPersonMatches.of(s)
        assertEquals(later.map { it.observation }.toSet(), matches.map { it.face }.toSet())
        assertTrue(matches.all { it.person == ada && it.mean >= NamedPersonMatches.MEAN_FLOOR })
        assertEquals("best first", matches.sortedByDescending { it.mean }, matches)
        // A reading only: nothing moved, and the same state reads the same.
        assertEquals(before, s.unknownClusters)
        assertEquals(later.size, s.unknownClusters.sumOf { c -> c.members.count { m -> later.any { it.observation == m } } })
        assertEquals(matches, NamedPersonMatches.of(s))
    }

    @Test fun aPersonWithFewerThanThreeExamplesMatchesNothing() {
        val a = unit()
        val known = List(2) { face(a) }
        val later = List(3) { face(a) }
        val s = state(known + later, ClusterReviewFacts(confirmed = named(ada to known)))
        assertTrue(NamedPersonMatches.of(s).isEmpty())
    }

    @Test fun aLookAlikeBelowTheFloorIsNotMatched() {
        // Mean cosine about 0.95 * 0.95 * 0.45 = 0.41: enough for a question, not for a match.
        val a = unit()
        val known = List(4) { face(a) }
        val near = mix(a, 0.45)
        val lookAlike = List(3) { face(near) }
        val s = state(known + lookAlike, ClusterReviewFacts(confirmed = named(ada to known)))
        assertTrue(NamedPersonMatches.of(s).isEmpty())
        assertEquals("still asked on the person's page", 1, SamePersonHypotheses.forPeople(s).size)
    }

    @Test fun aFaceThatFitsTwoNamedPeopleMatchesNeither() {
        // Relatives: Bo's direction is at 0.6 to Ada's, so a Bo face reaches
        // about 0.54 against Ada and 0.9 against Bo. Both are over the floor.
        val a = unit()
        val b = mix(a, 0.6)
        val adaFaces = List(4) { face(a) }
        val boFaces = List(4) { face(b) }
        val laterBo = List(2) { face(b) }
        val reviews = ClusterReviewFacts(confirmed = named(ada to adaFaces, bo to boFaces))
        val s = state(adaFaces + boFaces + laterBo, reviews)
        assertTrue(NamedPersonMatches.of(s).isEmpty())
        // Asking only for Bo does not hide that Ada fits too.
        assertTrue(NamedPersonMatches.of(s, setOf(bo)).isEmpty())
    }

    @Test fun twoUnrelatedNamedPeopleEachGetTheirOwnFaces() {
        val a = unit(); val b = unit()
        val adaFaces = List(3) { face(a) }
        val boFaces = List(3) { face(b) }
        val laterAda = List(2) { face(a) }
        val laterBo = List(2) { face(b) }
        val s = state(adaFaces + boFaces + laterAda + laterBo,
            ClusterReviewFacts(confirmed = named(ada to adaFaces, bo to boFaces)))
        val all = NamedPersonMatches.of(s)
        assertEquals(listOf(ada, ada, bo, bo), all.map { it.person })
        assertEquals(laterAda.map { it.observation }.toSet(), all.filter { it.person == ada }.map { it.face }.toSet())
        assertEquals(all.filter { it.person == bo }, NamedPersonMatches.of(s, setOf(bo)))
    }

    @Test fun aHumanNoEndsAMatchForGood() {
        val a = unit()
        val known = List(4) { face(a) }
        val later = List(3) { face(a) }
        val confirmed = named(ada to known)
        // "Not this person" for one face; "not the same person" against one of Ada's faces for another.
        val reviews = ClusterReviewFacts(confirmed = confirmed,
            rejectedPeople = setOf(RejectedPerson(later[0].observation, ada)),
            cannotLink = setOf(FacePair.of(later[1].observation, known[2].observation)))
        val s = state(known + later, reviews)
        assertEquals(listOf(later[2].observation), NamedPersonMatches.of(s).map { it.face })
    }

    @Test fun twoFacesInOnePictureAreNeverBothThePerson() {
        val a = unit()
        val known = List(4) { face(a) }
        // One picture already holds a confirmed face of Ada: its other face cannot be her.
        val besideAda = face(a, frame = known[0].frame)
        // Another picture holds two unnamed faces that both fit: only the better one matches.
        val shared = nextFrame++
        val strong = face(a, frame = shared, closeness = 0.98)
        val weak = face(a, frame = shared, closeness = 0.90)
        val s = state(known + besideAda + strong + weak, ClusterReviewFacts(confirmed = named(ada to known)))
        assertEquals(listOf(strong.observation), NamedPersonMatches.of(s).map { it.face })
    }

    @Test fun centroidPoliciesAndEmptyStatesMatchNothing() {
        val a = unit()
        val known = List(4) { face(a) }
        val later = List(2) { face(a) }
        val centroid = FaceClusterer.update(
            FaceClusterState.empty(model, dim, ClusterPolicy.SFACE_CONSERVATIVE),
            add = known + later, reviews = ClusterReviewFacts(confirmed = named(ada to known)))
        assertTrue(NamedPersonMatches.of(centroid).isEmpty())
        assertTrue(NamedPersonMatches.of(FaceClusterState.empty(model, dim, policy)).isEmpty())
        assertTrue(NamedPersonMatches.of(state(known, ClusterReviewFacts(confirmed = named(ada to known)))).isEmpty())
    }
}
