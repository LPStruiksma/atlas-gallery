package nl.atlas.gallery.featureface

/** One bounded, synchronous slice of indexing; scheduling and IO threads belong to I.
 * A previous asynchronous detector may still hold pixels: never spin on DEFERRED.
 * maxElapsedMs is checked BETWEEN photos, not a hard bound on an individual decode.
 * VS-V1b: [maxPhotos] is a budget of decoded frames; a video spends one unit
 * per keyframe, so one long video ends the slice after it.
 */
data class FaceBatchBudget(
    val maxPhotos: Int = 8,
    val maxElapsedMs: Long = 2_000
) {
    init { require(maxPhotos in 1..32 && maxElapsedMs in 1..60_000) }
}

enum class FaceBatchStop {
    PHOTO_LIMIT, TIME_LIMIT, NO_PENDING, HELD, PERMISSION_STALE,
    DETECTOR_PENDING, PHOTO_FAILED, DETECTOR_STALLED
}

data class FaceBatchResult(
    val photosIndexed: Int,
    val unknownFaceObservations: Int,
    val stepsAttempted: Int,
    val stop: FaceBatchStop,
    /** Suggested minimum retry delay only. I owns real worker/backoff scheduling. */
    val retryAfterMs: Long = 0
) {
    init {
        require(photosIndexed >= 0 && unknownFaceObservations >= 0 && stepsAttempted >= 0)
        require(retryAfterMs >= 0)
    }
}

/** Process multiple images only while individual steps actually succeed.
 * Stop after ONE failure/pending/no-work/permission event, not an unbounded loop.
 * Never turns zero detected faces into 'no humans in the photo'.
 */
class FaceScanBatchPolicy(
    private val clockElapsedMs: () -> Long,
    private val step: () -> FaceScanStepResult
) {
    fun run(budget: FaceBatchBudget = FaceBatchBudget()): FaceBatchResult {
        val start = clockElapsedMs()
        require(start >= 0) { "Monotonic time must be nonnegative" }
        var photos = 0
        var faces = 0
        var calls = 0
        var frameUnits = 0
        while (frameUnits < budget.maxPhotos) {
            val now = clockElapsedMs()
            // Clock reset/reboot means we must NOT run an unbounded work slice.
            if (now < start || now - start >= budget.maxElapsedMs) {
                return FaceBatchResult(photos, faces, calls, FaceBatchStop.TIME_LIMIT)
            }
            val result = step()
            calls++
            when (result.step) {
                FaceScanStep.INDEXED -> {
                    photos++
                    faces += result.observations
                    frameUnits += maxOf(1, result.frames)
                }
                // A typed skip finished its source without faces; move on.
                FaceScanStep.SKIPPED -> frameUnits += maxOf(1, result.frames)
                FaceScanStep.NO_PENDING -> return FaceBatchResult(photos, faces, calls, FaceBatchStop.NO_PENDING)
                FaceScanStep.HELD -> return FaceBatchResult(photos, faces, calls, FaceBatchStop.HELD)
                FaceScanStep.RELEASED_STALE -> return FaceBatchResult(photos, faces, calls, FaceBatchStop.PERMISSION_STALE)
                FaceScanStep.DEFERRED -> return FaceBatchResult(photos, faces, calls, FaceBatchStop.DETECTOR_PENDING, 5_000)
                FaceScanStep.MODEL_UNAVAILABLE -> return FaceBatchResult(photos, faces, calls, FaceBatchStop.DETECTOR_STALLED)
                FaceScanStep.RETRYABLE_FAILURE -> return FaceBatchResult(photos, faces, calls, FaceBatchStop.PHOTO_FAILED, 15_000)
            }
        }
        return FaceBatchResult(photos, faces, calls, FaceBatchStop.PHOTO_LIMIT)
    }
}
