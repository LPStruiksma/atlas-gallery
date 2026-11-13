package nl.atlas.gallery.featuregrid

/**
 * Tiny completed-layout cache for back-and-forth 3/4/7 timeline pinches.
 *
 * Own this instance with Compose remember(source publication, sort, measured
 * viewport, timezone, locale, header height). This deliberately does not
 * retain layouts across MediaStore permission/republication changes.
 *
 * At most two full 150k-item plans stay resident; a third level evicts the
 * least recently used. Never cache in-flight or cancelled computations.
 * The read-only [peek] does not mutate cache recency during composition.
 */
internal class RecentGridDensityPlans<T : Any>(private val capacity: Int = 2) {
    init { require(capacity in 1..3) }

    private val plans = LinkedHashMap<GridDensity, T>(3, 0.75f, true)

    /** Read during a Compose pass without changing access order. O(capacity). */
    fun peek(density: GridDensity): T? =
        plans.entries.firstOrNull { it.key == density }?.value

    /** Used only by the planner effect, to record genuine reuse. */
    fun get(density: GridDensity): T? = plans[density]

    fun putCompleted(density: GridDensity, plan: T) {
        plans[density] = plan
        while (plans.size > capacity) {
            val eldest = plans.keys.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    fun retainedCount(): Int = plans.size
}


/**
 * Select the first *currently authorized* completed plan. A previous-density
 * asynchronous publication must not shadow a ready cache hit. The caller owns
 * the publication/viewport/slot predicate; no catalogue traversal is needed.
 */
internal inline fun <T : Any> currentOrCachedGridPlan(
    latest: T?,
    cached: T?,
    isCurrent: (T) -> Boolean
): T? = latest?.takeIf(isCurrent) ?: cached?.takeIf(isCurrent)
