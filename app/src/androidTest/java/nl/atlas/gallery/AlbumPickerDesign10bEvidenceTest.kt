package nl.atlas.gallery

import android.util.Log
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurealbums.AlbumPickerScreen
import nl.atlas.gallery.featurealbums.AlbumSummary
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.AtlasSystemBarIcons
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #364 (DESIGN-10b) rendered evidence for the Copy/Move album picker.
 * Invented album names only; no MediaStore, no covers, no transfer consent.
 * Each test sets content once and flips light/dark through state.
 */
@RunWith(AndroidJUnit4::class)
class AlbumPickerDesign10bEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private fun album(i: Int, title: String, location: String? = null) = AlbumSummary(
        "Fixture/$i/", title, 3 + i % 40, 3 + i % 40, 0, null, false, 1_700_000_000_000L - i,
        locationLabel = location
    )

    /** Short, long and duplicate names, the cases #364 calls out. */
    private val mixed = listOf(
        album(0, "Camera"),
        album(1, "Camera", location = "SD card"),
        album(2, "Holiday on the coast with the whole extended family, summer"),
        album(3, "Screenshots"),
        album(4, "Fixture_Weekend"),
        album(5, "Fixture_Weekend", location = "Pictures"),
        album(6, "Downloads"),
        album(7, "A very long album name that must ellipsize cleanly in two lines"),
        album(8, "Recipes")
    )

    private fun picker(title: String, albums: List<AlbumSummary>, base: String) {
        var dark by mutableStateOf(false)
        compose.setContent {
            AppRoot(dark) {
                AlbumPickerScreen(
                    title = title, albums = albums, onPick = {}, onCreate = {},
                    nameProblem = { if (it.isBlank()) "Name required" else null }, onClose = {}
                )
            }
        }
        for (next in listOf(false, true)) {
            dark = next
            compose.waitForIdle()
            saveUiEvidence("$base-${if (next) "dark" else "light"}.png")
        }
    }

    @Test fun copyPickerLongAndDuplicateNames() =
        picker("Copy to album", mixed, "design-10b-copy-picker")

    @Test fun movePickerLongAndDuplicateNames() =
        picker("Move to album", mixed, "design-10b-move-picker")

    @Test fun emptyCopyPicker() =
        picker("Copy to album", emptyList(), "design-10b-empty-picker")

    @Test fun twoHundredAlbumsScrolledAndBottomInset() {
        val many = (0 until 220).map { album(it, "Fixture album ${it + 1}") }
        var dark by mutableStateOf(true)
        compose.setContent {
            AppRoot(dark) {
                AlbumPickerScreen(
                    title = "Move to album", albums = many, onPick = {}, onCreate = {},
                    nameProblem = { null }, onClose = {}
                )
            }
        }
        val grid = compose.onNodeWithTag("atlas-album-picker-grid")
        grid.performScrollToIndex(120)
        compose.waitForIdle()
        saveUiEvidence("design-10b-picker-220-scrolled-dark.png")
        // Last row: the grid's bottom padding keeps it clear of the navigation bar.
        grid.performScrollToNode(hasTestTag("atlas-album-picker-album-219"))
        compose.waitForIdle()
        saveUiEvidence("design-10b-picker-220-bottom-inset-dark.png")
        dark = false
        compose.waitForIdle()
        saveUiEvidence("design-10b-picker-220-bottom-inset-light.png")
    }

    @Test fun createDialogWithKeyboard() {
        var dark by mutableStateOf(false)
        var imeBottom by mutableIntStateOf(0)
        compose.setContent {
            val ime = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { imeBottom = ime }
            AppRoot(dark) {
                AlbumPickerScreen(
                    title = "Copy to album", albums = mixed, onPick = {}, onCreate = {},
                    nameProblem = { if (it.isBlank()) "Name required" else null }, onClose = {}
                )
            }
        }
        compose.onNodeWithTag("atlas-album-picker-create").performClick()
        compose.onNodeWithTag("atlas-create-album-name").performClick()
        compose.onNodeWithTag("atlas-create-album-name").performTextInput("Fixture trip")
        for (next in listOf(false, true)) {
            dark = next
            val shown = runCatching { compose.waitUntil(5_000) { imeBottom > 0 } }.isSuccess
            // Report, don't fail: some CI images run without a soft keyboard.
            Log.i("ATLAS_EVIDENCE", "design-10b create dialog ime visible=$shown bottomPx=$imeBottom dark=$next")
            compose.waitForIdle()
            saveUiEvidence("design-10b-create-dialog-${if (next) "dark" else "light"}.png")
        }
    }
}

/**
 * The same root MainActivity draws every route in: system bar icons for the
 * theme, then a Surface that supplies the readable content colour. Without
 * it, picker text falls back to black and is unreadable in dark captures.
 */
@Composable
private fun AppRoot(dark: Boolean, content: @Composable () -> Unit) {
    AtlasSystemBarIcons(dark)
    AtlasGalleryTheme(dark = dark) {
        Surface(Modifier.fillMaxSize(), color = DeepBg,
            contentColor = MaterialTheme.colorScheme.onBackground) { content() }
    }
}
