package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

/** PERF-22: only completed, caller-authorized density plans may be reused. */
class RecentGridDensityPlansScaleTest {
    private val levels = GridDensity.entries
    private data class Plan(
        val publication: Long,
        val viewport: String,
        val density: GridDensity,
        val keys: IntArray
    )

    private fun plan(level: GridDensity, publication: Long = 1, viewport: String = "portrait") =
        Plan(publication, viewport, level, IntArray(250_000) { it })

    @Test fun capacitiesOneTwoThreeEvictPreciselyTheLeastRecentlyUsed() {
        for (capacity in 1..3) {
            val cache = RecentGridDensityPlans<Plan>(capacity)
            val a = plan(levels[0]); val b = plan(levels[1])
            val c = plan(levels[2]); val d = plan(levels[3])
            val candidates = listOf(a, b, c, d)
            val expected = linkedMapOf<GridDensity, Plan>()
            for (candidate in candidates) {
                cache.putCompleted(candidate.density, candidate)
                expected.remove(candidate.density)
                expected[candidate.density] = candidate
                while (expected.size > capacity) expected.remove(expected.keys.first())
                assertEquals(capacity.coerceAtMost(expected.size), cache.retainedCount())
                for (level in levels) assertSame(expected[level], cache.peek(level))
            }
            assertEquals(capacity, cache.retainedCount())
        }
    }

    @Test fun peekDoesNotRefreshLruButGetDoes() {
        val cache = RecentGridDensityPlans<Plan>(2)
        val a = plan(levels[0]); val b = plan(levels[1]); val c = plan(levels[2])
        cache.putCompleted(a.density, a)
        cache.putCompleted(b.density, b)
        repeat(10) { assertSame(a, cache.peek(a.density)) }
        cache.putCompleted(c.density, c)
        assertNull(cache.peek(a.density))
        assertSame(b, cache.get(b.density))
        cache.putCompleted(a.density, a)
        assertNull(cache.peek(c.density))
        assertSame(b, cache.peek(b.density))
        assertSame(a, cache.peek(a.density))
    }

    @Test fun replacingAnExistingKeyRefreshesRecencyWithoutGrowingRetention() {
        val cache = RecentGridDensityPlans<Plan>(2)
        val a = plan(levels[0]); val newerA = plan(levels[0], 2)
        val b = plan(levels[1]); val c = plan(levels[2])
        cache.putCompleted(a.density, a)
        cache.putCompleted(b.density, b)
        cache.putCompleted(newerA.density, newerA)
        assertEquals(2, cache.retainedCount())
        cache.putCompleted(c.density, c)
        assertNull(cache.peek(b.density))
        assertSame(newerA, cache.peek(a.density))
    }

    @Test fun backAndForthAcrossFiveDensitiesNeverKeepsMoreThanCapacity() {
        val order = listOf(3, 4, 7, 12, 28, 12, 7, 4, 3, 4, 7, 12, 28)
            .map(GridDensity::fromColumns)
        for (capacity in 1..3) {
            val cache = RecentGridDensityPlans<Plan>(capacity)
            val expected = LinkedHashMap<GridDensity, Plan>(3, 0.75f, true)
            val all = levels.associateWith { plan(it) }
            repeat(100) {
                for (level in order) {
                    val value = cache.get(level)
                    val oracle = expected[level]
                    assertSame(oracle, value)
                    if (value == null) {
                        val candidate = all.getValue(level)
                        cache.putCompleted(level, candidate)
                        expected[level] = candidate
                        while (expected.size > capacity) expected.keys.iterator().apply {
                            next()
                            remove()
                        }
                    }
                    assertEquals(capacity.coerceAtMost(expected.size), cache.retainedCount())
                    for (density in levels) assertSame(expected.entries.firstOrNull {
                        it.key == density
                    }?.value, cache.peek(density))
                }
            }
        }
    }

    @Test fun latestNullStaleAndFalsePositiveIdentityCannotShadowAuthorizedCache() {
        val current = plan(levels[1], 8, "portrait")
        val oldPublication = plan(levels[1], 7, "portrait")
        val oldViewport = plan(levels[1], 8, "landscape")
        val samePublicationWrongDensity = plan(levels[3], 8, "portrait")
        val valid: (Plan) -> Boolean = {
            it.publication == 8L && it.viewport == "portrait" && it.density == levels[1]
        }
        assertSame(current, currentOrCachedGridPlan(null, current, valid))
        assertSame(current, currentOrCachedGridPlan(oldPublication, current, valid))
        assertSame(current, currentOrCachedGridPlan(oldViewport, current, valid))
        assertSame(current, currentOrCachedGridPlan(samePublicationWrongDensity, current, valid))
        assertNull(currentOrCachedGridPlan(oldPublication, oldViewport, valid))
        assertNull(currentOrCachedGridPlan(null, oldPublication, valid))
        assertSame(current, currentOrCachedGridPlan(current, oldPublication, valid))
    }

    @Test fun rejectedOrInFlightObjectsAreNotAdmittedWithoutPutCompleted() {
        val cache = RecentGridDensityPlans<Plan>(3)
        val inFlight = plan(levels[0])
        val failed = plan(levels[1])
        assertNull(cache.peek(inFlight.density))
        assertNull(cache.peek(failed.density))
        assertEquals(0, cache.retainedCount())
        val completed = plan(levels[2])
        cache.putCompleted(completed.density, completed)
        assertEquals(1, cache.retainedCount())
        assertSame(completed, cache.peek(completed.density))
        // This API cannot distinguish completion: callers must invoke putCompleted
        // only for successfully completed, publication-current plans.
    }

    @Test fun rejectsInvalidCapacities() {
        for (bad in listOf(-1, 0, 4, 100)) {
            assertThrows(IllegalArgumentException::class.java) {
                RecentGridDensityPlans<Plan>(bad)
            }
        }
    }
}
