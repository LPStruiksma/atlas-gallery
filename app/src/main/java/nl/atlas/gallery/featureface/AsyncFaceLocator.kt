package nl.atlas.gallery.featureface

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Not an inference failure: another ML Kit Task still owns the bitmap budget. */
class FaceInferenceBusy : RuntimeException("On-device face detector has an outstanding task")

/** The original Task is STILL processing after our caller's deadline.
 * Release the SQLite photo lease without consuming one of three retries;
 * the bitmap remains held until the late Task callback releases it.
 */
class FaceInferencePending : RuntimeException("On-device face detector task remains pending")

/** A Task has exceeded the hard health deadline without completion. Do NOT
 * launch another decode or repeatedly wake WorkManager waiting for this Task.
 */
class FaceInferenceStalled : RuntimeException("On-device face detector has stalled")

/** Use only when it is CERTAIN that no asynchronous Task was launched.
 * A thrown callback-registration error AFTER Task launch is NOT this type.
 */
class FaceDispatchNotStarted : RuntimeException("Face detector did not launch")

/**
 * Bridge ML Kit Task callbacks to FaceScanEngine's one-photo blocking API.
 * The integrator maps FaceDetector.process(InputImage.fromBitmap(bitmap,0))
 * to submit(bitmap, callback) and ALWAYS completes callback on success/failure.
 * No Task, model, Internet or Gradle dependency is pulled into this module.
 *
 * Timeouts and worker interruption DO NOT forcibly recycle live input pixels:
 * the task's completion callback releases the retained bitmap and busy gate.
 * Keep a single locator per worker process; never spin/retry while busy.
 */
class AsyncFaceLocator(
    private val submit: (Bitmap, (Result<List<FacePixelBounds>>) -> Unit) -> Unit,
    private val timeoutMillis: Long = 15_000L,
    private val stallAfterMillis: Long = 120_000L,
    private val monotonicNanos: () -> Long = System::nanoTime
) : OnDeviceFaceLocator {
    private val busy = AtomicBoolean(false)
    private val activeTaskStartedNanos = AtomicReference<Long?>(null)

    init {
        require(timeoutMillis in 1L..60_000L)
        require(stallAfterMillis in 1_000L..600_000L && stallAfterMillis > timeoutMillis) {
            "Stall deadline must be longer than the task await timeout"
        }
    }

    /** A timeout is temporarily pending. A truly hung Task is terminal:
     * only its real completion callback can release the retained bitmap.
     * The injected monotonic clock is ONLY for deterministic, fictional tests.
     */
    private fun isStalled(): Boolean {
        val started = activeTaskStartedNanos.get() ?: return false
        val now = monotonicNanos()
        return now < started || now - started >= stallAfterMillis * 1_000_000L
    }

    private fun rejectOverlap(): Nothing {
        if (isStalled()) throw FaceInferenceStalled()
        throw FaceInferenceBusy()
    }

    override fun canStart(): Boolean = !busy.get()

    /** Acquire the single-flight gate before the caller opens any image frame.
     * If the reservation was never used, close releases it immediately. Once
     * inference starts, only the actual callback may release the gate.
     */
    override fun tryReserve(): FaceInferenceReservation? {
        if (!busy.compareAndSet(false, true)) {
            if (isStalled()) throw FaceInferenceStalled()
            return null
        }
        val used = AtomicBoolean(false)
        return object : FaceInferenceReservation {
            override fun locate(frame: BoundedFaceFrame): List<FacePixelBounds> {
                check(used.compareAndSet(false, true)) { "Inference reservation reused" }
                return locateReserved(frame)
            }
            override fun close() {
                if (used.compareAndSet(false, true)) busy.set(false)
            }
        }
    }

    override fun locate(frame: BoundedFaceFrame): List<FacePixelBounds> {
        if (!busy.compareAndSet(false, true)) rejectOverlap()
        return locateReserved(frame)
    }

    private fun locateReserved(frame: BoundedFaceFrame): List<FacePixelBounds> {
        val androidFrame = frame as? AndroidBitmapFaceFrame
            ?: run {
                busy.set(false)
                throw IllegalArgumentException("ML Kit bridge requires a bounded Android frame")
            }
        val lease = try { androidFrame.retainForAsyncInference() }
        catch (_: IllegalStateException) {
            busy.set(false)
            throw FaceDispatchNotStarted() // No task was dispatched.
        } catch (error: Throwable) { busy.set(false); throw error }
        // A concurrent worker stop can close the frame *after* retaining it.
        // Evaluate the bitmap BEFORE calling submit(): if access fails there is
        // provably no native Task, so release the reservation instead of
        // retaining it forever as an ambiguous post-dispatch failure.
        val bitmap = try { androidFrame.bitmap }
        catch (_: IllegalStateException) {
            try { lease.close() } finally { busy.set(false) }
            throw FaceDispatchNotStarted()
        }
        activeTaskStartedNanos.set(monotonicNanos())
        val complete = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        var outcome: Result<List<FacePixelBounds>>? = null
        val callback: (Result<List<FacePixelBounds>>) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) {
                outcome = result
                try { lease.close() }
                finally {
                    activeTaskStartedNanos.set(null)
                    busy.set(false)
                    complete.countDown()
                }
            }
        }
        try { submit(bitmap, callback) }
        catch (error: Throwable) {
            if (error is FaceDispatchNotStarted) {
                // Only a positively known prelaunch failure permits us to
                // recycle input pixels. Caller must NEVER use this exception
                // when detector.process() may already have started a Task.
                if (delivered.compareAndSet(false, true)) {
                    try { lease.close() }
                    finally { activeTaskStartedNanos.set(null); busy.set(false); complete.countDown() }
                }
                throw error
            }
            // Synchronous exceptions can happen *after* detector.process()
            // launches a Task, e.g. callback executor registration rejection.
            // We cannot tell whether native inference is still reading the
            // bitmap: hold its reference and single-flight slot until the
            // actual Task callback or terminal stall. Never recycle here.
            if (delivered.get()) throw error
            throw FaceInferencePending()
        }
        if (!complete.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            // A timed-out Task may still be reading pixels; callback owns cleanup.
            throw FaceInferencePending()
        }
        return checkNotNull(outcome).getOrThrow()
    }
}
