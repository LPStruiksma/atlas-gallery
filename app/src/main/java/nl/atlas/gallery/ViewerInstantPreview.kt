package nl.atlas.gallery

/**
 * PERF-3 (#124): the viewer's first frame.
 *
 * A photo opened from a grid, an album, a People page or the filmstrip
 * usually already has a smaller decode of the same revision in Coil's memory
 * cache (the tile the user just tapped). The viewer shows that bitmap at once
 * as the placeholder of its own 800 px preview, so a tap never shows a black
 * page while the preview decodes. The 800 px preview and then the 2400 px
 * base replace it in the same ContentScale.Fit geometry, so nothing jumps.
 *
 * Only exact presentation keys are borrowed: the same URI, provider version,
 * generation, geometry, publication epoch and editor revision, differing in
 * request size alone. A stale, edited or unauthorized bitmap can therefore
 * never be shown, and media without a known revision (no cache key) get no
 * placeholder. No Android type here, so every rule is JVM-tested.
 */
internal object ViewerInstantPreview {
    /** The viewer page's own preview size (the request the placeholder serves). */
    const val VIEWER_PREVIEW_PIXELS = 800

    /**
     * Sizes other screens request, largest first, so the sharpest cached copy
     * wins. Grid tiles use gridPreviewRequestPixels (multiples of 32 from 96 to
     * 896, or 900); the filmstrip uses 144; covers, People and search use 180,
     * 400 and 512; the shared transition 600 and video posters 700. The
     * viewer's own 800 is left out: that request already reads its own key.
     */
    val CANDIDATE_PIXELS: IntArray = buildList {
        add(900)
        for (px in 896 downTo 96 step 32) add(px)
        addAll(listOf(700, 600, 512, 400, 180, 144))
    }.distinct().filter { it != VIEWER_PREVIEW_PIXELS }.sortedDescending().toIntArray()
}

/**
 * The memory-cache key of the sharpest cached smaller copy of this photo, or
 * null. [keyFor] returns the photo's presentation key at a request size, or
 * null when the photo has no cacheable revision (then nothing is borrowed).
 * At most one key per candidate size is built, and the search stops at the
 * first hit.
 */
internal fun viewerPlaceholderKey(
    keyFor: (Int) -> String?,
    isCached: (String) -> Boolean,
    candidates: IntArray = ViewerInstantPreview.CANDIDATE_PIXELS
): String? {
    for (px in candidates) {
        val key = keyFor(px) ?: return null
        if (isCached(key)) return key
    }
    return null
}

/**
 * The 2400 px base waits 110 ms after a page settles, so a scrub or a run of
 * swipes never starts large decodes it would cancel. The page the viewer
 * opened on has had no motion yet, so its base starts at once.
 */
internal fun viewerLargeDecodeDelayMs(openingPage: Boolean): Long = if (openingPage) 0L else 110L
