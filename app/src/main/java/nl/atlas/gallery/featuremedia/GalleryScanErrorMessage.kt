package nl.atlas.gallery

/**
 * Provider exceptions can embed photo filenames, content URIs, mounted
 * storage identifiers or SQL fragments. Never surface exception.message,
 * cause.toString(), stack traces or media identities through the gallery UI.
 *
 * These messages intentionally reveal only a coarse condition and action.
 * Cancellation is not a failure; GalleryViewModel catches it separately.
 */
internal fun galleryScanFailureMessage(cause: Exception): String = when (cause) {
    is SecurityException ->
        "Photo access was interrupted. Check Photos permission and refresh."
    is IllegalStateException ->
        "Gallery contents changed or became unavailable. Try refreshing."
    else ->
        "Couldn't read the gallery. Try refreshing."
}
