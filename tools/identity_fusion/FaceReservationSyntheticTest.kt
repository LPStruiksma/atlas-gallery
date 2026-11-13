package nl.atlas.gallery.featureface

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    var assertions = 0
    fun verify(ok: Boolean) { check(ok); assertions++ }
    val media = FaceMediaKey("content://media/fictional/1", "r1")
    val lease = FaceScanLease(media,"model-v1","scope-1","token-a")
    val cond = FaceScanConditions(true,true,true,true,true,true,false,false)

    // A producer with a blocked SQLite claim MUST not let another scanner
    // allocate a bitmap before a single-flight detector reservation is released.
    val enteredClaim = CountDownLatch(1)
    val allowClaim = CountDownLatch(1)
    val submitted = CountDownLatch(1)
    val claims = AtomicInteger()
    val decodes = AtomicInteger()
    val releases = AtomicInteger()
    val commits = AtomicInteger()
    val store = object : FaceScanIndexPort {
        override fun claim(permissionEpoch: String): FaceScanLease? {
            claims.incrementAndGet()
            enteredClaim.countDown()
            check(allowClaim.await(5,TimeUnit.SECONDS))
            return lease
        }
        override fun complete(lease: FaceScanLease,observations: List<UnknownFaceBox>):Boolean {
            commits.incrementAndGet(); return true
        }
        override fun release(lease:FaceScanLease):Boolean { releases.incrementAndGet();return true }
        override fun fail(lease:FaceScanLease)=true
    }
    var pending: ((Result<List<FacePixelBounds>>) -> Unit)? = null
    val locator = AsyncFaceLocator({_,cb -> pending=cb; submitted.countDown()},timeoutMillis=30_000)
    val reader = FaceFrameReader {_,_ -> decodes.incrementAndGet(); AndroidBitmapFaceFrame(Bitmap()) }
    val first = FaceScanEngine({"scope-1"},{cond},{true},store,reader,locator)
    val second = FaceScanEngine({"scope-1"},{cond},{true},store,reader,locator)
    var firstResult:FaceScanStepResult? = null
    val thread = Thread { firstResult = first.scanOne("scope-1") }
    thread.start()
    verify(enteredClaim.await(5,TimeUnit.SECONDS))
    // Critical: first worker has not yet allocated pixels, second must not.
    repeat(500) {
        verify(second.scanOne("scope-1").step == FaceScanStep.DEFERRED)
    }
    verify(claims.get() == 1 && decodes.get()==0)
    allowClaim.countDown()
    verify(submitted.await(5,TimeUnit.SECONDS))
    verify(claims.get()==1 && decodes.get()==1)
    pending!!.invoke(Result.success(listOf(FacePixelBounds(10,10,20,20))))
    thread.join(3000)
    verify(!thread.isAlive && firstResult?.step == FaceScanStep.INDEXED)
    verify(commits.get()==1 && releases.get()==0)
    verify(locator.canStart())

    // No queued image: reservation is returned, not left in busy state.
    val empty = object : FaceScanIndexPort {
        override fun claim(permissionEpoch:String):FaceScanLease?=null
        override fun complete(lease:FaceScanLease,observations:List<UnknownFaceBox>)=false
        override fun release(lease:FaceScanLease)=false
        override fun fail(lease:FaceScanLease)=false
    }
    val noPending = FaceScanEngine({"scope-1"},{cond},{true},empty,reader,locator)
    verify(noPending.scanOne("scope-1").step == FaceScanStep.NO_PENDING)
    verify(locator.canStart())

    // Throwing claim must close unused reservation, so a later job isn't starved.
    val exploded = object :FaceScanIndexPort by empty {
        override fun claim(permissionEpoch:String):FaceScanLease? = throw IllegalStateException("synthetic storage fault")
    }
    val broken = FaceScanEngine({"scope-1"},{cond},{true},exploded,reader,locator)
    try { broken.scanOne("scope-1"); error("claim should have failed") }
    catch (_:IllegalStateException) { assertions++ }
    verify(locator.canStart())

    // Decoder failure occurs after admission but before ML Kit submission.
    // The reserved slot must be released and the same worker may retry safely.
    var scanFailures = 0
    var scanReleases = 0
    val failIndex = object : FaceScanIndexPort {
        override fun claim(permissionEpoch: String): FaceScanLease? = lease
        override fun complete(lease:FaceScanLease, observations:List<UnknownFaceBox>) = false
        override fun release(lease:FaceScanLease):Boolean { scanReleases++; return true }
        override fun fail(lease:FaceScanLease):Boolean { scanFailures++; return true }
    }
    val failedDecode = FaceScanEngine({"scope-1"},{cond},{true},failIndex,
        FaceFrameReader {_,_ -> throw java.io.IOException("synthetic decode refusal")},locator)
    verify(failedDecode.scanOne("scope-1").step == FaceScanStep.RETRYABLE_FAILURE)
    verify(scanFailures == 1 && scanReleases == 0 && locator.canStart())

    // Permission scope can change while SQLite returns its claim; the worker
    // must not decode or leak its unused reservation.
    var currentScope = "scope-1"
    var staleReleased = 0
    val revokedIndex = object : FaceScanIndexPort {
        override fun claim(permissionEpoch:String):FaceScanLease? {
            currentScope="revoked"; return lease
        }
        override fun complete(lease:FaceScanLease, observations:List<UnknownFaceBox>) = false
        override fun release(lease:FaceScanLease):Boolean { staleReleased++; return true }
        override fun fail(lease:FaceScanLease)=false
    }
    val currentCheck = FaceScanEngine({currentScope},{cond},{true},revokedIndex,reader,locator)
    val beforeRevocationDecodes=decodes.get()
    verify(currentCheck.scanOne("scope-1").step == FaceScanStep.RELEASED_STALE)
    verify(staleReleased == 1 && decodes.get()==beforeRevocationDecodes && locator.canStart())

    // Used reservation MUST stay busy after timeout until task callback.
    var late: ((Result<List<FacePixelBounds>>) -> Unit)?=null
    val slow = AsyncFaceLocator({_,cb->late=cb}, timeoutMillis=5)
    val reservation=slow.tryReserve()!!
    val stalled=AndroidBitmapFaceFrame(Bitmap())
    val bitmap=stalled.bitmap
    try { reservation.locate(stalled); error("timeout expected") }
    catch (_:FaceInferencePending) { assertions++ }
    reservation.close()
    stalled.close()
    verify(!slow.canStart() && !bitmap.isRecycled)
    verify(slow.tryReserve()==null)
    late!!.invoke(Result.success(emptyList()))
    verify(slow.canStart() && bitmap.isRecycled)

    // A never-used reservation can be closed idempotently to free the slot.
    val unused=slow.tryReserve()!!
    verify(!slow.canStart())
    unused.close(); unused.close()
    verify(slow.canStart())
    println("PASS $assertions synthetic atomic-reservation assertions, including 500 overlapping second-worker attempts")
}
