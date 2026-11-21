package nl.atlas.gallery

import android.accessibilityservice.AccessibilityService
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #675 design brief: Settings › About › What's new with a synthetic changelog,
 * light and dark, installed = newest and installed = older, empty, 200% font.
 * No media, no preferences, no network.
 */
@RunWith(AndroidJUnit4::class)
class WhatsNewJourneyTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val sample = """
        {"schema": 1, "releases": [
          {"version": "0.5.0", "build": 261124101, "date": "2026-11-24", "groups": [
            {"type": "fix", "title": "Fixed", "entries": ["Opening a video from Search no longer shows a black frame first."]},
            {"type": "feature", "title": "New", "entries": ["Settings shows what changed in each version.", "Collections can be sorted by file size."]},
            {"type": "improvement", "title": "Improved", "entries": ["Albums load faster on large libraries."]}
          ]},
          {"version": "0.4.1", "build": 261121090, "date": "2026-11-21", "groups": []},
          {"version": "0.4.0", "build": 261120034, "date": "2026-11-20", "groups": [
            {"type": "feature", "title": "New", "entries": ["Atlas now uses proper version numbers, starting at 0.4.0."]},
            {"type": "internal", "title": "Under the hood", "entries": ["Must never show."]},
            {"type": "fix", "title": "Fixed", "entries": ["The fal.ai preset sends your API key in the format fal expects."]}
          ]}
        ]}
    """.trimIndent()

    @Test fun parserKeepsOrderSortsGroupsAndDropsInternal() {
        val log = AtlasChangelog.parse(sample)
        assertNotNull(log)
        log!!
        assertEquals(listOf("0.5.0", "0.4.1", "0.4.0"), log.releases.map { it.version })
        assertEquals(listOf("feature", "improvement", "fix"), log.releases[0].groups.map { it.type })
        assertEquals(listOf("feature", "fix"), log.releases[2].groups.map { it.type })
        assertEquals("24 November 2026, build 261124101", whatsNewSubline(log.releases[0]))
        assertEquals("0.5.0", whatsNewRowValue(log))
        assertEquals("None in this build", whatsNewRowValue(null))
        assertNull(AtlasChangelog.parse("{\"schema\": 2, \"releases\": []}"))
        assertNull(AtlasChangelog.parse("not json"))
        assertEquals(emptyList<AtlasChangelog.Release>(),
            AtlasChangelog.parse("{\"schema\":1,\"releases\":[{\"version\":\"x\"}]}")!!.releases)
    }

    @Test fun whatsNewStateMatrix() {
        val log = AtlasChangelog.parse(sample)!!
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var installed by mutableStateOf("0.5.0")
        var changelog by mutableStateOf<AtlasChangelog?>(log)
        var generation by mutableIntStateOf(0)
        // Dialog windows take density from their own Context, so the 200% font
        // state renders the screen itself in the main window instead.
        var direct by mutableStateOf(false)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        androidx.compose.runtime.key(generation) {
                            if (direct) {
                                AtlasWhatsNewScreen(changelog, installed, onBack = { direct = false })
                            } else {
                                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                    AtlasSettingsScreen(
                                        scanPhase = null, onPauseScan = null, onResumeScan = null,
                                        visualIndexOn = null, showPhotoDetails = false,
                                        versionName = installed, onVisualIndex = {},
                                        onPeopleNumbers = {}, onPhotoDetails = {}, onImportV4 = {},
                                        onLinkV4 = {}, onDiagnostics = {}, changelog = changelog)
                                }
                            }
                        }
                    }
                }
            }
        }

        for (mode in listOf("light", "dark")) {
            dark = mode == "dark"
            fontScale = 1f
            installed = "0.5.0"
            changelog = log
            generation++
            compose.waitForIdle()

            // Row in About shows the newest version.
            compose.onNodeWithTag("atlas-settings-whats-new").performScrollTo()
                .assertIsDisplayed().assert(hasText("0.5.0"))
            saveUiEvidence("whatsnew-row-$mode.png")

            // Installed = newest: one sphere, on 0.5.0; internal entries never shown.
            open()
            assertEquals(1, compose.onAllNodesWithTag("atlas-whats-new-installed").fetchSemanticsNodes().size)
            assertTrue(sphereBelongsTo("0.5.0"))
            assertEquals(0, compose.onAllNodes(hasText("Must never show.")).fetchSemanticsNodes().size)
            saveUiEvidence("whatsnew-list-$mode.png")
            back()
            closed()

            // Installed = older (0.4.0): sphere moves there, and an empty release says so.
            installed = "0.4.0"
            generation++
            compose.waitForIdle()
            open()
            compose.onNodeWithText("Small fixes and maintenance.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("atlas-whats-new-release-0.4.0").performScrollTo()
            assertTrue(sphereBelongsTo("0.4.0"))
            compose.waitForIdle()
            saveUiEvidence("whatsnew-older-$mode.png")
            compose.onNodeWithTag("atlas-whats-new-back").performClick()
            closed()

            // Debug build: no asset.
            changelog = null
            generation++
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-settings-whats-new").performScrollTo()
                .assert(hasText("None in this build"))
            open()
            compose.onNodeWithTag("atlas-whats-new-empty").assertIsDisplayed()
            saveUiEvidence("whatsnew-empty-$mode.png")
            back()
            closed()

            // 200% font: every entry wraps, none clips.
            changelog = log
            installed = "0.5.0"
            fontScale = 2f
            direct = true
            generation++
            compose.waitForIdle()
            compose.onAllNodes(hasText("Settings shows what changed in each version.")).fetchSemanticsNodes()
                .also { assertEquals(1, it.size) }
                .forEach { node ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                    val layout = layouts.single()
                    assertTrue("entry laid out at 200%", layout.layoutInput.density.fontScale >= 1.99f)
                    assertTrue("entry wraps, not clipped", !layout.hasVisualOverflow && layout.lineCount >= 2)
                }
            compose.waitForIdle()
            saveUiEvidence("whatsnew-font200-$mode.png")
            back()
            closed()
            compose.runOnIdle { assertTrue("Back leaves What's new", !direct) }
        }
    }

    private fun open() {
        compose.onNodeWithTag("atlas-settings-whats-new").performScrollTo().performClick()
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("atlas-whats-new-screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun closed() {
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("atlas-whats-new-screen").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun back() {
        assertTrue("Android Back closes What's new",
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK))
    }

    private fun sphereBelongsTo(version: String): Boolean {
        val sphere = compose.onNodeWithTag("atlas-whats-new-installed").fetchSemanticsNode()
        assertEquals(listOf("Installed version"),
            sphere.config[SemanticsProperties.ContentDescription])
        val release = compose.onNodeWithTag("atlas-whats-new-release-$version").fetchSemanticsNode()
        return release.boundsInRoot.contains(sphere.boundsInRoot.center)
    }
}
