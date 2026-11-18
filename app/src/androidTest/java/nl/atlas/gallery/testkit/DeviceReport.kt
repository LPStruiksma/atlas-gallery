package nl.atlas.gallery.testkit

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.describeFocus

/**
 * One line of device state for failure messages: keyboard, display, animator
 * scale, night mode, focused window and top activity. Settings and window
 * titles only; never view text or media.
 */
fun describeDevice(): String = runCatching {
    val inputMethod = testShell("dumpsys input_method").lineSequence().map { it.trim() }
    val shown = inputMethod.firstOrNull { it.startsWith("mInputShown=") }
        ?.substringBefore(' ') ?: "mInputShown=?"
    listOf(
        "keyboard ${testShell("settings get secure default_input_method")} $shown",
        testShell("wm size").replace("\n", ", "),
        testShell("wm density").replace("\n", ", "),
        "animator scale ${testShell("settings get global animator_duration_scale")}",
        testShell("cmd uimode night"),
        describeFocus()
    ).joinToString(" | ")
}.getOrElse { "device state unavailable (${it.javaClass.simpleName}: ${it.message})" }

/** Runs a shell command as the shell user and returns its trimmed output. */
internal fun testShell(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command)
    // Reading to the end waits for the command to finish.
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        .use { it.readBytes().decodeToString() }.trim()
}
