package nl.atlas.gallery.featureface.work

/**
 * #552: one WorkManager execution runs consecutive bounded batches instead of
 * handing every 8 photos back to WorkManager. Each batch is unchanged (at most
 * 8 photos or 2 s, every guard checked per photo, afterBatch publishes
 * clusters on its cadence). Only the hand-over changes: while this plan is
 * active, the host's "next batch now" is answered by running it here, without
 * a new WorkRequest and the job-dispatch gap that came with it.
 *
 * Pure Kotlin (no Android types) so the decisions are JVM-tested. The scan
 * position itself lives in SQLite (the catalogue's queued/processed states),
 * so a stopped run resumes where it stopped, whichever execution picks it up.
 */
class PeopleScanRunPlan(
    /** Stay well inside WorkManager's 10-minute execution window. */
    private val maxRunMs: Long = MAX_RUN_MS,
    private val clockMs: () -> Long
) {
    enum class Request { NONE, NOW, LATER }

    enum class Decision {
        /** Run the next batch in this execution. */
        CONTINUE,
        /** Hand over to a new WorkRequest now (this execution used its time). */
        ENQUEUE_NOW,
        /** Hand over to a delayed WorkRequest (a hold: thermal, unplugged, busy). */
        ENQUEUE_LATER,
        /** Nothing follows: drained, paused, revoked, cancelled or stopped. */
        STOP
    }

    private val startedMs = clockMs()
    private val lock = Any()
    private var requested = Request.NONE
    private var cancelled = false

    init { require(maxRunMs in 1..MAX_RUN_MS) }

    /** The host asked for its next batch (from inside afterBatch). */
    fun request(next: Request) = synchronized(lock) {
        // A batch asks once; if both come, a delayed retry must not swallow "now".
        if (requested != Request.NOW) requested = next
    }

    /** The host cancelled the unique work (pause, revoke, opt-out). */
    fun cancel() = synchronized(lock) { cancelled = true }

    /**
     * After each batch. [stopped]: WorkManager stopped this worker. It then
     * reschedules the work by itself (lost constraint) or was cancelled, so
     * nothing is handed over in either case.
     */
    fun afterBatch(stopped: Boolean): Decision = synchronized(lock) {
        val next = requested
        requested = Request.NONE
        when {
            stopped || cancelled -> Decision.STOP
            next == Request.NONE -> Decision.STOP
            next == Request.LATER -> Decision.ENQUEUE_LATER
            elapsedMs() >= maxRunMs -> Decision.ENQUEUE_NOW
            else -> Decision.CONTINUE
        }
    }

    private fun elapsedMs(): Long {
        val now = clockMs()
        // A clock that went backwards must not grant an unbounded execution.
        return if (now < startedMs) Long.MAX_VALUE else now - startedMs
    }

    companion object {
        const val MAX_RUN_MS = 8L * 60 * 1000
    }
}
