package nl.atlas.gallery.testkit

import android.app.ActivityManager
import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** The disposable emulator test host only, never a physical device. */
class DeviceCredentialRule(val pin: String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            check(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("emulator")) {
                "Synthetic credential fixture requires an emulator"
            }
            require(pin.matches(Regex("[0-9]{4,16}"))) { "Expected a synthetic numeric PIN" }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val activityManager = context.getSystemService(ActivityManager::class.java)
            check(!keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) {
                "Synthetic credential fixture requires an initially unlocked, unsecured host"
            }
            val pinning = checkedShell("read pinning", "settings get secure lock_to_app_enabled")
            val exitLocked = checkedShell("read lock-on-unpin", "settings get secure lock_to_app_exit_locked")
            var failure: Throwable? = null
            var pinAttempted = false
            try {
                checkedShell("disable lock-on-unpin", "settings put secure lock_to_app_exit_locked 0")
                check(checkedShell("read lock-on-unpin", "settings get secure lock_to_app_exit_locked") == "0")
                checkedShell("enable pinning", "settings put secure lock_to_app_enabled 1")
                check(checkedShell("read pinning", "settings get secure lock_to_app_enabled") == "1")
                pinAttempted = true
                checkedShell("set synthetic PIN", "locksettings set-pin $pin")
                awaitDevice("synthetic credential installed") { keyguard.isDeviceSecure }
                check(checkedShell("verify synthetic PIN", "locksettings verify --old $pin") ==
                    "Lock credential verified successfully") { "Synthetic credential verification failed" }
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            } finally {
                fun cleanup(action: () -> Unit) {
                    try {
                        action()
                    } catch (t: Throwable) {
                        val primary = failure
                        if (primary == null) failure = t
                        else if (primary !== t) primary.addSuppressed(t)
                    }
                }
                cleanup {
                    if (activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                        checkedShell("stop synthetic pin", "am task lock stop")
                    }
                    awaitDevice("unpinned") { activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE }
                }
                cleanup {
                    if (credentialOnTop()) checkedShell("close synthetic credential prompt", "am force-stop com.android.settings")
                    awaitDevice("credential prompt closed") { !credentialOnTop() }
                }
                cleanup {
                    // Setup can mutate state even if the command reports failure.
                    // Pre-existing credentials were rejected before any mutation.
                    if (pinAttempted && keyguard.isDeviceSecure) {
                        checkedShell("clear synthetic PIN", "locksettings clear --old $pin")
                    }
                    awaitDevice("synthetic credential removed") { !keyguard.isDeviceSecure }
                }
                cleanup {
                    if (keyguard.isKeyguardLocked) checkedShell("dismiss synthetic keyguard", "wm dismiss-keyguard")
                    awaitDevice("keyguard unlocked") { !keyguard.isKeyguardLocked }
                }
                cleanup { restore("lock_to_app_enabled", pinning) }
                cleanup { restore("lock_to_app_exit_locked", exitLocked) }
            }
            failure?.let { throw it }
        }
    }

    private fun restore(key: String, previous: String) {
        check(previous == "null" || previous.matches(Regex("-?[0-9]+"))) { "Unexpected prior secure setting $key" }
        if (previous == "null") checkedShell("delete $key", "settings delete secure $key")
        else checkedShell("restore $key", "settings put secure $key $previous")
        check(checkedShell("read $key", "settings get secure $key") == previous) {
            "Failed to restore secure setting $key"
        }
    }

    private fun credentialOnTop(): Boolean =
        checkedShell("read resumed activity", "dumpsys activity activities").lineSequence()
            .filter { it.contains("mResumedActivity") || it.contains("topResumedActivity") }
            .any { it.contains("ConfirmLock") || it.contains("ConfirmDeviceCredential") }

    private fun awaitDevice(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Synthetic credential postcondition failed: $label" }
            SystemClock.sleep(50L) // bounded polling of a system predicate
        }
    }

    private fun checkedShell(label: String, command: String): String {
        check(!command.contains('\'') && !command.contains('\n'))
        // UiAutomation executes an argv string, not an interactive shell; feed
        // the script over stdin so quotation and the exit marker reach sh intact.
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "Credential fixture requires API 31+" }
        val descriptors = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { stdout ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter().use { stdin ->
                stdin.write("$command\nprintf '\\nATLAS_CREDENTIAL_EXIT=%s\\n' \"\$?\"\nexit\n")
            }
            stdout.bufferedReader().readText().trim()
        }
        val lastLine = output.lineSequence().lastOrNull()
        val status = lastLine?.takeIf { it.startsWith("ATLAS_CREDENTIAL_EXIT=") }
            ?.substringAfter('=')?.toIntOrNull()
        // Never log the command or its output: locksettings can echo the PIN.
        check(status == 0) { "$label failed (shell exit: $status)" }
        return output.substringBeforeLast("ATLAS_CREDENTIAL_EXIT=").trim()
    }
}
