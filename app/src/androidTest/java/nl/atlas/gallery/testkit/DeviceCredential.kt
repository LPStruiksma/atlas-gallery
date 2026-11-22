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
            try {
                testShell("settings put secure lock_to_app_exit_locked 0")
                check(testShell("settings get secure lock_to_app_exit_locked").trim() == "0")
                testShell("settings put secure lock_to_app_enabled 1")
                Log.i(TEST_KIT_LOG_TAG, "set-pin: " + testShell("locksettings set-pin $pin").trim())
                base.evaluate()
            } finally {
                runCatching { testShell("am task lock stop") }
                runCatching {
                    if (testShell("dumpsys activity activities").contains("ConfirmDeviceCredential")) {
                        testShell("am force-stop com.android.settings")
                    }
                }
                runCatching { testShell("locksettings clear --old $pin") }
                runCatching { testShell("wm dismiss-keyguard") }
                restore("lock_to_app_enabled", pinning)
                restore("lock_to_app_exit_locked", exitLocked)
            }
        }
    }
    private fun restore(key: String, previous: String) {
        if (previous == "null") testShell("settings delete secure $key")
        else testShell("settings put secure $key $previous")
        check(testShell("settings get secure $key").trim() == previous)
    }
}
