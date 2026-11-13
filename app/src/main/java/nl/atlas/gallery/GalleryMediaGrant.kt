package nl.atlas.gallery

/**
 * Android 14+ partial-photo selection is a grant scope, not an inventory
 * generation. A user can reselect completely different photos while every
 * permission flag stays identical and MediaStore emits no change callback.
 */
internal data class GalleryMediaGrant(
    val images: Boolean,
    val videos: Boolean,
    val selected: Boolean
) {
    val hasAny: Boolean get() = images || videos || selected
    val selectionScoped: Boolean get() = selected && !(images && videos)
}

/** A selected-photo scope must be re-queried on resume even if flags match. */
internal fun requiresGalleryRescanOnResume(
    previous: GalleryMediaGrant, current: GalleryMediaGrant
): Boolean = current.hasAny && (previous != current || current.selectionScoped)

/**
 * #505: only full access loss closes the open viewer/deck. A narrowed or
 * re-picked grant keeps the user where they were; Android refuses to open
 * any URI that is no longer allowed and the background scan drops it.
 */
@Suppress("UNUSED_PARAMETER")
internal fun invalidatesGallerySnapshotOnResume(
    previous: GalleryMediaGrant, current: GalleryMediaGrant
): Boolean = !current.hasAny

/**
 * After Activity recreation, a retained ViewModel may still hold an inventory
 * from a wider former grant. Before any new lifecycle callback arrives, hide
 * that old catalog for restricted image/video/selected-photo scopes.
 *
 * MAX_VALUE is an intentionally unsatisfiable sentinel: refreshQueue tickets
 * stop strictly below it, and the first successful fresh read replaces this
 * pending threshold with an actual request ticket.
 */
internal fun initialRestrictedMediaRefreshTicket(grant: GalleryMediaGrant): Long =
    if (grant.hasAny && !(grant.images && grant.videos)) Long.MAX_VALUE else 0L

/**
 * Permission bits freshly read by a recreated Activity can equal themselves
 * even though its retained ViewModel still has a paused, zero-access queue.
 * Treat that dormant state as an access transition when any grant now exists.
 */
internal fun needsRetainedGalleryRegrantRecovery(
    current: GalleryMediaGrant, retainedRevoked: Boolean
): Boolean = current.hasAny && retainedRevoked

/**
 * A retained photo scan requested for scope A can still be in flight when
 * Activity recreation or Settings restores scope B. A nonzero pending ticket
 * alone does not prove the scan belongs to B, and matching previously
 * published records do not make the A scan safe to complete under B.
 *
 * Requery when the current scope differs from the latest requested scope,
 * unless no scan was requested and the published catalog already matches.
 * This check is O(1) and never examines any photo.
 */
internal fun needsNewGrantBoundScan(
    current: GalleryMediaGrant,
    published: GalleryMediaGrant?,
    pending: GalleryMediaGrant?
): Boolean = current.hasAny && when {
    pending == current -> false
    pending != null -> true
    else -> published != current
}
