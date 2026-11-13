package nl.atlas.gallery

/**
 * End-of-video is not a useful resume checkpoint. A completed video should
 * open at its beginning next time; a genuinely paused clip should return to
 * the last watched position. durationMs may be Media3 TIME_UNSET (<0).
 */
internal fun viewerPlaybackCheckpoint(
    currentMs: Long, durationMs: Long, isEnded: Boolean
): Long {
    if (isEnded || currentMs <= 0L) return 0L
    if (durationMs > 0L && currentMs >= durationMs - 500L.coerceAtMost(durationMs)) return 0L
    return currentMs
}

/** Ephemeral per-viewer video positions, not persisted, logged or synchronized. */
internal class ViewerPlaybackPositions(private val capacity: Int = 32) {
    init { require(capacity in 1..256) }
    private val positions = object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > capacity
    }
    fun get(uri: String): Long = positions[uri] ?: 0L
    /** The saved position, or null when this video was not played in this viewer. */
    fun find(uri: String): Long? = positions[uri]
    fun save(uri: String, positionMs: Long) {
        if (uri.isNotEmpty() && positionMs >= 0) positions[uri] = positionMs
    }
    fun size(): Int = positions.size
}
