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
 * Synthetic URI metadata only; no permissions, private media or physical
 * Android Back key are needed. Dispatches through the real Compose host's
 * Android Back dispatcher, exercising the viewer's registered BackHandler.
 */
@RunWith(AndroidJUnit4::class)
class ViewerSystemBackTest {
    @get:Rule val rule = createComposeRule()

    private fun invented() = listOf(
        MediaRecord(731L, "content://media/external/images/media/731",
            "Invented frame", "DCIM/ATLAS-TEST/", 512L, 1L, false)
    )

    @Test fun systemBackLeavesViewerWhenTopBarIsShowing() {
        val media = invented()
        var back = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            val owner = LocalOnBackPressedDispatcherOwner.current
            SideEffect { dispatcher = owner?.onBackPressedDispatcher }
            var opened by remember { mutableStateOf(true) }
            MaterialTheme {
                if (opened) GalleryViewer(media, media.single().uri, onBack = {
                    back++
                    opened = false
                }) else Text("Gallery restored")
            }
        }
        rule.onNodeWithTag("atlas-back").assertExists()
        rule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        rule.onNodeWithText("Gallery restored").assertExists()
        rule.runOnIdle { assertEquals(1, back) }
    }

    @Test fun systemBackStillWorksAfterPhotoHidesAllViewerChrome() {
        val media = invented()
        var back = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            val owner = LocalOnBackPressedDispatcherOwner.current
            SideEffect { dispatcher = owner?.onBackPressedDispatcher }
            var opened by remember { mutableStateOf(true) }
            MaterialTheme {
                if (opened) GalleryViewer(media, media.single().uri, onBack = {
                    back++
                    opened = false
                }) else Text("Gallery restored")
            }
        }
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { click(center) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("atlas-filmstrip").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("atlas-back").assertDoesNotExist()
        rule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        rule.onNodeWithText("Gallery restored").assertExists()
        rule.runOnIdle { assertEquals(1, back) }
    }
}
