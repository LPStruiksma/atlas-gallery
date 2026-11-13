package nl.atlas.gallery.featurestories

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StoryLocalizationTest {
    @Test fun monthHeadingUsesStableEnglishWhileDeviceLocaleChanges() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val now = LocalDate.of(2026, 5, 3).atTime(12,0).atZone(zone).toInstant().toEpochMilli()
        val media = (1..4).map { day -> StoryMedia("content://atlas/$day",
            LocalDate.of(2026,4,day).atTime(11,0).atZone(zone).toInstant().toEpochMilli()) }
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val card = planStories(StoryCatalog("g1", "rev1", media), "g1", now, zone).cards.single()
            assertEquals("April recap", card.title)
        } finally {
            Locale.setDefault(before)
        }
    }
}
