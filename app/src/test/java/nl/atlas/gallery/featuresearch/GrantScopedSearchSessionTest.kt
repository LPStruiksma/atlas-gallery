package nl.atlas.gallery.featuresearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantScopedSearchSessionTest {
    private val typed = LocalSearchFacets(
        text = "Fictional Alice",
        confirmedPersonIds = setOf(3L, 9L),
        kind = SearchMediaKind.PHOTOS,
        albumLocations = setOf(SearchAlbumLocation("external_primary", "DCIM/Camera/")),
        onlyTheseIndexedFaces = true
    )

    private fun active(): GrantScopedSearchSession =
        GrantScopedSearchSession().enterScope("grant-A", "reviewed-people-1")

    @Test fun firstGrantStartsEmptyAndKnown() {
        val state = active()
        assertEquals("grant-A", state.photoScopeEpoch)
        assertEquals("reviewed-people-1", state.peopleGeneration)
        assertEquals(LocalSearchFacets(), state.facets)
        assertTrue(state.recent.terms.isEmpty())
        assertSame(state, state.enterScope("grant-A", "reviewed-people-1"))
    }

    @Test fun revokeErasesTextPersonIdsAlbumPathsAndRecentsAtOnce() {
        val s = active().changeFacets(typed, "grant-A", "reviewed-people-1",
            "grant-A", "reviewed-people-1")
            .submit("Fictional Alice", "grant-A", "reviewed-people-1",
                "grant-A", "reviewed-people-1")
        assertEquals(typed, s.facets)
        assertEquals(1, s.recent.terms.size)
        val revoked = s.enterScope(null, "reviewed-people-1")
        assertEquals(LocalSearchFacets(), revoked.facets)
        assertTrue(revoked.recent.terms.isEmpty())
        assertNull(revoked.currentOrNull(null, "reviewed-people-1"))
        assertNull(revoked.photoScopeEpoch)
    }

    @Test fun aNewGrantDoesNotRestorePreviousPrivateSearchSelection() {
        val s = active().changeFacets(typed, "grant-A", "reviewed-people-1",
            "grant-A", "reviewed-people-1")
            .submit("Fictional Alice", "grant-A", "reviewed-people-1",
                "grant-A", "reviewed-people-1")
        val changed = s.enterScope("grant-B", "reviewed-people-1")
        assertEquals(LocalSearchFacets(), changed.facets)
        assertTrue(changed.recent.terms.isEmpty())
        assertNull(s.currentOrNull("grant-B", "reviewed-people-1"))
        assertEquals("grant-B", changed.photoScopeEpoch)
        assertNull(changed.currentOrNull("grant-B", "reviewed-people-2"))
    }

    @Test fun confirmedPeopleReplaceInvalidatesOldSelectedIdsEvenOnSameGrant() {
        val s = active().changeFacets(typed, "grant-A", "reviewed-people-1",
            "grant-A", "reviewed-people-1")
        val changed = s.enterScope("grant-A", "reviewed-people-2")
        assertEquals(LocalSearchFacets(), changed.facets)
        assertTrue(changed.recent.terms.isEmpty())
        assertNull(s.currentOrNull("grant-A", "reviewed-people-2"))
    }

    @Test fun oldImeAndFilterCallbacksCannotWriteIntoNewGrant() {
        val stale = active()
        val newGrant = stale.changeFacets(typed, "grant-A", "reviewed-people-1",
            "grant-B", "reviewed-people-1")
        assertEquals(LocalSearchFacets(), newGrant.facets)
        assertTrue(newGrant.recent.terms.isEmpty())
        assertEquals(LocalSearchFacets(), stale.submit(
            "Fictional Alice", "grant-A", "reviewed-people-1",
            "grant-B", "reviewed-people-1").facets)
        assertTrue(stale.submit("Fictional Alice", "grant-A", "reviewed-people-1",
            "grant-B", "reviewed-people-1").recent.terms.isEmpty())
    }

    @Test fun currentGrantAllowsEditingClearingAndDistinctAlbums() {
        val s = active().changeFacets(typed, "grant-A", "reviewed-people-1",
            "grant-A", "reviewed-people-1")
            .submit("Fictional Alice", "grant-A", "reviewed-people-1",
                "grant-A", "reviewed-people-1")
        assertEquals(typed, s.facets)
        assertEquals(listOf("Fictional Alice"), s.recent.terms)
        val clean = s.clearRecents("grant-A", "reviewed-people-1",
            "grant-A", "reviewed-people-1")
        assertTrue(clean.recent.terms.isEmpty())
        assertEquals(typed, clean.facets)
        assertEquals("grant-A", clean.recent.permissionEpoch)
    }

    @Test fun staleUiEventCannotMutateAlreadyAdmittedNewGrant() {
        val newGrant = active().enterScope("grant-B", "reviewed-people-2")
        val oldClick = newGrant.changeFacets(
            typed, "grant-A", "reviewed-people-1", "grant-B", "reviewed-people-2"
        )
        assertEquals(LocalSearchFacets(), oldClick.facets)
        assertSame(newGrant, oldClick)
        val oldIme = newGrant.submit(
            "Fictional Alice", "grant-A", "reviewed-people-1",
            "grant-B", "reviewed-people-2"
        )
        assertTrue(oldIme.recent.terms.isEmpty())
        assertSame(newGrant, oldIme)
        val oldClear = newGrant.clearRecents(
            "grant-A", "reviewed-people-1", "grant-B", "reviewed-people-2"
        )
        assertSame(newGrant, oldClear)
    }

    @Test fun missingPeopleGenerationPreventsAdmissionEvenWhenPhotoGrantExists() {
        val s = active().changeFacets(typed, "grant-A", "reviewed-people-1",
            "grant-A", "reviewed-people-1")
        val missing = s.enterScope("grant-A", null)
        assertEquals(LocalSearchFacets(), missing.facets)
        assertNull(missing.currentOrNull("grant-A", null))
        assertNull(s.currentOrNull("grant-A", ""))
    }

    @Test fun forgedMatchingKeysWithOldHistoryCannotBeAdmitted() {
        val inconsistent = GrantScopedSearchSession(
            photoScopeEpoch = "grant-A",
            peopleGeneration = "reviewed-people-1",
            facets = typed,
            recent = EphemeralSearchHistory(listOf("old Person name"), "grant-OLDER")
        )
        assertNull(inconsistent.currentOrNull("grant-A", "reviewed-people-1"))
        val repaired = inconsistent.enterScope("grant-A", "reviewed-people-1")
        assertEquals(LocalSearchFacets(), repaired.facets)
        assertTrue(repaired.recent.terms.isEmpty())
        assertEquals("grant-A", repaired.recent.permissionEpoch)
        assertSame(repaired, repaired.enterScope("grant-A", "reviewed-people-1"))
    }

    @Test fun noScopeCannotSaveQueriesOrExistingSelections() {
        val unscoped = GrantScopedSearchSession(
            facets = typed,
            recent = EphemeralSearchHistory(listOf("private legacy query"))
        )
        val rejected = unscoped.submit("New private query", null, null, null, null)
        assertEquals(LocalSearchFacets(), rejected.facets)
        assertTrue(rejected.recent.terms.isEmpty())
        assertNull(rejected.currentOrNull(null, null))
    }
}
