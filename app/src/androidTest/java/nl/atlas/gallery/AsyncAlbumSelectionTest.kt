package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Purely fictional metadata; no Android MediaStore access, user photos, or
 * actual installed People DB. Source/key changes must never show stale rows.
 * Uses the v2 compose rule (issue #452, as #447 did for AsyncPeopleCoversTest):
 * its StandardTestDispatcher resumes the produceState write after
 * withContext(Dispatchers.Default) on the main thread, so the v1 rule's
 * CalledFromWrongThreadException flake cannot occur.
 */
@RunWith(AndroidJUnit4::class)
class AsyncAlbumSelectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rapidlySwitchingFoldersAndMediaGenerationNeverReusesWrongAlbum() {
        val all = (0 until 15_000).map { index ->
            MediaRecord(index.toLong(), "content://atlas.synthetic/$index",
                "synthetic-$index.jpg",
                if (index % 2 == 0) "DCIM/Fictional A/" else "DCIM/Fictional B/",
                1024, index.toLong(), false)
        }
        var source by mutableStateOf(all, referentialEqualityPolicy())
        var album by mutableStateOf<String?>("DCIM/Fictional A/")
        rule.setContent {
            val snapshot = rememberAsyncAlbumSelection(source, album)
            val current = currentAlbumSelectionOrNull(snapshot, source, album)
            val visible = if (album == null) source else current.orEmpty()
            MaterialTheme {
                Text(
                    if (album != null && current == null) "Filtering…" else
                        "${album ?: "All"}: ${visible.size}: ${visible.firstOrNull()?.id ?: -1}",
                    modifier = Modifier.testTag("atlas-selected-album")
                )
            }
        }
        // Compose's semantics test APIs synchronize with the UI and must run
        // off main; waitUntil invokes this predicate on the instrumentation thread.
        fun rendered(): String = rule.onNodeWithTag("atlas-selected-album")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }

        rule.waitUntil(15_000) { rendered() == "DCIM/Fictional A/: 7500: 0" }
        rule.runOnIdle { album = "DCIM/Fictional B/" }
        rule.waitUntil(15_000) { rendered() == "DCIM/Fictional B/: 7500: 1" }
        rule.runOnIdle { album = null }
        rule.waitUntil(15_000) { rendered() == "All: 15000: 0" }

        // Same metadata, new MediaStore generation. A new source reference
        // must invalidate old filtered items even if values are identical.
        rule.runOnIdle {
            source = all.toList()
            album = "DCIM/Fictional A/"
        }
        rule.waitUntil(15_000) { rendered() == "DCIM/Fictional A/: 7500: 0" }

        val replaced = all.map { it.copy(relativePath = "DCIM/New Folder/") }
        rule.runOnIdle {
            source = replaced
            album = "DCIM/New Folder/"
        }
        rule.waitUntil(15_000) { rendered() == "DCIM/New Folder/: 15000: 0" }
        rule.onNodeWithTag("atlas-selected-album")
            .assertTextEquals("DCIM/New Folder/: 15000: 0")
    }
}
