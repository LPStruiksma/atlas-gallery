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
 * Fictional MediaRecord metadata only. Illegal structural list equality
 * makes accidental O(150k) equals work fail deterministically in Compose.
 * No actual photos, Android Trash or People database access.
 */
@RunWith(AndroidJUnit4::class)
class LargeListIdentityKeyTest {
    @get:Rule val rule = createComposeRule()

    private class PoisonEquality(
        private val data: List<MediaRecord>
    ) : List<MediaRecord> by data {
        override fun equals(other: Any?): Boolean =
            error("Compose compared a large media list structurally")
        override fun hashCode(): Int =
            error("Compose hashed a large media list structurally")
    }

    private fun fake(i: Int) = MediaRecord(i.toLong(),
        "content://atlas.synthetic/$i", "fictional-$i.jpg",
        "DCIM/Fictional/", i.toLong() * 100L, i.toLong(), false)

    @Test fun viewerRemountWithEqualMetadataButNewGenerationNeverUsesDeepEquals() {
        val sameContent = (0 until 80).map(::fake)
        var generation by mutableStateOf<List<MediaRecord>>(
            PoisonEquality(sameContent), referentialEqualityPolicy())
        rule.setContent {
            MaterialTheme { GalleryViewer(generation, sameContent[12].uri, onBack = {}) }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("13 of 80")
        rule.runOnIdle { generation = PoisonEquality(sameContent) }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("13 of 80")
        rule.onNodeWithTag("atlas-filmstrip").assertExists()
    }

    @Test fun declutterNewDeckResetsSafelyWithoutComparingEveryMetadataRow() {
        val sameContent = (0 until 12).map(::fake)
        var generation by mutableStateOf<List<MediaRecord>>(
            PoisonEquality(sameContent), referentialEqualityPolicy())
        rule.setContent {
            MaterialTheme {
                DeclutterScreen("DCIM/Fictional/", generation, onExit = {},
                    onTrashRequest = {})
            }
        }
        rule.onNodeWithTag("atlas-declutter-discard").performClick()
        rule.onNodeWithTag("atlas-declutter-review").assertTextContains("Review staged (1)")
        rule.runOnIdle { generation = PoisonEquality(sameContent) }
        rule.onNodeWithTag("atlas-declutter-review").assertTextContains("Review staged (0)")
        rule.onNodeWithTag("atlas-declutter-discard").assertIsEnabled()
    }
}
