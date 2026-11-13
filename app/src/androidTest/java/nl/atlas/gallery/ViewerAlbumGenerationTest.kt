package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * All source items are fictional; no MediaStore permission, real photo bytes,
 * installed app-private People records, or system Trash interactions.
 */
@RunWith(AndroidJUnit4::class)
class ViewerAlbumGenerationTest {
    @get:Rule val rule = createComposeRule()

    private fun invented(): List<MediaRecord> = (0 until 8).map { i ->
        MediaRecord(
            i.toLong(), "content://atlas.synthetic/viewer/$i", "fake-$i.jpg",
            "DCIM/ATLAS-TEST/", 1024L, i.toLong(), false
        )
    }

    @Test fun shorterReorderedAlbumStartsFromUriInsteadOfRetainingStalePagerIndex() {
        val original = invented()
        var source by mutableStateOf<List<MediaRecord>>(
            original, referentialEqualityPolicy())
        var requestedUri by mutableStateOf(original[5].uri)
        rule.setContent {
            MaterialTheme {
                GalleryViewer(source, requestedUri, onBack = {})
            }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("6 of 8")
        rule.onNodeWithTag("atlas-thumbnail-6").performClick()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("7 of 8")

        // MediaStore permission/query can return a re-ordered shorter album.
        // Photo 6 is still present, but at position zero, not the old index 6.
        rule.runOnIdle {
            requestedUri = original[6].uri
            source = listOf(original[6], original[2])
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("1 of 2")
        rule.onNodeWithTag("atlas-thumbnail-0").assertIsSelected()
        rule.onNodeWithTag("atlas-thumbnail-1").performClick()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("2 of 2")

        // The former URI is no longer accessible; fail closed to the first
        // actually visible record, rather than trying to index a stale page.
        rule.runOnIdle {
            source = listOf(original[1])
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("1 of 1")
        rule.onNodeWithTag("atlas-coarse-seek").assertDoesNotExist()
        rule.onNodeWithTag("atlas-thumbnail-0").assertIsSelected()
    }

    @Test fun explicitNewStartUriReanchorsEvenWhenAlbumReferenceIsUnchanged() {
        val sameAlbum = invented()
        var requestedUri by mutableStateOf(sameAlbum[1].uri)
        rule.setContent {
            MaterialTheme {
                GalleryViewer(sameAlbum, requestedUri, onBack = {})
            }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("2 of 8")
        rule.onNodeWithTag("atlas-thumbnail-4").performClick()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("5 of 8")
        // Not a gallery refresh: parent issued a new explicit open target
        // while keeping the exact same album list object.
        rule.runOnIdle { requestedUri = sameAlbum[6].uri }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("7 of 8")
        rule.onNodeWithTag("atlas-thumbnail-6").assertIsSelected()
    }

    @Test fun equalContentNewGenerationReinitializesToRequestedUri() {
        val original = invented()
        var source by mutableStateOf<List<MediaRecord>>(
            original, referentialEqualityPolicy())
        var requestedUri by mutableStateOf(original[2].uri)
        rule.setContent {
            MaterialTheme {
                GalleryViewer(source, requestedUri, onBack = {})
            }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("3 of 8")
        rule.onNodeWithTag("atlas-thumbnail-3").performClick()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("4 of 8")

        // Structural equality must not prevent a fresh generation from
        // resetting both PagerState and the centered filmstrip anchor.
        rule.runOnIdle {
            source = original.toList()
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("3 of 8")
        rule.onNodeWithTag("atlas-thumbnail-2").assertIsSelected()
    }
}
