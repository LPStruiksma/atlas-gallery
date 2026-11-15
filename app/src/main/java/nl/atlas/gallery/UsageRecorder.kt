package nl.atlas.gallery

import android.content.Context
import java.time.LocalDate

/** App-private, backup-disabled store. All persistence is opt-in; no outbound transport. */
internal class UsageRecorder(private val context: Context) {
    private val preferences = context.getSharedPreferences("atlas_usage_private", Context.MODE_PRIVATE)
    private val log = runCatching {
        UsageAggregates.fromJson(preferences.getString("daily", "") ?: "")
    }.getOrDefault(UsageAggregates())

    @Synchronized fun enabled(): Boolean = preferences.getBoolean("enabled", false)

    @Synchronized fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
    }

    @Synchronized fun action(id: String) {
        if (!enabled()) return
        log.action(LocalDate.now(), id)
        save()
    }

    @Synchronized fun screen(id: String, seconds: Long) {
        if (!enabled()) return
        log.screen(LocalDate.now(), id, seconds)
        save()
    }

    @Synchronized fun performance(id: String, latencyMs: Double, jankFrames: Int, frames: Int) {
        if (!enabled()) return
        log.performance(LocalDate.now(), id, latencyMs, jankFrames, frames)
        save()
    }

    @Synchronized fun scanner(seconds: Long, photos: Long) {
        if (!enabled()) return
        log.scanner(LocalDate.now(), seconds, photos)
        save()
    }

    @Synchronized fun export(): String {
        log.prune(LocalDate.now())
        save()
        return log.toJson()
    }

    @Synchronized fun clear() {
        log.clear()
        preferences.edit().remove("daily").commit()
        UsageExportFile.clear(context)
    }

    @Synchronized private fun save() {
        log.prune(LocalDate.now())
        preferences.edit().putString("daily", log.toJson()).apply()
    }
}
