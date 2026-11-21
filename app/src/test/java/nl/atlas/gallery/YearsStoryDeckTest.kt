package nl.atlas.gallery

import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurestories.StoryCard
import nl.atlas.gallery.featurestories.StoryDeck
import nl.atlas.gallery.featurestories.StoryKind
import nl.atlas.gallery.featurestories.StoryMedia
import nl.atlas.gallery.featurestories.StoryPlaybackPosition
import nl.atlas.gallery.featurestories.StoryPlaybackState
import nl.atlas.gallery.featurestories.RestoredStoryPosition
import nl.atlas.gallery.featurestories.storyFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** YEARS-1 (#586) review fixes: the Years card cap and save/restore of a people Story. Synthetic only. */
class YearsStoryDeckTest {
    private fun key(n: Int) = PersonKey("synthetic-p%02d".format(n))
    private fun card(p: PersonKey) = StoryCard(yearsStoryKey(p), "t", "c", StoryKind.PERSON_YEARS,
        listOf(StoryMedia("content://synthetic/${p.value}_1", 1L), StoryMedia("content://synthetic/${p.value}_2", 2L)),
        frameCaptions = listOf("2019", "2020"))

    @Test fun capCountsFinishedCardsSoIneligiblePeopleDoNotMaskEligibleOnes() {
        // 14 people, most photographed first. The first 12 are ineligible, the last 2 eligible.
        val ordered = (1..14).map { key(it) to "P$it" }
        val eligible = setOf(key(13), key(14))
        val cards = selectYearsCards(ordered, null, 12) { p, _ -> if (p in eligible) card(p) else null }
        assertEquals(listOf(yearsStoryKey(key(13)), yearsStoryKey(key(14))), cards.map { it.key })
    }

    @Test fun thirteenthEligiblePersonGetsACardOnlyWhenOpened() {
        val ordered = (1..13).map { key(it) to "P$it" }
        val all = selectYearsCards(ordered, null, 12) { p, _ -> card(p) }
        assertEquals(12, all.size)
        assertNull(all.firstOrNull { it.key == yearsStoryKey(key(13)) })
        val pinned = selectYearsCards(ordered, key(13), 12) { p, _ -> card(p) }
        assertEquals(13, pinned.size)
        assertNotNull(pinned.firstOrNull { it.key == yearsStoryKey(key(13)) })
    }

    @Test fun yearsKeyRoundTripsToThePerson() {
        assertEquals(key(5), yearsPersonOfKey(yearsStoryKey(key(5))))
        assertNull(yearsPersonOfKey("on-this-day"))
        assertNull(yearsPersonOfKey(null))
    }

    @Test fun openPeopleStoryIsSavedFromTheCompositeDeckAndRestoresOnlyWithItsCard() {
        val c = card(key(1))
        val composite = StoryDeck("g", "r", listOf(c))
        val state = StoryPlaybackState()
        state.position = StoryPlaybackPosition("g", "r", c.key, 1)
        val saved = state.savedOrNull(composite)
        assertEquals(RestoredStoryPosition(c.key, storyFingerprint(c), 1, true), saved)
        // Without the people card in the deck the same position cannot be saved (the original bug).
        assertNull(state.savedOrNull(StoryDeck("g", "r", emptyList())))

        val fresh = StoryPlaybackState().also { it.restore(saved!!) }
        // A live deck without the card drops the restore; with the card it resumes at the same moment.
        assertNull(fresh.resolvedOrNull(StoryDeck("g2", "r2", emptyList()), "g2", "r2"))
        val back = fresh.resolvedOrNull(StoryDeck("g2", "r2", listOf(c)), "g2", "r2")
        assertEquals(c.key, back?.storyKey)
        assertEquals(1, back?.index)
    }
}
