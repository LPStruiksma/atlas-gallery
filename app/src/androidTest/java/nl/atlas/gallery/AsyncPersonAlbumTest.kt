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
 * Only fictional confirmed-path mappings; never reads a real People database.
 * Uses the v2 compose rule (issue #452, as #447 did for AsyncPeopleCoversTest):
 * its StandardTestDispatcher resumes the produceState write after
 * withContext(Dispatchers.Default) on the main thread, so the v1 rule's
 * CalledFromWrongThreadException flake cannot occur.
 */
@RunWith(AndroidJUnit4::class)
class AsyncPersonAlbumTest {
    @get:Rule val rule = createComposeRule()

    @Test fun switchingReviewedPersonAndBwFilterDropsSupersededSelections() {
        // Reviewed V4 identities are admitted only for primary-volume
        // MediaStore *image* URIs (mayAttributeReviewedV4Person). Use that
        // exact synthetic shape; no provider is queried.
        val media = (0 until 12_000).map { index ->
            MediaRecord(index.toLong(), "content://media/external/images/media/$index",
                "fake-$index.jpg",
                if (index % 2 == 0) "DCIM/Black and White/" else "DCIM/Elsewhere/",
                1024L, index.toLong(), false)
        }
        val alice = PersonEntry(41L, "Alex Example",
            setOf(media[0].absolutePath, media[1].absolutePath))
        val homonym = PersonEntry(42L, "Alex Example",
            setOf(media[3].absolutePath))
        var sorted by mutableStateOf(media, referentialEqualityPolicy())
        var people by mutableStateOf(listOf(alice, homonym), referentialEqualityPolicy())
        var personId by mutableStateOf<Long?>(41L)
        var bw by mutableStateOf(false)
        rule.setContent {
            val snap = rememberAsyncPersonAlbum(sorted, people, personId, bw)
            val valid = currentPersonAlbumOrNull(snap, sorted, people, personId, bw)
            MaterialTheme {
                Text(
                    if (personId != null && valid == null) "Filtering People…" else
                        "${personId ?: -1}:$bw:${valid?.size ?: 0}:" +
                        "${valid?.firstOrNull()?.id ?: -1}",
                    modifier = Modifier.testTag("atlas-async-person-album")
                )
            }
        }
        fun visible(): String = rule.onNodeWithTag("atlas-async-person-album")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
        rule.waitUntil(15_000) { visible() == "41:false:2:0" }
        rule.runOnIdle { bw = true }
        rule.waitUntil(15_000) { visible() == "41:true:1:0" }
        rule.runOnIdle { personId = 42L }
        rule.waitUntil(15_000) { visible() == "42:true:0:-1" }
        rule.runOnIdle { bw = false }
        rule.waitUntil(15_000) { visible() == "42:false:1:3" }

        // A fresh equal-content database roster AND a new MediaStore list
        // must invalidate stale results even when their values match.
        rule.runOnIdle {
            people = people.toList()
            sorted = media.toList()
        }
        rule.waitUntil(15_000) { visible() == "42:false:1:3" }

        // User-confirmed membership changes on import; an old album cannot
        // persist just because the person display name remains identical.
        rule.runOnIdle {
            people = listOf(alice, homonym.copy(imagePaths = setOf(media[5].absolutePath)))
        }
        rule.waitUntil(15_000) { visible() == "42:false:1:5" }
        rule.onNodeWithTag("atlas-async-person-album")
            .assertTextEquals("42:false:1:5")
    }
}
