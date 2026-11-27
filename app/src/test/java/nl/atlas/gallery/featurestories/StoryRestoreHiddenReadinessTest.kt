package nl.atlas.gallery.featurestories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HIDE-1 (#585): a story restored after an Activity recreate (#451) must
 * survive the window where the Hidden set is still unread (no plan tokens),
 * and must still be dropped when its story is truly gone or changed (an item
 * was hidden). MainActivity withholds the plan tokens until the set is read;
 * these tests pin the playback-state contract that wiring relies on.
 */
class StoryRestoreHiddenReadinessTest {
    private fun media(n: Int) = StoryMedia("content://media/external/images/media/$n", n * 1000L)
    private fun card(vararg items: Int) = StoryCard("on_this_day", "On this day", "",
        StoryKind.ON_THIS_DAY, items.map { media(it) })
    private fun deck(rev: String, vararg cards: StoryCard) = StoryDeck("g1", rev, cards.toList())

    private fun restored(card: StoryCard) = StoryPlaybackState().apply {
        restore(RestoredStoryPosition(card.key, storyFingerprint(card), 1, false))
    }

    @Test fun restoreWaitsWhileNoTokensExistAndIsNotDropped() {
        val state = restored(card(1, 2, 3, 4))
        // Hidden set unread: no tokens, no deck. Nothing settles and nothing is dropped.
        state.settleRestored(null, null, null)
        assertNotNull(state.restored)
        assertFalse("no tokens means nothing will be planned, so no waiting screen",
            state.awaitingDeck(null, null, null))
        assertNull(state.resolvedOrNull(null, null, null))
    }

    @Test fun restoreRebindsOnceTheFilteredDeckIsLive() {
        val original = card(1, 2, 3, 4)
        val state = restored(original)
        state.settleRestored(null, null, null)
        val live = deck("r2", card(1, 2, 3, 4))
        assertEquals(true, state.awaitingDeck(null, "g1", "r2"))
        state.settleRestored(live, "g1", "r2")
        val position = state.position
        assertNotNull(position)
        assertEquals("on_this_day", position!!.storyKey)
        assertEquals(1, position.index)
    }

    @Test fun anEmptyLiveDeckDropsTheRestoreWhichIsWhyTokensMustWaitForTheHiddenSet() {
        val state = restored(card(1, 2, 3, 4))
        state.settleRestored(deck("r2"), "g1", "r2")
        assertNull(state.restored)
        assertNull(state.position)
    }

    @Test fun aStoryWithAHiddenItemIsInvalidatedNotReopenedAtAnotherMoment() {
        val state = restored(card(1, 2, 3, 4))
        // Item 2 was hidden: same key, different items, so the restore is dropped.
        state.settleRestored(deck("r2", card(1, 3, 4)), "g1", "r2")
        assertNull(state.restored)
        assertNull(state.position)
    }

    @Test fun aMissingStoryIsInvalidated() {
        val state = restored(card(1, 2, 3, 4))
        val other = StoryCard("last_month", "Last month", "", StoryKind.LAST_MONTH, listOf(media(9)))
        state.settleRestored(deck("r2", other), "g1", "r2")
        assertNull(state.restored)
        assertNull(state.position)
    }
}
