package nl.atlas.gallery.featureface

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import java.io.IOException

/** Synthetic API-contract test only; fake API models are NOT a device/emulator. */
fun main() {
    var assertions = 0
    fun verify(ok: Boolean) { check(ok); assertions++ }
    val lease = FaceScanLease(FaceMediaKey("fictional", "r"), "v1", "epoch", "token")
    val reader = AndroidMediaFaceFrameReader(ContentResolver(), {
        Uri.parse("content://media/external/images/media/1")
    })
    val frame = reader.open(lease, 960) as AndroidBitmapFaceFrame
    verify(frame.widthPixels == 960 && frame.heightPixels == 720)
    verify(ImageDecoder.lastDecoder!!.chosenAllocator == ImageDecoder.ALLOCATOR_SOFTWARE)
    verify(frame.bitmap === ImageDecoder.lastBitmap)
    frame.close(); frame.close()
    verify(ImageDecoder.lastBitmap!!.isRecycled)
    try { frame.bitmap; error("closed frame reopened") } catch (_: IllegalStateException) { assertions++ }

    val forbidden = AndroidMediaFaceFrameReader(ContentResolver(), { Uri.parse("file:///fake.jpg") })
    try { forbidden.open(lease,960); error("file URI accepted") }
    catch (_: IllegalArgumentException) { assertions++ }
    ImageDecoder.overrideBitmap = Bitmap(960, 720, 9 * 1024 * 1024)
    try { reader.open(lease, 960); error("oversized decoded bitmap accepted") }
    catch (_: IOException) { assertions++ }
    verify(ImageDecoder.lastBitmap!!.isRecycled)
    ImageDecoder.overrideBitmap = null
    Looper.current = Looper.getMainLooper()
    try { reader.open(lease, 960); error("main-thread decode accepted") }
    catch (_: IllegalStateException) { assertions++ }
    Looper.current = null

    ImageDecoder.fail = SecurityException("fictional revoked grant")
    try { reader.open(lease, 960); error("revocation swallowed") }
    catch (_: SecurityException) { assertions++ }
    ImageDecoder.fail = null
    ImageDecoder.sourceWidth = 16000
    ImageDecoder.sourceHeight = 12000
    val big = reader.open(lease, 960) as AndroidBitmapFaceFrame
    verify(big.widthPixels <= 960 && big.heightPixels <= 960)
    big.close()

    val async = reader.open(lease,960) as AndroidBitmapFaceFrame
    val retained = async.retainForAsyncInference()
    val bitmap = async.bitmap
    async.close()
    verify(!bitmap.isRecycled)
    retained.close(); retained.close()
    verify(bitmap.isRecycled)

    val twice = reader.open(lease,960) as AndroidBitmapFaceFrame
    val h1 = twice.retainForAsyncInference(); val h2 = twice.retainForAsyncInference()
    val held = twice.bitmap
    twice.close(); h2.close()
    verify(!held.isRecycled)
    h1.close()
    verify(held.isRecycled)

    repeat(500) {
        val item = reader.open(lease,960) as AndroidBitmapFaceFrame
        val hold = item.retainForAsyncInference()
        val pixels = item.bitmap
        val a = Thread { item.close() }
        val b = Thread { hold.close(); hold.close() }
        a.start(); b.start(); a.join(); b.join()
        verify(pixels.isRecycled)
    }

    val success = AsyncFaceLocator({ _, cb ->
        cb(Result.success(listOf(FacePixelBounds(1,2,3,4))))
    })
    val yes = reader.open(lease,960) as AndroidBitmapFaceFrame
    verify(success.locate(yes).size == 1)
    yes.close()
    verify(ImageDecoder.lastBitmap!!.isRecycled)

    val failure = AsyncFaceLocator({ _, cb ->
        cb(Result.failure(IOException("synthetic detector exception")))
    })
    val no = reader.open(lease,960) as AndroidBitmapFaceFrame
    try { failure.locate(no); error("failed task reported success") }
    catch (_: IOException) { assertions++ }
    no.close()
    verify(ImageDecoder.lastBitmap!!.isRecycled)

    var pending: ((Result<List<FacePixelBounds>>) -> Unit)? = null
    val slow = AsyncFaceLocator({ _, cb -> pending = cb }, timeoutMillis=5)
    val timed = reader.open(lease,960) as AndroidBitmapFaceFrame
    try { slow.locate(timed); error("missing timeout") } catch (_: FaceInferencePending) { assertions++ }
    val timedPixels = ImageDecoder.lastBitmap!!
    timed.close()
    verify(!timedPixels.isRecycled)
    verify(!slow.canStart())
    // The next scan must not even claim/decode while a previous ML Kit Task
    // holds a bounded bitmap after its timeout.
    var claimsDuringBusy = 0
    var decodesDuringBusy = 0
    val pendingIndex = object : FaceScanIndexPort {
        override fun claim(permissionEpoch: String): FaceScanLease? {
            claimsDuringBusy++
            return lease
        }
        override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>) = true
        override fun release(lease: FaceScanLease) = true
        override fun fail(lease: FaceScanLease) = true
    }
    val pendingEngine = FaceScanEngine({"epoch"}, {
        FaceScanConditions(true,true,true,true,true,true,false,false)
    }, {true}, pendingIndex, FaceFrameReader { l, max ->
        decodesDuringBusy++
        reader.open(l,max)
    }, slow)
    verify(pendingEngine.scanOne("epoch").step == FaceScanStep.DEFERRED)
    verify(claimsDuringBusy == 0 && decodesDuringBusy == 0)
    val competing = reader.open(lease,960) as AndroidBitmapFaceFrame
    try { slow.locate(competing); error("overlap accepted") }
    catch (_: FaceInferenceBusy) { assertions++ }
    competing.close()
    pending!!.invoke(Result.success(emptyList()))
    pending!!.invoke(Result.failure(IOException("duplicate callback")))
    verify(timedPixels.isRecycled && slow.canStart())
    val retry = reader.open(lease,960) as AndroidBitmapFaceFrame
    try { slow.locate(retry); error("missing timeout") } catch (_: FaceInferencePending) { assertions++ }
    pending!!.invoke(Result.success(emptyList()))
    retry.close()

    // A timeout while the real engine owns a photo returns DEFERRED and
    // releases the exact claim, not FAILED or a zero-face processed result.
    var taskDone: ((Result<List<FacePixelBounds>>) -> Unit)? = null
    val timeoutLocator = AsyncFaceLocator({ _, callback -> taskDone = callback }, timeoutMillis=5)
    var timeoutsReleased = 0
    var timeoutsFailed = 0
    var timeoutsCommitted = 0
    val timeoutIndex = object : FaceScanIndexPort {
        override fun claim(permissionEpoch: String) = lease
        override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>): Boolean {
            timeoutsCommitted++; return true
        }
        override fun release(lease: FaceScanLease): Boolean { timeoutsReleased++; return true }
        override fun fail(lease: FaceScanLease): Boolean { timeoutsFailed++; return true }
    }
    val timeoutEngine = FaceScanEngine({"epoch"}, {
        FaceScanConditions(true,true,true,true,true,true,false,false)
    }, {true}, timeoutIndex, reader, timeoutLocator)
    verify(timeoutEngine.scanOne("epoch").step == FaceScanStep.DEFERRED)
    verify(timeoutsReleased == 1 && timeoutsFailed == 0 && timeoutsCommitted == 0)
    verify(timeoutLocator.canStart().not())
    taskDone!!.invoke(Result.success(listOf(FacePixelBounds(0,0,2,2))))
    verify(timeoutLocator.canStart())

    val setup = AsyncFaceLocator({ _, _ -> throw FaceDispatchNotStarted() })
    val failedSetup = reader.open(lease,960) as AndroidBitmapFaceFrame
    try { setup.locate(failedSetup); error("safe prelaunch failure swallowed") }
    catch (_: FaceDispatchNotStarted) { assertions++ }
    failedSetup.close()
    verify(ImageDecoder.lastBitmap!!.isRecycled)

    // Production scan engine must treat a still-running detector as deferred,
    // never as a failed or fully indexed photograph.
    var commits = 0
    var released = 0
    var failures = 0
    val store = object : FaceScanIndexPort {
        var next: FaceScanLease? = lease
        override fun claim(permissionEpoch: String) = next.also { next = null }
        override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>): Boolean {
            commits++; return true
        }
        override fun release(lease: FaceScanLease): Boolean { released++; return true }
        override fun fail(lease: FaceScanLease): Boolean { failures++; return true }
    }
    val ready = FaceScanConditions(true,true,true,true,true,true,false,false)
    val engine = FaceScanEngine({"epoch"}, {ready}, {true}, store, reader,
        OnDeviceFaceLocator { throw FaceInferenceBusy() })
    verify(engine.scanOne("epoch").step == FaceScanStep.DEFERRED)
    verify(released == 1 && failures == 0 && commits == 0)
    verify(ImageDecoder.lastBitmap!!.isRecycled)

    // A cancelled/interrupted WorkManager wait cannot physically recycle the
    // one frame while ML Kit's already-started Task still owns its pixels.
    val submitted = java.util.concurrent.CountDownLatch(1)
    var completionAfterStop: ((Result<List<FacePixelBounds>>) -> Unit)? = null
    val interruptedLocator = AsyncFaceLocator({ _, callback ->
        completionAfterStop = callback
        submitted.countDown()
    }, timeoutMillis = 60_000)
    val interruptedFrame = reader.open(lease, 960) as AndroidBitmapFaceFrame
    val interruptedPixels = interruptedFrame.bitmap
    var sawInterrupt = false
    val worker = Thread {
        try { interruptedLocator.locate(interruptedFrame); error("unwanted completed result") }
        catch (_: InterruptedException) { sawInterrupt = true }
        finally { interruptedFrame.close() }
    }
    worker.start()
    verify(submitted.await(5, java.util.concurrent.TimeUnit.SECONDS))
    worker.interrupt()
    worker.join(2_000)
    verify(sawInterrupt && !worker.isAlive)
    verify(!interruptedPixels.isRecycled)
    completionAfterStop!!.invoke(Result.success(emptyList()))
    verify(interruptedPixels.isRecycled)

    println("PASS $assertions fake-Android checks including 500 cross-thread bitmap lease releases")
}
