package nl.atlas.gallery

import kotlinx.coroutines.delay

/**
 * The two independent failure paths must use this SAME budget instance.
 * Record the FOLLOW-UP ticket, not the ticket that failed. That follow-up
 * may not schedule another automatic scan, regardless of whether it fails
 * in the exception catch path or the completed-snapshot publication gate.
 * A later genuine explicit request may have its own bounded attempt.
 */
internal class GalleryAutomaticRetryBudget {
    private var automaticallyRequestedTicket: Long? = null

    fun availableFor(currentTicket: Long): Boolean =
        currentTicket > 0L && automaticallyRequestedTicket != currentTicket

    fun recordFollowup(ticket: Long) {
        require(ticket > 0L)
        automaticallyRequestedTicket = ticket
    }
}

/** One bounded provider-settle pause. The real default is cancellable. */
internal const val GALLERY_SNAPSHOT_SETTLE_MILLIS = 2_500L

/** Borrowed from M's narrow failure predicate; I's typed exception remains authoritative. */
internal fun shouldRetryFailedGalleryScan(
    failure: Exception,
    latestRequest: Boolean,
    accessExplicitlyRevoked: Boolean,
    grantAtScanStart: GalleryMediaGrant,
    grantAtFailure: GalleryMediaGrant?,
    oneRetryStillAvailable: Boolean
): Boolean =
    failure is GalleryProviderSnapshotChanged &&
        latestRequest && !accessExplicitlyRevoked && oneRetryStillAvailable &&
        grantAtScanStart.hasAny && grantAtFailure != null &&
        grantAtScanStart == grantAtFailure

/**
 * Wait without occupying the UI thread. Synthetic tests inject a non-sleeping
 * scheduler; production uses kotlinx.coroutines.delay, which is cancellable.
 * The callback MUST revalidate latest queue ticket, live grant and revocation
 * after the pause, never rely on the pre-delay result.
 */
internal suspend fun awaitGallerySnapshotRetrySettle(
    wait: suspend (Long) -> Unit = { millis -> delay(millis) },
    stillEligible: () -> Boolean
): Boolean {
    wait(GALLERY_SNAPSHOT_SETTLE_MILLIS)
    return stillEligible()
}

/** Only ticket identifiers; no photo or provider values are ever logged. */
internal fun galleryScanRetryLogLine(
    event: String,
    parentTicket: Long,
    retryTicket: Long? = null
): String {
    require(event == "settling" || event == "started" || event == "aborted")
    require(parentTicket > 0L)
    require(retryTicket == null || retryTicket > parentTicket)
    return "event=retry_$event ticket=$parentTicket" +
        (retryTicket?.let { " followup=$it" } ?: "") +
        " delay_ms=$GALLERY_SNAPSHOT_SETTLE_MILLIS"
}
