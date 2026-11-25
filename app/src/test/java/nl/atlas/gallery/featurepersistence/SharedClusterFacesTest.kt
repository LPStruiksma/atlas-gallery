package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Synthetic vectors only; no real face or biometric content. */
class SharedClusterFacesTest {
    private val version = SFaceModel.EMBEDDER_VERSION
    private val policy = ClusterPolicy.DEFAULT
    private fun uid(n: Int) = n.toString(16).padStart(32, '0')
    private fun frameOf(n: Int) = ClusterFrameKey.of("media-$n", null)
    private fun face(n: Int) = policy.embedding(NativeFaceObservationId(uid(n)), version,
        FloatArray(128) { if (it == n % 128) 1f else 0.01f * (it % 7) }, frameOf(n))
    private fun state(faces: List<FaceEmbedding>) = FaceClusterer.restore(version, policy,
        faces, faces.map { listOf(it.observation) }, ClusterReviewFacts.NONE)

    @Test fun laterReadReusesTheSameEmbeddingObjects() {
        val shared = SharedClusterFaces(policy)
        val first = state(List(5) { face(it + 1) })
        shared.remember(first)
        val prior = shared.prior(version)!!
        for (n in 1..5) {
            val reused = SharedClusterFaces.reuse(prior, uid(n), frameOf(n), policy)
            assertSame(first.faces.getValue(NativeFaceObservationId(uid(n))), reused)
        }
        assertNull("unknown face decodes fresh",
            SharedClusterFaces.reuse(prior, uid(99), frameOf(99), policy))
    }

    @Test fun reusedStateEqualsFreshlyDecodedState() {
        val faces = List(40) { face(it + 1) }
        val fresh = state(faces)
        val shared = SharedClusterFaces(policy).apply { remember(fresh) }
        val prior = shared.prior(version)
        val rebuilt = state(List(40) { n ->
            SharedClusterFaces.reuse(prior, uid(n + 1), frameOf(n + 1), policy) ?: face(n + 1)
        })
        assertEquals(fresh.unknownClusters.map { it.members }, rebuilt.unknownClusters.map { it.members })
        assertEquals(fresh.matched, rebuilt.matched)
    }

    @Test fun otherModelVersionOrPictureIsNeverReused() {
        val shared = SharedClusterFaces(policy)
        shared.remember(state(listOf(face(1))))
        assertNull(shared.prior("$version-next"))
        val prior = shared.prior(version)!!
        val kept = SharedClusterFaces.reuse(prior, uid(1), frameOf(2), policy)
        if (policy.sameFrameVeto) assertNull("frame changed, so decode fresh", kept)
    }

    @Test fun holdsTheStateOnlyWeakly() {
        val shared = SharedClusterFaces(policy)
        shared.remember(state(List(3) { face(it + 1) }))
        repeat(20) {
            if (shared.prior(version) == null) return
            System.gc(); Thread.sleep(20)
        }
        assertNull("state no one holds must be collectable", shared.prior(version))
    }

    @Test fun forgetDropsIt() {
        val shared = SharedClusterFaces(policy)
        val kept = state(listOf(face(1)))
        shared.remember(kept)
        shared.forget()
        assertNull(shared.prior(version))
        assertEquals(1, kept.faceCount)
    }
}
