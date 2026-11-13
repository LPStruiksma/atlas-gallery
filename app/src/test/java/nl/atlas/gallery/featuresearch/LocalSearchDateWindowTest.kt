package nl.atlas.gallery.featuresearch

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchDateWindowTest {
    private val amsterdam = TimeZone.getTimeZone("Europe/Amsterdam")
    private fun millis(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        Calendar.getInstance(amsterdam).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    @Test fun distinctLocalCalendarYearsWithExactEdges() {
        val options = localSearchDateOptions(millis(2026, 11, 3), amsterdam)
        assertEquals(listOf("Any date", "2026", "2025", "Before 2025"), options.map { it.label })
        val thisYear = options[1].window!!
        assertFalse(thisYear.includes(millis(2025, 12, 31, 23)))
        assertTrue(thisYear.includes(millis(2026, 1, 1)))
        assertTrue(thisYear.includes(millis(2026, 12, 31, 23)))
        assertFalse(thisYear.includes(millis(2027, 1, 1)))
        assertTrue(options[2].window!!.includes(millis(2025, 7, 2)))
        assertFalse(options[2].window!!.includes(millis(2026, 1, 1)))
        assertTrue(options[3].window!!.includes(millis(2018, 4, 6)))
        assertFalse(options[3].window!!.includes(millis(2025, 1, 1)))
    }

    @Test fun daylightSavingTransitionUsesLocalMidnightNotFixedDuration() {
        val options = localSearchDateOptions(millis(2026, 10, 25), amsterdam)
        val thisYear = options[1].window!!
        assertEquals(millis(2026, 1, 1), thisYear.startInclusiveMillis)
        assertEquals(millis(2027, 1, 1), thisYear.endExclusiveMillis)
        assertTrue(thisYear.includes(millis(2026, 10, 25, 23)))
    }

    @Test fun unknownMediaDateNeverPassesSelectedPeriod() {
        val window = localSearchDateOptions(millis(2026, 7, 1), amsterdam)[1].window
        assertTrue(matchesSearchDateFacet(0L, null))
        assertFalse(matchesSearchDateFacet(0L, window))
        assertFalse(matchesSearchDateFacet(-1L, window))
        assertTrue(matchesSearchDateFacet(millis(2026, 7, 1), window))
        assertFalse(matchesSearchDateFacet(millis(2025, 7, 1), window))
    }

    @Test fun choiceIsAbsoluteEvenWhenClockMoves() {
        val chosen = localSearchDateOptions(millis(2026, 12, 31), amsterdam)[1].window
        assertEquals(chosen, localSearchDateOptions(millis(2026, 11, 3), amsterdam)[1].window)
        assertFalse(matchesSearchDateFacet(millis(2027, 1, 1), chosen))
        assertEquals("2027", localSearchDateOptions(millis(2027, 1, 1), amsterdam)[1].label)
    }

    @Test fun invalidIntervalsAreRejectedInsteadOfBroadening() {
        assertThrows(IllegalArgumentException::class.java) { SearchDateWindow(0L, 10L) }
        assertThrows(IllegalArgumentException::class.java) { SearchDateWindow(10L, 10L) }
        assertThrows(IllegalArgumentException::class.java) { SearchDateWindow(12L, 10L) }
    }

    @Test fun invalidDeviceClockOffersSafeUnfilteredChoice() {
        val choices = localSearchDateOptions(millis(1970, 1, 1), amsterdam)
        assertEquals(listOf(SearchDateChoice.ANY), choices.map { it.choice })
    }

    @Test fun fictionalLargeCatalogDatePredicateRemainsBounded() {
        val window = localSearchDateOptions(millis(2026, 11, 3), amsterdam)[1].window
        val dates = LongArray(150_157) { if (it % 2 == 0) millis(2026, 4, 1) else millis(2024, 3, 1) }
        assertEquals(75_079, dates.count { matchesSearchDateFacet(it, window) })
        assertEquals(dates.size, dates.count { matchesSearchDateFacet(it, null) })
    }

    @Test fun everyDateWindowUsesInclusiveFirstMillisecondAndExclusiveNextMidnight() {
        val options = localSearchDateOptions(millis(2026, 11, 3), amsterdam)
        for (option in options.drop(1)) {
            val interval = option.window!!
            assertFalse(matchesSearchDateFacet(interval.startInclusiveMillis - 1L, interval))
            assertTrue(matchesSearchDateFacet(interval.startInclusiveMillis, interval))
            assertTrue(matchesSearchDateFacet(interval.endExclusiveMillis - 1L, interval))
            assertFalse(matchesSearchDateFacet(interval.endExclusiveMillis, interval))
            assertFalse(matchesSearchDateFacet(0L, interval))
        }
        // The fall-back DST day is 25 hours in Amsterdam. The year and
        // period boundaries are calendar midnights, not 24-hour arithmetic.
        val thisYear = options[1].window!!
        assertTrue(thisYear.includes(millis(2026, 10, 25, 23)))
        assertFalse(thisYear.includes(millis(2027, 1, 1)))
    }
}
