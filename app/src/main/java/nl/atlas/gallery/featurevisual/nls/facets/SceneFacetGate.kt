package nl.atlas.gallery.featurevisual.nls.facets

/**
 * A pure intersection over already authorized, ordered scene hits and current
 * membership facts supplied by existing permission-scoped stores.
 *
 * This class neither discovers media nor persists a metadata source. The caller
 * must provide only approved candidates, fence membership reads to [Scope], and
 * advance the scope whenever a photo grant or People publication changes.
 * A missing membership row is an unknown/non-match for selected facets; a
 * source that cannot answer reliably must throw [UnavailableFacetData].
 */
class SceneFacetGate(private val capturedScope: Scope) {
    data class Scope(val grantEpoch: String, val peopleGeneration: String) {
        fun isValid(): Boolean = grantEpoch.isNotBlank() && peopleGeneration.isNotBlank()
    }

    data class Selection(
        val person: String? = null,
        val date: String? = null,
        val album: String? = null,
        val place: String? = null
    ) {
        val active: Boolean
            get() = person != null || date != null || album != null || place != null

        fun isValid(): Boolean = listOfNotNull(person, date, album, place)
            .all { it.isNotBlank() }
    }

    /** Media-level membership. All video frames share the canonical media key. */
    data class Membership(
        val mediaKey: String,
        val persons: Set<String> = emptySet(),
        val dates: Set<String> = emptySet(),
        val albums: Set<String> = emptySet(),
        val places: Set<String> = emptySet()
    )

    class UnavailableFacetData : RuntimeException("Facet membership source unavailable")

    sealed interface Result<out T> {
        data class Filtered<T>(val hits: List<T>, val scanned: Int) : Result<T>
        data object Aborted : Result<Nothing>
    }

    @Volatile private var invalidated: Boolean = false

    /** Explicitly revoke a captured snapshot, even if an epoch token is reused. */
    fun invalidate() {
        invalidated = true
    }

    /**
     * Stream candidates in their existing rank/order; only accepted references
     * are retained. [maxResults] bounds retained memory and is NOT an early
     * iteration cutoff: all rows are visited to catch late revocation/failure.
     *
     * [currentScope] must read the live grant and People generation, not a
     * closure returning the captured snapshot without checking live state.
     * [membershipFor] must be a current-scope lookup keyed by canonical media ID.
     * It returns null for an unknown media ID and throws [UnavailableFacetData]
     * if its backing store is missing, stale, or cannot answer.
     */
    fun <T> filter(
        candidates: Iterator<T>,
        selection: Selection,
        expectedScope: Scope,
        membershipFor: (String) -> Membership?,
        currentScope: () -> Scope?,
        maxResults: Int = 250,
        mediaKey: (T) -> String = { candidate ->
            // Callers with other hit types must supply an ID projection.
            (candidate as? HasMediaKey)?.mediaKey.orEmpty()
        },
        isCancelled: () -> Boolean = { false }
    ): Result<T> {
        require(maxResults > 0) { "maxResults must be positive" }
        fun stopped(): Boolean = invalidated || isCancelled() ||
            Thread.currentThread().isInterrupted ||
            !capturedScope.isValid() || !expectedScope.isValid() ||
            expectedScope != capturedScope || currentScope() != capturedScope

        if (stopped() || !selection.isValid()) return Result.Aborted
        val matches = ArrayList<T>(minOf(maxResults, 256))
        var scanned = 0
        try {
            while (true) {
                if (stopped()) return Result.Aborted
                if (!candidates.hasNext()) break
                if (stopped()) return Result.Aborted
                val candidate = candidates.next()
                scanned++
                val key = mediaKey(candidate)
                if (key.isBlank()) continue
                if (selection.active) {
                    // Scope must also remain valid across an external store read.
                    val facts = membershipFor(key)
                    if (stopped()) return Result.Aborted
                    if (facts == null || facts.mediaKey != key ||
                        (selection.person != null && selection.person !in facts.persons) ||
                        (selection.date != null && selection.date !in facts.dates) ||
                        (selection.album != null && selection.album !in facts.albums) ||
                        (selection.place != null && selection.place !in facts.places)
                    ) continue
                }
                if (matches.size < maxResults) matches.add(candidate)
            }
        } catch (_: UnavailableFacetData) {
            return Result.Aborted
        }
        if (stopped()) return Result.Aborted
        return Result.Filtered(matches, scanned)
    }

    /** Optional adapter for a hit carrying the same canonical ID as the store. */
    interface HasMediaKey { val mediaKey: String }
}
