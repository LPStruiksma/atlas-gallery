package nl.atlas.gallery.featureface

fun main() {
    var checks = 0
    fun verify(ok: Boolean) { check(ok); checks++ }
    var now = 100L
    var calls = 0
    val full = FaceScanBatchPolicy({ now }, { calls++; FaceScanStepResult(FaceScanStep.INDEXED, 2) })
    val cap = full.run(FaceBatchBudget(maxPhotos = 8, maxElapsedMs = 2000))
    verify(cap.stop == FaceBatchStop.PHOTO_LIMIT && cap.photosIndexed == 8 && cap.unknownFaceObservations == 16)
    verify(cap.stepsAttempted == 8 && calls == 8)
    calls = 0
    val elapsed = FaceScanBatchPolicy({ now }, { now += 100L; calls++; FaceScanStepResult(FaceScanStep.INDEXED, 0) })
    val timed = elapsed.run(FaceBatchBudget(maxPhotos = 32, maxElapsedMs = 250))
    verify(timed.stop == FaceBatchStop.TIME_LIMIT && timed.photosIndexed == 3 && calls == 3)
    verify(timed.unknownFaceObservations == 0)
    val outcomes = listOf(
        Triple(FaceScanStep.NO_PENDING, FaceBatchStop.NO_PENDING, 0L),
        Triple(FaceScanStep.HELD, FaceBatchStop.HELD, 0L),
        Triple(FaceScanStep.RELEASED_STALE, FaceBatchStop.PERMISSION_STALE, 0L),
        Triple(FaceScanStep.DEFERRED, FaceBatchStop.DETECTOR_PENDING, 5000L),
        Triple(FaceScanStep.RETRYABLE_FAILURE, FaceBatchStop.PHOTO_FAILED, 15000L)
    )
    for ((step, expected, delay) in outcomes) {
        var n = 0
        val run = FaceScanBatchPolicy({ 42L }, { n++; FaceScanStepResult(step) })
            .run(FaceBatchBudget(maxPhotos = 32))
        verify(run.stop == expected && run.retryAfterMs == delay)
        verify(run.stepsAttempted == 1 && run.photosIndexed == 0 && n == 1)
    }
    // If elapsed time never changes, photo count still imposes a hard cap.
    val frozen = FaceScanBatchPolicy({ 12L }, { FaceScanStepResult(FaceScanStep.INDEXED) })
        .run(FaceBatchBudget(maxPhotos = 32))
    verify(frozen.photosIndexed == 32 && frozen.stop == FaceBatchStop.PHOTO_LIMIT)
    var actualTime = 100L
    val reset = FaceScanBatchPolicy({ val t = actualTime; actualTime = 1L; t },
        { error("Must not scan after monotonic clock resets") }).run()
    verify(reset.stop == FaceBatchStop.TIME_LIMIT && reset.stepsAttempted == 0)
    for (invalid in listOf(0, 33, -1)) {
        try { FaceBatchBudget(maxPhotos = invalid); error("accepted invalid photo cap") }
        catch (_: IllegalArgumentException) { checks++ }
    }
    try { FaceBatchBudget(maxElapsedMs = 0); error("accepted invalid time") }
    catch (_: IllegalArgumentException) { checks++ }
    println("PASS $checks bounded batch-policy assertions")
}
