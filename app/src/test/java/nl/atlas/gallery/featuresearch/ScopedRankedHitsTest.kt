package nl.atlas.gallery.featuresearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedRankedHitsTest {
    private data class Hit(val uri: String, val tier: String)

    @Test fun bestMatchesOnlyIntersectTheCurrentScreenWithoutLosingEngineOrderOrTier() {
        val global = listOf(
            Hit("library-only", "strong"),
            Hit("screen-b", "strong"),
            Hit("screen-a", "possible"),
            Hit("library-only-2", "possible"))
        val screen = listOf("screen-a", "screen-b")
        assertEquals(listOf(global[1], global[2]),
            scopeRankedHits(global, screen, { it.uri }))
        assertTrue(scopeRankedHits(global, emptyList(), { it.uri }).isEmpty())
    }

    @Test fun screenSortChangesOrderButNotMembershipAndNeverPromotesOutsideReference() {
        val global = listOf(Hit("screen-b", "strong"), Hit("outside", "possible"),
            Hit("screen-a", "possible"), Hit("screen-b", "possible"))
        val screen = listOf("screen-a", "screen-b")
        val best = scopeRankedHits(global, screen, { it.uri })
        val sorted = scopeRankedHits(global, screen, { it.uri }, ScopedRankOrder.SCREEN_SORT)
        assertEquals(listOf(global[0], global[2], global[3]), best)
        assertEquals(listOf(global[2], global[0], global[3]), sorted)
        assertEquals(best.toSet(), sorted.toSet())
    }

    @Test fun visualVideoMomentsKeepTheirOwnOrderWhenConstrained() {
        val global = listOf(Hit("video", "frame-2000"),
            Hit("elsewhere", "frame-0"), Hit("video", "frame-8000"))
        assertEquals(listOf(global[0], global[2]),
            scopeRankedHits(global, listOf("video"), { it.uri }))
    }
}
