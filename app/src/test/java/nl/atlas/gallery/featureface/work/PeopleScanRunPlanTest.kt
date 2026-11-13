package nl.atlas.gallery.featureface.work

import nl.atlas.gallery.featureface.work.PeopleScanRunPlan.Decision
import nl.atlas.gallery.featureface.work.PeopleScanRunPlan.Request
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #552: one WorkManager execution keeps running batches while the host asks
 * for the next one now, and hands over (or stops) exactly as the chained
 * WorkRequests did before. The scan position itself is SQLite's.
 */
class PeopleScanRunPlanTest {
    private var now = 1_000L
    private fun plan(maxRunMs: Long = 60_000) = PeopleScanRunPlan(maxRunMs) { now }

    @Test fun nextBatchNowRunsInTheSameExecution() {
        val plan = plan()
        repeat(50) {
            plan.request(Request.NOW)
            now += 1_000
            assertEquals(Decision.CONTINUE, plan.afterBatch(stopped = false))
        }
    }

    @Test fun noRequestMeansTheRunEnded() {
        // Drained queue, paused, revoked or stale scope: the host asks for nothing.
        assertEquals(Decision.STOP, plan().afterBatch(stopped = false))
    }

    @Test fun aRequestIsUsedOnceNotForEveryLaterBatch() {
        val plan = plan()
        plan.request(Request.NOW)
        assertEquals(Decision.CONTINUE, plan.afterBatch(false))
        assertEquals(Decision.STOP, plan.afterBatch(false))
    }

    @Test fun aHoldHandsOverToADelayedRequest() {
        val plan = plan()
        plan.request(Request.LATER)
        assertEquals(Decision.ENQUEUE_LATER, plan.afterBatch(false))
    }

    @Test fun nowWinsWhenBothAreAsked() {
        val plan = plan()
        plan.request(Request.NOW)
        plan.request(Request.LATER)
        assertEquals(Decision.CONTINUE, plan.afterBatch(false))
        val other = plan()
        other.request(Request.LATER)
        other.request(Request.NOW)
        assertEquals(Decision.CONTINUE, other.afterBatch(false))
    }

    @Test fun anExecutionThatUsedItsTimeHandsOverNow() {
        val plan = plan(maxRunMs = 10_000)
        plan.request(Request.NOW)
        now += 9_999
        assertEquals(Decision.CONTINUE, plan.afterBatch(false))
        plan.request(Request.NOW)
        now += 1
        assertEquals(Decision.ENQUEUE_NOW, plan.afterBatch(false))
    }

    @Test fun aClockThatWentBackwardsNeverGrantsAnUnboundedExecution() {
        val plan = plan()
        plan.request(Request.NOW)
        now -= 5_000
        assertEquals(Decision.ENQUEUE_NOW, plan.afterBatch(false))
    }

    /** Stopped by WorkManager (lost constraint: it reschedules) or cancelled: hand over nothing. */
    @Test fun stoppedOrCancelledRunsHandOverNothing() {
        val stopped = plan()
        stopped.request(Request.NOW)
        assertEquals(Decision.STOP, stopped.afterBatch(stopped = true))
        val cancelled = plan()
        cancelled.request(Request.NOW)
        cancelled.cancel()
        assertEquals(Decision.STOP, cancelled.afterBatch(false))
        val later = plan()
        later.request(Request.LATER)
        later.cancel()
        assertEquals(Decision.STOP, later.afterBatch(false))
    }

    @Test fun theExecutionBudgetStaysInsideWorkManagersWindow() {
        assertEquals(8L * 60 * 1000, PeopleScanRunPlan.MAX_RUN_MS)
        try {
            PeopleScanRunPlan(PeopleScanRunPlan.MAX_RUN_MS + 1) { 0L }
            throw AssertionError("an execution longer than the budget was allowed")
        } catch (_: IllegalArgumentException) { }
    }
}
