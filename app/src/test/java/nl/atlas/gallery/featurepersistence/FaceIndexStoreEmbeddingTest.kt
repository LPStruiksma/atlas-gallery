package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import nl.atlas.gallery.featureface.onnx.SFaceModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM checks for the SQLite vector payload and restart restoration seam.
 * Android's SQLiteOpenHelper itself needs the hosted emulator, not a mocked JVM Android API.
 * No real face or biometric content enters these tests.
 */
class FaceIndexStoreEmbeddingTest {
    private val version = SFaceModel.EMBEDDER_VERSION
    private fun id(n: Int) = NativeFaceObservationId(n.toString(16).padStart(32, '0'))
    private fun vector(a: Int) = FloatArray(128) { if (it == a) 1f else 0f }
    private fun embedding(n: Int, a: Int) = FaceEmbedding(id(n), version, vector(a))
    private val policy = ClusterPolicy.SFACE_CONSERVATIVE

    @Test fun vectorPersistenceCodecRoundTripAndDefensiveCopy() {
        val raw = vector(3)
        val payload = FaceEmbeddingVectorCodec.encode(raw)
        assertEquals(128 * 4, payload.size)
        raw[3] = 0f
        val decoded = FaceEmbeddingVectorCodec.decode(payload)
        assertEquals(1f, decoded[3], 0f)
        assertArrayEquals(vector(3), decoded, 0.000001f)
        val indexed = IndexedFaceEmbedding(id(1).localHex, "media-rev-1", version, decoded)
        decoded[3] = 0f
        assertEquals(1f, indexed.vector[3], 0f)
        indexed.vector[3] = 0f
        assertEquals(1f, indexed.vector[3], 0f)
    }

    @Test fun rejectedCorruptOrZeroVectorsNeverReadAsSaved() {
        for (bad in listOf(ByteArray(0), ByteArray(511), ByteArray(513))) {
            try {
                FaceEmbeddingVectorCodec.decode(bad)
                fail("Truncated or oversized vector was accepted")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            FaceEmbeddingVectorCodec.encode(FloatArray(128))
            fail("Zero embedding was accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun versionBumpForcesReembeddingButSameModelDoesNot() {
        assertTrue(FaceEmbeddingVersionFence.changed(null, version))
        assertFalse(FaceEmbeddingVersionFence.changed(version, version))
        assertTrue(FaceEmbeddingVersionFence.changed(version, version + "-a2"))
        assertTrue(FaceEmbeddingVersionFence.changed("legacy-version", version))
    }

    @Test fun restartRestoresSavedMembershipThenAcceptsNextBatch() {
        val first = listOf(embedding(1, 0), embedding(2, 0), embedding(3, 1))
        val initial = FaceClusterer.update(FaceClusterState.empty(version, 128, policy), add = first)
        // Persist a vector payload + cluster anchor per observation; construct a fresh
        // object graph as happens after process death (no in-memory state reuse).
        val records = initial.faces.values.map { face ->
            val axis = if (face.observation == id(3)) 1 else 0
            Triple(face.observation, FaceEmbeddingVectorCodec.encode(vector(axis)), face.modelVersion)
        }
        val hydrated = records.map { (uid, payload, model) ->
            FaceEmbedding(uid, model, FaceEmbeddingVectorCodec.decode(payload))
        }
        val memberships = initial.unknownClusters.map { it.members.toList() }
        val restored = FaceClusterer.restore(version, policy, hydrated,
            memberships, ClusterReviewFacts.NONE)
        assertEquals(initial.unknownClusters, restored.unknownClusters)
        val resumed = FaceClusterer.update(restored, add = listOf(embedding(4, 0)))
        assertEquals(4, resumed.faces.size)
        assertEquals(2, resumed.unknownClusters.size)
        assertTrue(resumed.unknownClusters.any { id(1) in it.members && id(4) in it.members })
        assertNotEquals(version + "-a2", restored.modelVersion)
    }

    // D2-5c part 1 (#434): the pure half of schema v3 identity review.

    private fun reviewed(): Pair<FaceClusterState, Map<PersonKey, String>> {
        val faces = listOf(embedding(1, 0), embedding(2, 0), embedding(3, 0), embedding(4, 5))
        val clusters = FaceClusterer.update(FaceClusterState.empty(version, 128, policy), add = faces)
        val start = PeopleClusterReviewState("epoch", "people-review:1", clusters)
        val big = clusters.unknownClusters.first { it.members.size == 3 }
        val named = requireNotNull(reviewPeopleCluster(start,
            PeopleReviewAction.NameCluster(big.id, PersonKey("p-ana"), "Ana")))
        val split = requireNotNull(reviewPeopleCluster(named,
            PeopleReviewAction.SplitPerson(PersonKey("p-ana"), setOf(id(1)))))
        return split.clusters to split.names
    }

    @Test fun reviewFactsRoundTripThroughV3RowsAndPersistedMembership() {
        val (clusters, names) = reviewed()
        val facts = clusters.reviews
        assertEquals(2, facts.confirmed.size)
        assertEquals(2, facts.cannotLink.size)
        assertEquals(1, facts.rejectedPeople.size)
        val rows = PeopleReviewRecords.rows(facts)
        assertTrue(rows.cannotLink.all { (a, b) -> a < b }) // CHECK(face_a < face_b)
        assertEquals(facts, PeopleReviewRecords.facts(rows))
        PeopleReviewRecords.requireConsistent(clusters, names)
        // What the store restores after a restart: vectors, the unknown
        // membership rows and the facts; confirmed faces have no membership row.
        val membership = ClusterPublication.membership(clusters)
        assertFalse(membership.keys.any { NativeFaceObservationId(it) in facts.confirmed })
        val groups = membership.entries.groupBy({ it.value }, { NativeFaceObservationId(it.key) })
        val restored = FaceClusterer.restore(version, policy,
            listOf(embedding(1, 0), embedding(2, 0), embedding(3, 0), embedding(4, 5)),
            groups.values, PeopleReviewRecords.facts(rows))
        assertEquals(clusters.unknownClusters, restored.unknownClusters)
        assertEquals(facts, restored.reviews)
    }

    @Test fun reviewDeltaWritesOnlyChangedRows() {
        val delta = PeopleReviewRecords.mapDelta(mapOf("a" to "p1", "b" to "p1"),
            mapOf("a" to "p1", "b" to "p2", "c" to "p3"))
        assertEquals(listOf("b" to "p1"), delta.delete)
        assertEquals(setOf("b" to "p2", "c" to "p3"), delta.insert.toSet())
        val same = PeopleReviewRecords.setDelta(setOf(1, 2), setOf(1, 2))
        assertTrue(same.delete.isEmpty() && same.insert.isEmpty())
        assertEquals("people-review:7", PeopleReviewRecords.generation(7))
    }

    @Test fun inconsistentReviewSnapshotsAreRefused() {
        val (clusters, names) = reviewed()
        for (bad in listOf(emptyMap(), mapOf(PersonKey("p-ana") to " Ana"),
                mapOf(PersonKey("p-ana") to "x".repeat(81)),
                mapOf(PersonKey("p-ana") to "Ana\n"))) {
            try {
                PeopleReviewRecords.requireConsistent(clusters, bad)
                fail("Accepted names $bad")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(PeopleReviewRecords.validName("Ana"))
        assertFalse(PeopleReviewRecords.validName(""))
    }
}

