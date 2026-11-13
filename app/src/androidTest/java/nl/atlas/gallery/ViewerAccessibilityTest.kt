package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No MediaStore permission, installed People records or original photo bytes.
 * Accessible actions are tested on fictional URI metadata only; TalkBack's
 * actual spoken ordering and focus need separate owner-approved device tests.
 */
@RunWith(AndroidJUnit4::class)
class ViewerAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private fun media() = (0L..2L).map { index ->
        MediaRecord(index, "content://media/external/images/media/${index + 9100}",
            "Fictional frame ${index + 1}", "DCIM/ATLAS-TEST/", 100, index,
            isVideo = index == 2L)
    }

    @Test fun focusedThumbnailReportsSelectionAndAccessiblePosition() {
        val images = media()
        rule.setContent {
            MaterialTheme {
                GalleryViewer(images, images[0].uri, onBack = {})
            }
        }
        rule.onNodeWithTag("atlas-thumbnail-0")
            .assert(hasContentDescription("Photo 1 of 3: Fictional frame 1"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithTag("atlas-thumbnail-1")
            .assert(hasContentDescription("Photo 2 of 3: Fictional frame 2"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        rule.onNodeWithTag("atlas-thumbnail-2")
            .assert(hasContentDescription("Video 3 of 3: Fictional frame 3"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        rule.onNodeWithTag("atlas-thumbnail-1").performClick()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("2 of 3")
        rule.onNodeWithTag("atlas-thumbnail-1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        // #509: Samsung has no second position rail under the strip.
        rule.onNodeWithTag("atlas-coarse-seek").assertDoesNotExist()
    }

    @Test fun videoPosterAccessibleActionRestoresFilmstripWithoutStealingPlayerTouch() {
        val items = media()
        rule.setContent {
            MaterialTheme {
                GalleryViewer(items, items[2].uri, onBack = {})
            }
        }
        val surface = hasTestTag("atlas-video-surface") and hasClickAction()
        // The video surface exposes a secondary screen-reader action for
        // the gallery chrome; PlayerView's own touch controls are untouched.
        rule.onNode(surface)
            .assert(hasContentDescription("Video viewer: Fictional frame 3"))
            .assert(hasStateDescription("Gallery controls visible"))
        // The video's semantics OnClick is intentionally *not* a touch
        // listener: physical taps belong to the Media3 player's controls.
        // Exercise TalkBack-style activation instead of injecting a tap.
        rule.onNode(surface).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNode(surface).assert(hasStateDescription("Gallery controls hidden"))
        rule.onNodeWithTag("atlas-filmstrip").assertDoesNotExist()
        rule.onNode(surface).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("atlas-filmstrip").assertExists()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("3 of 3")
    }

    @Test fun accessibilityActivationCanRestoreHiddenGalleryControls() {
        val images = media()
        rule.setContent {
            MaterialTheme { GalleryViewer(images, images[1].uri, onBack = {}) }
        }
        // Only the focused surface offers the accessibility action; adjacent
        // precomposed pages must not show controls or hijack TalkBack focus.
        val focused = hasTestTag("atlas-photo-surface") and hasClickAction()
        rule.onAllNodes(focused).assertCountEquals(1)
        rule.onNode(focused).assert(hasStateDescription("Gallery controls visible"))
        // Invoke the advertised accessibility action, not a raw pointer
        // gesture that can be consumed by the photo's transformable layer.
        rule.onNode(focused).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNode(focused).assert(hasStateDescription("Gallery controls hidden"))
        rule.onNodeWithTag("atlas-filmstrip").assertDoesNotExist()
        rule.onNode(focused).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("atlas-filmstrip").assertExists()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("2 of 3")
    }
}
