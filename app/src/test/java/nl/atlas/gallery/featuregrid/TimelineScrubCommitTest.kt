package nl.atlas.gallery.featuregrid

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineScrubCommitTest {
    private val original = List(150_000) { "media://$it" }
    private val epoch = GridCatalogIdentity(original, 17L)
    private val layout = Any()
    private val command = TimelineScrubCommit(epoch, layout, 151_231, 151_500)

    @Test fun admitsOnlyCurrentPublicationAndExactlyMountedHeaderSlotCount() {
        assertTrue(command.isCurrent(GridCatalogIdentity(original, 17L), layout, 151_500))
        assertFalse(command.isCurrent(GridCatalogIdentity(original, 18L), layout, 151_500))
        assertFalse(command.isCurrent(GridCatalogIdentity(original.toList(), 17L), layout, 151_500))
    }

    @Test fun regroupedHeaderRowsDoNotAcceptAnOldSlotCoordinate() {
        assertFalse(command.isCurrent(epoch, Any(), 151_500))
        assertFalse(command.isCurrent(epoch, layout, 150_000))
        assertFalse(command.isCurrent(epoch, layout, 151_501))
        assertFalse(command.isCurrent(epoch, null, 151_500))
    }

    @Test fun boundsRejectMalformedAndHeaderOnlyDestinations() {
        assertFalse(command.copy(slotIndex = 151_500).isCurrent(epoch, layout, 151_500))
        assertFalse(command.copy(slotIndex = -1).isCurrent(epoch, layout, 151_500))
        assertFalse(command.copy(expectedSlotCount = 0).isCurrent(epoch, layout, 0))
        assertFalse(command.copy(expectedSlotCount = -1).isCurrent(epoch, layout, -1))
    }

    @Test fun releasedScrubHoldsTargetFractionUntilCurrentGridCommits() {
        val held = command.copy(requestedFraction = .83f)
        assertEquals(.83f, held.heldPreviewFractionOrNull(epoch, layout, 151_500)!!, 0.00001f)
        // A new drag can take precedence; a stale published layout cannot.
        assertNull(held.heldPreviewFractionOrNull(GridCatalogIdentity(original, 18L), layout, 151_500))
        assertNull(held.heldPreviewFractionOrNull(epoch, Any(), 151_500))
        assertNull(held.heldPreviewFractionOrNull(epoch, layout, 151_499))
        assertNull(held.heldPreviewFractionOrNull(epoch, null, 151_500))
    }

    @Test fun invalidOrLegacyPreviewNeverInventsAThumbPosition() {
        assertNull(command.heldPreviewFractionOrNull(epoch, layout, 151_500))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.01f, 1.01f)) {
            assertNull(command.copy(requestedFraction = bad)
                .heldPreviewFractionOrNull(epoch, layout, 151_500))
        }
        assertEquals(0f, command.copy(requestedFraction = 0f)
            .heldPreviewFractionOrNull(epoch, layout, 151_500)!!, 0f)
        assertEquals(1f, command.copy(requestedFraction = 1f)
            .heldPreviewFractionOrNull(epoch, layout, 151_500)!!, 0f)
    }

    @Test fun cacheHitCanAuthorizeSeekBeforeSecondaryPublishedPointerCatchesUp() {
        val cachedLayout = Any()
        val previousLatestPlan = Any()
        val cache = RecentGridDensityPlans<Any>()
        cache.putCompleted(GridDensity.LARGE, cachedLayout)
        // A density flip immediately displays the cached ready plan, while the
        // separate latestPlan state can still point to the previous layout.
        assertFalse(previousLatestPlan === cache.peek(GridDensity.LARGE))
        val seek = command.copy(layoutToken = cachedLayout, requestedFraction = .73f)
        assertTrue(seek.isCurrent(epoch, cache.peek(GridDensity.LARGE), 151_500))
        assertEquals(.73f,
            seek.heldPreviewFractionOrNull(epoch, cache.peek(GridDensity.LARGE), 151_500)!!,
            0f)
        // Neither the previous layout, a changed slot count nor a new grant
        // may be navigated using the cached ready instance.
        assertFalse(seek.isCurrent(epoch, previousLatestPlan, 151_500))
        assertFalse(seek.isCurrent(epoch, cachedLayout, 151_499))
        assertFalse(seek.isCurrent(GridCatalogIdentity(original, 18L), cachedLayout, 151_500))
    }

    @Test fun unchangedSortAndHeaderIndexDoNotRequireLinearMediaSearch() {
        repeat(10_000) {
            assertTrue(command.isCurrent(epoch, layout, 151_500))
        }
    }
}
