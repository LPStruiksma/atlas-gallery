package nl.atlas.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.PeopleHomePerson
import java.io.ByteArrayOutputStream
import java.text.NumberFormat
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import nl.atlas.gallery.featurepeopleui.PeopleProfileUi
import nl.atlas.gallery.featurepeopleui.PeopleSortChoice
import nl.atlas.gallery.featurepeopleui.PeopleSortKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendering/navigation on synthetic metadata only. Screenshots must be captured by CI emulator. */
@RunWith(AndroidJUnit4::class)
class PeopleSortJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sortSheetSelectsAndRestoresAllFiveKeys() {
        var chosen by mutableStateOf(PeopleSortChoice())
        val profiles = listOf(
            PeopleProfileUi(1L, "Synthetic Ada", 3, 3,
                firstAppearanceMs = 100L, mostRecentAppearanceMs = 300L),
            PeopleProfileUi(2L, "Synthetic Ben", 8, 8,
                firstAppearanceMs = 200L, mostRecentAppearanceMs = 500L)
        )
        compose.setContent {
            AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                    PeopleHomeScreen(
                        state = PeopleHomeUiState(profiles = profiles),
                        onOpenProfile = {},
                        sortChoice = chosen,
                        onSortChoice = { chosen = it }
                    )
                }
            }
        }
        compose.onNodeWithTag("atlas-people-sort-open").performClick()
        compose.onNodeWithTag("atlas-people-sort-sheet").assertExists()
        // Each selection dismisses; the icon opens the next sheet.
        PeopleSortKey.entries.forEach { key ->
            compose.onNodeWithTag("atlas-people-sort-${key.name}-asc")
                .performScrollTo().performClick()
            compose.runOnIdle { assertEquals(PeopleSortChoice(key, false), chosen) }
            compose.onNodeWithTag("atlas-people-sort-open").performClick()
            compose.onNodeWithTag("atlas-people-sort-${key.name}-desc")
                .performScrollTo().performClick()
            compose.runOnIdle { assertEquals(PeopleSortChoice(key, true), chosen) }
            if (key != PeopleSortKey.RANDOM)
                compose.onNodeWithTag("atlas-people-sort-open").performClick()
        }
        compose.onNodeWithTag("atlas-people-sort-open").assertExists()
    }

    @Test fun peopleCountGridLightTheme() = renderPeopleCounts(dark = false)

    @Test fun peopleCountGridDarkTheme() = renderPeopleCounts(dark = true)

    private fun renderPeopleCounts(dark: Boolean) {
        val epoch = "synthetic-epoch"
        val generation = "synthetic-generation"
        val longName = "Synthetic person with a very long name that must be ellipsized"
        val native = PeopleHomeSummary(epoch, generation, mapOf(
            PersonKey("video-only") to PeopleHomePerson("Video only", 1, "a".repeat(32),
                mediaCount = 45, photoCount = 0, videoCount = 45),
            PersonKey("long-name") to PeopleHomePerson(longName, 1, "b".repeat(32),
                mediaCount = 3, photoCount = 2, videoCount = 1)
        ))
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PeopleHomeScreen(
                        state = PeopleHomeUiState(profiles = listOf(
                            PeopleProfileUi(1, "Synthetic Ada", 12305, 12305))),
                        onOpenProfile = {},
                        currentPermissionEpoch = epoch,
                        nativePeopleReview = native,
                        nativePeopleSourceGeneration = generation
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = context.resources.configuration.locales[0]
        val amount = NumberFormat.getInstance(locale).format(12305)
        compose.onNodeWithTag("atlas-people-profile-1")
            .assertContentDescriptionEquals("Synthetic Ada, $amount photos")
        compose.onNodeWithContentDescription("Video only, 45 videos").assertExists()
        compose.onNodeWithContentDescription("$longName, 2 photos, 1 video").assertExists()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(true, bitmap.width > 0 && bitmap.height > 0)
        // The existing CI evidence puller collects this shell-owned, non-MediaStore path.
        // Only synthetic portraits and names are rendered.
        val encoded = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded))
        writeUiEvidenceFile(uiEvidenceDisplayName(
            "people-count-${if (dark) "dark" else "light"}.png"), encoded.toByteArray())
    }
}
