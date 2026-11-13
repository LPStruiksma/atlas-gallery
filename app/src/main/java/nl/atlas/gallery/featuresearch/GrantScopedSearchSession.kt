package nl.atlas.gallery.featuresearch

/**
 * Volatile search state bound to TWO independent authorization inputs:
 * the current photo grant publication and the confirmed People generation.
 * This deliberately contains no Android I/O, persistence, telemetry or DB.
 *
 * Lane I MUST advance photoScopeEpoch on any revoke/regrant/republication,
 * and peopleGeneration on a reviewed CSV replacement/merge. Reusing an old
 * epoch defeats the fencing contract and must be prevented by its owner.
 */
data class GrantScopedSearchSession(
    val photoScopeEpoch: String? = null,
    val peopleGeneration: String? = null,
    val facets: LocalSearchFacets = LocalSearchFacets(),
    val recent: EphemeralSearchHistory = EphemeralSearchHistory()
) {
    /**
     * Atomic fail-closed reset: an absent source is NOT an empty authorized
     * source. Never keep old typed Person IDs or album locations on a new grant.
     */
    fun enterScope(
        currentPhotoScopeEpoch: String?,
        currentPeopleGeneration: String?
    ): GrantScopedSearchSession {
        if (currentPhotoScopeEpoch.isNullOrBlank() ||
            currentPeopleGeneration.isNullOrBlank()
        ) return GrantScopedSearchSession()
        if (photoScopeEpoch == currentPhotoScopeEpoch &&
            peopleGeneration == currentPeopleGeneration &&
            recent.permissionEpoch == currentPhotoScopeEpoch) return this
        return GrantScopedSearchSession(
            photoScopeEpoch = currentPhotoScopeEpoch,
            peopleGeneration = currentPeopleGeneration,
            recent = EphemeralSearchHistory(permissionEpoch = currentPhotoScopeEpoch)
        )
    }

    /** Never publish an obsolete callback's filters into a newly scoped view. */
    fun changeFacets(
        incoming: LocalSearchFacets,
        /** Scope captured when the UI callback was rendered. */
        eventPhotoScopeEpoch: String?,
        eventPeopleGeneration: String?,
        /** Scope most recently published by I at the moment of the callback. */
        currentPhotoScopeEpoch: String?,
        currentPeopleGeneration: String?
    ): GrantScopedSearchSession {
        val current = enterScope(currentPhotoScopeEpoch, currentPeopleGeneration)
        if (current !== this || current.photoScopeEpoch == null ||
            eventPhotoScopeEpoch != currentPhotoScopeEpoch ||
            eventPeopleGeneration != currentPeopleGeneration
        ) return current
        return copy(facets = incoming)
    }

    /** Ignore old-grant IME submissions and avoid saving private typed names. */
    fun submit(
        text: String,
        eventPhotoScopeEpoch: String?,
        eventPeopleGeneration: String?,
        currentPhotoScopeEpoch: String?,
        currentPeopleGeneration: String?
    ): GrantScopedSearchSession {
        val current = enterScope(currentPhotoScopeEpoch, currentPeopleGeneration)
        if (current !== this || current.photoScopeEpoch == null ||
            eventPhotoScopeEpoch != currentPhotoScopeEpoch ||
            eventPeopleGeneration != currentPeopleGeneration
        ) return current
        return copy(recent = recent.rememberForGrant(text, currentPhotoScopeEpoch))
    }

    fun clearRecents(
        eventPhotoScopeEpoch: String?,
        eventPeopleGeneration: String?,
        currentPhotoScopeEpoch: String?,
        currentPeopleGeneration: String?
    ): GrantScopedSearchSession {
        val current = enterScope(currentPhotoScopeEpoch, currentPeopleGeneration)
        if (current !== this || current.photoScopeEpoch == null ||
            eventPhotoScopeEpoch != currentPhotoScopeEpoch ||
            eventPeopleGeneration != currentPeopleGeneration
        ) return current
        return copy(recent = recent.clear())
    }

    /** Snapshot admission before rendering, not just inside a launched worker. */
    fun currentOrNull(
        currentPhotoScopeEpoch: String?,
        currentPeopleGeneration: String?
    ): GrantScopedSearchSession? =
        takeIf {
            !currentPhotoScopeEpoch.isNullOrBlank() &&
                !currentPeopleGeneration.isNullOrBlank() &&
                it.photoScopeEpoch == currentPhotoScopeEpoch &&
                it.peopleGeneration == currentPeopleGeneration &&
                it.recent.permissionEpoch == currentPhotoScopeEpoch
        }
}
