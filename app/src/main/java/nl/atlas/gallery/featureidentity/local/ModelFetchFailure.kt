package nl.atlas.gallery.featureidentity.local

/**
 * IDS-19 (#619): why a [ModelFetcher] could not deliver bytes. A code only: a fetch failure
 * never carries a URL (signed redirect URLs hold tokens), a header or exception text.
 */
enum class ModelFetchFailure {
    /** No connection, a timeout, a dropped stream or a temporary host error. Worth retrying. */
    NETWORK,
    /** Not an original host, a redirect off the allowlist, or the host refused the file. Not retried. */
    HOST_REFUSED
}

class ModelFetchException(val failure: ModelFetchFailure) : IllegalStateException("Model fetch failed: $failure")
