package nl.atlas.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuresearch.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone

/**
 * API35 Compose journey for the existing offline Search controls. All media
 * and the grant token are synthetic. Host-owned facets are deliberately held
 * outside the controls, just as MainActivity does across Back/tab transitions.
 * This does not install an APK, read user media, or assert physical-device UI.
 */
@RunWith(AndroidJUnit4::class)
class SearchDateFacetJourneyTest {
    @get:Rule val rule = createComposeRule()

    private fun timestamp(year: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, Calendar.MAY, 10, 12, 0, 0)
    }.timeInMillis

    private fun item(id: Long, year: Int?, video: Boolean = false): MediaRecord =
        MediaRecord(
            id, "content://media/external/${if (video) "video" else "images"}/media/$id",
            "fictional-$id.${if (video) "mp4" else "jpg"}", "Pictures/Beach/",
            100L, year?.let(::timestamp) ?: 0L, video
        )

    @Test fun dateKindBackClearAndResetKeepOnlyIntendedSearchState() {
        val catalog = listOf(
            item(1L, 2026), item(2L, 2026, video = true),
            item(3L, 2025), item(4L, null), item(5L, 2027)
        )
        var facets by mutableStateOf(LocalSearchFacets(
            text = "fictional", albumPaths = setOf("Pictures/Beach/")
        ))
        var onSearchTab by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (onSearchTab) LocalSearchControls(
                        filters = facets,
                        people = emptyList(),
                        history = EphemeralSearchHistory(permissionEpoch = "fake-grant"),
                        onlyTheseFacesAvailable = false,
                        onFiltersChange = { facets = it },
                        onSubmittedQuery = {},
                        onClearHistory = {},
                        currentPermissionEpoch = "fake-grant",
                        dateNowMillis = timestamp(2026),
                        hasResults = true
                    )
                    val matches = searchWithFacets(catalog, emptyList(), facets,
                        mediaVolumeOf = { it.volumeName })
                    Text("IDs=" + matches.joinToString(",") { it.id.toString() },
                        Modifier.testTag("atlas-synthetic-date-results"))
                }
            }
        }
        fun expectIds(ids: String) = rule.onNodeWithTag("atlas-synthetic-date-results")
            .assertTextEquals("IDs=$ids")

        expectIds("1,2,3,4,5")
        rule.onNodeWithTag("atlas-search-date-THIS_YEAR").performClick()
        expectIds("1,2")
        rule.onNodeWithTag("atlas-search-kind-PHOTOS").performClick()
        expectIds("1")

        // The real host stores filters outside the child Search UI. Detaching
        // and remounting that child on tab/Back leaves date + kind untouched.
        rule.runOnIdle { onSearchTab = false }
        rule.onNodeWithTag("atlas-local-search").assertDoesNotExist()
        rule.runOnIdle { onSearchTab = true }
        rule.onNodeWithTag("atlas-local-search").assertExists()
        expectIds("1")
        rule.runOnIdle {
            assertEquals(SearchMediaKind.PHOTOS, facets.kind)
            assertEquals("fictional", facets.text)
            assertEquals(timestamp(2026) in
                (facets.dateWindow!!.startInclusiveMillis until
                    facets.dateWindow!!.endExclusiveMillis), true)
        }

        rule.onNodeWithTag("atlas-search-date-clear").performClick()
        expectIds("1,3,4,5") // Unknown date returns only after explicit clear.
        rule.onNodeWithTag("atlas-search-date-clear").assertDoesNotExist()
        rule.runOnIdle { facets = facets.copy(
            confirmedPersonIds = setOf(999L), onlyTheseIndexedFaces = true
        ) }
        expectIds("") // A missing reviewed identity cannot fabricate matches.

        rule.onNodeWithTag("atlas-search-clear-filters").performScrollTo().performClick()
        expectIds("1,2,3,4,5")
        rule.runOnIdle { assertEquals(LocalSearchFacets(text = "fictional"), facets) }
        rule.onNodeWithTag("atlas-search-reset-all").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(LocalSearchFacets(), facets) }
        expectIds("1,2,3,4,5")
    }

    @Test fun previouslySelectedAbsoluteDateDoesNotDisappearAfterYearRollover() {
        val oldWindow = localSearchDateOptions(timestamp(2026),
            TimeZone.getTimeZone("UTC"))[1].window!!
        var facets by mutableStateOf(LocalSearchFacets(
            text = "fictional", dateWindow = oldWindow
        ))
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocalSearchControls(
                        filters = facets, people = emptyList(),
                        history = EphemeralSearchHistory(permissionEpoch = "fake-grant"),
                        onlyTheseFacesAvailable = false,
                        onFiltersChange = { facets = it },
                        onSubmittedQuery = {},
                        onClearHistory = {},
                        currentPermissionEpoch = "fake-grant",
                        dateNowMillis = timestamp(2030)
                    )
                }
            }
        }
        rule.onNodeWithTag("atlas-search-date-historical").assertExists()
        rule.onNodeWithTag("atlas-search-date-clear").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(null, facets.dateWindow)
            assertEquals("fictional", facets.text)
        }
        rule.onNodeWithTag("atlas-search-date-historical").assertDoesNotExist()
    }
}
