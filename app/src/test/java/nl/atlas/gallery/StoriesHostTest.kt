package nl.atlas.gallery

import nl.atlas.gallery.featurestories.RestoredStoryPosition
import nl.atlas.gallery.featurestories.StoryKind
import nl.atlas.gallery.featurestories.StoryPlaybackPosition
import nl.atlas.gallery.featurestories.StoryPlaybackState
import nl.atlas.gallery.featurestories.storyFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** E2a (#439) host wiring: synthetic records only, no MediaStore. */
class StoriesHostTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 11, 6)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val full = GalleryMediaGrant(images = true, videos = true, selected = false)

    private fun record(
        id: Long, date: LocalDate, name: String = "IMG_$id.jpg",
        path: String = "DCIM/Camera/", favorite: Boolean = false,
        favoriteKnown: Boolean = true, video: Boolean = false
    ) = MediaRecord(
        id = id, uri = "content://media/external_primary/images/media/$id",
        name = name, relativePath = path, sizeBytes = 1000L,
        takenAtMillis = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
        isVideo = video, isFavorite = favorite, favoriteKnown = favoriteKnown
    )

    private fun onThisDay(): List<MediaRecord> = listOf(
        record(1, today.minusYears(1)), record(2, today.minusYears(2)),
        record(3, today.minusYears(3)), record(4, today.minusYears(4))
    )

    // Mirrors rememberStoryCatalogRevision: one slot keyed by list identity,
    // so a new reference (even swapping back to an older one) mints anew.
    private var lastSource: List<MediaRecord>? = null
    private var lastRevision = 0L
    private fun revisionOf(source: List<MediaRecord>): Long {
        if (source !== lastSource) {
            lastSource = source
            lastRevision = nextStoryCatalogRevision()
        }
        return lastRevision
    }

    private fun tokens(source: List<MediaRecord>, grant: GalleryMediaGrant = full,
        epoch: Long = 7L, day: Long = today.toEpochDay()) =
        requireNotNull(storyPlanTokensOrNull(grant, epoch, true,
            revisionOf(source), day))

    @Test fun mappingKeepsUriDateKindAndOnlyVerifiedFavorites() {
        val mapped = storyMediaFromAuthorized(listOf(
            record(1, today, favorite = true),
            record(2, today, favorite = true, favoriteKnown = false),
            record(3, today, video = true)))
        assertEquals(listOf(record(1, today).uri, record(2, today).uri, record(3, today).uri),
            mapped.map { it.uri })
        assertEquals(record(1, today).takenAtMillis, mapped[0].takenAtMillis)
        assertEquals(listOf(true, false, false), mapped.map { it.isFavorite })
        assertEquals(listOf(false, false, true), mapped.map { it.isVideo })
        assertTrue(mapped.none { it.isHidden || it.isTrashed })
    }

    @Test fun mappingFlagsTrashedPendingAndDotFolderRows() {
        val mapped = storyMediaFromAuthorized(listOf(
            record(1, today, name = ".trashed-1767225600-IMG_1.jpg"),
            record(2, today, name = ".pending-1767225600-IMG_2.jpg"),
            record(3, today, path = "Pictures/.private/"),
            record(4, today)))
        assertEquals(listOf(true, false, false, false), mapped.map { it.isTrashed })
        assertEquals(listOf(false, true, true, false), mapped.map { it.isHidden })
    }

    @Test fun hiddenAndTrashedRowsNeverReachAStory() {
        val source = onThisDay().take(2) + listOf(
            record(5, today.minusYears(5), name = ".trashed-1767225600-IMG_5.jpg"),
            record(6, today.minusYears(6), path = "Pictures/.hidden/"))
        val deck = planStoryDeck(source, tokens(source), now, zone)
        // Only two visible moments remain: below On this day's threshold of three.
        assertTrue(deck.cards.isEmpty())

        val withThird = source + record(7, today.minusYears(7))
        val card = planStoryDeck(withThird, tokens(withThird), now, zone).cards.single()
        assertEquals(StoryKind.ON_THIS_DAY, card.kind)
        assertEquals(setOf(1L, 2L, 7L).map { record(it, today).uri }.toSet(),
            card.items.map { it.uri }.toSet())
    }

    @Test fun tokensRequireAuthorizationAndChangeWithGrantCatalogueAndDay() {
        val source = onThisDay()
        val revision = revisionOf(source)
        assertNull(storyPlanTokensOrNull(full, 7L, false, revision, 1L))
        assertNull(storyPlanTokensOrNull(GalleryMediaGrant(false, false, false), 7L, true, revision, 1L))
        assertNull(storyPlanTokensOrNull(full, -1L, true, revision, 1L))
        val base = tokens(source)
        assertEquals(base, tokens(source))
        val selectedOnly = GalleryMediaGrant(images = false, videos = false, selected = true)
        assertNotEquals(base.grantEpoch, tokens(source, grant = selectedOnly).grantEpoch)
        assertNotEquals(base.grantEpoch, tokens(source, epoch = 8L).grantEpoch)
        assertNotEquals(base.catalogRevision, tokens(source.toList()).catalogRevision)
        assertNotEquals(base.catalogRevision, tokens(source, day = today.toEpochDay() + 1L).catalogRevision)
    }

    @Test fun sameSizeSameEpochListSwapRePlans() {
        // #444: the old revision hashed identity + size + epoch, which can
        // collide when the host swaps in another list of the same size.
        val source = onThisDay()
        val first = tokens(source)
        val deck = planStoryDeck(source, first, now, zone)
        assertFalse(storyDeckNeedsPlan(deck, tokens(source)))

        // Equal content, same size, same epoch, same day: a different list re-plans.
        val equalCopy = ArrayList(source)
        val copyTokens = tokens(equalCopy)
        assertEquals(first.grantEpoch, copyTokens.grantEpoch)
        assertNotEquals(first.catalogRevision, copyTokens.catalogRevision)
        assertTrue(storyDeckNeedsPlan(deck, copyTokens))
        assertTrue(currentStoryCards(deck, copyTokens).isEmpty())

        // Different content of the same size re-plans too, and the new deck holds it.
        val swapped = source.dropLast(1) + record(9, today.minusYears(9))
        assertEquals(source.size, swapped.size)
        val swappedTokens = tokens(swapped)
        assertTrue(storyDeckNeedsPlan(deck, swappedTokens))
        val replanned = planStoryDeck(swapped, swappedTokens, now, zone)
        assertFalse(storyDeckNeedsPlan(replanned, swappedTokens))
        assertTrue(currentStoryCards(replanned, swappedTokens).single().items
            .any { it.uri == record(9, today).uri })

        // Swapping back to the first list never reuses its old revision.
        assertNotEquals(first.catalogRevision, tokens(source).catalogRevision)
    }

    @Test fun separateHostsNeverShareARevision() {
        val a = nextStoryCatalogRevision()
        val b = nextStoryCatalogRevision()
        assertNotEquals(a, b)
    }

    @Test fun grantChangeRePlansAndHidesTheStaleDeck() {
        val source = onThisDay()
        val first = tokens(source)
        assertTrue(storyDeckNeedsPlan(null, first))
        val deck = planStoryDeck(source, first, now, zone)
        assertEquals(1, currentStoryCards(deck, first).size)
        // Same tokens on a later recomposition: no new planning pass.
        assertFalse(storyDeckNeedsPlan(deck, tokens(source)))

        // Replaced grant (new publication + selected-photos scope): stale deck hidden, re-plan.
        val replaced = tokens(source, GalleryMediaGrant(false, false, true), epoch = 8L)
        assertTrue(storyDeckNeedsPlan(deck, replaced))
        assertTrue(currentStoryCards(deck, replaced).isEmpty())
        // Revoked: no tokens, nothing to plan, nothing shown.
        assertFalse(storyDeckNeedsPlan(deck, null))
        assertTrue(currentStoryCards(deck, null).isEmpty())

        val replanned = planStoryDeck(source, replaced, now, zone)
        assertFalse(storyDeckNeedsPlan(replanned, replaced))
        assertEquals(1, currentStoryCards(replanned, replaced).size)
    }

    @Test fun viewerOpenRechecksLiveTokensDeckAndSource() {
        val source = onThisDay()
        val live = tokens(source)
        val deck = planStoryDeck(source, live, now, zone)
        val uri = deck.cards.single().items.first().uri
        assertEquals(uri, authorizedStoryViewerUriOrNull(uri, deck, live, source))
        assertNull(authorizedStoryViewerUriOrNull(uri, deck, null, source))
        assertNull(authorizedStoryViewerUriOrNull(uri, deck, tokens(source, epoch = 9L), source))
        assertNull(authorizedStoryViewerUriOrNull(uri, deck, live,
            source.filterNot { it.uri == uri }))
        assertNull(authorizedStoryViewerUriOrNull(
            "content://media/external_primary/images/media/999", deck, live, source))
        assertNotNull(deck)
    }

    @Test fun cancellationCheckpointRunsDuringMappingAndPlanning() {
        val source = (1L..3000L).map { record(it, today.minusYears(1 + it % 5)) }
        var checks = 0
        planStoryDeck(source, tokens(source), now, zone) { checks++ }
        assertTrue("checkpoint should run while mapping and planning, ran $checks", checks >= 6)
    }

    /**
     * #451: what a recreate keeps is the story identity and moment, never the
     * tokens. It re-binds only to a deck planned for the live tokens that still
     * holds the same story with the same items; otherwise the grid shows.
     */
    @Test fun restoredPlaybackRebindsOnlyToALiveDeckWithTheSameStory() {
        val source = onThisDay()
        val before = tokens(source)
        val deck = planStoryDeck(source, before, now, zone)
        val card = deck.cards.single()
        val open = StoryPlaybackState()
        open.position = StoryPlaybackPosition(before.grantEpoch, before.catalogRevision,
            card.key, index = 1, playing = false)
        val saved = requireNotNull(open.savedOrNull(deck))
        assertEquals(RestoredStoryPosition(card.key, storyFingerprint(card), 1, false), saved)
        // A stale deck cannot fingerprint the open story: nothing is saved.
        assertNull(open.savedOrNull(planStoryDeck(source, tokens(source, epoch = 9L), now, zone)))

        // Recreate: the same records come back under a new catalogue revision.
        val after = tokens(source.toList())
        val replanned = planStoryDeck(source, after, now, zone)
        val restored = StoryPlaybackState().apply { restore(saved) }
        assertEquals(saved, restored.savedOrNull(null))
        // No live deck yet (or a deck for older tokens): wait, show nothing.
        assertNull(restored.resolvedOrNull(null, after.grantEpoch, after.catalogRevision))
        assertNull(restored.resolvedOrNull(deck, after.grantEpoch, after.catalogRevision))
        restored.settleRestored(deck, after.grantEpoch, after.catalogRevision)
        assertEquals(saved, restored.restored)
        // Live deck with the same story: back on moment 2, still paused.
        val expected = StoryPlaybackPosition(after.grantEpoch, after.catalogRevision,
            card.key, 1, false)
        assertEquals(expected, restored.resolvedOrNull(replanned, after.grantEpoch,
            after.catalogRevision))
        restored.settleRestored(replanned, after.grantEpoch, after.catalogRevision)
        assertNull(restored.restored)
        assertEquals(expected, restored.currentOrNull(after.grantEpoch, after.catalogRevision))
    }

    @Test fun restoredPlaybackFallsBackToTheGridWhenTheStoryIsGoneOrChanged() {
        val source = onThisDay()
        val before = tokens(source)
        val card = planStoryDeck(source, before, now, zone).cards.single()
        val saved = RestoredStoryPosition(card.key, storyFingerprint(card), 2, true)

        // Same key, other moments (one photo left the library): not the same story.
        val changed = source.filterNot { it.uri == card.items.last().uri } +
            record(9, today.minusYears(9))
        val changedTokens = tokens(changed)
        val changedDeck = planStoryDeck(changed, changedTokens, now, zone)
        assertEquals(card.key, changedDeck.cards.single().key)
        val a = StoryPlaybackState().apply { restore(saved) }
        assertNull(a.resolvedOrNull(changedDeck, changedTokens.grantEpoch,
            changedTokens.catalogRevision))
        a.settleRestored(changedDeck, changedTokens.grantEpoch, changedTokens.catalogRevision)
        assertNull(a.restored)
        assertNull(a.position)

        // The story no longer exists at all.
        val gone = listOf(record(1, today.minusDays(3)))
        val goneTokens = tokens(gone)
        val goneDeck = planStoryDeck(gone, goneTokens, now, zone)
        val b = StoryPlaybackState().apply { restore(saved) }
        b.settleRestored(goneDeck, goneTokens.grantEpoch, goneTokens.catalogRevision)
        assertNull(b.restored)
        assertNull(b.position)

        // Any explicit change (tab switch, close) also drops a pending restore.
        val c = StoryPlaybackState().apply { restore(saved) }
        c.position = null
        assertNull(c.restored)
    }

    /**
     * #457: while a restored position waits for the re-planned deck the screen
     * shows a planning placeholder (never without live tokens, never once a
     * live deck is there), and closing Stories drops the pending restore.
     */
    @Test fun pendingRestoreWaitsOnlyForALiveDeckAndCloseDropsIt() {
        val source = onThisDay()
        val before = tokens(source)
        val card = planStoryDeck(source, before, now, zone).cards.single()
        val saved = RestoredStoryPosition(card.key, storyFingerprint(card), 1, false)
        val after = tokens(source.toList())
        val stale = planStoryDeck(source, before, now, zone)
        val live = planStoryDeck(source, after, now, zone)

        val waiting = StoryPlaybackState().apply { restore(saved) }
        assertTrue(waiting.awaitingDeck(null, after.grantEpoch, after.catalogRevision))
        assertTrue(waiting.awaitingDeck(stale, after.grantEpoch, after.catalogRevision))
        // No live tokens: nothing will be planned, so no endless placeholder.
        assertFalse(waiting.awaitingDeck(null, null, null))
        // A live deck has arrived: the player or the grid shows, not the placeholder.
        assertFalse(waiting.awaitingDeck(live, after.grantEpoch, after.catalogRevision))
        // Nothing pending: never the placeholder.
        assertFalse(StoryPlaybackState().awaitingDeck(null, after.grantEpoch,
            after.catalogRevision))

        // Back before the deck is ready: the restore is gone for good.
        waiting.close()
        assertNull(waiting.restored)
        assertFalse(waiting.awaitingDeck(null, after.grantEpoch, after.catalogRevision))
        assertNull(waiting.resolvedOrNull(live, after.grantEpoch, after.catalogRevision))
        assertNull(waiting.savedOrNull(live))

        // Close also forgets a live slideshow.
        val open = StoryPlaybackState()
        open.position = StoryPlaybackPosition(after.grantEpoch, after.catalogRevision,
            card.key, 0)
        open.close()
        assertNull(open.currentOrNull(after.grantEpoch, after.catalogRevision))
    }
}
