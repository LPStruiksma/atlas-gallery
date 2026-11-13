package nl.atlas.gallery

import android.util.Log
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.printToString

/**
 * Shared self-diagnosing wait for journey tests (#641 follow-up of #653):
 * on timeout the failure message carries every root's unmerged semantics
 * tree (popups and dropdowns are separate roots), so the CI job log names
 * the screen the test was on without the shard's test-result artifact.
 */
fun ComposeTestRule.waitForNode(
    matcher: SemanticsMatcher,
    timeoutMillis: Long = 60_000L,
    useUnmergedTree: Boolean = false
) {
    try {
        waitUntil(timeoutMillis) {
            onAllNodes(matcher, useUnmergedTree).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("timed out (${timeoutMillis / 1000} s) at ${callerLine()} waiting for " +
            "${matcher.description}; on screen: ${screenDump(matcher.description)}", timeout)
    }
}

/**
 * Every root's unmerged tree, logged in full. Returned on one line as a
 * compact list of the nodes that name the screen (test tags, texts, state,
 * selection, scroll position), skipping media tiles, so a grid full of
 * thumbnails cannot push the header and tab bar past the cap.
 */
fun SemanticsNodeInteractionsProvider.screenDump(what: String): String {
    val tree = runCatching {
        onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 40)
    }.getOrElse { "tree unavailable: $it" }
    Log.e("AtlasUiWait", "timed out waiting for $what\n$tree")
    val compact = runCatching {
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .mapNotNull { node -> node.summary() }
            .joinToString(" | ")
    }.getOrElse { "compact dump unavailable: $it" }
    return compact.take(6000)
}

private fun SemanticsNode.summary(): String? {
    val c = config
    val tag = c.getOrNull(SemanticsProperties.TestTag)
    if (tag != null && MEDIA_TILE.containsMatchIn(tag)) return null
    val text = c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
    val state = c.getOrNull(SemanticsProperties.StateDescription)
    val selected = c.getOrNull(SemanticsProperties.Selected)
    val scroll = c.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
    if (tag == null && text == null && state == null && selected == null && scroll == null &&
        (desc == null || desc.startsWith("IMG_") || desc.startsWith("[IMG_"))) return null
    val b = boundsInRoot
    return buildString {
        append(tag ?: "#$id")
        text?.let { append(" text='").append(it).append('\'') }
        if (desc != null && !desc.startsWith("IMG_")) append(" desc='").append(desc).append('\'')
        state?.let { append(" state='").append(it).append('\'') }
        if (selected == true) append(" selected")
        scroll?.let { append(" scroll=").append(it.value()).append('/').append(it.maxValue()) }
        append(" @").append(b.top.toInt()).append("..").append(b.bottom.toInt())
    }
}

private val MEDIA_TILE = Regex("-media-|thumbnail-|tile-")

/** The test file and line that called the wait, so a shared helper still names its step. */
private fun callerLine(): String = Throwable().stackTrace
    .firstOrNull { it.fileName?.endsWith("Test.kt") == true && !it.methodName.startsWith("waitFor") }
    ?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown step"
