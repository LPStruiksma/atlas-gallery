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
 * Purely fictional People IDs/photo paths; neither local DB nor images read.
 * Uses the v2 compose rule: its StandardTestDispatcher resumes the
 * produceState write after withContext(Dispatchers.Default) on the main
 * thread. The v1 rule's unconfined effect dispatcher could resume it on a
 * DefaultDispatcher worker (CalledFromWrongThreadException, issue #446).
 */
@RunWith(AndroidJUnit4::class)
class AsyncPeopleCoversTest {
    @get:Rule val rule = createComposeRule()

    private fun photo(id: Int) = MediaRecord(id.toLong(),
        "content://atlas.synthetic/$id", "fictional-$id.jpg",
        "DCIM/ATLAS-TEST/", 1024, id.toLong(), false)

    @Test fun updatedRosterAndMediaGenerationDoNotExposeOldPeopleCountsOrCovers() {
        val all = (0 until 15_000).map(::photo)
        var media by mutableStateOf(all, referentialEqualityPolicy())
        val person = PersonEntry(7L, "Alex Example",
            setOf(all[0].absolutePath, all[14999].absolutePath))
        var people by mutableStateOf(listOf(person), referentialEqualityPolicy())
        rule.setContent {
            val snapshot = rememberAsyncPeopleCovers(media, people)
            val valid = currentPeopleCoversOrNull(snapshot, media, people)
            MaterialTheme {
                Text(
                    if (valid == null) "Matching confirmed photos locally…" else
                        "${valid.accessibleCounts[7L] ?: 0}:" +
                        "${valid.coversByPersonId[7L]?.id ?: -1}",
                    modifier = Modifier.testTag("atlas-people-covers-state")
                )
            }
        }
        fun rendered(): String = rule.onNodeWithTag("atlas-people-covers-state")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
        // Expected first cover follows PersonEntry.imagePaths iteration
        // semantics; both are confirmed and exist at their exact paths.
        rule.waitUntil(15_000) { rendered().startsWith("2:") }
        rule.runOnIdle {
            people = listOf(person.copy(imagePaths = setOf(all[42].absolutePath)))
        }
        rule.waitUntil(15_000) { rendered() == "1:42" }
        rule.runOnIdle {
            media = all.map { it.copy(relativePath = "DCIM/AFTER-PERMISSIONS/") }
        }
        rule.waitUntil(15_000) { rendered() == "0:-1" }
        // Same metadata under a fresh inventory generation must not
        // structurally reuse stale matching records.
        rule.runOnIdle { media = media.toList() }
        rule.waitUntil(15_000) { rendered() == "0:-1" }
        rule.onNodeWithTag("atlas-people-covers-state").assertTextEquals("0:-1")
    }
}
