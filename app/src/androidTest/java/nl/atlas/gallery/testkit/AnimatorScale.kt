package nl.atlas.gallery.testkit

import android.util.Log

/**
 * Runs [body] with the global animator duration scale set to [scale] and puts
 * the previous value back afterwards, also when [body] fails (rule 7).
 *
 * CI emulators run at scale 0 (`disable-animations: true`), and the platform
 * then shows the keyboard with no WindowInsetsAnimation at all. A test about
 * what happens while the keyboard opens turns the scale on for its own
 * duration only.
 */
fun <T> withAnimatorScale(scale: String, body: () -> T): T {
    val before = testShell("settings get global animator_duration_scale")
    testShell("settings put global animator_duration_scale $scale")
    Log.i(TEST_KIT_LOG_TAG, "animator scale $scale (was $before)")
    try {
        return body()
    } finally {
        if (before == "null" || before.isEmpty()) {
            testShell("settings delete global animator_duration_scale")
        } else {
            testShell("settings put global animator_duration_scale $before")
        }
        Log.i(TEST_KIT_LOG_TAG, "animator scale restored: " +
            testShell("settings get global animator_duration_scale"))
    }
}
