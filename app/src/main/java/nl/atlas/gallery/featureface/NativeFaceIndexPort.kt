package nl.atlas.gallery.featureface

import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedFaceLease
import nl.atlas.gallery.featurepersistence.IndexedMedia

/**
 * Strict, reversible adapter between P's on-device one-photo scanner and
 * X's separate app-private detected-face SQLite store.
 *
 * X owns storage, migrations, catalog staging, epoch revocation and UUIDs.
 * This adapter does not touch PeopleRepository/V4, launch WorkManager, open
 * media, infer an identity, or grant permission. I must supply one correctly
 * initialized store only after both source branches have been integrated.
 *
 * All calls are blocking; use only I's dedicated background worker.
 */
class NativeFaceIndexPort(private val store: FaceIndexStore) : FaceScanIndexPort {
    override fun claim(permissionEpoch: String): FaceScanLease? =
        store.claim(permissionEpoch)?.toScanLease()

    override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>): Boolean =
        completeFrames(lease, observations, observations.map { null })

    /** VS-V1b: frameMs[i] is observation i's keyframe time in a video, null for a photo. */
    override fun completeFrames(
        lease: FaceScanLease, observations: List<UnknownFaceBox>, frameMs: List<Long?>
    ): Boolean {
        require(observations.size <= FaceDiscoveryContract.MAX_FACES_PER_IMAGE) {
            "Unbounded face observations"
        }
        // No bare ordinals, V4 numeric face IDs, filenames or identity labels
        // cross this boundary. X atomically rechecks epoch/revision/model/token
        // and mints private observation UUIDs only on successful completion.
        return store.complete(lease.toIndexedLease(), observations.map {
            IndexedFaceBox(it.left, it.top, it.right, it.bottom)
        }, frameMs)
    }

    /** A corrupt/DRM video is recorded as a terminal typed skip, not three retries. */
    override fun skip(lease: FaceScanLease, reason: String): Boolean =
        store.skip(lease.toIndexedLease(), reason)

    /** Revoked/stale workers may release only the original matching lease. */
    override fun release(lease: FaceScanLease): Boolean =
        store.release(lease.toIndexedLease())

    /** Only genuine decoder/model failures spend X's bounded retry budget. */
    override fun fail(lease: FaceScanLease): Boolean =
        store.fail(lease.toIndexedLease())
}

private fun IndexedFaceLease.toScanLease(): FaceScanLease = FaceScanLease(
    media = FaceMediaKey(media.key, media.revision),
    detectorVersion = detectorVersion,
    permissionEpoch = permissionEpoch,
    opaqueToken = token
)

private fun FaceScanLease.toIndexedLease(): IndexedFaceLease = IndexedFaceLease(
    media = IndexedMedia(media.canonicalMediaKey, media.sourceRevision),
    detectorVersion = detectorVersion,
    permissionEpoch = permissionEpoch,
    token = opaqueToken
)
