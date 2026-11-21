package nl.atlas.gallery.featurestories

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * YEARS-1 (#586): the pure half of a named person's "Through the years" Story.
 *
 * Input is only what the People index already knows about photos of ONE named
 * person: a confirmed face, or a face the engine added above its calibrated
 * confidence floor. An uncertain suggestion never reaches this planner. There
 * is no new model, no scan and no guessed age: an age is shown only when the
 * user typed a birth date for that person.
 */
data class YearsCandidate(
    val uri: String,
    val takenAtMillis: Long,
    /** True for a user-confirmed face, false for an engine-added match above the floor. */
    val confirmed: Boolean,
    /** Existing best-shot face quality (0..1), or null when none was computed. */
    val quality: Float? = null,
    val isVideo: Boolean = false,
    val isHidden: Boolean = false,
    val isTrashed: Boolean = false
)

/** One frame of the Story, oldest to newest. [caption] is a year or an age. */
data class YearsFrame(
    val uri: String,
    val takenAtMillis: Long,
    val year: Int,
    val caption: String
)

object YearsStoryPlanner {
    /** A Story is offered once two different years each have at least this many photos. */
    const val MIN_PHOTOS_PER_YEAR = 4
    const val MIN_YEARS = 2
    const val MAX_PHOTOS_PER_YEAR = 3

    /**
     * The Story frames, or an empty list when the person is not eligible
     * (fewer than [MIN_YEARS] years with [MIN_PHOTOS_PER_YEAR] photos each).
     * Once eligible every observed year contributes its 1..3 best photos,
     * including sparse years with fewer than four.
     */
    fun plan(
        candidates: List<YearsCandidate>,
        zone: ZoneId,
        nowMillis: Long,
        birthDate: LocalDate? = null,
        cancellationCheckpoint: (() -> Unit)? = null
    ): List<YearsFrame> {
        val blocked = HashSet<String>()
        candidates.forEach { if (it.isHidden || it.isTrashed) blocked += it.uri }
        // One entry per media item; the strongest observation of it wins.
        val best = LinkedHashMap<String, Pair<YearsCandidate, LocalDate>>()
        candidates.forEachIndexed { index, c ->
            if ((index and 1023) == 0) cancellationCheckpoint?.invoke()
            if (c.isVideo || c.uri in blocked || !c.uri.startsWith("content://")) return@forEachIndexed
            if (c.takenAtMillis <= 0L || c.takenAtMillis > nowMillis) return@forEachIndexed
            val date = runCatching {
                Instant.ofEpochMilli(c.takenAtMillis).atZone(zone).toLocalDate()
            }.getOrNull() ?: return@forEachIndexed
            val held = best[c.uri]
            if (held == null || compareCandidates(c, held.first) < 0) best[c.uri] = c to date
        }
        val byYear = sortedMapOf<Int, MutableList<Pair<YearsCandidate, LocalDate>>>()
        best.values.forEach { byYear.getOrPut(it.second.year) { ArrayList() }.add(it) }
        val eligibleYears = byYear.count { it.value.size >= MIN_PHOTOS_PER_YEAR }
        if (eligibleYears < MIN_YEARS) return emptyList()
        cancellationCheckpoint?.invoke()
        val frames = ArrayList<YearsFrame>()
        for ((year, items) in byYear) {
            val picked = items.sortedWith { a, b -> compareCandidates(a.first, b.first) }
                .take(MAX_PHOTOS_PER_YEAR)
                // Within a year the Story still runs oldest to newest.
                .sortedWith(compareBy<Pair<YearsCandidate, LocalDate>> { it.first.takenAtMillis }
                    .thenBy { it.first.uri })
            for ((c, date) in picked) {
                frames += YearsFrame(c.uri, c.takenAtMillis, year, caption(date, birthDate))
            }
        }
        return frames
    }

    /**
     * "Age N" from the full local birth date and the photo's local date; "Under 1"
     * before the first birthday; the plain year when no birth date is set or the
     * photo predates it (never a negative age).
     */
    fun caption(photoDate: LocalDate, birthDate: LocalDate?): String {
        if (birthDate == null || photoDate.isBefore(birthDate)) return photoDate.year.toString()
        val age = ageOn(photoDate, birthDate)
        return if (age < 1) "Under 1" else "Age $age"
    }

    /** Completed years on [on]; a leap-day birthday is not reached before 29 Feb or 1 Mar. */
    fun ageOn(on: LocalDate, birthDate: LocalDate): Int {
        var age = on.year - birthDate.year
        val reached = on.monthValue > birthDate.monthValue ||
            (on.monthValue == birthDate.monthValue && on.dayOfMonth >= birthDate.dayOfMonth)
        if (!reached) age -= 1
        return age
    }

    /** Whether a birth date the user typed is acceptable: a real past-or-today date. */
    fun validBirthDate(date: LocalDate?, today: LocalDate): Boolean =
        date != null && !date.isAfter(today) && date.year >= 1900

    /** Quality first (none last), then confirmed over added, then earliest, then URI. */
    internal fun compareCandidates(a: YearsCandidate, b: YearsCandidate): Int {
        val qa = a.quality ?: -1f
        val qb = b.quality ?: -1f
        if (qa != qb) return qb.compareTo(qa)
        if (a.confirmed != b.confirmed) return if (a.confirmed) -1 else 1
        val byTime = a.takenAtMillis.compareTo(b.takenAtMillis)
        return if (byTime != 0) byTime else a.uri.compareTo(b.uri)
    }
}
