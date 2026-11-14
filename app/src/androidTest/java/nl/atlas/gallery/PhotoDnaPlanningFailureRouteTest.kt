package nl.atlas.gallery

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import nl.atlas.gallery.featurecuration.PhotoDnaCopy
import nl.atlas.gallery.featurecuration.hashCurationBytes
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #42: Photo DNA wired as MainActivity wires it (rememberPhotoDna feeding
 * PhotoDnaRoute) with a planner that throws on the first try. The screen
 * survives with "Couldn't finish checking duplicates" and Retry, the failure
 * is the handled Last crash, and Retry plans again. Invented records only.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaPlanningFailureRouteTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private val grant = GalleryMediaGrant(images = true, videos = false, selected = false)
    private val base = 9_420_000L + (System.nanoTime() and 0xffff)
    private val records = (1L..2L).map { n ->
        MediaRecord(
            id = base + n, uri = "content://media/external/images/media/${base + n}",
            name = "ATLAS_DNA_42_$n.png", relativePath = "Pictures/ATLAS-PhotoDna-42/",
            sizeBytes = 40L, takenAtMillis = 1_700_000_000_000L + n, isVideo = false,
            modifiedAtSeconds = 1_700_000_000L
        )
    }

    @Test fun aThrowingPlannerShowsRetryInsteadOfQuittingAndRetryPlansAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokens = requireNotNull(photoDnaTokensOrNull(grant, 42L, true, records))
        val calls = AtomicInteger()
        val failNext = AtomicBoolean(true)
        var dark by mutableStateOf(false)
        var statusTopPx = -1
        rule.setContent {
            statusTopPx = WindowInsets.statusBars.getTop(LocalDensity.current)
            AtlasGalleryTheme(dark = dark) {
                // The root Surface MainActivity draws behind every route, so the
                // dark evidence shows the dark background, not a bare window.
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val planning = rememberPhotoDna(records, tokens, enabled = true) { record, _ ->
                        calls.incrementAndGet()
                        // First planning attempt: the provider read blows up.
                        if (failNext.getAndSet(false)) error("synthetic planner failure")
                        hashCurationBytes(record.sizeBytes,
                            openAuthorized = { ByteArrayInputStream(ByteArray(40) { 4 }) },
                            stillAuthorized = { true })
                    }
                    PhotoDnaRoute(
                        result = currentPhotoDna(planning.result, tokens), verifying = false,
                        onBack = {}, onOpenPhoto = {}, onReviewRequested = {},
                        failed = planning.failed, onRetry = planning::retry
                    )
                }
            }
        }
        rule.waitForNode(hasTestTag("atlas-photo-dna-failed"), 20_000L)
        rule.onNodeWithTag("atlas-photo-dna-status")
            .assertTextContains(PhotoDnaCopy.FAILED_STATUS, substring = true)
        rule.onNodeWithTag("atlas-photo-dna-progress").assertDoesNotExist()
        rule.onNodeWithTag("atlas-photo-dna-retry").assertExists()
        // #73: under edge-to-edge the Photo DNA back row must not hide behind
        // the S24+ status bar. Test both the back hit target and the title.
        assertHeaderClearsStatusBar(statusTopPx)
        capture("photo-dna-42-failed-light.png")
        rule.runOnIdle { dark = true }
        rule.waitForIdle()
        assertHeaderClearsStatusBar(statusTopPx)
        capture("photo-dna-42-failed-dark.png")

        val report = requireNotNull(LastCrashRecorder.read(context))
        assertTrue(report, report.contains("Outcome: handled, app kept running"))
        assertTrue(report, report.contains("IllegalStateException"))
        assertTrue(report, !report.contains("synthetic planner failure"))

        rule.onNodeWithTag("atlas-photo-dna-retry").performClick()
        rule.waitForNode(hasTestTag("photo-dna-exact-group"), 20_000L)
        rule.onNodeWithTag("atlas-photo-dna-failed").assertDoesNotExist()
        // One throwing call, then both files hashed by the retry.
        assertEquals(1 + records.size, calls.get())
    }

    private fun assertHeaderClearsStatusBar(statusTopPx: Int) {
        assertTrue("S24+ status bar must report a nonzero inset: $statusTopPx",
            statusTopPx > 0)
        for (tag in listOf("atlas-photo-dna-back", "atlas-photo-dna-title")) {
            val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            assertTrue("$tag top ${bounds.top} overlaps status bar $statusTopPx",
                bounds.top >= statusTopPx)
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        Thread.sleep(700)
        rule.waitForIdle()
        saveUiEvidence(name)
    }
}
