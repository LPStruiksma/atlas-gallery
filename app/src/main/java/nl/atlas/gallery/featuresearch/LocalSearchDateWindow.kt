package nl.atlas.gallery.featuresearch

import java.util.Calendar
import java.util.TimeZone

/** Absolute interval fixed when the user chooses a calendar period. */
data class SearchDateWindow(
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long
) {
    init {
        require(startInclusiveMillis > 0L && endExclusiveMillis > startInclusiveMillis)
    }

    /** A missing/untrusted provider date is not evidence of belonging to any period. */
    fun includes(mediaDateMillis: Long): Boolean =
        mediaDateMillis >= startInclusiveMillis && mediaDateMillis < endExclusiveMillis
}

enum class SearchDateChoice { ANY, THIS_YEAR, LAST_YEAR, EARLIER }

data class SearchDateOption(
    val choice: SearchDateChoice,
    val label: String,
    val window: SearchDateWindow?
)

/**
 * The UI offers local calendar years, not a rolling 365-day approximation.
 * Compute just four tiny entries at presentation time, never per media row.
 * Each choice stores an ABSOLUTE window in LocalSearchFacets, so async
 * searches, process time changes and daylight-saving boundaries do not drift.
 */
fun localSearchDateOptions(
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault()
): List<SearchDateOption> {
    val cal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
    val currentYear = cal.get(Calendar.YEAR)
    if (currentYear !in 1972..2099) return listOf(
        SearchDateOption(SearchDateChoice.ANY, "Any date", null)
    )

    fun yearStart(year: Int): Long = Calendar.getInstance(timeZone).apply {
        clear()
        set(year, Calendar.JANUARY, 1, 0, 0, 0)
    }.timeInMillis

    val thisYear = yearStart(currentYear)
    val nextYear = yearStart(currentYear + 1)
    val lastYear = yearStart(currentYear - 1)
    return listOf(
        SearchDateOption(SearchDateChoice.ANY, "Any date", null),
        SearchDateOption(SearchDateChoice.THIS_YEAR, "$currentYear", SearchDateWindow(thisYear, nextYear)),
        SearchDateOption(SearchDateChoice.LAST_YEAR, "${currentYear - 1}", SearchDateWindow(lastYear, thisYear)),
        SearchDateOption(SearchDateChoice.EARLIER, "Before ${currentYear - 1}", SearchDateWindow(1L, lastYear))
    )
}

/** Central date predicate shared by the search worker and deterministic tests. */
fun matchesSearchDateFacet(mediaDateMillis: Long, window: SearchDateWindow?): Boolean =
    window == null || window.includes(mediaDateMillis)
