package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search-only synthetic metadata: no private gallery, V4 source or device DB.
 * Repeated changes must show results from the latest query and People
 * inventory, never stale matches carried over from a previous generation.
 * Uses the v2 compose rule (issue #452, as #447 did for AsyncPeopleCoversTest):
 * its StandardTestDispatcher resumes the produceState write after
 * withContext(Dispatchers.Default) on the main thread, so the v1 rule's
 * CalledFromWrongThreadException flake cannot occur.
 */
@RunWith(AndroidJUnit4::class)
class AsyncGallerySearchTest {
    @get:Rule val rule = createComposeRule()

    @Test fun typingAndPeopleUpdatesAlwaysDisplayLatestSearchResults() {
        val media = (0 until 15_000).map { index ->
            MediaRecord(
                index.toLong(), "content://atlas.synthetic/search/$index",
                if (index == 42 || index == 9001) "Special_target_$index.jpg"
                    else "IMG_$index.jpg",
                "DCIM/Fictional/",
                sizeBytes = 100, takenAtMillis = index.toLong(), isVideo = false
            )
        }
        val originalPeople = listOf(
            PersonEntry(41L, "Alex Example", setOf(media[5].absolutePath)),
            PersonEntry(77L, "Alex Example", setOf(media[6].absolutePath))
        )
        var source by mutableStateOf(media, referentialEqualityPolicy())
        var people by mutableStateOf(originalPeople, referentialEqualityPolicy())
        var query by mutableStateOf("target")
        var enabled by mutableStateOf(true)

        rule.setContent {
            val snapshot = rememberAsyncGallerySearch(source, people, query, enabled)
            val matches = currentSearchCatalogOrNull(snapshot, source, people, query)
            MaterialTheme {
                Text(
                    if (!enabled || query.isBlank()) "Idle" else if (matches == null)
                        "Searching" else matches.joinToString(",") { it.id.toString() },
                    modifier = Modifier.testTag("atlas-synthetic-search-result")
                )
            }
        }

        fun rendered() = rule.onNodeWithTag("atlas-synthetic-search-result")
            .fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }

        rule.waitUntil(15_000) { rendered() == "42,9001" }
        rule.runOnIdle { query = "alex" }
        rule.waitUntil(15_000) { rendered() == "5,6" }

        // Two distinct reviewed IDs can have identical names; they are not
        // silently merged. Narrowing People must remove only that membership.
        rule.runOnIdle { people = originalPeople.take(1) }
        rule.waitUntil(15_000) { rendered() == "5" }

        // A fresh MediaStore list of equal contents must rerun the worker.
        rule.runOnIdle {
            source = media.toList()
            assertNotSame(media, source)
        }
        rule.waitForIdle()
        rule.waitUntil(15_000) { rendered() == "5" }

        rule.runOnIdle { query = "absolutely-no-match" }
        rule.waitUntil(15_000) { rendered() == "" }
        rule.runOnIdle { query = "target" }
        rule.waitUntil(15_000) { rendered() == "42,9001" }

        rule.runOnIdle { enabled = false }
        rule.onNodeWithTag("atlas-synthetic-search-result").assertTextEquals("Idle")
        rule.runOnIdle { enabled = true; query = "IMG_14999" }
        rule.waitUntil(15_000) { rendered() == "14999" }
    }
}
