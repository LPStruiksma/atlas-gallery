package nl.atlas.gallery.featureface

import java.io.IOException
import java.util.concurrent.CancellationException

private class FakeIndex : FaceScanIndexPort {
    var next: FaceScanLease? = FaceScanLease(FaceMediaKey("content://fictional/images/1", "rev1"), "mock-v1", "p1", "lease-1")
    var stored: List<UnknownFaceBox> = emptyList()
    var failures = 0
    var allowFailure = true
    var released = 0
    var commits = 0
    override fun claim(permissionEpoch: String) = next.also { next = null }
    override fun complete(lease: FaceScanLease, observations: List<UnknownFaceBox>): Boolean {
        commits++
        stored = observations
        return true
    }
    override fun release(lease: FaceScanLease): Boolean { released++; return true }
    override fun fail(lease: FaceScanLease): Boolean { failures++; return allowFailure }
}
private class Frame(override val widthPixels: Int = 960, override val heightPixels: Int = 720) : BoundedFaceFrame {
    var closed = false
    var closeFailure: IOException? = null
    override fun close() { closed = true; closeFailure?.let { throw it } }
}
private fun normal() = FaceScanConditions(true, true, true, true, true, false, false, false)

fun main() {
    var assertions = 0
    fun pass(cond: Boolean) { check(cond); assertions++ }
    var epoch = "p1"
    var conditions = normal()
    var current = true
    var sourceError: Exception? = null
    var sourceLookupCount = 0
    var cancelOnSourceLookup = -1
    var openError: Exception? = null
    var pixels = Frame()
    var boxes = listOf(FacePixelBounds(50, 90, 250, 320), FacePixelBounds(1, 1, 20, 20))
    var detection: () -> List<FacePixelBounds> = { boxes }
    val index = FakeIndex()
    fun runner() = FaceScanEngine({ epoch }, { conditions }, {
        sourceLookupCount++
        if (sourceLookupCount == cancelOnSourceLookup) {
            throw CancellationException("fictional grant cancellation during decode")
        }
        sourceError?.let { throw it }; current
    }, index,
        FaceFrameReader { _, max -> pass(max == 960); openError?.let { throw it }; pixels }, OnDeviceFaceLocator { detection() })

    val first = runner().scanOne("p1")
    pass(first.step == FaceScanStep.INDEXED && first.observations == 2)
    pass(index.stored.size == 2 && index.stored.first().left == 1f / 960f)
    pass(pixels.closed)
    pass(index.commits == 1 && index.failures == 0)

    // Permission changed while detector is processing: never publish or count an error.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/2", "rev2"), "mock-v1", "p1", "lease-2")
    pixels = Frame()
    detection = { epoch = "p2"; boxes }
    pass(runner().scanOne("p1").step == FaceScanStep.RELEASED_STALE)
    pass(index.released == 1 && index.commits == 1 && pixels.closed)

    // A paused scan cannot claim or decode.
    epoch = "p1"
    conditions = normal().copy(pausedByUser = true)
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/3", "rev1"), "mock-v1", "p1", "lease-3")
    pass(runner().scanOne("p1").step == FaceScanStep.HELD)
    pass(index.next != null)

    // Content edit while inference runs invalidates any output.
    conditions = normal()
    current = false
    detection = { boxes }
    pass(runner().scanOne("p1").step == FaceScanStep.RELEASED_STALE)
    pass(index.commits == 1 && index.released == 2)

    // Failed inference never becomes zero-face 'processed'.
    current = true
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/4", "rev1"), "mock-v1", "p1", "lease-4")
    detection = { throw FaceScanIoFailure("fake filename secret") }
    pixels = Frame()
    pass(runner().scanOne("p1").step == FaceScanStep.RETRYABLE_FAILURE)
    pass(index.failures == 1 && index.commits == 1 && pixels.closed)

    // Modified source discovered before a decode must avoid opening a frame at all.
    current = false
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/5", "rev1"), "mock-v1", "p1", "lease-5")
    pixels = Frame()
    pass(runner().scanOne("p1").step == FaceScanStep.RELEASED_STALE)
    pass(!pixels.closed && index.released == 3)

    // Model throws after permission loss: not an inference failure or completed scan.
    current = true
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/6", "rev1"), "mock-v1", "p1", "lease-6")
    detection = { epoch = "p2"; throw FaceScanIoFailure("revoked") }
    pass(runner().scanOne("p1").step == FaceScanStep.RELEASED_STALE)
    pass(index.failures == 1 && index.released == 4)

    // A successful zero-face pass is not itself a named person or proof of no people.
    epoch = "p1"
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/7", "rev1"), "mock-v1", "p1", "lease-7")
    detection = { emptyList() }
    pass(runner().scanOne("p1") == FaceScanStepResult(FaceScanStep.INDEXED, 0))

    // Closing the decoded frame fails AFTER detection: must not commit the observation.
    val commitsBeforeBadClose = index.commits
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/8", "rev1"), "mock-v1", "p1", "lease-8")
    detection = { boxes }
    pixels = Frame().also { it.closeFailure = IOException("fictional failure") }
    pass(runner().scanOne("p1").step == FaceScanStep.RETRYABLE_FAILURE)
    pass(pixels.closed && index.commits == commitsBeforeBadClose && index.failures == 2)

    // Provider permission loss can throw without having updated the permission observer yet.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/9", "rev1"), "mock-v1", "p1", "lease-9")
    openError = SecurityException("fictional denied")
    pass(runner().scanOne("p1").step == FaceScanStep.RELEASED_STALE)
    pass(index.failures == 2 && index.released == 5)
    openError = null

    // Unchecked model exception must not claim there were zero faces.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/10", "rev1"), "mock-v1", "p1", "lease-10")
    pixels = Frame()
    detection = { throw IllegalStateException("invented model failure") }
    pass(runner().scanOne("p1").step == FaceScanStep.RETRYABLE_FAILURE)
    pass(index.failures == 3 && pixels.closed && index.commits == commitsBeforeBadClose)

    // Cancellation must cleanup and propagate, not be converted to failure or success.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/11", "rev1"), "mock-v1", "p1", "lease-11")
    detection = { throw CancellationException("work cancelled") }
    try { runner().scanOne("p1"); error("cancellation swallowed") }
    catch (_: CancellationException) { assertions++ }
    pass(index.released == 6 && index.failures == 3)

    // MediaStore revision lookup might also be denied, even with a nominal grant.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/12", "rev1"), "mock-v1", "p1", "lease-12")
    sourceError = SecurityException("media grant disappeared")
    pass(runner().scanOne("p1").step == FaceScanStep.RELEASED_STALE)
    pass(index.released == 7 && index.failures == 3)
    sourceError = null

    // Cooperative cancellation can be thrown by MediaStore revision check BEFORE opening.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/13", "rev1"), "mock-v1", "p1", "lease-13")
    sourceError = CancellationException("index metadata access canceled")
    val releasedBefore = index.released
    try { runner().scanOne("p1"); error("metadata cancellation swallowed") }
    catch (_: CancellationException) { assertions++ }
    pass(index.released == releasedBefore + 1 && index.failures == 3)
    sourceError = null

    // An earlier asynchronous ML Kit task is still holding its bitmap:
    // release this claim WITHOUT consuming a failure retry or decoding pixels.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/14", "rev1"), "mock-v1", "p1", "lease-14")
    val retryBudgetBeforeBusy = index.failures
    val releasedBeforeBusy = index.released
    pixels = Frame()
    detection = { throw FaceInferenceBusy() }
    pass(runner().scanOne("p1").step == FaceScanStep.DEFERRED)
    pass(index.released == releasedBeforeBusy + 1 &&
        index.failures == retryBudgetBeforeBusy && pixels.closed)

    // Model was closed after reservation but before process(). This was NOT a
    // photo decode/inference failure, so do not consume a durable retry.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/15", "rev1"),
        "mock-v1", "p1", "lease-15")
    val beforeStopFailures = index.failures
    val beforeStopReleases = index.released
    pixels = Frame()
    detection = { throw FaceDispatchNotStarted() }
    pass(runner().scanOne("p1").step == FaceScanStep.DEFERRED)
    pass(pixels.closed && index.failures == beforeStopFailures &&
        index.released == beforeStopReleases + 1)

    // Out-of-bounds rectangles are clamped; off-frame, >32 and invalid frames fail closed.
    val clipped = FaceGeometry.normalize(100, 100, listOf(FacePixelBounds(-20, 0, 30, 50)))
    pass(clipped.single().left == 0f)
    try { FaceGeometry.normalize(100, 100, listOf(FacePixelBounds(101, 0, 120, 10))); error("accepted") }
    catch (_: IllegalArgumentException) { assertions++ }
    try { FaceGeometry.normalize(100, 100, List(33) { FacePixelBounds(1, 1, 2, 2) }); error("accepted") }
    catch (_: IllegalArgumentException) { assertions++ }
    pass(index.next == null)
    // The permission epoch may be superseded inside X's final retry
    // transaction even if the preflight checks passed. A refused write
    // must NOT surface as a failed photo or ask the UI to retry it.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/16", "rev1"),
        "mock-v1", "p1", "lease-16")
    index.allowFailure = false
    current = true
    epoch = "p1"
    detection = { throw FaceScanIoFailure("fictional model IO failure") }
    pixels = Frame()
    val commitsBeforeRejectedFailure = index.commits
    pass(runner().scanOne("p1").step == FaceScanStep.RELEASED_STALE)
    pass(pixels.closed && index.commits == commitsBeforeRejectedFailure)
    index.allowFailure = true

    // The second permission-revision check runs inside the open frame's
    // use {} block. Cancellation must close its bitmap and return exactly
    // ONE X claim; double release can corrupt stateful lease accounting.
    index.next = FaceScanLease(FaceMediaKey("content://fictional/images/17", "rev1"),
        "mock-v1", "p1", "lease-17")
    sourceLookupCount = 0
    cancelOnSourceLookup = 2
    current = true
    epoch = "p1"
    pixels = Frame()
    val beforeInFrameCancelRelease = index.released
    val beforeInFrameCancelCommit = index.commits
    try { runner().scanOne("p1"); error("in-frame cancellation swallowed") }
    catch (_: CancellationException) { assertions++ }
    pass(pixels.closed && index.released == beforeInFrameCancelRelease + 1)
    pass(index.commits == beforeInFrameCancelCommit)
    cancelOnSourceLookup = -1

    println("PASS $assertions synthetic FaceScanEngine checks; no Android runtime, model or people inference")
}
