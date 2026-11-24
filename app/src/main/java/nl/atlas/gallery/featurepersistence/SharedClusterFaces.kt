package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import java.lang.ref.WeakReference

/**
 * Lets every cluster state a [FaceIndexStore] builds share one decoded copy of
 * each face embedding (OOM at 264k faces, heap 511/512 MB, v0.8.4).
 *
 * Before, each read of the whole library (the People snapshot the UI keeps,
 * the seed of every publication step, a reload after a publication) decoded
 * every vector again: about 260 B per face per copy, so two or three copies of
 * a 264k-face library alone took 140-210 MB. A [FaceEmbedding] never changes
 * for its face uid (a rescan mints a new uid, rows are never updated), so a
 * later read can reuse the object of an earlier state with the same model
 * version and policy.
 *
 * Holds the last state only weakly: once nothing else holds it, it costs
 * nothing and the next read decodes again. Thread-safe: reads take a
 * snapshot of the reference.
 */
internal class SharedClusterFaces(private val policy: ClusterPolicy = ClusterPolicy.DEFAULT) {
    @Volatile private var last: WeakReference<FaceClusterState>? = null

    /** Call after building or publishing [state] from this store's rows. */
    fun remember(state: FaceClusterState) {
        if (state.faceCount > 0 && state.policy.fingerprint == policy.fingerprint)
            last = WeakReference(state)
    }

    /** The faces of the remembered state when it is still alive and of [version]. */
    fun prior(version: String): Map<NativeFaceObservationId, FaceEmbedding>? =
        last?.get()?.takeIf {
            it.modelVersion == version && it.policy.fingerprint == policy.fingerprint
        }?.faces

    fun forget() { last = null }

    companion object {
        /**
         * The embedding of [uid] in [prior] when it was built from the same
         * picture key ([frame] before the policy's same-frame rule), else null.
         */
        fun reuse(prior: Map<NativeFaceObservationId, FaceEmbedding>?, uid: String,
                  frame: Long, policy: ClusterPolicy = ClusterPolicy.DEFAULT): FaceEmbedding? {
            if (prior == null) return null
            val found = prior[NativeFaceObservationId(uid)] ?: return null
            val kept = if (policy.sameFrameVeto) frame else 0L
            return found.takeIf { it.frame == kept }
        }
    }
}
