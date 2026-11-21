package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** USAGE-0 (#173): counts, latency, retention, Clear, opt-in, codec and the no-free-text export check. */
class UsageAggregatesTest {
    private class FakeClock(var ymd: Int = 20261121, var ms: Long = 0) : UsageClock {
        override fun todayYmd() = ymd
        override fun nowMs() = ms
    }
    private class MemoryStore(initial: Boolean = false) : UsageStore {
        val saved = HashMap<Int, String>(); private var on0 = initial
        override fun loadAll() = saved.mapNotNull { (k, v) -> UsageDayCodec.decode(k, v) }
        override fun save(day: UsageDay) { saved[day.ymd] = UsageDayCodec.encode(day) }
        override fun delete(ymd: Int) { saved.remove(ymd) }
        override fun clear() { saved.clear() }
        override fun isEnabled() = on0
        override fun setEnabled(on: Boolean) { on0 = on }
    }

    private fun log(store: MemoryStore = MemoryStore(true), clock: FakeClock = FakeClock()) =
        UsageLog(store, clock, flushIntervalMs = 0)

    @Test fun offByDefaultRecordsNothing() {
        val store = MemoryStore()
        val l = log(store)
        l.count(UsageAction.A15); l.measured(UsageAction.A1, 400.0)
        l.dwell(UsageScreen.D1, 30); l.scanner(10, 1000)
        assertFalse(l.hasData()); assertNull(l.exportJson()); assertTrue(store.saved.isEmpty())
    }

    @Test fun countsLatencyAndPercentiles() {
        val l = log()
        (1..10).forEach { l.measured(UsageAction.A3, it * 10.0) }
        l.count(UsageAction.A15, 3)
        val json = l.exportJson()!!
        assertTrue(json, json.contains("\"actions\":{\"A3\":10,\"A15\":3}"))
        assertTrue(json, json.contains("\"A3\":{\"p50_ms\":50,\"p90_ms\":90}"))
    }

    @Test fun unmeasuredActionsAreAbsentNotZero() {
        val l = log(); l.count(UsageAction.A15)
        val json = l.exportJson()!!
        assertFalse(json.contains("A1\""))
        assertTrue(json, json.contains("\"performance\":{}"))
    }

    @Test fun scrollCountsOncePerScrollAndKeepsJank() {
        val clock = FakeClock(); val l = log(clock = clock)
        repeat(10) { clock.ms += 16; l.scrollFrame(UsageAction.A2, 16.0, it < 2) }
        clock.ms += 5_000
        repeat(10) { clock.ms += 16; l.scrollFrame(UsageAction.A2, 16.0, false) }
        val json = l.exportJson()!!
        assertTrue(json, json.contains("\"actions\":{\"A2\":2}"))
        assertTrue(json, json.contains("\"A2\":{\"p50_ms\":16,\"p90_ms\":16,\"jank_percent\":10.0}"))
    }

    @Test fun dwellAndScannerAreAggregates() {
        val l = log(); l.dwell(UsageScreen.D1, 90); l.dwell(UsageScreen.D1, 30)
        l.scanner(300, 60_000); l.scanner(300, 60_000)
        val json = l.exportJson()!!
        assertTrue(json, json.contains("\"screens_seconds\":{\"D1\":120}"))
        assertTrue(json, json.contains("\"scanner\":{\"minutes\":2.0,\"photos_per_minute\":300.0}"))
    }

    @Test fun dwellTrackerPausesInBackground() {
        val seen = HashMap<UsageScreen, Long>()
        val t = UsageDwellTracker { s, sec -> seen[s] = (seen[s] ?: 0) + sec }
        t.enter(UsageScreen.D1, 0); t.pause(10_000)
        t.resume(500_000); t.enter(UsageScreen.D5, 505_000); t.pause(507_000)
        assertEquals(15L, seen[UsageScreen.D1]); assertEquals(2L, seen[UsageScreen.D5])
    }

    @Test fun retentionKeepsRollingNinetyDays() {
        val store = MemoryStore(true); val clock = FakeClock(20261121)
        val l = UsageLog(store, clock, flushIntervalMs = 0)
        clock.ymd = 20260820; l.count(UsageAction.A1)   // 93 days before 2026-11-21
        clock.ymd = 20260824; l.count(UsageAction.A1)   // 89 days before: kept
        clock.ymd = 20261121; l.count(UsageAction.A1)
        l.flush()
        assertEquals(setOf(20260824, 20261121), store.saved.keys)
        val json = l.exportJson()!!
        assertFalse(json.contains("2026-08-20")); assertTrue(json.contains("2026-08-24"))
    }

    @Test fun clearDeletesEverythingAndKeepsSwitch() {
        val store = MemoryStore(true); val l = log(store)
        l.count(UsageAction.A1); l.flush()
        assertTrue(l.hasData())
        l.clear()
        assertFalse(l.hasData()); assertNull(l.exportJson()); assertTrue(store.saved.isEmpty())
        assertTrue(l.isEnabled())
    }

    @Test fun turningOffKeepsExistingDataUntilClear() {
        val store = MemoryStore(true); val l = log(store)
        l.count(UsageAction.A1); l.setEnabled(false)
        l.count(UsageAction.A1)
        assertTrue(l.hasData())
        assertTrue(l.exportJson()!!.contains("\"actions\":{\"A1\":1}"))
    }

    @Test fun codecRoundTripsAndRejectsGarbage() {
        val d = UsageDay(20261121)
        d.counts[0] = 4; d.addLatency(UsageAction.A1, 400.0); d.addLatency(UsageAction.A1, 500.0)
        d.frames[1] = 20; d.framesOver[1] = 3; d.dwellSeconds[0] = 77; d.scannerMs = 5; d.scannerPhotos = 6
        val back = UsageDayCodec.decode(20261121, UsageDayCodec.encode(d))!!
        assertEquals(UsageExport.toJson(listOf(d)), UsageExport.toJson(listOf(back)))
        assertNull(UsageDayCodec.decode(1, "v2;C0=1")); assertNull(UsageDayCodec.decode(1, "v1;Cx=1"))
        assertNull(UsageDayCodec.decode(1, "v1;C99=1"))
    }

    @Test fun exportHasNoFreeText() {
        val l = log(clock = FakeClock(20261121, 0))
        UsageAction.entries.forEach { a -> l.measured(a, 12.0); l.count(a) }
        UsageScreen.entries.forEach { s -> l.dwell(s, 5) }
        l.scanner(10, 1000)
        val json = l.exportJson()!!
        assertFalse("no escapes", json.contains('\\'))
        val allowed = UsageAction.entries.map { it.id } + UsageScreen.entries.map { it.id } +
            listOf("schema_version", "days", "day", "actions", "screens_seconds", "performance",
                "p50_ms", "p90_ms", "jank_percent", "scanner", "minutes", "photos_per_minute")
        val strings = Regex("\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        assertTrue(strings.isNotEmpty())
        strings.forEach {
            assertTrue("unexpected string: $it", it in allowed || it == "2026-11-21")
        }
        // Everything outside the quoted strings is structure or a number.
        val rest = Regex("\"[^\"]*\"").replace(json, "")
        assertTrue(rest, rest.matches(Regex("[\\[\\]{}:,0-9.]*")))
        assertNotNull(Regex("\"day\":\"2026-11-21\"").find(json))
    }

    @Test fun exportNamesAndSerialisesWithoutAndroid() {
        assertEquals("atlas-usage-20261121.json", log().exportFileName())
        assertEquals(22, UsageAction.entries.size); assertEquals(19, UsageScreen.entries.size)
        assertEquals(UsageAction.A22, UsageAction.fromNumber(22)); assertNull(UsageAction.fromNumber(23))
    }
}
