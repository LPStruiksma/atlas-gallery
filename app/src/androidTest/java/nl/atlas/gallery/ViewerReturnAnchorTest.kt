package nl.atlas.gallery

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic gallery metadata only. The return-anchor contract must never be
 * tied to a stale integer index in a reordered Photos/Search/People catalogue.
 * No real photo, permission, package install or Android original is touched.
 */
@RunWith(AndroidJUnit4::class)
class ViewerReturnAnchorTest {
    @get:Rule val rule = createComposeRule()

    private fun fictionalAlbum() = (0..3).map { i ->
        MediaRecord(i.toLong(), "content://media/external/images/media/${99100 + i}",
            "Fake frame ${i + 1}", "DCIM/ATLAS-SYNTHETIC/", 1L, i.toLong(), false)
    }

    @Test fun toolbarBackReportsLastSettledUriBeforeLeavingViewer() {
        val items = fictionalAlbum()
        val events = mutableListOf<String>()
        rule.setContent {
            var opened by remember { mutableStateOf(true) }
            MaterialTheme {
                if (opened) GalleryViewer(
                    items = items, startUri = items[0].uri,
                    onExitSettledUri = { events += "exit:$it" },
                    onBack = { events += "back"; opened = false }
                ) else Text("Album restored")
            }
        }
        rule.onNodeWithTag("atlas-thumbnail-1").performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("2 of 4").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-back").performClick()
        rule.onNodeWithText("Album restored").assertExists()
        rule.runOnIdle {
            assertEquals(listOf("exit:${items[1].uri}", "back"), events)
        }
    }

    @Test fun systemBackFromImmersiveModeUsesIdenticalExitContract() {
        val items = fictionalAlbum()
        val events = mutableListOf<String>()
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            val owner = LocalOnBackPressedDispatcherOwner.current
            SideEffect { dispatcher = owner?.onBackPressedDispatcher }
            var opened by remember { mutableStateOf(true) }
            MaterialTheme {
                if (opened) GalleryViewer(
                    items = items, startUri = items[2].uri,
                    onExitSettledUri = { events += "exit:$it" },
                    onBack = { events += "back"; opened = false }
                ) else Text("Album restored")
            }
        }
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { click(center) }
        rule.waitUntil(8_000) {
            rule.onAllNodesWithTag("atlas-filmstrip").fetchSemanticsNodes().isEmpty()
        }
        rule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        rule.onNodeWithText("Album restored").assertExists()
        rule.runOnIdle {
            assertEquals(listOf("exit:${items[2].uri}", "back"), events)
        }
    }
}
