package nl.atlas.gallery

/**
 * PERF-9 (#138): what the viewer warms after a swipe settles, so the next
 * swipe lands on a page whose sharp base is already decoded.
 *
 * The page shows an 800 px preview and, once focused and settled, a 2400 px
 * base. Before PERF-9 the neighbour prewarm decoded 800 px under Coil's
 * default key, which the page never reads (its preview uses the presentation
 * key), and nothing warmed the base, so every swipe waited for the 110 ms
 * settle plus a full 2400 px decode before the photo was sharp.
 *
 * Now, in the direction of travel first:
 * - the 2400 px base of the photo one step away on each side, under the same
 *   key the page reads ([viewerBaseMemoryKey]);
 * - the 800 px preview of the photo two steps away on each side, under the
 *   page's presentation key (the pager already composes one page on each
 *   side, which requests its own preview).
 * Videos are skipped: their pages show a poster frame of their own.
 *
 * At most two extra bases are held (one per side), and only when the memory
 * cache can hold several of them next to the grid's tiles
 * ([viewerBasePrefetchAllowed]). A new swipe or a scrub cancels the effect,
 * and with it every request still running. No Android type here.
 */
internal object ViewerSwipePrefetch {
    const val BASE_PIXELS = 2400

    /** Bytes of one decoded 2400 x 2400 ARGB base, the worst case. */
    const val BASE_BYTES: Long = BASE_PIXELS.toLong() * BASE_PIXELS * 4

    /** The memory cache must hold this many worst-case bases to allow base prefetch. */
    const val MIN_BASES_IN_CACHE = 6
}

internal data class ViewerPrefetchStep(val index: Int, val base: Boolean)

/**
 * The ordered prefetch for a settled [focus]. [previousFocus] gives the
 * direction of travel (forward first when it is unknown or equal). [isVideo]
 * is asked only for indices inside the catalogue.
 */
internal fun viewerSwipePrefetchPlan(
    focus: Int, previousFocus: Int, count: Int,
    allowBase: Boolean, isVideo: (Int) -> Boolean
): List<ViewerPrefetchStep> {
    if (count <= 1 || focus !in 0 until count) return emptyList()
    val ahead = if (previousFocus in 0 until count && previousFocus > focus) -1 else 1
    val order = listOf(
        ViewerPrefetchStep(focus + ahead, allowBase),
        ViewerPrefetchStep(focus - ahead, allowBase),
        ViewerPrefetchStep(focus + 2 * ahead, false),
        ViewerPrefetchStep(focus - 2 * ahead, false))
    return order.filter { it.index in 0 until count && !isVideo(it.index) }
}

/** Base prefetch only when the memory cache holds [ViewerSwipePrefetch.MIN_BASES_IN_CACHE] bases. */
internal fun viewerBasePrefetchAllowed(memoryCacheMaxBytes: Long): Boolean =
    memoryCacheMaxBytes >= ViewerSwipePrefetch.BASE_BYTES * ViewerSwipePrefetch.MIN_BASES_IN_CACHE

/**
 * The memory-cache key of a page's 2400 px base. An editor return keeps its
 * revision key (that page bypasses every older copy). Otherwise the photo's
 * presentation key, so a prefetched base is reused only for the same
 * revision; null (no known revision) keeps Coil's own key, as before PERF-9,
 * and such a photo is never prefetched.
 */
internal fun viewerBaseMemoryKey(
    uri: String, forceFresh: Boolean, refreshRevision: Long,
    presentationKey: (Int) -> String?
): String? =
    if (forceFresh) editedPhotoMemoryKey(uri, refreshRevision, ViewerSwipePrefetch.BASE_PIXELS)
    else presentationKey(ViewerSwipePrefetch.BASE_PIXELS)

/**
 * The page's base waits 110 ms after settling unless it opened the viewer
 * (PERF-3) or its base is already in memory (PERF-9): showing a cached bitmap
 * starts no decode, so there is nothing to protect from a quick next swipe.
 */
internal fun viewerBaseDelayMs(openingPage: Boolean, baseCached: Boolean): Long =
    viewerLargeDecodeDelayMs(openingPage || baseCached)
