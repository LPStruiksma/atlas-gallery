package nl.atlas.gallery

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Test-only helpers for system windows that sit on top of the app on a slow
 * CI emulator. Another process (Quickstep, System UI) can show an
 * "isn't responding" dialog. That dialog takes focus and is not cancelable,
 * so it swallows every Back and every tap meant for the app (#610).
 */
internal const val SYSTEM_WINDOWS_LOG_TAG = "ATLAS_WINDOWS"

/**
 * UiAutomation returns an empty window list unless its service asks for
 * interactive windows (#611 printed "accessibility windows unavailable").
 */
internal fun interactiveUiAutomation(): UiAutomation {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val info = automation.serviceInfo
    if (info != null &&
        info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }
    return automation
}

private fun anrNode(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
    if (node == null || depth > 12) return null
    val text = listOf(node.text, node.contentDescription).joinToString(" ")
    if (text.contains("isn't responding", ignoreCase = true) ||
        text.contains("is not responding", ignoreCase = true)) return node
    return (0 until node.childCount).firstNotNullOfOrNull { anrNode(node.getChild(it), depth + 1) }
}

/** The root of a system "isn't responding" dialog on screen, or null. */
internal fun visibleSystemAnrRoot(): AccessibilityNodeInfo? {
    val automation = interactiveUiAutomation()
    val roots = runCatching { automation.windows.mapNotNull { it.root } }.getOrDefault(emptyList()) +
        listOfNotNull(runCatching { automation.rootInActiveWindow }.getOrNull())
    return roots.firstOrNull { anrNode(it) != null }
}

/**
 * Presses Wait on any system "isn't responding" dialog (or closes system
 * dialogs when it has no Wait button), up to [attempts] times. Returns how
 * many it cleared. Never touches the app's own windows.
 */
internal fun clearSystemAnrDialogs(attempts: Int = 5): Int {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = interactiveUiAutomation()
    var cleared = 0
    repeat(attempts) {
        val root = visibleSystemAnrRoot() ?: return cleared
        Log.w(SYSTEM_WINDOWS_LOG_TAG, "System ANR dialog on screen (${root.packageName}); pressing Wait")
        // Accessibility sometimes exposes the "Wait" label as a text child
        // while its enclosing button is the clickable node. Restrict the
        // action to that exact label inside the detected ANR window; never
        // click a generic positive/negative button or the app underneath.
        val wait = root.findAccessibilityNodeInfosByText("Wait").firstNotNullOfOrNull { label ->
            if (!label.text?.toString()?.trim().equals("Wait", ignoreCase = true)) null
            else generateSequence(label) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        }
        if (wait != null) {
            val pressed = wait.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.w(SYSTEM_WINDOWS_LOG_TAG, "System ANR Wait action accepted=$pressed")
        } else {
            Log.w(SYSTEM_WINDOWS_LOG_TAG, "System ANR has no actionable Wait; trying system dialog close")
            automation.executeShellCommand(
                "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
        }
        cleared++
        instrumentation.waitForIdleSync()
        Thread.sleep(1_000L)
    }
    return cleared
}

private fun windowType(type: Int) = when (type) {
    AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y-overlay"
    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
    else -> "type$type"
}

/**
 * A bounded description of what has focus, for failure messages: up to 12
 * accessibility windows (type, title, package, focused/active) and the
 * window manager's focus lines. Window titles and packages only; never view
 * text or media.
 */
internal fun describeFocus(): String {
    val automation = interactiveUiAutomation()
    val windows = runCatching {
        automation.windows.take(12).joinToString("; ") { w ->
            val pkg = runCatching { w.root?.packageName }.getOrNull() ?: "?"
            val title = w.title?.toString()?.take(60) ?: "-"
            buildString {
                append(windowType(w.type)).append(" '").append(title).append("' ").append(pkg)
                if (w.isFocused) append(" focused")
                if (w.isActive) append(" active")
            }
        }.ifEmpty { "none" }
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    val wm = runCatching {
        val fd = automation.executeShellCommand("dumpsys window")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.startsWith("mCurrentFocus=") || it.startsWith("mFocusedApp=") }
                .distinct().take(4).joinToString("; ") { it.take(160) }
        }.ifEmpty { "none" }
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    return "windows: $windows | wm: $wm"
}
