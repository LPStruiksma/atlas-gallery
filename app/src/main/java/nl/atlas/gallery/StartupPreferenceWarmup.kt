package nl.atlas.gallery

import android.content.Context

/**
 * #435 (PERF-7b): the first Photos grid reads these app-private preference
 * files on Main to pick its initial sort, album order, timeline density and
 * People opt-in. Android caches an opened file per name for the application
 * context and loads it on a background thread, so opening each one here, off
 * Main, at process start means the grid's own synchronous reads find it
 * loaded instead of waiting on the disk.
 *
 * Callers must open these names through `applicationContext`: an Activity's
 * own context resolves the file path again, which checks the preferences
 * directory on disk.
 */
internal object StartupPreferenceWarmup {
    val NAMES = listOf(
        "atlas_album_order",
        "atlas_media_sort",
        "atlas_featuregrid_density",
        "atlas_people_discovery",
        "atlas_v4_live_sync",
    )

    /** Blocking; call from a background thread. */
    fun warm(context: Context) {
        val app = context.applicationContext
        for (name in NAMES) {
            // Any read waits for the background load to finish.
            app.getSharedPreferences(name, Context.MODE_PRIVATE).contains("")
        }
    }
}
