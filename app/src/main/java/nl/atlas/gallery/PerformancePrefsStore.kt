package nl.atlas.gallery

import android.content.Context

/**
 * SharedPreferences persistence for [PerformanceAggregates] samples, one set of keys per
 * app version (`v<versionCode>.<metric>`). Values are comma-separated numbers only; no
 * network, no IDs, no paths. Metric names are validated by [PerformanceAggregates].
 */
internal class PerformancePrefsStore(context: Context, private val versionCode: Long) :
    PerformanceAggregates.PerformanceStore {
    private val prefs = context.applicationContext.getSharedPreferences("atlas_perf", Context.MODE_PRIVATE)
    private val prefix = "v$versionCode."

    override fun load(): Map<String, List<Double>> = prefs.all.mapNotNull { (key, value) ->
        val text = value as? String ?: return@mapNotNull null
        if (!key.startsWith(prefix)) return@mapNotNull null
        key.removePrefix(prefix) to text.split(',').mapNotNull { it.toDoubleOrNull() }
    }.toMap()

    override fun save(metric: String, values: List<Double>) {
        prefs.edit().putString(prefix + metric, values.joinToString(",")).apply()
    }

    override fun saveDurably(metric: String, values: List<Double>) {
        // apply() can still be in flight when Android swipe-kills the process.
        // A lifecycle flush must finish the disk write before returning.
        check(prefs.edit().putString(prefix + metric, values.joinToString(",")).commit()) {
            "Performance sample commit failed"
        }
    }

    /** Removes every version except the newest [keepVersions] (including the current one). */
    fun pruneOldVersions(keepVersions: Int) {
        val versions = prefs.all.keys
            .mapNotNull { key -> VERSION.find(key)?.groupValues?.get(1)?.toLongOrNull() }
            .toMutableSet()
        versions.add(versionCode)
        val keep = versions.sortedDescending().take(keepVersions).toSet()
        val edit = prefs.edit()
        prefs.all.keys.forEach { key ->
            val version = VERSION.find(key)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
            if (version !in keep) edit.remove(key)
        }
        edit.apply()
    }

    private companion object {
        val VERSION = Regex("^v(\\d+)\\.")
    }
}
