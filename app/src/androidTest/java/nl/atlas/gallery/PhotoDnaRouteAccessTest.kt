package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurecuration.hashCurationBytes
import nl.atlas.gallery.featurepersistence.CurationKeepStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * E2d (#445): the Photo DNA route as MainActivity wires it, on invented
 * records. No tokens means a no-access message, never an endless spinner;
 * an explicit Keep tap is saved as a receipt and marks the group decided.
 *
 * Uses the v2 compose rule (#456): the Keep coroutine resumes after
 * withContext(Dispatchers.IO) and then shows a Toast or updates state. The v1
 * rule's unconfined effect dispatcher could resume it on an IO worker
 * ("Can't toast on a thread that has not called Looper.prepare()"), as in #446.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaRouteAccessTest {
    @get:Rule val rule = createComposeRule()

    private val grant = GalleryMediaGrant(images = true, videos = false, selected = false)
    private val base = 9_200_000L + (System.nanoTime() and 0xffff)
    private val records = (1L..3L).map { n ->
        MediaRecord(
            id = base + n, uri = "content://media/external/images/media/${base + n}",
            name = "ATLAS_DNA_ROUTE_$n.png", relativePath = "Pictures/ATLAS-PhotoDna-Route/",
            sizeBytes = 40L, takenAtMillis = 1_700_000_000_000L + n, isVideo = false,
            modifiedAtSeconds = 1_700_000_000L
        )
    }
    private val bytes = records.associate { it.uri to ByteArray(40) { 3 } }
    private fun hash(record: MediaRecord) = hashCurationBytes(record.sizeBytes,
        openAuthorized = { bytes[record.uri]?.let(::ByteArrayInputStream) },
        stillAuthorized = { true })

    @Test fun noTokensShowNoAccessAndAKeepTapIsSavedAndShownDecided() {
        val tokens = requireNotNull(photoDnaTokensOrNull(grant, 5L, true, records))
        val planned = planPhotoDna(records, tokens, ::hash)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var accessible by mutableStateOf(false)
        var result by mutableStateOf<PhotoDnaResult?>(null)
        rule.setContent {
            MaterialTheme {
                PhotoDnaRoute(
                    result = result, verifying = false, onBack = {}, onOpenPhoto = {},
                    onReviewRequested = {}, accessible = accessible,
                    hashForKeep = { record, _ -> hash(record) },
                    unchanged = { _, _ -> true }
                )
            }
        }
        // Revoked: no tokens at all. A message, no spinner.
        rule.onNodeWithTag("atlas-photo-dna-no-access").assertExists()
        rule.onNodeWithTag("atlas-photo-dna-progress").assertDoesNotExist()
        rule.onNodeWithTag("atlas-photo-dna-status").assertTextContains("No access to your photos", substring = true)

        // Authorized and still planning: the spinner is honest.
        accessible = true
        rule.onNodeWithTag("atlas-photo-dna-progress").assertExists()
        rule.onNodeWithTag("atlas-photo-dna-no-access").assertDoesNotExist()

        result = planned
        waitFor(hasTestTag("photo-dna-exact-group"))
        rule.onAllNodes(hasTestTag("photo-dna-keeper"))[0].assertIsSelected()
        rule.onAllNodes(hasTestTag("photo-dna-keeper"))[1].performClick()
        rule.onAllNodes(hasTestTag("photo-dna-keeper"))[1].assertIsSelected()
        val group = planned.plan.exactGroups.single()
        assertEquals(records[1].uri, group.suggestedKeeperUri)
        val saved = CurationKeepStore(context).use {
            it.resolveCurrentlyValid(listOf(photoDnaKeepFamily(group)))
        }
        assertEquals(records[1].uri, saved.single().keeperKey)

        // A plan that is on screen when access goes is hidden, not acted on.
        accessible = false
        rule.onNodeWithTag("atlas-photo-dna-no-access").assertExists()
        rule.onNodeWithTag("photo-dna-exact-group").assertDoesNotExist()
        rule.onNodeWithTag("atlas-photo-dna-progress").assertDoesNotExist()
    }

    private fun waitFor(matcher: SemanticsMatcher) = rule.waitForNode(matcher, 20_000L)
}
