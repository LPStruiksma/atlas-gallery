package nl.atlas.gallery.featuregrid

/**
 * A usable ratio row needs positive image space after *measured physical*
 * horizontal padding and its minimum separator. The old UI checked an
 * unrelated 8px constant, then RatioLayoutOptions threw on a 9–30px viewport
 * at high device density or in a narrow fold/split layout.
 *
 * Long arithmetic prevents a bogus "valid" result if hostile dimensions or
 * inset values would overflow the old Int multiplication/addition.
 */
internal fun canPlanRatioViewport(
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    horizontalInsetPx: Int,
    gapPx: Int
): Boolean =
    viewportHeightPx > 0 && horizontalInsetPx >= 0 && gapPx >= 0 &&
        viewportWidthPx.toLong() > 2L * horizontalInsetPx + gapPx
