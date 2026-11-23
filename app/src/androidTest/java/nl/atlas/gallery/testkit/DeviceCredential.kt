package nl.atlas.gallery.testkit

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class DeviceCredentialRule(val pin: String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val pinning = testShell("settings get secure lock_to_app_enabled").trim()
            val exitLocked = testShell("settings get secure lock_to_app_exit_locked").trim()
            var failure: Throwable? = null
            try {
                testShell("settings put secure lock_to_app_exit_locked 0")
                check(testShell("settings get secure lock_to_app_exit_locked").trim() == "0")
                testShell("settings put secure lock_to_app_enabled 1")
                check(testShell("settings get secure lock_to_app_enabled").trim() == "1")
                Log.i(TEST_KIT_LOG_TAG, "set-pin: " + testShell("locksettings set-pin $pin").trim())
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            } finally {
                // Attempt every cleanup even if an earlier operation fails. Never hide
                // the test's original failure behind a restoration failure.
                fun cleanup(action: () -> Unit) {
                    try {
                        action()
                    } catch (t: Throwable) {
                        val primary = failure
                        if (primary == null) failure = t
                        else if (primary !== t) primary.addSuppressed(t)
                    }
                }
                cleanup { testShell("am task lock stop") }
                cleanup {
                    if (testShell("dumpsys activity activities").contains("ConfirmDeviceCredential")) {
                        testShell("am force-stop com.android.settings")
                    }
                }
                cleanup { testShell("locksettings clear --old $pin") }
                cleanup { testShell("wm dismiss-keyguard") }
                cleanup { restore("lock_to_app_enabled", pinning) }
                cleanup { restore("lock_to_app_exit_locked", exitLocked) }
            }
            failure?.let { throw it }
        }
    }

    private fun restore(key: String, previous: String) {
        if (previous == "null") testShell("settings delete secure $key")
        else testShell("settings put secure $key $previous")
        check(testShell("settings get secure $key").trim() == previous) {
            "Failed to restore secure setting $key"
        }
    }
}
