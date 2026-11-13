package nl.atlas.gallery

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * #623: a machine-readable capture *contract*, not a Samsung parity result.
 *
 * The only captured states are independently reached via the real Activity's
 * established nav tags. Every other state is explicitly NOT_CAPTURED, with a
 * test id and a seam reason. No owner media or Samsung implementation is read.
 *
 * Run this test only on an isolated, synthetic-only API35 emulator. The manifest
 * stays in instrumentation target files/parity-audit/ and must be exported with
 * the PNGs by a later, separately approved CI integration. Existing CI does NOT
 * export this directory; no PNG is claimed until that integration exists.
 */
@RunWith(AndroidJUnit4::class)
class ParityAuditCaptureContractTest {
    private val fixture = SyntheticHeaderFixtures(
        sizes = List(12) { i -> if (i % 2 == 0) 160 to 120 else 120 to 160 },
        folderOf = { step -> "ATLAS-Parity-${(step - 1) / 4 + 1}" })
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixture).around(compose)

    private data class Surface(val id: String, val reason: String? = null)
    private val surfaces = listOf(
        Surface("pictures-default"),
        Surface("pictures-pinch-compact", "Pinch gesture and stable zoom-level assertion not wired"),
        Surface("pictures-pinch-large", "Pinch gesture and stable zoom-level assertion not wired"),
        Surface("pictures-original-ratio", "Original-proportions gesture state not wired"),
        Surface("pictures-header-away", "Stable scroll-away capture sequence not wired"),
        Surface("albums-overview"),
        Surface("albums-detail", "Deterministic album-card selection not wired"),
        Surface("people-overview"),
        Surface("people-named", "Synthetic named face-state injector not wired to Activity"),
        Surface("people-hidden", "Hidden People route and state assertion not wired"),
        Surface("viewer-photo", "Deterministic gallery-tile-to-viewer route not wired"),
        Surface("viewer-details-people", "Synthetic face-to-Details People mapping not wired"),
        Surface("viewer-filmstrip", "Deterministic viewer and filmstrip route not wired"),
        Surface("search-person", "Synthetic named-person search route not wired"),
        Surface("selection-more", "Synthetic selection gesture and menu state not wired"),
        Surface("photo-dna", "Deterministic Photo DNA analysis state not wired"),
        Surface("declutter", "Deterministic declutter result state not wired"),
        Surface("settings", "Settings entry route and stable state not wired")
    )

    @Test fun manifestIncludesBothThemesWithoutFabricatedEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.filesDir, "parity-audit").apply { mkdirs() }
        val expectedDark = "dark"
        val expectedLight = "light"
        val current = if ((context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            expectedDark else expectedLight
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        assertEquals("Expected exact 1080 pixel width", 1080, width)
        assertEquals("Expected exact 2340 pixel height", 2340, height)
        assertTrue("Test fixture must be nonempty", surfaces.isNotEmpty())

        // Reset the directory so stale images cannot be mistaken for this run.
        output.listFiles()?.forEach { assertTrue("Cannot reset stale evidence: ${it.name}", it.delete()) }
        val states = mutableMapOf<String, String>()
        if (current == expectedDark) {
            waitFor("atlas-nav-photos")
            capture(output, "pictures-default", expectedDark)
            states["pictures-default"] = expectedDark
            compose.onNodeWithTag("atlas-nav-albums").performClick()
            waitFor("atlas-album-grid")
            capture(output, "albums-overview", expectedDark)
            states["albums-overview"] = expectedDark
            compose.onNodeWithTag("atlas-nav-people").performClick()
            waitFor("atlas-people-home")
            capture(output, "people-overview", expectedDark)
            states["people-overview"] = expectedDark
        }
        val rows = buildList {
            add("test_id\tsurface\ttheme\twidth\theight\tfixture\texpected_png\tstatus\treason\treference")
            for (theme in listOf(expectedLight, expectedDark)) {
                for (surface in surfaces) {
                    val png = "atlas-parity-${surface.id}-$theme-1080x2340.png"
                    val captured = states[surface.id] == theme
                    val reason = when {
                        captured -> ""
                        theme != current -> "Theme not controlled in this test run; requires isolated themed rerun"
                        surface.reason != null -> surface.reason
                        else -> "Real Activity capture not wired for this state"
                    }
                    add(listOf(
                        "ParityAuditCaptureContractTest.manifestIncludesBothThemesWithoutFabricatedEvidence",
                        surface.id, theme, "1080", "2340",
                        "SyntheticHeaderFixtures:12-generated-PNGs",
                        png, if (captured) "CAPTURED" else "NOT_CAPTURED", reason,
                        "reference-pending/${surface.id}/$theme"
                    ).joinToString("\t"))
                }
            }
        }
        File(output, "manifest.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
        assertEquals(1 + surfaces.size * 2, rows.size)
        for (surface in surfaces) for (theme in listOf(expectedLight, expectedDark)) {
            val filename = "atlas-parity-${surface.id}-$theme-1080x2340.png"
            assertEquals("Manifest status must match file presence for $filename",
                states[surface.id] == theme, File(output, filename).exists())
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(60_000L) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun capture(output: File, surface: String, theme: String) {
        val filename = "atlas-parity-$surface-$theme-1080x2340.png"
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation()
            .uiAutomation.takeScreenshot()) { "No real screenshot for $surface" }
        try {
            assertEquals(1080, screenshot.width)
            assertEquals(2340, screenshot.height)
            File(output, filename).outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
    }
}
