package nl.atlas.gallery

/**
 * Main-thread-only owner of a single active MediaStore scan's abort action.
 *
 * A selected-photo grant reselection must supersede the in-flight query, not
 * merely queue a replacement behind its entire old 150k-row enumeration.
 * Abort is idempotent for each ticket; finishing that canceled ticket still
 * lets GalleryRefreshQueue start the newest request.
 *
 * The action is ephemeral. No photo records, URIs, or credentials are stored.
 */
internal class GalleryScanSupersession {
    private class Active(
        val ticket: Long,
        val abort: () -> Unit,
        var abortRequested: Boolean = false
    )

    private var active: Active? = null

    fun register(ticket: Long, abort: () -> Unit) {
        require(ticket > 0L) { "Invalid active gallery scan ticket" }
        check(active == null) { "Only one MediaStore scan may be active" }
        active = Active(ticket, abort)
    }

    /**
     * Register ownership BEFORE a lazy coroutine begins executing.
     *
     * Dispatchers.Main.immediate may run the new coroutine synchronously:
     * an empty/null MediaStore cursor, already-canceled scope or immediate
     * failure can reach finally in the same call stack. Registration after
     * launch() would allow cleanup to race ahead and throw.
     */
    fun registerThenStart(ticket: Long, abort: () -> Unit, start: () -> Unit) {
        register(ticket, abort)
        try {
            start()
        } catch (failure: Throwable) {
            // A failed job start must not strand the only scan ownership.
            // The start action may also have finished synchronously.
            if (active?.ticket == ticket) active = null
            throw failure
        }
    }

    /** Called immediately after a newer permission-scope ticket is recorded. */
    fun abortOlderThan(ticket: Long) {
        val current = active ?: return
        if (ticket > current.ticket) abort(current)
    }

    /** ViewModel teardown must also abort any blocked provider query. */
    fun abortAll() {
        active?.let(::abort)
    }

    fun finish(ticket: Long) {
        check(active?.ticket == ticket) { "Wrong active gallery scan completion" }
        active = null
    }

    private fun abort(current: Active) {
        if (current.abortRequested) return
        current.abortRequested = true
        try {
            current.abort()
        } catch (_: Exception) {
            // Android provider cancellation is best-effort. The ViewModel
            // abort callback always cancels its Job in finally, even if the
            // provider's signal throws. Do not let that provider failure
            // escape a permission callback or prevent the queued new scan.
            // Latest-only publication still refuses the superseded ticket.
        }
    }
}
