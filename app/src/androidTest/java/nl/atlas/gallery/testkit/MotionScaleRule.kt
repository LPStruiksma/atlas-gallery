package nl.atlas.gallery.testkit

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** System animator scales are device-wide state: restore them even after assertion failure. */
class MotionScaleRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val keys = listOf("animator_duration_scale", "transition_animation_scale")
                val before = keys.associateWith { motionShell("settings get global $it").trim() }
                keys.forEach { motionShell("settings put global $it 1") }
                try {
                    base.evaluate()
                } finally {
                    before.forEach { (key, old) ->
                        if (old == "null" || old.isBlank()) motionShell("settings delete global $key")
                        else motionShell("settings put global $key $old")
                    }
                }
            }
        }
}

/** Live reduced-motion switch, scoped by [MotionScaleRule]'s restoration. */
fun setAnimatorScale(scale: Int) {
    require(scale == 0 || scale == 1)
    motionShell("settings put global animator_duration_scale $scale")
}

private fun motionShell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd)
        .use { it.readBytes().decodeToString() }
}
