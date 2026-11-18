package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.advanceVirtualTime
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.awaitWindowSettled
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.typeText

/**
 * #417 PERF-7a: debug StrictMode baseline for the first opening of the Photos
 * grid, the viewer, People and Search in a fresh process, on invented MediaStore
 * PNGs. It measures and reports; it never fails on a violation.
 *
 * Opt-in only (`-e atlas_strictmode_baseline true`), with the debug provider's
 * marker file created first, in its own `am instrument` process so that no
 * earlier test has opened a store yet. scripts/ci_android_upgrade_emulator.sh
 * runs it on emulator shard 1; docs/perf/PERF_7A_STRICTMODE_BASELINE.md shows how
 * to run it by hand. Output goes to logcat tag `ATLAS_STRICT`.
 */
@RunWith(AndroidJUnit4::class)
class StrictModeBaselineTest {
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val args = InstrumentationRegistry.getArguments()
                assumeTrue("Explicit atlas_strictmode_baseline=true required",
                    args.getString("atlas_strictmode_baseline") == "true")
                assertTrue("Create the marker first, then start a fresh process: adb shell " +
                    "run-as nl.atlas.gallery touch files/atlas-strictmode-baseline",
                    StrictModeBaseline.installed)
                base.evaluate()
            }
        }
    }
    private val fixtures = SyntheticHeaderFixtures(
        sizes = List(FIXTURES) { if (it % 3 == 0) 90 to 120 else 120 to 90 },
        folderOf = { FOLDER })
    /** Innermost before the Activity: its launch is the grid's first opening. */
    private val gridPhase = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                begin("grid-first-open")
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(optIn)
        .around(KeyboardRule(TestKeyboard.None)).around(S24DisplayRule())
        .around(fixtures).around(gridPhase).around(compose)

    private val timings = linkedMapOf<String, MutableList<Long>>()
    private val frames = mutableListOf<String>()
    private var phaseStart = 0L

    @Test fun firstOpenFlowsReportMainThreadDiskIo() {
        // 1. Photos grid: Activity launch to the first decoded tile.
        firstTileTag()
        end("grid-first-open")

        // 2. Viewer: tap the first tile, then Back.
        openViewer("viewer-first-open")

        // 3. People tab, first entry.
        openTab("people-first-open", "atlas-nav-people", "atlas-people-home")

        // 4. Search tab, first entry, then the first typed query.
        openTab("search-first-open", "atlas-nav-search", "atlas-search-field", "atlas-search-expand")
        if (present("atlas-search-expand") && !present("atlas-search-field")) {
            compose.onNodeWithTag("atlas-search-expand").performClick()
            compose.waitForNode(hasTestTag("atlas-search-field"))
        }
        begin("search-first-query")
        // #497: the kit types through the no-op test keyboard (KeyboardRule) and
        // leaves it hidden, so the nav pill stays reachable for the re-entries.
        compose.typeText(hasTestTag("atlas-search-field"), "IMG")
        settledPhase()
        end("search-first-query")

        // 5. Re-entries: does a site recur (per-entry cost) or was it first-open only?
        repeat(REPEATS) {
            openTab("photos-reentry", "atlas-nav-photos", "atlas-timeline-grid", "atlas-feature-grid")
            openViewer("viewer-reopen")
            openTab("people-reentry", "atlas-nav-people", "atlas-people-home")
            openTab("search-reentry", "atlas-nav-search", "atlas-search-field", "atlas-search-expand")
        }
        StrictModeBaseline.phase = "after-flows"
        report()
    }

    private fun openViewer(phase: String) {
        if (!present("atlas-timeline-grid") && !present("atlas-feature-grid")) {
            compose.onNodeWithTag("atlas-nav-photos").performClick()
        }
        val tile = firstTileTag()
        begin(phase)
        compose.onNodeWithTag(tile, useUnmergedTree = true).performClick()
        compose.waitForNode(hasTestTag("atlas-viewer"))
        settledPhase()
        end(phase)
        compose.onNodeWithTag("atlas-back").performClick()
        compose.awaitThat("the viewer to close", timeoutMs = 30_000L) {
            !compose.exists(hasTestTag("atlas-viewer"))
        }
        compose.waitForIdle()
    }

    /** Tap a tab and wait for any of [tags]; time and frames cover tap to settled. */
    private fun openTab(phase: String, tab: String, vararg tags: String) {
        compose.waitForNode(hasTestTag(tab))
        begin(phase)
        compose.onNodeWithTag(tab).performClick()
        compose.waitForNode(tags.map { hasTestTag(it) }.reduce { a, b -> a or b })
        settledPhase()
        end(phase)
    }

    /**
     * The end of a phase (#497, test kit rule 1): the window has settled and
     * product timers have run SETTLE_MS of virtual time. Main-thread work that
     * lands later still counts, under the `<phase>:idle` label.
     */
    private fun settledPhase() {
        compose.awaitWindowSettled()
        compose.advanceVirtualTime(SETTLE_MS)
    }

    private fun firstTileTag(): String {
        compose.awaitThat("a decoded media tile", timeoutMs = 60_000L) {
            compose.exists(tile, useUnmergedTree = true)
        }
        compose.waitForIdle()
        return compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
            .first().config[SemanticsProperties.TestTag]
    }

    private fun present(tag: String) =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private val tile = SemanticsMatcher("media tile") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        tag.startsWith("atlas-timeline-media-") || tag.startsWith("atlas-grid-media-")
    }

    /**
     * Label later violations with [phase]. Run on the main thread so violations
     * StrictMode queued for earlier work are delivered under the earlier label.
     */
    private fun begin(phase: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            StrictModeBaseline.phase = phase
        }
        shell("dumpsys gfxinfo nl.atlas.gallery reset")
        phaseStart = SystemClock.uptimeMillis()
    }

    private fun end(phase: String) {
        val ms = SystemClock.uptimeMillis() - phaseStart
        timings.getOrPut(phase) { mutableListOf() }.add(ms)
        frames += "frames phase=$phase " + gfxSummary(shell("dumpsys gfxinfo nl.atlas.gallery"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            StrictModeBaseline.phase = "$phase:idle"
        }
    }

    private fun report() {
        val records = StrictModeBaseline.snapshot()
        val sites = StrictModeSites.group(records)
        log("BEGIN #417 StrictMode baseline (synthetic emulator, debug build, main thread)")
        log("records=${records.size} dropped=${StrictModeBaseline.dropped()} sites=${sites.size} " +
            "app_sites=${sites.count { !it.harness }}")
        timings.forEach { (phase, ms) ->
            val sorted = ms.sorted()
            log("timing phase=$phase n=${sorted.size} p50_ms=${pct(sorted, 50)} " +
                "p95_ms=${pct(sorted, 95)} (tap to window settled + ${SETTLE_MS} ms virtual; test-side poll)")
        }
        frames.forEach { log(it) }
        sites.forEachIndexed { i, s ->
            log("site #${i + 1} phase=${s.phase} ${s.violation} opening=${s.opening} count=${s.count} " +
                "first_ms=${s.firstMs} harness=${s.harness} trigger=${s.trigger} app=${s.appFrame}")
        }
        sites.withIndex().filter { !it.value.harness }.take(MAX_STACKS).forEach { (i, s) ->
            s.stack.filter { f -> !f.className.startsWith("android.os.StrictMode") }
                .take(STACK_FRAMES).forEach { log("stack #${i + 1}   at $it") }
        }
        log("END #417 StrictMode baseline")
    }

    private fun log(line: String) = Log.i(TAG, line)

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private fun gfxSummary(dump: String): String {
        fun value(label: String) = Regex("""(?m)^\s*$label:\s*([^\n]+)""").find(dump)
            ?.groupValues?.get(1)?.trim()
        val total = value("Total frames rendered") ?: return "unavailable (no gfxinfo stats)"
        return "total=$total janky=${value("Janky frames")} p50=${value("50th percentile")} " +
            "p90=${value("90th percentile")} p95=${value("95th percentile")} " +
            "p99=${value("99th percentile")}"
    }

    private fun pct(sorted: List<Long>, p: Int): Long =
        sorted[((sorted.size - 1) * p + 50) / 100]

    private companion object {
        const val TAG = "ATLAS_STRICT"
        const val FIXTURES = 48
        const val FOLDER = "ATLAS-StrictMode-417"
        const val REPEATS = 5
        const val SETTLE_MS = 500L
        const val MAX_STACKS = 40
        const val STACK_FRAMES = 22
    }
}
