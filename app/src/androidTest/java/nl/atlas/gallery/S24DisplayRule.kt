package nl.atlas.gallery

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Renders at the owner's Galaxy S24+ size (1080×2340, density 450) for the
 * test, then restores the emulator's own display. Shared by evidence tests.
 */
internal class S24DisplayRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                shell("wm size 1080x2340")
                shell("wm density 450")
                try {
                    base.evaluate()
                } finally {
                    shell("wm size reset")
                    shell("wm density reset")
                }
            }
        }

    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        // Reading to EOF waits for the command to finish.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
