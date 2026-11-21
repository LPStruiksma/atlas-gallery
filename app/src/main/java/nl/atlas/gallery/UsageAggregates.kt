package nl.atlas.gallery

import java.time.LocalDate
import kotlin.math.ceil

/**
 * USAGE-0 (#173): private, on-device, per-day usage aggregates.
 *
 * Closed vocabulary only. Every recorded item is an enum ID (A1–A22 from #119,
 * D1–D19 from #120) plus numbers. No names, file names, URIs, search text,
 * locations, timestamps finer than a local day, or sequences of actions exist
 * anywhere in this model, so none can be exported. No network, no Android types.
 */
enum class UsageAction { A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11,
    A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22;
    val id: String get() = name
    companion object {
        fun fromNumber(n: Int): UsageAction? = entries.getOrNull(n - 1)
    }
}

enum class UsageScreen { D1, D2, D3, D4, D5, D6, D7, D8, D9, D10,
    D11, D12, D13, D14, D15, D16, D17, D18, D19;
    val id: String get() = name
}

/** One local day. Mutable, bounded, numbers only. */
class UsageDay(val ymd: Int) {
    val counts = IntArray(UsageAction.entries.size)
    val dwellSeconds = LongArray(UsageScreen.entries.size)
    /** Newest-last latency samples (ms) per action, capped at [MAX_SAMPLES]. */
    val latencies: Array<ArrayList<Int>> = Array(UsageAction.entries.size) { ArrayList() }
    /** Frame totals for the actions that scroll (A2, A10): frames seen, frames over budget. */
    val frames = IntArray(UsageAction.entries.size)
    val framesOver = IntArray(UsageAction.entries.size)
    var scannerMs = 0L
    var scannerPhotos = 0L

    fun isEmpty(): Boolean = counts.all { it == 0 } && dwellSeconds.all { it == 0L } &&
        latencies.all { it.isEmpty() } && frames.all { it == 0 } &&
        scannerMs == 0L && scannerPhotos == 0L

    fun addLatency(a: UsageAction, ms: Double) {
        if (!ms.isFinite() || ms < 0.0) return
        val ring = latencies[a.ordinal]
        ring.add(ms.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())
        if (ring.size > MAX_SAMPLES) ring.removeAt(0)
    }

    companion object { const val MAX_SAMPLES = 128 }
}

/** Source of "today" and of a monotonic millisecond clock. Injectable for tests. */
interface UsageClock {
    fun todayYmd(): Int
    fun nowMs(): Long
}

/** Persistence port. Android implementation lives in [UsagePrefsStore]. */
interface UsageStore {
    fun loadAll(): List<UsageDay>
    fun save(day: UsageDay)
    fun delete(ymd: Int)
    fun clear()
    fun isEnabled(): Boolean
    fun setEnabled(on: Boolean)
}

/**
 * The recorder. Records only while the opt-in is on. Retention is a rolling 90
 * local days, pruned on every flush and every export read.
 */
class UsageLog(
    private val store: UsageStore,
    private val clock: UsageClock,
    private val flushIntervalMs: Long = 15_000,
    /**
     * Called around switching recording on/off and on Clear. `true` (before turning off):
     * credit the time measured so far. `false` (after turning on, or after Clear): drop it.
     */
    private val onBoundary: (creditElapsed: Boolean) -> Unit = {}
) {
    private val days = HashMap<Int, UsageDay>()
    private val dirty = HashSet<Int>()
    private var loaded = false
    private var lastFlushMs = Long.MIN_VALUE
    private var frameEpisodeLastMs = LongArray(UsageAction.entries.size) { Long.MIN_VALUE }

    @Synchronized fun isEnabled(): Boolean = store.isEnabled()

    @Synchronized fun setEnabled(on: Boolean) {
        if (!on) { onBoundary(true); flushLocked() }
        store.setEnabled(on)
        if (on) onBoundary(false)
    }

    /** True when at least one day holds a non-empty aggregate (including today's partial day). */
    @Synchronized fun hasData(): Boolean { ensureLoaded(); pruneLocked(); return days.values.any { !it.isEmpty() } }

    @Synchronized fun count(action: UsageAction, n: Int = 1) {
        val d = today() ?: return
        d.counts[action.ordinal] = (d.counts[action.ordinal] + n).coerceAtLeast(0)
        touch(d)
    }

    /** One finished, measured action: counts once and adds one latency sample. */
    @Synchronized fun measured(action: UsageAction, elapsedMs: Double) {
        val d = today() ?: return
        d.counts[action.ordinal] += 1
        d.addLatency(action, elapsedMs)
        touch(d)
    }

    /** Latency sample for an action that was already counted elsewhere. */
    @Synchronized fun latencyOnly(action: UsageAction, elapsedMs: Double) {
        val d = today() ?: return
        d.addLatency(action, elapsedMs)
        touch(d)
    }

    /**
     * One display frame while a scroll is active. Counts the action once per scroll
     * (a gap of more than [EPISODE_GAP_MS] since the previous frame starts a new
     * scroll), adds a latency sample, and keeps the frames-over-budget share.
     */
    @Synchronized fun scrollFrame(action: UsageAction, frameMs: Double, overBudget: Boolean) {
        if (!frameMs.isFinite() || frameMs <= 0.0) return
        val d = today() ?: return
        val now = clock.nowMs()
        val last = frameEpisodeLastMs[action.ordinal]
        if (last == Long.MIN_VALUE || now - last > EPISODE_GAP_MS) d.counts[action.ordinal] += 1
        frameEpisodeLastMs[action.ordinal] = now
        d.addLatency(action, frameMs)
        d.frames[action.ordinal] += 1
        if (overBudget) d.framesOver[action.ordinal] += 1
        touch(d)
    }

    @Synchronized fun dwell(screen: UsageScreen, seconds: Long) {
        if (seconds <= 0) return
        val d = today() ?: return
        // A day cannot hold more than 24 h on one screen; guards against clock jumps.
        d.dwellSeconds[screen.ordinal] = (d.dwellSeconds[screen.ordinal] + seconds).coerceAtMost(86_400)
        touch(d)
    }

    @Synchronized fun scanner(photos: Int, elapsedMs: Long) {
        if (photos <= 0 || elapsedMs <= 0) return
        val d = today() ?: return
        d.scannerPhotos += photos
        d.scannerMs += elapsedMs
        touch(d)
    }

    @Synchronized fun flush() = flushLocked()

    @Synchronized fun clear() {
        days.clear(); dirty.clear(); loaded = true
        store.clear()
        onBoundary(false)
    }

    /** Serialised export of every retained day, or null when nothing is recorded. */
    @Synchronized fun exportJson(): String? {
        ensureLoaded(); pruneLocked()
        val nonEmpty = days.values.filter { !it.isEmpty() }.sortedBy { it.ymd }
        if (nonEmpty.isEmpty()) return null
        return UsageExport.toJson(nonEmpty)
    }

    fun exportFileName(): String = "atlas-usage-${clock.todayYmd()}.json"

    // -- internals ---------------------------------------------------------
    private fun today(): UsageDay? {
        if (!store.isEnabled()) return null
        ensureLoaded()
        return days.getOrPut(clock.todayYmd()) { UsageDay(clock.todayYmd()) }
    }

    private fun touch(d: UsageDay) {
        dirty.add(d.ymd)
        val now = clock.nowMs()
        if (lastFlushMs == Long.MIN_VALUE || now - lastFlushMs >= flushIntervalMs) flushLocked()
    }

    private fun ensureLoaded() {
        if (loaded) return
        store.loadAll().forEach { days[it.ymd] = it }
        loaded = true
    }

    private fun flushLocked() {
        lastFlushMs = clock.nowMs()
        if (!loaded) return
        pruneLocked()
        dirty.toList().forEach { ymd -> days[ymd]?.let { store.save(it) } }
        dirty.clear()
    }

    private fun pruneLocked() {
        val cutoff = UsageDates.minusDays(clock.todayYmd(), RETENTION_DAYS - 1)
        days.keys.filter { it < cutoff }.forEach { days.remove(it); dirty.remove(it); store.delete(it) }
    }

    companion object {
        const val RETENTION_DAYS = 90
        const val EPISODE_GAP_MS = 1_500L
    }
}

/**
 * Which usage action a scrolling grid counts for: only the Pictures grid is A2 and only a
 * person's photos are A10 (#119). Search results, albums and everything else count for neither.
 */
fun usageScrollAction(tab: Int, personOpen: Boolean, albumOpen: Boolean): UsageAction? = when {
    personOpen -> UsageAction.A10
    tab == 0 && !albumOpen -> UsageAction.A2
    else -> null
}

internal object UsageDates {
    fun minusDays(ymd: Int, days: Int): Int {
        val d = LocalDate.of(ymd / 10000, ymd / 100 % 100, ymd % 100).minusDays(days.toLong())
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }
}

/**
 * Tracks how long each screen ID is on screen. Pure: the caller supplies the
 * clock. [pause] stops the timer while the app is in the background.
 */
class UsageDwellTracker(private val sink: (UsageScreen, Long) -> Unit) {
    private var screen: UsageScreen? = null
    private var sinceMs = 0L
    private var running = false
    private var carryMs = 0L

    @Synchronized fun enter(next: UsageScreen?, nowMs: Long) {
        if (next == screen && running) return
        settle(nowMs)
        screen = next; sinceMs = nowMs; running = next != null
        carryMs = 0
    }
    /** Credits the time measured so far and keeps timing from now. */
    @Synchronized fun checkpoint(nowMs: Long) { settle(nowMs) }
    /** Drops the time measured so far on this screen and starts a fresh interval now. */
    @Synchronized fun reset(nowMs: Long) { carryMs = 0; sinceMs = nowMs }
    @Synchronized fun pause(nowMs: Long) { settle(nowMs); running = false }
    @Synchronized fun resume(nowMs: Long) { if (screen != null && !running) { running = true; sinceMs = nowMs } }

    private fun settle(nowMs: Long) {
        val s = screen ?: return
        if (running) carryMs += (nowMs - sinceMs).coerceAtLeast(0)
        val whole = carryMs / 1000
        if (whole > 0) sink(s, whole)
        carryMs -= whole * 1000
        sinceMs = nowMs
    }
}

/**
 * Closed-vocabulary serialiser for the USAGE-1 consumer (`scripts/usage_rank.py`, schema 1).
 * Never escapes: every string is an enum ID, a fixed field name or a `YYYY-MM-DD` day.
 * An action with no timing sample has no `performance` entry: "not measured" is absence, not zero.
 */
object UsageExport {
    const val SCHEMA_VERSION = 1
    internal fun percentile(sorted: List<Int>, fraction: Double): Int =
        sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]

    internal fun dayText(ymd: Int): String =
        "%04d-%02d-%02d".format(ymd / 10000, ymd / 100 % 100, ymd % 100)

    fun toJson(days: List<UsageDay>): String = buildString {
        append("{\"schema_version\":").append(SCHEMA_VERSION).append(",\"days\":[")
        days.forEachIndexed { i, d ->
            if (i > 0) append(',')
            append("{\"day\":\"").append(dayText(d.ymd)).append('"')
            append(",\"actions\":{")
            var first = true
            UsageAction.entries.forEach { a ->
                val n = d.counts[a.ordinal]
                if (n == 0) return@forEach
                if (!first) append(','); first = false
                append('"').append(a.id).append("\":").append(n)
            }
            append("},\"screens_seconds\":{")
            first = true
            UsageScreen.entries.forEach { s ->
                val sec = d.dwellSeconds[s.ordinal]
                if (sec == 0L) return@forEach
                if (!first) append(','); first = false
                append('"').append(s.id).append("\":").append(sec)
            }
            append("},\"performance\":{")
            first = true
            UsageAction.entries.forEach { a ->
                val lat = d.latencies[a.ordinal]
                val frames = d.frames[a.ordinal]
                if (lat.isEmpty() && frames == 0) return@forEach
                if (!first) append(','); first = false
                append('"').append(a.id).append("\":{")
                var inner = false
                if (lat.isNotEmpty()) {
                    val sorted = lat.sorted()
                    append("\"p50_ms\":").append(percentile(sorted, 0.50))
                    append(",\"p90_ms\":").append(percentile(sorted, 0.90))
                    inner = true
                }
                if (frames > 0) {
                    if (inner) append(',')
                    append("\"jank_percent\":").append(Math.round(d.framesOver[a.ordinal] * 1000.0 / frames) / 10.0)
                }
                append('}')
            }
            append('}')
            if (d.scannerMs > 0 && d.scannerPhotos > 0) {
                append(",\"scanner\":{\"minutes\":").append(Math.round(d.scannerMs / 6000.0) / 10.0)
                append(",\"photos_per_minute\":")
                    .append(Math.round(d.scannerPhotos * 600_000.0 / d.scannerMs) / 10.0)
                append('}')
            }
            append('}')
        }
        append("]}")
    }
}

/** Compact text codec for one day, used by [UsagePrefsStore]. Numbers and fixed letters only. */
object UsageDayCodec {
    fun encode(d: UsageDay): String = buildString {
        append("v1")
        d.counts.forEachIndexed { i, n -> if (n != 0) append(";C").append(i).append('=').append(n) }
        d.latencies.forEachIndexed { i, l -> if (l.isNotEmpty()) append(";L").append(i).append('=').append(l.joinToString(",")) }
        d.frames.forEachIndexed { i, n -> if (n != 0) append(";F").append(i).append('=').append(n).append(',').append(d.framesOver[i]) }
        d.dwellSeconds.forEachIndexed { i, n -> if (n != 0L) append(";D").append(i).append('=').append(n) }
        if (d.scannerMs != 0L || d.scannerPhotos != 0L) append(";S=").append(d.scannerMs).append(',').append(d.scannerPhotos)
    }

    /** Returns null for anything that is not a well-formed v1 day; callers drop such days. */
    fun decode(ymd: Int, text: String): UsageDay? {
        val parts = text.split(';')
        if (parts.firstOrNull() != "v1") return null
        val d = UsageDay(ymd)
        try {
            for (p in parts.drop(1)) {
                val eq = p.indexOf('='); if (eq < 2 && !(p.startsWith("S") && eq == 1)) return null
                val key = p[0]; val rest = p.substring(eq + 1)
                val idx = if (key == 'S') 0 else p.substring(1, eq).toInt()
                when (key) {
                    'C' -> d.counts[idx] = rest.toInt()
                    'L' -> rest.split(',').forEach { d.latencies[idx].add(it.toInt()) }
                    'F' -> rest.split(',').let { d.frames[idx] = it[0].toInt(); d.framesOver[idx] = it[1].toInt() }
                    'D' -> d.dwellSeconds[idx] = rest.toLong()
                    'S' -> rest.split(',').let { d.scannerMs = it[0].toLong(); d.scannerPhotos = it[1].toLong() }
                    else -> return null
                }
            }
        } catch (_: Exception) { return null }
        return d
    }
}
