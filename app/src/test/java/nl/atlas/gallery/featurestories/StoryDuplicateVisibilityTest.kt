package nl.atlas.gallery.featurestories

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StoryDuplicateVisibilityTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val now = LocalDate.of(2026, 11, 3).atTime(12, 0)
        .atZone(zone).toInstant().toEpochMilli()

    @Test fun duplicateVisibilityConflictIsFailClosedInEitherCatalogOrder() {
        val original = (1..5).map { id ->
            StoryMedia("content://atlas/images/$id",
                LocalDate.of(2026, 10, id).atTime(11, 0)
                    .atZone(zone).toInstant().toEpochMilli())
        }
        val veto = original[2].copy(isHidden = true)
        for (mediaRows in listOf(original + veto, listOf(veto) + original)) {
            val deck = planStories(StoryCatalog("g1", "r1", mediaRows), "g1", now, zone,
                manualStories = listOf(ManualStory("custom", "My picks",
                    original.map { it.uri })))
            assertTrue(deck.cards.all { card -> card.items.none { it.uri == veto.uri } })
            assertEquals(1, deck.cards.single { it.kind == StoryKind.MANUAL }.missingCount)
        }
    }
}
