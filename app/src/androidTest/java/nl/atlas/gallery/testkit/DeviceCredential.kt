package nl.atlas.gallery.testkit

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Gives the emulator a device PIN for one test, so the system's own
 * credential confirmation screen can open, and removes it afterwards, also
 * when the test fails (rule 7). Also ends any screen pinning the test left.
 * Make it an outer rule, around the Activity rule.
 */
class DeviceCredentialRule(val pin: String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            Log.i(TEST_KIT_LOG_TAG, "set-pin: " + testShell("locksettings set-pin $pin").trim())
            try {
                base.evaluate()
            } finally {
                runCatching { testShell("am task lock stop") }
                Log.i(TEST_KIT_LOG_TAG, "clear-pin: " +
                    testShell("locksettings clear --old $pin").trim())
            }
        }
    }
}
