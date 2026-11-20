package nl.atlas.gallery.featureintents

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import nl.atlas.gallery.Design6SystemBars
import nl.atlas.gallery.MainActivity
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * BENCH-2 (#573) A2b: the picker states that no emulator library can produce on
 * demand (nothing of the requested kind, no media access), rendered from the
 * real stateless picker frame with synthetic values, in light, dark and at
 * font scale 2. The picker has no text field, so there is no IME state.
 */
@RunWith(Parameterized::class)
class ExternalPickerStatesEvidenceTest(private val dark: Boolean, private val fontScale: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0},font={1}")
        fun displays(): List<Array<Any>> = listOf(arrayOf(false, 1f), arrayOf(true, 1f), arrayOf(false, 2f))
    }

    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = dark, fontScale = fontScale)).around(compose)
    private val suffix get() = when {
        fontScale > 1f -> "largetext"
        dark -> "dark"
        else -> "light"
    }

    private val videos = ExternalEntry.Pick(
        filter = requireNotNull(PickMediaFilter.of(listOf("video/*"))),
        allowMultiple = true,
        legacyPick = false
    )

    @Test fun emptyAndNoAccessStates() {
        var hasAccess by mutableStateOf(true)
        compose.setContent {
            Design6SystemBars(dark)
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ExternalPickerFrame(
                        request = videos, hasAccess = hasAccess, albumsTab = false, error = null,
                        chosenCount = 0, resolving = false, onAllowAccess = {}, onAlbumsTab = {},
                        onCancel = {}, onDone = {}
                    ) { PickerEmpty(videos.filter) }
                }
            }
        }
        compose.awaitThat("the empty state") { compose.exists(hasTestTag(ExternalPickerTags.EMPTY)) }
        compose.onNodeWithTag("atlas-picker-empty-title").assertTextEquals("No matching videos")
        compose.onNodeWithTag(ExternalPickerTags.TITLE).assertTextEquals("Choose videos")
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(ExternalPickerTags.CANCEL).assertIsDisplayed()
        compose.capture("picker-573-empty-$suffix")

        hasAccess = false
        compose.awaitThat("the access prompt") { compose.exists(hasTestTag(ExternalPickerTags.PERMISSION)) }
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(ExternalPickerTags.CANCEL).assertIsDisplayed()
        compose.capture("picker-573-noaccess-$suffix")
    }
}

/**
 * The real picker over generated media at font scale 2: the title, the grid and
 * both actions stay on screen, and a pick still answers the caller.
 */
class ExternalPickerLargeTextJourneyTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = false, fontScale = 2f)).around(compose)
    private val media = PickerMedia()

    @Before fun setUp() = media.create()
    @After fun tearDown() = media.delete()

    @Test fun aMultiplePickWorksAtFontScaleTwo() {
        val scenario: ActivityScenario<MainActivity> =
            media.launch(Intent.ACTION_GET_CONTENT, "image/*", multiple = true)
        compose.awaitPicker("Choose photos")
        compose.tapTile(media.second)
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertIsDisplayed().assertTextEquals("Done (1)")
        compose.onNodeWithTag(ExternalPickerTags.CANCEL).assertIsDisplayed()
        compose.onNodeWithTag(ExternalPickerTags.TOP_CANCEL).assertIsDisplayed()
        compose.capture("picker-573-largetext-multiple")
        compose.tap(hasTestTag(ExternalPickerTags.DONE))
        assertEquals(android.app.Activity.RESULT_OK, scenario.result.resultCode)
        assertSameItem(media.second, scenario.result.resultData!!.data)
    }
}
