package nl.atlas.gallery

/**
 * Main-thread-owned, bounded single-flight inventory request sequencer.
 *
 * A scope change while an old MediaStore query is running must not be
 * swallowed. Only the latest active ticket may publish photo metadata,
 * and full grant revocation invalidates the old ticket, pauses all queued
 * queries, and prevents stale publication until regrant.
 * No stored filenames or URI data is retained here.
 */
internal class GalleryRefreshQueue {
    private var nextRequest = 0L
    private var active: Long? = null
    private var lastStarted = 0L
    private var accessRevoked = false

    fun request(): Long {
        check(nextRequest < Long.MAX_VALUE) { "Gallery refresh generation exhausted" }
        nextRequest++
        accessRevoked = false
        return nextRequest
    }

    /**
     * All-media access was revoked. Invalidate every in-flight generation
     * without scheduling an unauthorized follow-up provider query.
     * The next explicit request reopens the queue after permission regrant.
     */
    fun pauseForAccessLoss(): Long {
        val ticket = request()
        accessRevoked = true
        return ticket
    }

    fun begin(): Long? {
        if (accessRevoked || active != null || nextRequest <= lastStarted) return null
        lastStarted = nextRequest
        return nextRequest.also { active = it }
    }

    /**
     * Only the still-active newest request may publish metadata or an error.
     * Called on the owning main thread immediately before StateFlow writes.
     * An older successful query MUST NOT expose even a transient previous
     * catalog once a later media-permission request has been recorded.
     */
    fun mayPublish(request: Long): Boolean =
        !accessRevoked && active == request && nextRequest == request

    /**
     * Ownership check for a delayed recovery AFTER the failed scan has
     * relinquished its single-flight slot. Any explicit refresh, regrant,
     * revocation or new active scan makes the old recovery ineligible.
     * No provider data or selected-media identity is stored here.
     */
    fun mayRetryAfterFinished(failedTicket: Long): Boolean =
        failedTicket > 0L && !accessRevoked && active == null &&
            lastStarted == failedTicket && nextRequest == failedTicket

    /** Return true if one or more newer requests arrived while this ran. */
    fun finish(request: Long): Boolean {
        check(active == request) { "Stale gallery refresh completion" }
        active = null
        return nextRequest > request
    }
}
