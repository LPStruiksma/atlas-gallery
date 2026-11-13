package nl.atlas.gallery.featureface

import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedMedia

// FaceVisibleMedia lives in FaceVisibleMedia.kt (lane P post-install port).

/** Counts source rows, NOT unique X catalog keys or a confirmed People count. */
data class FaceCatalogPublication(
    val permissionEpoch: String,
    val detectorVersion: String,
    val sourcePhotoRows: Int,
    /** Videos left out because their provenance is incomplete (VS-V1b). */
    val ignoredVideoRows: Int,
    /** Videos catalogued for keyframe face scanning (VS-V1b). */
    val sourceVideoRows: Int = 0,
    /**
     * VS-V1c: complete videos past [FaceCatalogLimits.MAX_VIDEOS], left out of
     * this publication (and of the directory) instead of failing it. Photos
     * are never affected; a later publication re-evaluates them.
     */
    val deferredVideoRows: Int = 0,
    /** #508: photos past the photo cap (the oldest, in a newest-first inventory). */
    val deferredPhotoRows: Int = 0,
    val ignoredPhotoRows: Int = 0,
    val weakPhotoRows: Int = 0,
    val observedPhotoRows: Int = 0,
    val missingPhotoFields: Map<String, Int> = emptyMap()
)

/**
 * Fail-closed, O(batchSize) staging into X's independent private face index.
 * Neither a staging exception nor provider drift is allowed to publish an
 * incomplete catalogue or revive observations from an old grant.
 *
 * Must execute in I's dedicated cancellable background worker. The caller
 * supplies fresh, independent grant and provider-generation checks, including
 * a final verification of Lane M's MediaStore generation/version snapshot.
 *
 * A zero-row complete snapshot is legitimate only when I has positively
 * verified the granted MediaStore inventory is truly empty.
 */
class FaceCatalogPublisher(
    private val store: FaceIndexStore,
    private val scopeCurrent: (String) -> Boolean,
    private val providerSnapshotCurrent: () -> Boolean,
    private val checkActive: () -> Unit = {},
    private val batchSize: Int = FaceDiscoveryContract.MAX_CATALOG_BATCH,
    private val maxPhotoRows: Int = FaceCatalogLimits.MAX_PHOTOS,
    private val maxVideoRows: Int = FaceCatalogLimits.MAX_VIDEOS
) {
    init {
        require(batchSize in 1..FaceDiscoveryContract.MAX_CATALOG_BATCH)
        require(maxPhotoRows in 1..FaceCatalogLimits.MAX_PHOTOS)
        require(maxVideoRows in 0..FaceCatalogLimits.MAX_VIDEOS)
    }

    fun publish(
        permissionEpoch: String,
        detectorVersion: String,
        sourceRows: Sequence<FaceVisibleMedia>
    ): FaceCatalogPublication {
        require(permissionEpoch.isNotBlank() && detectorVersion.isNotBlank())
        var published = false
        var snapshotBegun = false
        // VS-V1c: photos and videos spend separate budgets. Videos past theirs
        // are deferred (left out), so a video-heavy library never stops photos.
        val admission = FaceCatalogAdmission(maxPhotoRows, maxVideoRows)
        var stagedRows = 0
        val batch = ArrayList<IndexedMedia>(batchSize)
        val taken = LongArray(batchSize)
        try {
            checkScope(permissionEpoch)
            // Begin immediately hides every prior observation from a revoked
            // scope, without deleting confirmed V4 People data.
            storeStep(permissionEpoch) { store.beginSnapshot(permissionEpoch) }
            snapshotBegun = true
            for (row in sourceRows) {
                checkActive()
                // Partial photo metadata yields an explicit weak revision.
                // Only a row without canonical identity is omitted; the
                // directory applies the identical admission.
                val key = admission.admit(row)
                if (key == null) {
                    // Even a video-heavy (or over-cap) inventory must observe revocation.
                    if ((admission.leftOutRows and 127) == 0) {
                        checkScope(permissionEpoch)
                    }
                    continue
                }
                taken[batch.size] = row.takenAtMillis
                batch.add(IndexedMedia(key.canonicalMediaKey, key.sourceRevision))
                stagedRows++
                if (batch.size == batchSize) {
                    checkScope(permissionEpoch)
                    storeStep(permissionEpoch) {
                        store.stageBatch(permissionEpoch, batch, taken)
                    }
                    batch.clear()
                } else if ((stagedRows and 127) == 0) {
                    checkScope(permissionEpoch)
                }
            }
            checkScope(permissionEpoch)
            if (batch.isNotEmpty()) {
                storeStep(permissionEpoch) {
                    store.stageBatch(permissionEpoch, batch, taken.copyOf(batch.size))
                }
            }
            checkScope(permissionEpoch)
            storeStep(permissionEpoch) { store.commitSnapshot(permissionEpoch, detectorVersion) }
            // Revocation or provider drift may race with commit. Never leave
            // results visible if a post-commit verification fails.
            checkScope(permissionEpoch)
            published = true
            return FaceCatalogPublication(permissionEpoch, detectorVersion,
                admission.photoRows, admission.ignoredVideoRows, admission.videoRows,
                admission.deferredVideoRows, admission.deferredPhotoRows,
                admission.ignoredPhotoRows, admission.weakPhotoRows,
                admission.observedPhotoRows, admission.missingPhotoFields)
        } finally {
            // Never let an old cancelled publisher A revoke a newer valid B.
            // X checks the *active or staging* epoch atomically inside SQLite.
            // Actual grant loss is handled by I's unconditional privacy gate.
            // Do not revoke an unrelated valid snapshot if admission failed
            // before this publisher even opened its SQLite staging scope.
            // X handles any fail-closed rejection *inside* beginSnapshot().
            if (snapshotBegun && !published) {
                store.invalidateSnapshotIfCurrent(permissionEpoch)
            }
        }
    }

    /**
     * #538: a newer publication (or a revoke) can start its own snapshot
     * between this publisher's last scope check and its next store call.
     * The store then refuses this epoch's stale staging with an
     * IllegalArgumentException. That is a lost race, not a broken catalogue:
     * report it as the same IllegalStateException a scope check throws, so
     * the host neither shows "could not be prepared" nor switches off the
     * newer run that won. A refusal while this epoch is still current stays
     * an IllegalArgumentException (a genuine invariant failure).
     */
    private inline fun storeStep(epoch: String, step: () -> Unit) {
        try {
            step()
        } catch (refused: IllegalArgumentException) {
            val stillCurrent = try {
                scopeCurrent(epoch) && providerSnapshotCurrent()
            } catch (_: RuntimeException) { false }
            if (stillCurrent) throw refused
            throw IllegalStateException("Face catalogue publication superseded", refused)
        }
    }

    private fun checkScope(epoch: String) {
        checkActive()
        check(scopeCurrent(epoch) && providerSnapshotCurrent()) {
            "Face catalogue permission or provider revision changed"
        }
    }
}
