package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurecuration.CurationReviewRequest
import nl.atlas.gallery.featurecuration.PhotoDnaReviewScreen
import nl.atlas.gallery.featurecuration.CurationVisualHash
import nl.atlas.gallery.featurecuration.hashCurationBytes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * E2e (#450) on the Photo DNA route with invented records: a visual pair from
 * the perceptual-hash pass is shown and hidden again on revoke; a Keep tap
 * that is refused, or made while verifying, puts the radio back on the
 * suggested keeper (#449 receipt note 1).
 *
 * Uses the v2 compose rule (#456): the Keep coroutine resumes after
 * withContext(Dispatchers.IO) and then shows a Toast or updates state. The v1
 * rule's unconfined effect dispatcher could resume it on an IO worker
 * ("Can't toast on a thread that has not called Looper.prepare()"), as in #446.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaVisualRouteTest {
    @get:Rule val rule = createComposeRule()

    private val grant = GalleryMediaGrant(images = true, videos = false, selected = false)
    private val base = 9_300_000L + (System.nanoTime() and 0xffff)
    private fun record(n: Long, size: Long) = MediaRecord(
        id = base + n, uri = "content://media/external/images/media/${base + n}",
        name = "ATLAS_DNA_VISUAL_$n.png", relativePath = "Pictures/ATLAS-PhotoDna-Visual-Route/",
        sizeBytes = size, takenAtMillis = 1_700_000_000_000L + n, isVideo = false,
        modifiedAtSeconds = 1_700_000_000L
    )
    // 1..3 byte-identical; 4 a near visual match of 1 with another size.
    private val records = listOf(record(1, 40), record(2, 40), record(3, 40), record(4, 70))
    private val bytes = records.associate { it.uri to ByteArray(it.sizeBytes.toInt()) { 3 } }
    private fun hash(record: MediaRecord) = hashCurationBytes(record.sizeBytes,
        openAuthorized = { bytes[record.uri]?.let(::ByteArrayInputStream) },
        stillAuthorized = { true })

    private fun planned(): PhotoDnaResult {
        val tokens = requireNotNull(photoDnaTokensOrNull(grant, 7L, true, records))
        val result = planPhotoDna(records, tokens, ::hash)
        val near = 0x1234_5678_9abc_def0L
        val pass = planPhotoDnaVisual(records, tokens, result.plan, PhotoDnaVisualCache(),
            decode = { CurationVisualHash(if (it == records[3]) near xor 0b11L else near, true) },
            stillAuthorized = { true })
        result.addVisualPairs(pass)
        return result
    }

    @Test fun oneSelectionCirclePerCopyAndKeepCanMoveWithoutStaging() {
        val plan = planned().plan
        var request: CurationReviewRequest? = null
        rule.setContent {
            MaterialTheme {
                PhotoDnaReviewScreen(
                    plan = plan,
                    thumbnail = { _, modifier -> Box(modifier) },
                    onReviewRequested = { request = it },
                    onComparePair = {},
                    onKeeperChosen = { _, _ -> }
                )
            }
        }
        waitFor(hasTestTag("photo-dna-exact-group"))
        // A radio row would leave an extra control below each tile; the new
        // Keep affordance is the tappable caption, not a second radio.
        assertEquals(1, rule.onAllNodes(hasTestTag("photo-dna-keep-badge")).fetchSemanticsNodes().size)
        rule.onAllNodes(hasTestTag("photo-dna-keeper"))[0].assertIsSelected()
        rule.onAllNodes(hasTestTag("photo-dna-final-review"))[0].assertIsNotEnabled()
        rule.onAllNodes(hasTestTag("photo-dna-stage"))[1].performClick()
        rule.onAllNodes(hasTestTag("photo-dna-final-review"))[0].assertIsEnabled()
        // Promoting an explicitly selected copy to keeper also unstages it.
        rule.onAllNodes(hasTestTag("photo-dna-keeper"))[1].performClick()
        rule.onAllNodes(hasTestTag("photo-dna-keeper"))[1].assertIsSelected()
        rule.onAllNodes(hasTestTag("photo-dna-final-review"))[0].assertIsNotEnabled()
        rule.onAllNodes(hasTestTag("photo-dna-stage"))[0].performClick()
        rule.onAllNodes(hasTestTag("photo-dna-final-review"))[0].performClick()
        assertEquals(setOf(records[0].uri), request?.explicitlySelectedUris)
        check(rule.onAllNodes(hasText("None selected")).fetchSemanticsNodes().isEmpty())
        check(rule.onAllNodes(hasText("Keeping the first copy", substring = true))
            .fetchSemanticsNodes().isEmpty())
    }

    @Test fun aVisualPairIsShownAndHiddenOnRevoke() {
        val result = planned()
        // 1-4, 2-4 and 3-4 are near; 1-2-3 are one exact group, not pairs.
        check(result.plan.visualPairs.size == 3) { "pairs: ${result.plan.visualPairs.size}" }
        var accessible by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                PhotoDnaRoute(result = result, verifying = false, onBack = {}, onOpenPhoto = {},
                    onReviewRequested = {}, accessible = accessible)
            }
        }
        waitFor(hasText("Similar photos · 3 pairs", substring = true))
        val pair = hasTestTag("photo-dna-visual-pair") and
            hasAnyDescendant(hasText(records[3].name))
        rule.onNodeWithTag("photo-dna-list").performScrollToNode(pair)
        waitFor(pair)

        accessible = false
        rule.onNodeWithTag("atlas-photo-dna-no-access").assertExists()
        rule.onNodeWithTag("photo-dna-visual-pair").assertDoesNotExist()
    }

    @Test fun aRefusedOrIgnoredKeepPutsTheRadioBack() {
        val result = planned()
        var verifying by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                PhotoDnaRoute(result = result, verifying = verifying, onBack = {}, onOpenPhoto = {},
                    onReviewRequested = {},
                    // The fresh re-read fails: the save is refused, nothing is stored.
                    hashForKeep = { _, _ -> null },
                    unchanged = { _, _ -> true })
            }
        }
        waitFor(hasTestTag("photo-dna-exact-group"))
        val radios = { rule.onAllNodes(hasTestTag("photo-dna-keeper")) }
        radios()[0].assertIsSelected()

        radios()[1].performClick()
        // Refused after the re-read: back on the suggested keeper.
        rule.waitUntil(20_000L) {
            radios()[0].fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }
        }
        radios()[1].assertIsNotSelected()
        rule.onAllNodes(hasText("Keeping the copy you chose", substring = true))
            .fetchSemanticsNodes().let { check(it.isEmpty()) { "a refused Keep was shown as decided" } }

        // While verifying, a tap is ignored and the radio does not move.
        verifying = true
        rule.waitForIdle()
        radios()[2].performClick()
        rule.waitForIdle()
        radios()[0].assertIsSelected()
        radios()[2].assertIsNotSelected()
    }

    private fun waitFor(matcher: SemanticsMatcher) = rule.waitForNode(matcher, 20_000L)
}
