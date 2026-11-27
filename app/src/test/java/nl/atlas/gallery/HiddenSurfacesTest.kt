package nl.atlas.gallery

import java.time.LocalDate
import java.time.ZoneOffset
import nl.atlas.gallery.featureinsights.computeLibraryInsights
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.PeopleActionAvailability
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepersistence.HiddenMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HIDE-1 (#585) review follow-up: behaviour of the real planners for Stories,
 * Photo DNA and Library insights on the filtered catalogue, the Hide/Unhide
 * membership delta behind Undo, and the People generation gate.
 */
class HiddenSurfacesTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 6, 15)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun photo(id: Long, size: Long = 100L, takenAt: Long = id * 1000L) = MediaRecord(
        id, "content://media/external/images/media/$id", "IMG_$id.jpg", "DCIM/Camera/", size,
        takenAt, false, modifiedAtSeconds = 1_700_000_000L)

    private fun key(r: MediaRecord) = HiddenMedia.keyOf(r)

    @Test fun storiesNeverPlanAHiddenItem() {
        val onThisDay = (1..4).map { years ->
            photo(years.toLong(),
                takenAt = today.minusYears(years.toLong()).atTime(11, 0).atZone(zone)
                    .toInstant().toEpochMilli())
        }
        val hiddenOne = onThisDay[1]
        val tokens = StoryPlanTokens("g1", "r1")
        fun uris(source: List<MediaRecord>) = planStoryDeck(source, tokens, now, zone)
            .cards.flatMap { c -> c.items.map { it.uri } }
        assertTrue(hiddenOne.uri in uris(onThisDay)) // control: planner does pick it
        val shown = HiddenMedia.visible(onThisDay, setOf(key(hiddenOne)))
        assertFalse(hiddenOne.uri in uris(shown))
        assertTrue(uris(shown).isNotEmpty())
    }

    @Test fun photoDnaNeverHashesOrGroupsAHiddenItem() {
        val source = listOf(photo(1), photo(2), photo(3))
        assertEquals(listOf(1L, 2L, 3L), photoDnaCandidates(source).records.map { it.id })
        val one = HiddenMedia.visible(source, setOf(key(source[2])))
        assertEquals(listOf(1L, 2L), photoDnaCandidates(one).records.map { it.id })
        // Hiding the partner leaves no same-size pair at all.
        val lone = HiddenMedia.visible(source, setOf(key(source[1]), key(source[2])))
        assertTrue(photoDnaCandidates(lone).records.isEmpty())
    }

    @Test fun libraryInsightsCountOnlyVisibleItems() {
        val source = listOf(photo(1, 10), photo(2, 20), photo(3, 400))
        val shown = HiddenMedia.visible(source, setOf(key(source[2])))
        val insights = computeLibraryInsights(shown, zone)
        assertEquals(2, insights.totalCount)
        assertEquals(30L, insights.totalBytes)
    }

    @Test fun deltaCoversOnlyKeysThatReallyChange() {
        val current = setOf("v/1", "v/2")
        assertEquals(listOf("v/3"), HiddenMedia.delta(current, listOf("v/2", "v/3", "v/3"), hide = true))
        assertEquals(listOf("v/2"), HiddenMedia.delta(current, listOf("v/2", "v/9"), hide = false))
        // A repeated Hide of an already-hidden key changes nothing, so it has no Undo to replace.
        assertTrue(HiddenMedia.delta(current, listOf("v/1", "v/2"), hide = true).isEmpty())
        assertTrue(HiddenMedia.delta(current, listOf("v/8"), hide = false).isEmpty())
    }

    @Test fun undoIsFencedByLaterTransitions() {
        val changed = listOf("v/3", "v/4")
        var now = setOf("v/1", "v/3", "v/4")
        assertEquals(changed, HiddenMedia.stillUndoable(now, changed, hide = true))
        // v/4 was unhidden again by someone else: only v/3 is still in the produced state.
        now = now - "v/4"
        assertEquals(listOf("v/3"), HiddenMedia.stillUndoable(now, changed, hide = true))
        assertTrue(HiddenMedia.stillUndoable(emptySet(), changed, hide = true).isEmpty())
        // Undo of an Unhide: keys must still be visible (absent from the set).
        assertEquals(listOf("v/9"), HiddenMedia.stillUndoable(setOf("v/3"), listOf("v/9", "v/3"), hide = false))
    }

    private fun status(generation: Any?, people: PeopleHomeSummary?) = PeopleDiscoveryStatus(
        FaceDiscoveryUiState(), "epoch", PeopleActionAvailability(), people,
        hiddenGeneration = generation)

    private val home = PeopleHomeSummary("epoch", "gen", emptyMap(), emptyList())

    @Test fun peopleHomeIsOnlyShownForTheCurrentHiddenGeneration() {
        val g1 = HashSet<String>()
        val g2 = HashSet<String>()
        val fresh = status(g1, home)
        assertSame(fresh, fresh.gatedForHidden(g1))
        // Hidden set changed (g2) before the host caught up: fail closed, "not readable yet".
        val stale = fresh.gatedForHidden(g2)
        assertNull(stale.people)
        assertTrue(stale.peoplePending)
        // Hidden set still unknown: never an unfiltered home.
        assertNull(fresh.gatedForHidden(null).people)
        // A status built before the host ever received a generation is stale too.
        assertNull(status(null, home).gatedForHidden(g1).people)
    }

    @Test fun peopleStatusWithoutHomeIsUntouched() {
        val none = status(null, null)
        assertSame(none, none.gatedForHidden(HashSet<String>()))
    }

    @Test fun heldPendingPeopleNeverCrossesAGeneration() {
        val g1 = HashSet<String>()
        val g2 = HashSet<String>()
        val previous = status(g1, home)
        val pendingNext = status(g2, null).copy(peoplePending = true)
        assertNull(holdPendingPeople(previous, pendingNext).people)
        val sameGeneration = status(g1, null).copy(peoplePending = true)
        assertNotNull(holdPendingPeople(previous, sameGeneration).people)
    }
}
