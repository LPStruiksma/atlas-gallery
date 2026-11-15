package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UsageAggregatesTest {
    @Test fun countsAndScreenDurationsAreDailyAndClosed() {
        val log = UsageAggregates()
        val day = LocalDate.of(2026, 11, 15)
        assertTrue(log.action(day, "A3"))
        assertTrue(log.action(day, "A3"))
        assertFalse(log.action(day, "my-private-photo.jpg"))
        assertTrue(log.screen(day, "D2", 13))
        assertFalse(log.screen(day, "D20", 20))
        assertEquals(2, log.days().single().actions["A3"])
        assertEquals(13L, log.days().single().screens["D2"])
    }

    @Test fun latencyAndScannerStatisticsRemainNumericAndBounded() {
        val log = UsageAggregates()
        val day = LocalDate.of(2026, 11, 15)
        for (sample in listOf(10.0, 40.0, 20.0, 30.0, 50.0)) {
            assertTrue(log.performance(day, "A3", sample, 2, 100))
        }
        assertFalse(log.performance(day, "A23", 1.0, 0, 1))
        assertFalse(log.performance(day, "A3", Double.NaN, 0, 1))
        assertTrue(log.scanner(day, 120, 36))
        val stats = log.days().single().latency["A3"]!!
        assertEquals(30.0, stats.p50, 0.0)
        assertEquals(50.0, stats.p90, 0.0)
        assertEquals(2.0, stats.jankPercent, 0.0)
        assertEquals(2.0, log.days().single().scannerMinutes, 0.0)
        assertEquals(18.0, log.days().single().photosPerMinute, 0.0)
    }

    @Test fun rollingRetentionAndClear() {
        val log = UsageAggregates()
        assertTrue(log.action(LocalDate.of(2026, 1, 1), "A1"))
        assertTrue(log.action(LocalDate.of(2026, 4, 1), "A1"))
        log.prune(LocalDate.of(2026, 4, 1))
        assertEquals(listOf("2026-04-01"), log.days().map { it.date })
        log.clear()
        assertTrue(log.days().isEmpty())
    }

    @Test fun exportAndImportNeverAcceptArbitraryStringValues() {
        val day = LocalDate.of(2026, 11, 15)
        val log = UsageAggregates()
        log.action(day, "A22")
        log.screen(day, "D17", 4)
        log.performance(day, "A3", 42.0, 1, 20)
        log.scanner(day, 60, 60)
        val json = log.toJson()
        val parsed = org.json.JSONObject(json)
        assertEquals(1, parsed.getInt("schema_version"))
        val row = parsed.getJSONArray("days").getJSONObject(0)
        assertEquals("2026-11-15", row.getString("day"))
        assertTrue(json.contains("\"A22\""))
        assertFalse(json.contains("private"))
        val restored = UsageAggregates.fromJson(json)
        assertEquals(1, restored.days().single().actions["A22"])
        assertEquals(4L, restored.days().single().screens["D17"])
        assertThrows(IllegalArgumentException::class.java) {
            UsageAggregates.fromJson(json.replace("\"A22\"", "\"photo-private.jpg\""))
        }
    }
}
