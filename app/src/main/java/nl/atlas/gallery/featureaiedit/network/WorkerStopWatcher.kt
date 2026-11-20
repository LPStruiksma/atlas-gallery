package nl.atlas.gallery.featureaiedit.network

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Calls [onStop] as soon as [scope] starts cancelling (WorkManager stopping a worker), while the
 * scope's own thread may still be blocked in `HttpURLConnection` I/O.
 *
 * `Job.invokeOnCompletion` is not enough: it fires when the job *completes*, and a job blocked in a
 * socket read cannot complete until that read returns. This child coroutine is cancelled with its
 * parent and resumes on a separate IO thread, so the stop reaches the socket while it is blocked.
 * Call [release] when the guarded work ends normally; [onStop] then never runs.
 */
internal class WorkerStopWatcher(scope: CoroutineScope, private val onStop: () -> Unit) {
    private val released = AtomicBoolean(false)
    private val watcher = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (!released.get()) onStop() }
    }

    fun release() {
        released.set(true)
        watcher.cancel()
    }
}
