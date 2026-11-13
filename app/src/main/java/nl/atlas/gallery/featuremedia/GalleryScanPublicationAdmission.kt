package nl.atlas.gallery

/**
 * Final O(1) permission/provider admission for an already completed
 * MediaStore query. The repository's per-volume version/generation check
 * runs before returning; a provider notification or grant transition can
 * still occur before the ViewModel publishes its 150k-item snapshot.
 *
 * A selected-photo grant may be reselected with IDENTICAL permission bits:
 * Activity resume/scope tickets remain mandatory; this gate does not
 * pretend that matching permission flags prove the selected URI set.
 */
internal fun mayPublishCompletedGalleryScan(
    latestRequest: Boolean,
    accessExplicitlyRevoked: Boolean,
    grantAtScanStart: GalleryMediaGrant,
    grantAtPublication: GalleryMediaGrant,
    changeVersionAtScanStart: Long,
    changeVersionAtPublication: Long
): Boolean =
    latestRequest && !accessExplicitlyRevoked &&
        grantAtScanStart.hasAny && grantAtPublication.hasAny &&
        grantAtScanStart == grantAtPublication &&
        changeVersionAtScanStart == changeVersionAtPublication

/**
 * One fresh follow-up may be queued for an in-flight MediaStore change.
 * The ViewModel enforces a one-automatic-retry-per-explicit-request cap;
 * grant transitions belong to its separate permission-scope sequencer.
 * Never auto-read after revocation or an unknown/changed grant.
 */
internal fun shouldRetryCompletedGalleryScan(
    latestRequest: Boolean,
    accessExplicitlyRevoked: Boolean,
    grantAtScanStart: GalleryMediaGrant,
    grantAtPublication: GalleryMediaGrant,
    changeVersionAtScanStart: Long,
    changeVersionAtPublication: Long,
    oneRetryStillAvailable: Boolean
): Boolean =
    latestRequest && !accessExplicitlyRevoked && oneRetryStillAvailable &&
        grantAtScanStart.hasAny && grantAtScanStart == grantAtPublication &&
        changeVersionAtScanStart != changeVersionAtPublication
