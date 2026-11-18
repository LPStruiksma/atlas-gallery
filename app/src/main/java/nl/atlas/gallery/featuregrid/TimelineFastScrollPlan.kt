package nl.atlas.gallery.featuregrid

/**
 * O(1), allocation-free per scrub event. Delegates to the publication's existing media
 * position index so date headers never become fraction targets and no second index is built.
 *
 * The caller must replace this adapter when it publishes a new [GridTimelineIndex].
 * This does not wire any UI component or supply a scrub label.
 */
class TimelineFastScrollPlan(private val index: GridTimelineIndex) {
    /** Null for an empty gallery or non-finite input; finite values are clamped to [0, 1]. */
    fun entryAtFraction(fraction: Float): Int? = index.entryAtFraction(fraction)
}
