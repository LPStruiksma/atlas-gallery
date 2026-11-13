package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fictional MediaStore metadata only; no device image permissions or People DB.
 * Uses the v2 compose rule (issue #452, as #447 did for AsyncPeopleCoversTest):
 * its StandardTestDispatcher resumes the produceState write after
 * withContext(Dispatchers.Default) on the main thread, so the v1 rule's
 * CalledFromWrongThreadException flake cannot occur.
 */
@RunWith(AndroidJUnit4::class)
class AsyncAlbumCatalogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun changingInventoryNeverShowsPreviousGenerationAlbums() {
        val old = (0 until 15_000).map { index ->
            MediaRecord(index.toLong(), "content://atlas.synthetic/$index",
                "fake-$index.jpg", "DCIM/Before/", index.toLong(),
                index.toLong(), false)
        }
        var source by mutableStateOf(old, referentialEqualityPolicy())
        rule.setContent {
            val snapshot = rememberAsyncAlbumCatalog(source)
            val valid = currentAlbumCatalogOrNull(snapshot, source)
            MaterialTheme {
                Text(if (valid == null) "Indexing…" else valid.joinToString(";"),
                    modifier = Modifier.testTag("atlas-album-generation"))
            }
        }
        rule.waitUntil(15_000) {
            rule.onNodeWithTag("atlas-album-generation")
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString("") { it.text } == "DCIM/Before/"
        }

        val changed = old.mapIndexed { i, item ->
            if (i % 2 == 0) item.copy(relativePath = "DCIM/After/")
            else item.copy(relativePath = "Pictures/Elsewhere/")
        }
        rule.runOnIdle { source = changed }
        rule.waitUntil(15_000) {
            rule.onNodeWithTag("atlas-album-generation")
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString("") { it.text } ==
                "DCIM/After/;Pictures/Elsewhere/"
        }
        rule.onNodeWithTag("atlas-album-generation")
            .assertTextEquals("DCIM/After/;Pictures/Elsewhere/")

        // Source-reference identity, even for identical metadata, must restart
        // the actual Compose producer so the gallery never gets stuck loading.
        rule.runOnIdle {
            val fresh = changed.toList()
            assertNotSame(changed, fresh)
            source = fresh
        }
        rule.waitUntil(15_000) {
            rule.onNodeWithTag("atlas-album-generation")
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString("") { it.text } ==
                "DCIM/After/;Pictures/Elsewhere/"
        }
    }
}
