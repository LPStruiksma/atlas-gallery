package nl.atlas.gallery.featureface

import android.graphics.Bitmap
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

fun main() {
    var assertions = 0
    fun verify(ok: Boolean) { check(ok); assertions++ }
    val clock = AtomicLong(1_000_000_000L)
    val started = FaceMediaKey("content://media/fictional/image1", "rev1")
    val lease = FaceScanLease(started, "fake-model", "epoch1", "fake-token")
    val conditions = FaceScanConditions(true, true, true, true, true, true, false, false)
    var callback: ((Result<List<FacePixelBounds>>) -> Unit)? = null
    var submissions = 0
    var decoded = 0
    var claims = 0
    var returned = 0
    var failures = 0
    var committed = 0
    var lastPixels: Bitmap? = null
    val locator = AsyncFaceLocator({ bitmap, done ->
        submissions++
        callback = done
        lastPixels = bitmap
    }, timeoutMillis=1, stallAfterMillis=1000, monotonicNanos={clock.get()})
    val index = object : FaceScanIndexPort {
        override fun claim(permissionEpoch: String): FaceScanLease { claims++; return lease }
        override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>): Boolean {
            committed++
            return true
        }
        override fun release(lease: FaceScanLease): Boolean { returned++; return true }
        override fun fail(lease: FaceScanLease): Boolean { failures++; return true }
    }
    val reader = FaceFrameReader { _, _ -> decoded++; AndroidBitmapFaceFrame(Bitmap()) }
    val engine = FaceScanEngine({"epoch1"}, {conditions}, {true}, index, reader, locator)
    verify(engine.scanOne("epoch1").step == FaceScanStep.DEFERRED)
    verify(submissions==1 && claims==1 && decoded==1 && returned==1 && failures==0)
    verify(lastPixels!=null && !lastPixels!!.isRecycled)
    clock.set(1_999_999_999L) // 999.999ms, pending but not yet stalled
    verify(engine.scanOne("epoch1").step == FaceScanStep.DEFERRED)
    verify(claims==1 && decoded==1 && failures==0)
    clock.set(2_000_000_000L) // exact 1000ms health threshold
    verify(engine.scanOne("epoch1").step == FaceScanStep.MODEL_UNAVAILABLE)
    verify(claims==1 && decoded==1 && failures==0 && committed==0)
    verify(!locator.canStart() && !lastPixels!!.isRecycled)
    val batch = FaceScanBatchPolicy({100L}, {engine.scanOne("epoch1")}).run()
    verify(batch.stop==FaceBatchStop.DETECTOR_STALLED && batch.retryAfterMs==0L)
    verify(batch.stepsAttempted==1 && claims==1 && decoded==1)
    // No external worker can revive a stalled model while it retains live input.
    try { locator.locate(AndroidBitmapFaceFrame(Bitmap())); error("busy model admitted") }
    catch (_: FaceInferenceStalled) { assertions++ }
    verify(!lastPixels!!.isRecycled)
    // Late callback only releases its own buffer; late output must NOT commit.
    callback!!(Result.success(listOf(FacePixelBounds(1,1,2,2))))
    verify(lastPixels!!.isRecycled && locator.canStart())
    verify(committed==0 && failures==0)
    // Model can start again only after the real task completion callback.
    val recovery = AsyncFaceLocator({_,done -> done(Result.success(emptyList()))},
        timeoutMillis=1,stallAfterMillis=1000,monotonicNanos={clock.get()})
    val recovered = FaceScanEngine({"epoch1"},{conditions},{true},index,reader,recovery)
    verify(recovered.scanOne("epoch1").step == FaceScanStep.INDEXED)
    verify(committed==1 && failures==0)
    for (args in listOf(1L to 999L, 1000L to 1000L, 60000L to 60000L)) {
        try { AsyncFaceLocator({_,_ -> }, timeoutMillis=args.first, stallAfterMillis=args.second) ; error("bad budget") }
        catch (_: IllegalArgumentException) { assertions++ }
    }
    // Monotonic clock reversal is a terminal safety fault, never an endless defer.
    val resetClock = AtomicLong(1000L)
    var delayed: ((Result<List<FacePixelBounds>>) -> Unit)? = null
    val stalled = AsyncFaceLocator({_,done -> delayed=done },
        timeoutMillis=1,stallAfterMillis=1000,monotonicNanos={resetClock.get()})
    try { stalled.locate(AndroidBitmapFaceFrame(Bitmap())); error("deadline") }
    catch (_: FaceInferencePending) { assertions++ }
    resetClock.set(0L)
    try { stalled.tryReserve(); error("clock reset permitted") }
    catch (_: FaceInferenceStalled) { assertions++ }
    delayed!!(Result.failure(IOException("fake completion")))
    verify(stalled.canStart())
    // A rejected completion-listener registration may throw AFTER native Task
    // launch. We must not recycle the bitmap simply because submit() threw.
    var ambiguousCallback: ((Result<List<FacePixelBounds>>) -> Unit)? = null
    var submittedPixels: Bitmap? = null
    val uncertain = AsyncFaceLocator({ bmp, done ->
        ambiguousCallback = done
        submittedPixels = bmp
        throw IllegalStateException("synthetic executor rejected callback registration")
    }, timeoutMillis=1, stallAfterMillis=1000, monotonicNanos={clock.get()})
    val uncertainFrame = AndroidBitmapFaceFrame(Bitmap())
    try { uncertain.locate(uncertainFrame); error("postlaunch failure leaked") }
    catch (_: FaceInferencePending) { assertions++ }
    uncertainFrame.close()
    verify(submittedPixels != null && !submittedPixels!!.isRecycled)
    verify(!uncertain.canStart() && uncertain.tryReserve()==null)
    clock.addAndGet(1_000_000_000L)
    try { uncertain.tryReserve(); error("unconfirmed Task regained admission") }
    catch (_: FaceInferenceStalled) { assertions++ }
    ambiguousCallback!!(Result.failure(IOException("synthetic task ended")))
    verify(submittedPixels!!.isRecycled && uncertain.canStart())

    // Only an explicitly CERTIFIED prelaunch error may clean up immediately.
    val safeReject = AsyncFaceLocator({_,_ -> throw FaceDispatchNotStarted() },
        timeoutMillis=1, stallAfterMillis=1000, monotonicNanos={clock.get()})
    val safeFrame = AndroidBitmapFaceFrame(Bitmap())
    val safePixels = safeFrame.bitmap
    try { safeReject.locate(safeFrame); error("safe prelaunch rejection missing") }
    catch (_: FaceDispatchNotStarted) { assertions++ }
    safeFrame.close()
    verify(safeReject.canStart() && safePixels.isRecycled)

    println("PASS $assertions deterministic hung detector / recovery / no-busy-loop assertions")
}
