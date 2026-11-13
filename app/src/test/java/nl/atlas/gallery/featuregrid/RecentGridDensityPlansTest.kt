package nl.atlas.gallery.featuregrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RecentGridDensityPlansTest {
    @Test fun repeatDensityReturnsSamePlanWithoutRecomputation() {
        val plans = RecentGridDensityPlans<Any>()
        val large = Any()
        val medium = Any()
        plans.putCompleted(GridDensity.LARGE, large)
        plans.putCompleted(GridDensity.MEDIUM, medium)
        repeat(10_000) {
            assertSame(large, plans.peek(GridDensity.LARGE))
            assertSame(medium, plans.peek(GridDensity.MEDIUM))
        }
        assertEquals(2, plans.retainedCount())
    }

    @Test fun thirdLevelEvictsLeastRecentlyUsedButNeverMoreThanTwoPlans() {
        val plans = RecentGridDensityPlans<Any>()
        val large = Any()
        val medium = Any()
        val small = Any()
        plans.putCompleted(GridDensity.LARGE, large)
        plans.putCompleted(GridDensity.MEDIUM, medium)
        assertSame(large, plans.get(GridDensity.LARGE)) // actual reuse touches recency
        plans.putCompleted(GridDensity.SMALL, small)
        assertSame(large, plans.peek(GridDensity.LARGE))
        assertSame(small, plans.peek(GridDensity.SMALL))
        assertNull(plans.peek(GridDensity.MEDIUM))
        assertEquals(2, plans.retainedCount())
    }

    @Test fun compositionPeekCannotExtendLifetimeOfOldLayouts() {
        val plans = RecentGridDensityPlans<Any>()
        plans.putCompleted(GridDensity.LARGE, Any())
        plans.putCompleted(GridDensity.MEDIUM, Any())
        repeat(5000) { plans.peek(GridDensity.LARGE) }
        plans.putCompleted(GridDensity.SMALL, Any())
        assertNull(plans.peek(GridDensity.LARGE)) // peek was read-only
        assertEquals(2, plans.retainedCount())
    }

    @Test fun staleAsyncPlanCannotShadowCurrentlyDisplayedCachedDensity() {
        val cache = RecentGridDensityPlans<Any>()
        val oldAsyncPlan = Any()
        val cachedCurrent = Any()
        cache.putCompleted(GridDensity.SMALL, cachedCurrent)
        // A successful cache hit is already visible while latestPlan still
        // points to the old density; controls must select the same ready plan.
        val selected = currentOrCachedGridPlan(
            oldAsyncPlan, cache.peek(GridDensity.SMALL)
        ) { it === cachedCurrent }
        assertSame(cachedCurrent, selected)
        // On the next frame the asynchronous publication catches up.
        assertSame(cachedCurrent, currentOrCachedGridPlan(
            cachedCurrent, cache.peek(GridDensity.SMALL)
        ) { it === cachedCurrent })
    }

    @Test fun revokedOrRegroupedPlansNeverReachControls() {
        val stale = Any()
        val staleCache = Any()
        assertNull(currentOrCachedGridPlan(stale, staleCache) { false })
        assertNull(currentOrCachedGridPlan<Any>(null, null) { true })
        val current = Any()
        // A newer authorized layout takes precedence over a cached one.
        assertSame(current, currentOrCachedGridPlan(current, staleCache) { it === current })
    }

    @Test fun newPublicationCreatesIndependentCacheAndBadCapacityFails() {
        val old = RecentGridDensityPlans<Any>()
        old.putCompleted(GridDensity.LARGE, Any())
        // The Compose remember key supplies the real catalogue/epoch fence;
        // cache instances deliberately never share a static process-wide map.
        val next = RecentGridDensityPlans<Any>()
        assertNull(next.peek(GridDensity.LARGE))
        assertEquals(0, next.retainedCount())
        assertThrows(IllegalArgumentException::class.java) { RecentGridDensityPlans<Any>(0) }
        assertThrows(IllegalArgumentException::class.java) { RecentGridDensityPlans<Any>(4) }
    }
}
