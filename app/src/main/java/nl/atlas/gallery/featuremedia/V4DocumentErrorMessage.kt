package nl.atlas.gallery

/** Only the operation and broad error class may appear in V4 document UI. */
internal enum class V4DocumentOperation { LINK, IMPORT }

/**
 * Storage Access Framework and SQLite exceptions can include document names,
 * full content URIs, provider IDs, SQL or private CSV records. Never echo a
 * Throwable.message, toString() or nested cause into an on-screen result.
 * Does not alter import/link persistence, transactional behavior or retries.
 */
internal fun v4DocumentFailureMessage(
    operation: V4DocumentOperation, cause: Throwable
): String = when (cause) {
    is SecurityException -> when (operation) {
        V4DocumentOperation.LINK ->
            "Can't keep access to the V4 export. Select it again and allow document access."
        V4DocumentOperation.IMPORT ->
            "Can't access the selected V4 export. Check file access and try again."
    }
    is IllegalArgumentException ->
        "The selected V4 export was invalid or incomplete. Check the export and try again."
    is java.io.IOException ->
        "Couldn't read the selected V4 export. Check the file and try again."
    else -> when (operation) {
        V4DocumentOperation.LINK -> "Couldn't link the V4 export. Try again."
        V4DocumentOperation.IMPORT -> "Couldn't import the V4 export. Try again."
    }
}
