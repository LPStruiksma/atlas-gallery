package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses only synthetic metadata; no images, phone MediaStore or private V4 DB.
 * The actual Compose producer must never publish stale ordering on a key flip.
 * Uses the v2 compose rule (issue #452, as #447 did for AsyncPeopleCoversTest):
 * its StandardTestDispatcher resumes the produceState write after
 * withContext(Dispatchers.Default) on the main thread, so the v1 rule's
 * CalledFromWrongThreadException flake cannot occur.
 */
@RunWith(AndroidJUnit4::class)
class AsyncCatalogSortTest {
    @get:Rule val rule = createComposeRule()

    @Test fun backgroundSortingRespondsToSeedAndOrderChanges() {
        val assets = (0 until 15_000).map { index ->
            MediaRecord(
                index.toLong(),
                "content://atlas.synthetic/$index",
                "Synthetic photo $index",
                "DCIM/SYNTHETIC/",
                sizeBytes = (index + 1).toLong(),
                takenAtMillis = index.toLong(),
                isVideo = false
            )
        }
        var order by mutableStateOf(SortOrder.NEWEST)
        var seed by mutableLongStateOf(7L)
        // Unlike structural StateFlow equality, this simulates a fresh
        // MediaStore generation even when all metadata values match.
        var source by mutableStateOf(assets, referentialEqualityPolicy())
        rule.setContent {
            val snapshot = rememberAsyncSortedCatalog(source, order, seed)
            val valid = currentSortedCatalogOrNull(snapshot, source, order, seed)
            MaterialTheme {
                Text(
                    text = if (valid == null) "Ordering…" else
                        "${valid.first().id} of ${valid.size}",
                    modifier = Modifier.testTag("atlas-async-sort-result")
                )
            }
        }
        fun renderedText(): String = rule.onNodeWithTag("atlas-async-sort-result")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }

        rule.waitUntil(15_000) { renderedText() == "14999 of 15000" }
        rule.runOnIdle { order = SortOrder.OLDEST }
        rule.waitUntil(15_000) { renderedText() == "0 of 15000" }

        val randomFirst = sortMedia(assets, SortOrder.RANDOM, 77L).first().id
        rule.runOnIdle {
            order = SortOrder.RANDOM
            seed = 77L
        }
        rule.waitUntil(15_000) { renderedText() == "${randomFirst} of 15000" }

        // Critical: a new list with equal contents must not leave the
        // gallery forever stuck in "Ordering…" after the stale result
        // is rejected by the source-identity guard.
        rule.runOnIdle {
            source = assets.toList()
            org.junit.Assert.assertNotSame(assets, source)
        }
        rule.waitForIdle()
        rule.waitUntil(15_000) { renderedText() == "${randomFirst} of 15000" }

        rule.runOnIdle {
            seed = 78L
            order = SortOrder.NEWEST
        }
        rule.waitUntil(15_000) { renderedText() == "14999 of 15000" }
        rule.onNodeWithTag("atlas-async-sort-result")
            .assertTextEquals("14999 of 15000")
        assertEquals("All metadata remains available after sort transitions", 15_000, assets.size)
    }
    /**
     * Release-scale, synthetic-only Android/Compose acceptance gate.
     *
     * Exercises all 150k concrete MediaRecord entries through the production
     * async sorter, album-index producer and exact-folder selection. The UI
     * renders one text node, not 150k thumbnails; this does NOT prove 150k
     * MediaStore provider throughput, decoder RSS or physical-device thermals.
     */
    @Test fun synthetic150kCatalogCompletesAsyncSortAlbumIndexAndSelection() {
        val count = 150_000
        val targetFolder = "DCIM/ATLAS-TARGET/"
        val otherFolder = "DCIM/ATLAS-OTHER/"
        val items = (0 until count).map { index ->
            MediaRecord(
                index.toLong(),
                "content://atlas.synthetic/large/$index",
                "Fictional entry $index",
                if (index % 10 == 0) targetFolder else otherFolder,
                sizeBytes = (index + 1).toLong(),
                takenAtMillis = index.toLong(),
                isVideo = false
            )
        }
        var order by mutableStateOf(SortOrder.NEWEST)
        var folder by mutableStateOf<String?>(null)
        rule.setContent {
            val sortResult = rememberAsyncSortedCatalog(items, order, 9L)
            val sorted = currentSortedCatalogOrNull(sortResult, items, order, 9L)
            val albumResult = rememberAsyncAlbumCatalog(items)
            val albums = currentAlbumCatalogOrNull(albumResult, items)
            val selectionResult = if (sorted == null) null else
                rememberAsyncAlbumSelection(sorted, folder)
            val selection = if (sorted == null) null else
                currentAlbumSelectionOrNull(selectionResult, sorted, folder)
            MaterialTheme {
                Text(
                    text = if (sorted == null || albums == null || selection == null)
                        "Processing" else
                        "${selection.size}/${sorted.size}/${selection.firstOrNull()?.id}/${albums.size}",
                    modifier = Modifier.testTag("atlas-150k-catalog-status")
                )
            }
        }
        fun status(): String = rule.onNodeWithTag("atlas-150k-catalog-status")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }

        // 150k real in-memory metadata objects, not a virtual size-only list.
        rule.waitUntil(60_000) { status() == "150000/150000/149999/2" }
        rule.runOnIdle { folder = targetFolder }
        rule.waitUntil(60_000) { status() == "15000/150000/149990/2" }
        rule.runOnIdle { order = SortOrder.OLDEST }
        rule.waitUntil(60_000) { status() == "15000/150000/0/2" }
        rule.onNodeWithTag("atlas-150k-catalog-status")
            .assertTextEquals("15000/150000/0/2")
        assertEquals(count, items.size)
    }

}
