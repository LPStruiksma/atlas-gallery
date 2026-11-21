package nl.atlas.gallery.featurestories

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YearsStoryPlannerTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val now = LocalDate.of(2026, 11, 21).atTime(12, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int = 6, day: Int = 15) =
        LocalDate.of(year, month, day).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private var counter = 0
    private fun photos(year: Int, n: Int, confirmed: Boolean = true, quality: Float? = null) =
        (1..n).map {
            YearsCandidate("content://synthetic/${year}_${counter++}", at(year, 6, it), confirmed, quality)
        }

    @Test fun oneYearOfManyPhotosIsNotEligible() {
        assertTrue(YearsStoryPlanner.plan(photos(2020, 20), zone, now).isEmpty())
    }

    @Test fun twoYearsWithThreePhotosEachIsNotEligible() {
        assertTrue(YearsStoryPlanner.plan(photos(2019, 3) + photos(2020, 3), zone, now).isEmpty())
    }

    @Test fun exactlyTwoYearsByFourPhotosIsEligibleWithOnePhotoPerYearMinimum() {
        val frames = YearsStoryPlanner.plan(photos(2019, 4) + photos(2020, 4), zone, now)
        assertEquals(listOf(2019, 2019, 2019, 2020, 2020, 2020), frames.map { it.year })
        assertEquals(listOf("2019", "2019", "2019", "2020", "2020", "2020"), frames.map { it.caption })
    }

    @Test fun sparseYearsContributeOnceTheStoryIsEligible() {
        val frames = YearsStoryPlanner.plan(
            photos(2015, 4) + photos(2017, 1) + photos(2024, 5), zone, now)
        assertEquals(setOf(2015, 2017, 2024), frames.map { it.year }.toSet())
        assertEquals(1, frames.count { it.year == 2017 })
    }

    @Test fun sparseYearsAloneNeverMakeTheStoryEligible() {
        val sparse = (2010..2020).flatMap { photos(it, 3) }
        assertTrue(YearsStoryPlanner.plan(sparse, zone, now).isEmpty())
    }

    @Test fun bestQualityWinsThenConfirmedThenEarliest() {
        val low = YearsCandidate("content://synthetic/low", at(2019, 1, 1), true, 0.2f)
        val high = YearsCandidate("content://synthetic/high", at(2019, 5, 1), false, 0.9f)
        val mid = YearsCandidate("content://synthetic/mid", at(2019, 3, 1), true, 0.5f)
        val tieAdded = YearsCandidate("content://synthetic/tieadded", at(2019, 2, 1), false, 0.5f)
        val none = YearsCandidate("content://synthetic/none", at(2019, 1, 2), true, null)
        val frames = YearsStoryPlanner.plan(
            listOf(low, high, mid, tieAdded, none) + photos(2020, 4), zone, now)
        val picked = frames.filter { it.year == 2019 }.map { it.uri }
        assertEquals(setOf("content://synthetic/high", "content://synthetic/mid",
            "content://synthetic/tieadded"), picked.toSet())
        // Oldest to newest inside the year.
        assertEquals(listOf("content://synthetic/tieadded", "content://synthetic/mid",
            "content://synthetic/high"), picked)
    }

    @Test fun hiddenTrashedVideoAndFutureItemsNeverQualify() {
        val base = photos(2019, 4) + photos(2020, 4)
        val vetoed = base[0].copy(isHidden = true)
        val withVeto = base + vetoed + base[4].copy(isVideo = true, uri = "content://synthetic/v") +
            YearsCandidate("content://synthetic/future", now + 86_400_000L, true)
        val frames = YearsStoryPlanner.plan(withVeto, zone, now)
        assertFalse(frames.any { it.uri == vetoed.uri })
        assertFalse(frames.any { it.uri == "content://synthetic/v" || it.uri == "content://synthetic/future" })
        // 2019 now has 3 usable photos: still eligible through 2020 alone? No: two years needed.
        assertTrue(YearsStoryPlanner.plan(
            photos(2019, 4).let { listOf(it[0].copy(isTrashed = true)) + it.drop(1) } + photos(2020, 4),
            zone, now).isEmpty())
    }

    @Test fun duplicateObservationsOfOneMediaCountOnce() {
        val one = YearsCandidate("content://synthetic/dup", at(2019, 1, 1), true, 0.4f)
        val dupes = List(10) { one } + photos(2020, 4)
        assertTrue(YearsStoryPlanner.plan(dupes, zone, now).isEmpty())
    }

    @Test fun planningIsDeterministic() {
        val input = photos(2019, 6, quality = 0.5f) + photos(2022, 6, quality = 0.5f)
        assertEquals(YearsStoryPlanner.plan(input, zone, now),
            YearsStoryPlanner.plan(input.reversed(), zone, now))
    }

    @Test fun captionsUseAgeOnlyWhenABirthDateIsSet() {
        val birth = LocalDate.of(2010, 8, 20)
        assertEquals("2019", YearsStoryPlanner.caption(LocalDate.of(2019, 8, 19), null))
        assertEquals("Age 8", YearsStoryPlanner.caption(LocalDate.of(2019, 8, 19), birth))
        assertEquals("Age 9", YearsStoryPlanner.caption(LocalDate.of(2019, 8, 20), birth))
        assertEquals("Under 1", YearsStoryPlanner.caption(LocalDate.of(2011, 1, 5), birth))
        assertEquals("Age 1", YearsStoryPlanner.caption(LocalDate.of(2011, 8, 20), birth))
        // A photo dated before the birth date falls back to the year, never a negative age.
        assertEquals("2009", YearsStoryPlanner.caption(LocalDate.of(2009, 3, 1), birth))
    }

    @Test fun leapDayBirthdayIsNotReachedEarly() {
        val birth = LocalDate.of(2016, 2, 29)
        assertEquals(1, YearsStoryPlanner.ageOn(LocalDate.of(2017, 3, 1), birth))
        assertEquals(0, YearsStoryPlanner.ageOn(LocalDate.of(2017, 2, 28), birth))
        assertEquals(4, YearsStoryPlanner.ageOn(LocalDate.of(2020, 2, 29), birth))
        assertEquals(3, YearsStoryPlanner.ageOn(LocalDate.of(2020, 2, 28), birth))
    }

    @Test fun birthDateValidation() {
        val today = LocalDate.of(2026, 11, 21)
        assertTrue(YearsStoryPlanner.validBirthDate(today, today))
        assertFalse(YearsStoryPlanner.validBirthDate(today.plusDays(1), today))
        assertFalse(YearsStoryPlanner.validBirthDate(null, today))
        assertFalse(YearsStoryPlanner.validBirthDate(LocalDate.of(1800, 1, 1), today))
    }

    @Test fun framesCarryAgeCaptionsFromTheBirthDate() {
        val birth = LocalDate.of(2010, 1, 1)
        val frames = YearsStoryPlanner.plan(photos(2012, 4) + photos(2020, 4), zone, now, birth)
        assertEquals(setOf("Age 2", "Age 10"), frames.map { it.caption }.toSet())
    }
}
