package nl.atlas.gallery

/**
 * Pure policy for rapid scrubbing/pinching. No provider or Coil calls here.
 * The Compose integrator decides whether a matching cached thumbnail exists.
 */
enum class ThumbnailStage {
    MEMORY_OR_PLACEHOLDER,
    VISIBLE_DECODE,
    VISIBLE_SHARPEN
}

fun chooseThumbnailStage(
    fastGestureActive: Boolean,
    cellSettled: Boolean,
    aPreviewIsVisible: Boolean,
    sharpPreviewAlreadyVisible: Boolean
): ThumbnailStage = when {
    fastGestureActive || !cellSettled || sharpPreviewAlreadyVisible ->
        ThumbnailStage.MEMORY_OR_PLACEHOLDER
    aPreviewIsVisible -> ThumbnailStage.VISIBLE_SHARPEN
    else -> ThumbnailStage.VISIBLE_DECODE
}

/**
 * A recycled cell, newer inventory/access publication, or a newer request
 * makes an old result ineligible even if the provider finishes after cancel.
 * One token per visible cell; never allocate a map for the entire catalogue.
 */
data class ThumbnailBindingTicket(
    val slot: Long,
    val identity: MediaIdentity,
    val catalogueEpoch: Long,
    val sequence: Long
)

fun mayPublishThumbnailResult(
    completed: ThumbnailBindingTicket,
    current: ThumbnailBindingTicket?,
    accessCurrentlyGranted: Boolean
): Boolean = accessCurrentlyGranted && current == completed &&
    completed.catalogueEpoch >= 0L && completed.sequence >= 0L

/**
 * Integration rules:
 * - During quick gestures, keep warm images on screen; never start a cold decode.
 * - Request sharper imagery only after the same binding has settled.
 * - CancellationSignal.cancel() for loadThumbnail or Compose request disposal
 *   is best-effort; always apply mayPublishThumbnailResult on completion.
 */
