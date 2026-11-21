package nl.atlas.gallery.testkit

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Gives the emulator a device PIN for one test, so the system's own
 * credential confirmation screen can open, and removes it afterwards, also
 * when the test fails (rule 7). Also turns the system's app pinning setting
 * on for the test (so an app's pinning request reaches the OS prompt) and
 * puts it back, and ends any screen pinning or credential screen the test
 * left.
 * Make it an outer rule, around the Activity rule.
 */
class DeviceCredentialRule(val pin: String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val pinning = testShell("settings get secure lock_to_app_enabled").trim()
            testShell("settings put secure lock_to_app_enabled 1")
            Log.i(TEST_KIT_LOG_TAG, "set-pin: " + testShell("locksettings set-pin $pin").trim() +
                "; app pinning on (was $pinning)")
            try {
                base.evaluate()
            } finally {
                runCatching { testShell("am task lock stop") }
                // A confirmation left open by a failed test would cover every later test.
                runCatching {
                    if (testShell("dumpsys activity activities").contains("ConfirmDeviceCredential")) {
                        Log.i(TEST_KIT_LOG_TAG, "closing a credential screen the test left open")
                        testShell("am force-stop com.android.settings")
                    }
                }
                Log.i(TEST_KIT_LOG_TAG, "clear-pin: " +
                    testShell("locksettings clear --old $pin").trim())
                if (pinning == "0" || pinning == "1") testShell("settings put secure lock_to_app_enabled $pinning")
                else testShell("settings delete secure lock_to_app_enabled")
            }
        }
    }
}
