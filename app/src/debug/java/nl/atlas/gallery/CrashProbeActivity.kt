package nl.atlas.gallery

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * Debug-only, non-exported and separate-process test fixture.
 * Never present in release builds, and contains only invented media strings.
 */
class CrashProbeActivity : Activity() {
    companion object {
        const val ACTION_CRASH = "nl.atlas.gallery.debug.CRASH"
        const val ACTION_RELAUNCH = "nl.atlas.gallery.debug.RELAUNCH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_CRASH -> {
                Thread({
                    throw IllegalStateException("content://private/person-name /sdcard/secret")
                }, "content://private/person-name").start()
            }
            ACTION_RELAUNCH -> {
                val folder = File(filesDir, "crash")
                folder.mkdirs()
                File(folder, "probe-relaunched").writeText("yes")
                finish()
            }
            else -> finish()
        }
    }
}
