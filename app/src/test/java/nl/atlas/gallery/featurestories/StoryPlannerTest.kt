package nl.atlas.gallery.featurestories

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StoryPlannerTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 11, 3)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun media(id: Int, date: LocalDate, favorite: Boolean = false,
        video: Boolean = false): StoryMedia = StoryMedia(
        "content://atlas/images/$id", date.atTime(11, 0).atZone(zone).toInstant().toEpochMilli(),
        video, favorite
    )
    private fun catalog(items: List<StoryMedia>, grant: String = "g1") =
        StoryCatalog(grant, "r1", items)
    private fun plan(items: List<StoryMedia>, manuals: List<ManualStory> = emptyList()) =
        planStories(catalog(items), "g1", now, zone, manuals)

    @Test fun onThisDayExcludesTodayAndSelectsPastYearsOnly() {
        val items = listOf(media(1, today), media(2, today.minusYears(1)),
            media(3, today.minusYears(2)), media(4, today.minusYears(3)))
        val onDay = plan(items).cards.single()
        assertEquals(StoryKind.ON_THIS_DAY, onDay.kind)
        assertEquals(3, onDay.items.size)
        assertFalse(onDay.items.any { it.uri.endsWith("/1") })
    }

    @Test fun leapDayNeverInventsFebruary28OrMarch1() {
        val leapToday = LocalDate.of(2028, 2, 29)
        val leapNow = leapToday.atTime(12,0).atZone(zone).toInstant().toEpochMilli()
        val input = listOf(media(1, LocalDate.of(2027, 2, 28)),
            media(2, LocalDate.of(2027, 3, 1)), media(3, LocalDate.of(2024, 2, 29)))
        assertTrue(planStories(catalog(input), "g1", leapNow, zone).cards.none {
            it.kind == StoryKind.ON_THIS_DAY
        })
    }

    @Test fun monthBoundariesRespectLocalCalendarNotThirtyDays() {
        val input = (1..4).map { media(it, LocalDate.of(2026, 10, it)) } +
            media(5, LocalDate.of(2026, 9, 30)) + media(6, today)
        val recap = plan(input).cards.single()
        assertEquals(StoryKind.LAST_MONTH, recap.kind)
        assertEquals("October recap", recap.title)
        assertEquals(4, recap.items.size)
    }

    @Test fun favoritesDoNotIncludeOlderThan120CalendarDaysOrFuture() {
        val input = listOf(media(1, today.minusDays(119), true),
            media(2, today.minusDays(118), true), media(3, today.minusDays(1), true),
            media(4, today, true), media(5, today.minusDays(120), true),
            media(6, today.plusDays(1), true))
        val favorites = plan(input).cards.single()
        assertEquals(StoryKind.FAVORITES, favorites.kind)
        assertEquals(4, favorites.items.size)
        assertFalse(favorites.items.any { it.uri.endsWith("/5") || it.uri.endsWith("/6") })
    }

    @Test fun permissionOrRevisionMismatchImmediatelyHidesStaleThumbnails() {
        val input = (1..4).map { media(it, today.minusMonths(1).withDayOfMonth(it)) }
        val deck = plan(input)
        assertTrue(currentStoryCardsOrEmpty(deck, "g2", "r1").isEmpty())
        assertTrue(currentStoryCardsOrEmpty(deck, "g1", "r2").isEmpty())
        assertTrue(currentStoryCardsOrEmpty(deck, null, "r1").isEmpty())
        assertEquals(1, currentStoryCardsOrEmpty(deck, "g1", "r1").size)
        assertTrue(planStories(catalog(input), "wrong", now, zone).cards.isEmpty())
    }

    @Test fun privateOrTrashedMediaNeverEnterSuggestedOrManualStories() {
        val input = (1..5).map { media(it, today.minusMonths(1).withDayOfMonth(it)) }
        val items = input.mapIndexed { index, m -> when(index) {
            0 -> m.copy(isHidden = true)
            1 -> m.copy(isTrashed = true)
            else -> m
        }}
        val deck = plan(items, listOf(ManualStory("trip", "Trip", input.map { it.uri })))
        assertEquals(1, deck.cards.size)
        assertEquals(StoryKind.MANUAL, deck.cards.single().kind)
        assertEquals(3, deck.cards.single().items.size)
        assertEquals(2, deck.cards.single().missingCount)
    }

    @Test fun manualStoryUsesCurrentAuthorizedOrderOnlyWithExplicitMissingCount() {
        val a = media(1, today)
        val b = media(2, today)
        val def = ManualStory("trip", "  Weekend   break  ",
            listOf(b.uri, "content://atlas/images/missing", a.uri, b.uri))
        val card = plan(listOf(a,b), listOf(def)).cards.single()
        assertEquals("Weekend break", card.title)
        assertEquals(listOf(b.uri, a.uri), card.items.map { it.uri })
        assertEquals(1, card.missingCount)
    }

    @Test fun neverTrustFileUrisOrMissingCapturedDatesForAutoStories() {
        val old = (1..5).map { media(it, LocalDate.of(2026,10,it)) }
        val input = old.mapIndexed { index, m -> when(index) {
            0 -> m.copy(uri = "file:///storage/emulated/0/private.jpg")
            1 -> m.copy(uri = "https://example.com/1.jpg")
            2 -> m.copy(takenAtMillis = 0L)
            else -> m
        }}
        assertTrue(plan(input).cards.isEmpty())
    }

    @Test fun dayBalancingAvoidsSingleBurstAndKeepsFavorites() {
        val burst = (1..100).map { media(it, LocalDate.of(2026,10,1)) }
        val separateDays = (101..107).map { media(it, LocalDate.of(2026,10,it-99)) }
        val items = burst + separateDays + media(1000, LocalDate.of(2026,10,1), true)
        val month = plan(items).cards.single { it.kind == StoryKind.LAST_MONTH }
        assertEquals(10, month.items.size) // 3 from first day + 7 distinct days
        assertTrue(month.items.any { it.uri.endsWith("/1000") })
        assertEquals(8, month.items.map { instantDay(it) }.distinct().size)
    }
    private fun instantDay(m: StoryMedia) = java.time.Instant.ofEpochMilli(m.takenAtMillis)
        .atZone(zone).toLocalDate()

    @Test fun duplicateUriNeverAppearsTwiceOrLeaksAcrossManualAndSuggestedCards() {
        val items = (1..4).map { media(it, LocalDate.of(2026,10,it)) }
        val deck = plan(items + items, listOf(ManualStory("m", "Mine", listOf(items[0].uri, items[0].uri))))
        assertEquals(4, deck.cards.first { it.kind == StoryKind.LAST_MONTH }.items.size)
        assertEquals(1, deck.cards.first { it.kind == StoryKind.MANUAL }.items.size)
    }

    @Test fun repeatedCallsAreDeterministicDespiteCatalogueOrder() {
        val items = (1..60).map { media(it, LocalDate.of(2026,10,(it % 31) + 1), it % 7 == 0) }
        assertEquals(plan(items).cards, plan(items.reversed()).cards)
    }

    @Test fun callbackCancellationAbortsWithoutPartialPublication() {
        val items = (1..5_000).map { media(it, LocalDate.of(2026,10,1)) }
        var called = 0
        try {
            planStories(catalog(items), "g1", now, zone,
                cancellationCheckpoint = { if (++called >= 3) throw InterruptedException("new grant") })
            fail("expected cancellation")
        } catch (expected: InterruptedException) {
            assertEquals("new grant", expected.message)
        }
    }

    @Test fun hideRulesSuppressOnlySuggestionsAndKeepManualIntent() {
        val day = LocalDate.of(2026,10,1)
        val items = (1..5).map { media(it, day.plusDays(it.toLong()-1L)) }
        val policy = StorySuggestionPolicy(excludedDates = setOf(day, day.plusDays(1)),
            excludedUris = setOf(items[2].uri))
        val deck = planStories(catalog(items), "g1", now, zone,
            manualStories = listOf(ManualStory("choice", "Chosen", listOf(items[0].uri))),
            suggestionPolicy = policy)
        assertTrue(deck.cards.none { it.kind == StoryKind.LAST_MONTH })
        assertEquals(items[0].uri, deck.cards.single().items.single().uri)
    }

    @Test fun disablingAutomaticSuggestionsRetainsManualCollections() {
        val items = (1..5).map { media(it, LocalDate.of(2026,10,it)) }
        val deck = planStories(catalog(items), "g1", now, zone,
            manualStories = listOf(ManualStory("choice", "My story", listOf(items[3].uri))),
            suggestionPolicy = StorySuggestionPolicy(enabled = false))
        assertEquals(listOf(StoryKind.MANUAL), deck.cards.map { it.kind })
    }

    @Test fun largeSyntheticCatalogueKeepsRecapsBounded() {
        val items = (1..150_000).map { media(it, LocalDate.of(2026,10,(it % 31) + 1), it % 11 == 0) }
        val deck = plan(items)
        assertEquals(2, deck.cards.size) // last month + recent favorites
        assertTrue(deck.cards.all { it.items.size <= 24 })
        assertTrue(deck.cards.all { it.items.map { m -> m.uri }.distinct().size == it.items.size })
    }
}
