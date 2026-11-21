package nl.atlas.gallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

/** USAGE-0 (#173) Android edge: clock, SharedPreferences persistence, and the share-sheet export. */
internal object SystemUsageClock : UsageClock {
    override fun todayYmd(): Int =
        LocalDate.now().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}

/**
 * One SharedPreferences file, no network. Keys: `enabled` and `day.<yyyymmdd>`.
 * Clear removes every `day.*` key and keeps the on/off switch.
 */
internal class UsagePrefsStore(context: Context) : UsageStore {
    private val prefs = context.applicationContext.getSharedPreferences("atlas_usage", Context.MODE_PRIVATE)

    override fun loadAll(): List<UsageDay> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(DAY_PREFIX) || value !is String) return@mapNotNull null
        val ymd = key.removePrefix(DAY_PREFIX).toIntOrNull() ?: return@mapNotNull null
        UsageDayCodec.decode(ymd, value)
    }
    override fun save(day: UsageDay) { prefs.edit().putString(DAY_PREFIX + day.ymd, UsageDayCodec.encode(day)).apply() }
    override fun delete(ymd: Int) { prefs.edit().remove(DAY_PREFIX + ymd).apply() }
    override fun clear() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith(DAY_PREFIX) }.forEach { e.remove(it) }
        e.commit()
    }
    override fun isEnabled(): Boolean = prefs.getBoolean(ENABLED, false)
    override fun setEnabled(on: Boolean) { prefs.edit().putBoolean(ENABLED, on).apply() }

    private companion object { const val DAY_PREFIX = "day."; const val ENABLED = "enabled" }
}

/** Process-wide recorder. Off until the user turns on "Record usage for tuning". */
internal object UsageRecorder {
    @Volatile private var instance: UsageLog? = null
    val dwell = UsageDwellTracker { screen, seconds -> instance?.dwell(screen, seconds) }

    /**
     * Builds the log off the main thread: the first SharedPreferences read is disk I/O
     * (#435: no disk reads on Main at startup). Every hook is a no-op until it finishes.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        if (instance != null) return
        Thread({
            synchronized(this) {
                if (instance == null) {
                    val store = UsagePrefsStore(app)
                    store.isEnabled() // forces the preferences file to load here, not on Main
                    instance = UsageLog(store, SystemUsageClock)
                }
            }
        }, "atlas-usage-init").start()
    }

    /** Null before [install]; every hook below is then a no-op. */
    fun log(): UsageLog? = instance

    fun count(action: UsageAction) { instance?.count(action) }
    fun measured(action: Int, ms: Double) { UsageAction.fromNumber(action)?.let { instance?.measured(it, ms) } }
    fun latencyOnly(action: Int, ms: Double) { UsageAction.fromNumber(action)?.let { instance?.latencyOnly(it, ms) } }
    fun scrollFrame(action: Int, frameMs: Double, over: Boolean) {
        UsageAction.fromNumber(action)?.let { instance?.scrollFrame(it, frameMs, over) }
    }
    fun scanner(photos: Int, elapsedMs: Long) { instance?.scanner(photos, elapsedMs) }
}

object UsageExportFile {
    enum class Result { SHEET_OPENED, NOTHING_RECORDED, WRITE_FAILED, SHARE_FAILED }

    /**
     * Writes `atlas-usage-<yyyymmdd>.json` to the app cache and opens the Android
     * share sheet (ACTION_SEND, user initiated). Reuses the existing
     * `<applicationId>.diagnostics` FileProvider. Nothing is sent anywhere by the app.
     */
    internal fun share(context: Context, log: UsageLog): Result {
        log.flush()
        val json = log.exportJson() ?: return Result.NOTHING_RECORDED
        val uri = try {
            val folder = File(context.cacheDir, "usage")
            if (!folder.isDirectory && !folder.mkdirs()) throw java.io.IOException("Cannot prepare folder")
            folder.listFiles()?.forEach { it.delete() }
            val file = File(folder, log.exportFileName())
            file.writeText(json, Charsets.UTF_8)
            FileProvider.getUriForFile(context, context.packageName + ".diagnostics", file)
        } catch (_: Exception) {
            return Result.WRITE_FAILED
        }
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share usage")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            Result.SHEET_OPENED
        } catch (_: Exception) {
            Result.SHARE_FAILED
        }
    }
}
