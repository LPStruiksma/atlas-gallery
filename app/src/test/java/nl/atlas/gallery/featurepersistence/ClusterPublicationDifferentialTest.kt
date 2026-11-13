package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * D2-5p (#419) differential on synthetic vectors, no SQLite and no real media.
 *
 * "historical" = the pre-D2-5p store: restore persisted membership, then one
 * FaceClusterer.update per completed photo, in completion order.
 * "published" = this PR's store: restore the last published membership, then
 * [ClusterPublication.publish] with every pending face, at the boundaries
 * [ClusterPublicationCadence] picks. The persisted round trip is modelled with
 * FaceClusterer.restore, exactly as FaceIndexStore.buildClusterState does.
 *
 * Findings pinned here:
 * 1. A one-photo batch is identical to the historical store (members and anchors).
 * 2. Well-separated identities give identical clusters at any cadence and order.
 * 3. A batch result does not depend on completion order; the historical store does.
 * 4. Borderline faces may group differently across batch splits (accepted,
 *    documented in ClusterPublication); review facts hold either way. On
 *    gallery-like identities the difference is nil at realistic noise. On
 *    adversarial data (directions spread evenly over 180 degrees) one photo
 *    per update chains almost everything into 2 wide clusters, while a batch
 *    stays split: the batch is the more conservative of the two there.
 */
class ClusterPublicationDifferentialTest {
    private val model = "synthetic-model-v1"
    private val dim = 128
    private val policy = ClusterPolicy.SFACE_CONSERVATIVE

    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
    private fun empty() = FaceClusterState.empty(model, dim, policy)

    private fun angle(n: Int, degrees: Double): FaceEmbedding {
        val r = degrees * PI / 180.0
        return FaceEmbedding(id(n), model, FloatArray(dim) {
            when (it) { 0 -> cos(r).toFloat(); 1 -> sin(r).toFloat(); else -> 0f }
        })
    }

    /** [people] identities on distinct axes with small noise: far apart, tight inside. */
    private fun separated(people: Int, perPerson: Int, rng: Random): List<List<FaceEmbedding>> {
        var n = 1
        val photos = ArrayList<List<FaceEmbedding>>()
        repeat(perPerson) {
            for (p in 0 until people) {
                val v = FloatArray(dim) { if (it == p) 1f else 0.02f * (rng.nextFloat() - 0.5f) }
                photos += listOf(FaceEmbedding(id(n++), model, v))
            }
        }
        return photos.shuffled(rng)
    }

    private fun persistRoundTrip(s: FaceClusterState): FaceClusterState =
        if (s.faceCount == 0) empty() else FaceClusterer.restore(model, policy,
            s.unknownClusters.flatMap { c -> c.members.map { m -> faceOf(s, m) } },
            s.unknownClusters.map { it.members }, ClusterReviewFacts.NONE)

    private fun faceOf(s: FaceClusterState, m: NativeFaceObservationId): FaceEmbedding =
        s.faces.getValue(m)

    private fun historical(photos: List<List<FaceEmbedding>>): FaceClusterState =
        photos.fold(empty()) { s, photo ->
            FaceClusterer.update(persistRoundTrip(s), add = photo)
        }

    /** Publish whenever [boundary] says so after a photo, and at the end. */
    private fun published(
        photos: List<List<FaceEmbedding>>,
        boundary: (pendingPhotos: Int, publishedFaces: Int) -> Boolean
    ): Pair<FaceClusterState, Int> {
        var state = empty()
        val pending = ArrayList<FaceEmbedding>()
        var pendingPhotos = 0
        var publications = 0
        for ((i, photo) in photos.withIndex()) {
            pending += photo
            pendingPhotos++
            val last = i == photos.lastIndex
            if (last || boundary(pendingPhotos, state.faceCount)) {
                state = ClusterPublication.publish(persistRoundTrip(state), pending)
                pending.clear(); pendingPhotos = 0; publications++
            }
        }
        return state to publications
    }

    private fun cadence(p: Int, published: Int) =
        ClusterPublicationCadence.due(true, p, published, scanFinished = false)

    private fun rows(s: FaceClusterState) = ClusterPublication.membership(s)
    private fun partition(s: FaceClusterState) =
        s.unknownClusters.map { c -> c.members.map { it.localHex }.toSet() }.toSet()

    @Test fun oneBatchPerPhotoIsIdenticalToHistoricalStore() {
        val rng = Random(419)
        // Mixed: separated people plus borderline angles, several faces per photo.
        val faces = (1..120).map { n -> angle(n, rng.nextDouble(0.0, 140.0)) }
        val photos = faces.chunked(3)
        for (order in 0 until 5) {
            val shuffled = photos.shuffled(Random(order))
            val (pub, count) = published(shuffled) { _, _ -> true }
            assertEquals(shuffled.size, count)
            assertEquals("order=$order", rows(historical(shuffled)), rows(pub))
        }
    }

    @Test fun separatedIdentitiesAgreeAtEveryCadenceAndOrder() {
        for (seed in 0 until 4) {
            val photos = separated(people = 24, perPerson = 40, rng = Random(seed))
            val reference = rows(historical(photos))
            assertEquals(24, partition(historical(photos)).size)
            for (batch in listOf(1, 7, 64, 500, photos.size)) {
                val (pub, _) = published(photos) { p, _ -> p >= batch }
                assertEquals("seed=$seed batch=$batch", reference, rows(pub))
            }
            val (cad, publications) = published(photos, ::cadence)
            assertEquals(reference, rows(cad))
            assertTrue(publications >= 1)
        }
    }

    /**
     * Gallery-like synthetic identities (FaceClustererBenchmarkTest style):
     * 40 people with long-tailed counts, Gaussian noise around each centroid.
     * At realistic noise the store gives the same clusters at every cadence;
     * at heavy noise a few borderline faces split differently, never into a
     * cluster that mixes two people.
     */
    @Test fun galleryLikeIdentitiesMatchHistoricalStore() {
        for (sigma in listOf(0.8f, 1.0f)) {
            val rng = Random(1)
            val person = HashMap<String, Int>()
            val faces = ArrayList<FaceEmbedding>()
            var n = 1
            for (p in 0 until 40) {
                val c = FloatArray(dim) { gaussian(rng) }
                val norm = sqrt(c.sumOf { it.toDouble() * it }).toFloat()
                repeat(1 + 200 / (p + 1)) {
                    val face = FaceEmbedding(id(n++), model, FloatArray(dim) {
                        c[it] / norm + sigma / sqrt(dim.toFloat()) * gaussian(rng)
                    })
                    person[face.observation.localHex] = p
                    faces += face
                }
            }
            val photos = faces.shuffled(rng).map { listOf(it) }
            val hist = historical(photos)
            fun impure(s: FaceClusterState) = s.unknownClusters.count { c ->
                c.members.map { person.getValue(it.localHex) }.toSet().size > 1
            }
            for (batch in listOf(64, 256, photos.size)) {
                val (pub, _) = published(photos) { p, _ -> p >= batch }
                println("ATLAS_D25P_GALLERY sigma=$sigma batch=$batch faces=${faces.size} " +
                    "historical_clusters=${hist.unknownClusters.size} " +
                    "published_clusters=${pub.unknownClusters.size} " +
                    "impure_historical=${impure(hist)} impure_published=${impure(pub)}")
                assertEquals(0, impure(pub))
                if (sigma == 0.8f) assertEquals("sigma=$sigma batch=$batch", rows(hist), rows(pub))
                else assertTrue(kotlin.math.abs(hist.unknownClusters.size -
                    pub.unknownClusters.size) <= 5)
            }
        }
    }

    private fun gaussian(rng: Random): Float {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * PI * rng.nextDouble())).toFloat()
    }

    @Test fun batchIgnoresCompletionOrderButHistoricalStoreDoesNot() {
        // 0 and 110 degrees are two people; 55 degrees bridges them borderline.
        val a = angle(10, 0.0); val b = angle(20, 110.0); val bridge = angle(30, 55.0)
        val orders = listOf(listOf(a, b, bridge), listOf(bridge, a, b), listOf(b, bridge, a))
            .map { o -> o.map { listOf(it) } }
        val batched = orders.map { rows(published(it) { _, _ -> false }.first) }.toSet()
        val history = orders.map { rows(historical(it)) }.toSet()
        assertEquals("one batch is order independent", 1, batched.size)
        assertTrue("historical store is path dependent: $history", history.size > 1)
    }

    @Test fun borderlineDeviationIsBoundedAndRecorded() {
        // Directions spread over a half circle: many borderline joins. Count the
        // faces whose co-members differ between the historical store and the
        // cadence; this is the accepted deviation, written to the test log.
        val rng = Random(7)
        val faces = (1..400).map { n -> angle(n, rng.nextDouble(0.0, 180.0)) }
        val photos = faces.chunked(2)
        val hist = historical(photos)
        val (pub, _) = published(photos, ::cadence)
        val hRows = rows(hist); val pRows = rows(pub)
        assertEquals(hRows.keys, pRows.keys)
        val hGroups = hRows.entries.groupBy({ it.value }, { it.key })
        val pGroups = pRows.entries.groupBy({ it.value }, { it.key })
        val moved = hRows.keys.count { f ->
            hGroups.getValue(hRows.getValue(f)).toSet() != pGroups.getValue(pRows.getValue(f)).toSet()
        }
        val vec = faces.associateBy { it.observation.localHex }
        fun spread(groups: Map<String, List<String>>): Double = groups.values.maxOf { g ->
            var worst = 1.0
            for (x in g) for (y in g) {
                val a = vec.getValue(x).unit; val b = vec.getValue(y).unit
                var dot = 0.0; for (i in a.indices) dot += a[i] * b[i]
                worst = minOf(worst, dot)
            }
            Math.toDegrees(kotlin.math.acos(worst.coerceIn(-1.0, 1.0)))
        }
        val refines = pGroups.values.all { g -> g.map { hRows.getValue(it) }.toSet().size == 1 }
        println("ATLAS_D25P_DEVIATION faces=${hRows.size} moved=$moved " +
            "historical_clusters=${hGroups.size} published_clusters=${pGroups.size} " +
            "historical_max_spread_deg=${"%.1f".format(spread(hGroups))} " +
            "published_max_spread_deg=${"%.1f".format(spread(pGroups))} refines=$refines")
        for (s in listOf(hist, pub)) assertConservative(s)
        assertTrue("batch must not be wider than the per-photo history here",
            spread(pGroups) <= spread(hGroups))
    }

    @Test fun removalThenRescanKeepsPublishedSeed() {
        val a = angle(1, 0.0); val b = angle(2, 8.0); val c = angle(3, 110.0)
        val first = published(listOf(listOf(a), listOf(b), listOf(c))) { _, _ -> false }.first
        val afterDelete = FaceClusterer.update(persistRoundTrip(first), remove = listOf(a.observation))
        val rescanned = angle(40, 3.0)
        val hist = FaceClusterer.update(persistRoundTrip(afterDelete), add = listOf(rescanned))
        val pub = ClusterPublication.publish(persistRoundTrip(afterDelete), listOf(rescanned))
        assertEquals(rows(hist), rows(pub))
        assertFalse(a.observation.localHex in rows(pub))
    }

    @Test fun deltaWritesOnlyChangedRows() {
        val old = mapOf("a" to "a", "b" to "a", "c" to "c", "stale" to "x")
        val new = mapOf("a" to "a", "b" to "a", "c" to "a", "d" to "d")
        val d = ClusterPublication.delta(old, new)
        assertEquals(listOf("stale"), d.delete)
        assertEquals(mapOf("c" to "a", "d" to "d"), d.upsert.toMap())
        assertEquals(3, d.rows)
        assertEquals(0, ClusterPublication.delta(new, new).rows)
    }

    @Test fun cadenceRule() {
        assertFalse(ClusterPublicationCadence.due(false, 10_000, 0, scanFinished = true))
        assertTrue(ClusterPublicationCadence.due(true, 1, 0, scanFinished = true))
        assertFalse(ClusterPublicationCadence.due(true, 255, 0, scanFinished = false))
        assertTrue(ClusterPublicationCadence.due(true, 256, 0, scanFinished = false))
        assertFalse(ClusterPublicationCadence.due(true, 2_499, 10_000, scanFinished = false))
        assertTrue(ClusterPublicationCadence.due(true, 2_500, 10_000, scanFinished = false))
    }

    /**
     * Arithmetic model of a scan under the cadence (no SQL): faces read by all
     * publications stay within (GROWTH_DIVISOR + facesPerPhoto) per photo plus
     * one final library, so total publication work is linear in library size.
     */
    @Test fun cadenceKeepsTotalPublicationReadsLinear() {
        for (facesPerPhoto in listOf(1, 3, 32)) for (photos in listOf(1_000, 10_000, 50_000, 150_000)) {
            var published = 0; var pending = 0; var reads = 0L; var publications = 0
            for (i in 1..photos) {
                pending++
                if (ClusterPublicationCadence.due(true, pending, published, i == photos)) {
                    val total = published + pending * facesPerPhoto
                    reads += total; published = total; pending = 0; publications++
                }
            }
            val bound = (ClusterPublicationCadence.GROWTH_DIVISOR + facesPerPhoto).toLong() *
                photos + published
            println("ATLAS_SCALE model=cadence photos=$photos faces_per_photo=$facesPerPhoto " +
                "publications=$publications face_reads=$reads reads_per_photo=" +
                "%.2f".format(reads.toDouble() / photos))
            assertTrue("reads=$reads bound=$bound", reads <= bound)
            // Geometric growth: publications grow with log(photos), not photos.
            val log = kotlin.math.ln(photos.toDouble()) / kotlin.math.ln(1.0 + 1.0 /
                ClusterPublicationCadence.GROWTH_DIVISOR)
            assertTrue("publications=$publications", publications <= log + 2 + sqrt(photos / 256.0))
        }
    }

    private fun assertConservative(s: FaceClusterState) {
        val members = s.unknownClusters.flatMap { it.members }
        assertEquals(members.size, members.toSet().size)
        assertEquals(s.faceCount, members.size)
        for (c in s.unknownClusters) {
            assertEquals(c.members.sortedBy { it.localHex }, c.members)
            assertEquals(c.members.first(), c.id.anchor)
        }
    }
}
