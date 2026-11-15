package nl.atlas.gallery

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToLong

/** On-device aggregate only: never accepts event payloads, user strings or sub-day timestamps. */
class UsageAggregates {
    data class Timing(val p50: Double, val p90: Double, val jankPercent: Double?)
    data class Day(
        val date: String,
        val actions: Map<String, Int>,
        val screens: Map<String, Long>,
        val latency: Map<String, Timing>,
        val scannerMinutes: Double?,
        val photosPerMinute: Double?
    )

    private class MutableDay {
        val actions = mutableMapOf<String, Int>()
        val screens = mutableMapOf<String, Long>()
        val timing = mutableMapOf<String, ArrayDeque<Double>>()
        val cachedTiming = mutableMapOf<String, Timing>()
        val frameCounts = mutableMapOf<String, Long>()
        val jankCounts = mutableMapOf<String, Long>()
        var scanSeconds = 0L
        var scannedPhotos = 0L
        var scannerObserved = false
    }

    private val rows = sortedMapOf<LocalDate, MutableDay>()
    private fun validAction(id: String): Boolean = id in ACTION_IDS
    private fun validScreen(id: String): Boolean = id in SCREEN_IDS
    private fun row(day: LocalDate) = rows.getOrPut(day) { MutableDay() }

    @Synchronized fun action(day: LocalDate, id: String): Boolean {
        if (!validAction(id)) return false
        val r = row(day)
        r.actions[id] = (r.actions[id] ?: 0).let { if (it == Int.MAX_VALUE) it else it + 1 }
        return true
    }

    @Synchronized fun screen(day: LocalDate, id: String, seconds: Long): Boolean {
        if (!validScreen(id) || seconds < 0 || seconds > MAX_SCREEN_SECONDS) return false
        val r = row(day)
        r.screens[id] = ((r.screens[id] ?: 0L) + seconds).coerceAtMost(MAX_SCREEN_SECONDS)
        return true
    }

    @Synchronized fun performance(
        day: LocalDate, id: String, latencyMs: Double, jankFrames: Int, frameCount: Int
    ): Boolean {
        if (!validAction(id) || !latencyMs.isFinite() || latencyMs < 0 ||
            latencyMs > MAX_LATENCY_MS || frameCount < 0 || jankFrames !in 0..frameCount) return false
        val r = row(day)
        // A new sample starts a fresh bounded window after restore.
        r.cachedTiming.remove(id)
        val samples = r.timing.getOrPut(id) { ArrayDeque() }
        samples.addLast(latencyMs)
        if (samples.size > 256) samples.removeFirst()
        r.frameCounts[id] = ((r.frameCounts[id] ?: 0L) + frameCount).coerceAtMost(Int.MAX_VALUE.toLong())
        r.jankCounts[id] = ((r.jankCounts[id] ?: 0L) + jankFrames).coerceAtMost(Int.MAX_VALUE.toLong())
        return true
    }

    @Synchronized fun scanner(day: LocalDate, seconds: Long, photos: Long): Boolean {
        if (seconds <= 0 || seconds > MAX_SCREEN_SECONDS ||
            photos < 0 || photos > 100_000_000) return false
        val r = row(day)
        r.scannerObserved = true
        r.scanSeconds = (r.scanSeconds + seconds).coerceAtMost(MAX_SCREEN_SECONDS)
        r.scannedPhotos = (r.scannedPhotos + photos).coerceAtMost(100_000_000L)
        return true
    }

    @Synchronized fun prune(today: LocalDate) {
        val cutoff = today.minusDays(89)
        rows.keys.removeAll { it.isBefore(cutoff) || it.isAfter(today) }
    }

    @Synchronized fun clear() = rows.clear()

    @Synchronized fun days(): List<Day> = rows.map { (date, r) ->
        val timings = r.timing.mapValues { (id, samples) ->
            val sorted = samples.sorted()
            fun percentile(f: Double) = sorted[(ceil(sorted.size * f).toInt() - 1).coerceIn(sorted.indices)]
            val frames = r.frameCounts[id] ?: 0L
            val jank = r.jankCounts[id] ?: 0L
            Timing(percentile(.5), percentile(.9),
                if (frames == 0L) null else 100.0 * jank / frames)
        }
        Day(date.toString(), r.actions.toSortedMap(), r.screens.toSortedMap(),
            (r.cachedTiming + timings).toSortedMap(), if (r.scannerObserved) r.scanSeconds / 60.0 else null,
            if (r.scannerObserved) r.scannedPhotos * 60.0 / r.scanSeconds else null)
    }

    /** Fixed schema, fixed keys and enum IDs; no caller-supplied strings ever reach JSON. */
    @Synchronized fun toJson(): String {
        val daysJson = JSONArray()
        rows.forEach { (date, r) ->
            val actions = JSONObject()
            r.actions.toSortedMap().forEach { (id, count) -> actions.put(id, count) }
            val screens = JSONObject()
            r.screens.toSortedMap().forEach { (id, seconds) -> screens.put(id, seconds) }
            val timings = JSONObject()
            r.timing.toSortedMap().forEach { (id, samples) ->
                val sorted = samples.sorted()
                fun percentile(f: Double) = sorted[(ceil(sorted.size * f).toInt() - 1).coerceIn(sorted.indices)]
                val frames = r.frameCounts[id] ?: 0L
                val summary = JSONObject().put("p50_ms", percentile(.5))
                    .put("p90_ms", percentile(.9))
                // With no frame sample, jank is unknown, not zero.
                if (frames > 0L) summary.put("jank_percent",
                    100.0 * (r.jankCounts[id] ?: 0L) / frames)
                timings.put(id, summary)
            }
            r.cachedTiming.toSortedMap().forEach { (id, summary) ->
                if (!timings.has(id)) {
                    val json = JSONObject().put("p50_ms", summary.p50)
                        .put("p90_ms", summary.p90)
                    if (summary.jankPercent != null) json.put("jank_percent", summary.jankPercent)
                    timings.put(id, json)
                }
            }
            val scanner = JSONObject()
            // Empty object = no scan samples. A measured zero throughput is distinct.
            if (r.scannerObserved) {
                scanner.put("minutes", r.scanSeconds / 60.0)
                    .put("photos_per_minute", r.scannedPhotos * 60.0 / r.scanSeconds)
            }
            daysJson.put(JSONObject().put("day", date.toString()).put("actions", actions)
                .put("screens_seconds", screens).put("performance", timings)
                .put("scanner", scanner))
        }
        return JSONObject().put("schema_version", 1).put("days", daysJson).toString()
    }

    companion object {
        private val ACTION_IDS = (1..22).map { "A$it" }.toSet()
        private val SCREEN_IDS = (1..19).map { "D$it" }.toSet()
        private const val MAX_SCREEN_SECONDS = 90L * 86400
        private const val MAX_LATENCY_MS = 3_600_000.0

        /** Read own prior exports only. Unknown keys/fields fail closed instead of being copied. */
        fun fromJson(raw: String): UsageAggregates {
            val root = JSONObject(raw)
            require(root.get("schema_version") is Number &&
                root.getInt("schema_version") == 1 && root.length() == 2)
            val log = UsageAggregates()
            val days = root.getJSONArray("days")
            require(days.length() <= 90)
            for (i in 0 until days.length()) {
                val item = days.getJSONObject(i)
                require(item.length() == 5)
                require(item.get("day") is String)
                val dayString = item.getString("day")
                val day = LocalDate.parse(dayString)
                require(day.toString() == dayString && day !in log.rows)
                val actions = item.getJSONObject("actions")
                for (key in actions.keys()) {
                    require(key in ACTION_IDS)
                    require(actions.get(key) is Int || actions.get(key) is Long)
                    val count = actions.getInt(key)
                    require(count >= 0)
                    log.row(day).actions[key] = count
                }
                val screens = item.getJSONObject("screens_seconds")
                for (key in screens.keys()) {
                    require(key in SCREEN_IDS)
                    require(screens.get(key) is Int || screens.get(key) is Long)
                    val seconds = screens.getLong(key)
                    require(seconds in 0..MAX_SCREEN_SECONDS)
                    log.row(day).screens[key] = seconds
                }
                val timings = item.getJSONObject("performance")
                for (key in timings.keys()) {
                    require(key in ACTION_IDS)
                    val summary = timings.getJSONObject(key)
                    require(summary.length() in 2..3)
                    require(summary.get("p50_ms") is Number &&
                        summary.get("p90_ms") is Number)
                    val hasJank = summary.has("jank_percent")
                    require(summary.length() == 2 || hasJank)
                    if (hasJank) require(summary.get("jank_percent") is Number)
                    val p50 = summary.getDouble("p50_ms")
                    val p90 = summary.getDouble("p90_ms")
                    val jank = if (hasJank) summary.getDouble("jank_percent") else null
                    require(p50.isFinite() && p90.isFinite() && p50 >= 0 && p90 >= p50 &&
                        p90 <= MAX_LATENCY_MS &&
                        (jank == null || (jank.isFinite() && jank in 0.0..100.0)))
                    log.row(day).cachedTiming[key] = Timing(p50, p90, jank)
                }
                val scanner = item.getJSONObject("scanner")
                require(scanner.length() == 0 || scanner.length() == 2)
                if (scanner.length() == 2) {
                    require(scanner.get("minutes") is Number &&
                        scanner.get("photos_per_minute") is Number)
                    val minutes = scanner.getDouble("minutes")
                    val rate = scanner.getDouble("photos_per_minute")
                    require(minutes.isFinite() && minutes > 0.0 &&
                        minutes <= MAX_SCREEN_SECONDS / 60.0 &&
                        rate.isFinite() && rate >= 0.0 && rate <= 100_000_000.0)
                    // Refuse corrupted partial-second or impossible aggregate values;
                    // otherwise re-export could divide by zero or exceed scanner bounds.
                    val seconds = (minutes * 60).roundToLong()
                    val photos = (minutes * rate).roundToLong()
                    require(seconds in 1..MAX_SCREEN_SECONDS &&
                        photos in 0..100_000_000L)
                    val row = log.row(day)
                    row.scannerObserved = true
                    // Nearest integer preserves small completed batches across reloads.
                    row.scanSeconds = seconds
                    row.scannedPhotos = photos
                }
            }
            return log
        }
    }
}
