package nl.atlas.gallery.featureaiedit.provider.hosted

import nl.atlas.gallery.featureaiedit.network.EditHttpResult
import nl.atlas.gallery.featureaiedit.network.EditRequestCancellation
import nl.atlas.gallery.featureaiedit.network.FailureCode
import nl.atlas.gallery.featureaiedit.provider.EditJob
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.JobSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What a hosted job's worker sees: the cancellation token and a progress sink. */
interface HostedWorkContext {
    val cancellation: EditRequestCancellation
    val isCancelled: Boolean get() = cancellation.isCancelled
    fun reportProgress(fraction: Double?)
    /** Sleeps up to [millis]; returns false when the job was cancelled meanwhile. */
    fun pause(millis: Long): Boolean
}

/**
 * Runs one provider exchange off the caller's thread so [poll] never blocks longer than
 * [pollWaitMs] and [cancel] can interrupt the network call from any thread.
 *
 * - `poll()` waits at most `pollWaitMs` for the worker, then reports `Pending`.
 * - `cancel()` is idempotent; afterwards `poll()` reports `Cancelled` at once, the active socket is
 *   closed, and [onCancel] runs on the worker thread (for a provider-side cancel call).
 * - A paid POST is never replayed here; the worker decides what one attempt means.
 */
class HostedEditJob(
    override val id: String,
    private val pollWaitMs: Long,
    executor: Executor?,
    private val work: (HostedWorkContext) -> EditOutcome,
    private val onCancel: (() -> Unit)? = null
) : EditJob {
    private val cancellation = EditRequestCancellation()
    private val done = CountDownLatch(1)
    private val cancelRequested = AtomicBoolean(false)
    private val cancelSignal = CountDownLatch(1)
    @Volatile private var progress: Double? = null
    @Volatile private var result: EditOutcome? = null
    @Volatile private var terminal: JobSnapshot.Completed? = null

    init {
        require(pollWaitMs in 1..60_000)
        val task = Runnable { run() }
        if (executor != null) executor.execute(task)
        else Thread(task, "atlas-ai-edit-$id").apply { isDaemon = true }.start()
    }

    private fun run() {
        val context = object : HostedWorkContext {
            override val cancellation get() = this@HostedEditJob.cancellation
            override fun reportProgress(fraction: Double?) {
                progress = fraction?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
            }
            override fun pause(millis: Long): Boolean {
                if (millis > 0) try {
                    cancelSignal.await(millis, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                return !cancellation.isCancelled
            }
        }
        val outcome = try {
            work(context)
        } catch (_: Exception) {
            EditOutcome.Failed(FailureKind.UNKNOWN, false, "ADAPTER_ERROR")
        }
        result = if (cancellation.isCancelled) EditOutcome.Cancelled else outcome
        done.countDown()
        if (cancellation.isCancelled) runCatching { onCancel?.invoke() }
    }

    override fun poll(): JobSnapshot {
        terminal?.let { return it }
        if (!cancelRequested.get()) {
            try {
                done.await(pollWaitMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(this) {
            terminal?.let { return it }
            if (cancelRequested.get()) return finish(EditOutcome.Cancelled)
            if (done.count == 0L) return finish(result ?: EditOutcome.Cancelled)
        }
        return JobSnapshot.Pending(progress)
    }

    private fun finish(outcome: EditOutcome): JobSnapshot.Completed =
        JobSnapshot.Completed(outcome).also { terminal = it }

    override fun cancel() {
        synchronized(this) {
            if (terminal != null || !cancelRequested.compareAndSet(false, true)) return
        }
        cancellation.cancel()
        cancelSignal.countDown()
    }
}

/**
 * Maps a transport failure to the provider contract with a fixed, code-only message. The `else`
 * branch keeps adapters compiling and conservative when the transport adds codes: unknown codes
 * are never treated as retryable.
 */
fun hostedFailure(result: EditHttpResult.Failed, retryableOverride: Boolean? = null): EditOutcome.Failed {
    val (kind, retryable) = when (result.code) {
        FailureCode.AUTH -> FailureKind.AUTH to false
        FailureCode.QUOTA -> FailureKind.QUOTA to false
        FailureCode.TIMEOUT -> FailureKind.TIMEOUT to true
        FailureCode.NETWORK -> FailureKind.NETWORK to result.retryable
        FailureCode.SERVER -> FailureKind.SERVER to result.retryable
        FailureCode.INVALID_REQUEST -> FailureKind.INVALID_REQUEST to false
        FailureCode.RESPONSE_TOO_LARGE -> FailureKind.SERVER to false
        else -> FailureKind.UNKNOWN to false
    }
    return EditOutcome.Failed(kind, retryableOverride ?: retryable, "HTTP_" + result.code.name)
}
