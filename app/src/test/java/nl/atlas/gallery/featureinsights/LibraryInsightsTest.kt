package nl.atlas.gallery.featureinsights

import nl.atlas.gallery.INSIGHTS_ROUTE_PREFIX
import nl.atlas.gallery.INSIGHTS_ROUTE_VOLUME
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumRoute
import nl.atlas.gallery.matchesAlbumRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Synthetic records only: no MediaStore, no personal media. */
class LibraryInsightsTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun millis(year: Int, month: Int = 6) =
        ZonedDateTime.of(year, month, 15, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private fun record(
        id: Long, folder: String = "DCIM/Camera/", size: Long = 1000L, taken: Long = millis(2024),
        video: Boolean = false, name: String = "item$id.jpg", volume: String = "external_primary",
        duration: Long = 0L
    ) = MediaRecord(id = id, uri = "content://fiction/$volume/$id", name = name,
        relativePath = folder, sizeBytes = size, takenAtMillis = taken, isVideo = video,
        durationMillis = duration, volumeName = volume)

    @Test fun emptyLibraryHasNoRowsAndNoInventedTotals() {
        val insights = computeLibraryInsights(emptyList(), utc)
        assertTrue(insights.isEmpty)
        assertEquals(0L, insights.totalBytes)
        assertTrue(insights.folders.isEmpty() && insights.types.isEmpty() && insights.years.isEmpty())
    }

    @Test fun totalsAndTypeGroupsDoNotDoubleCountSubgroups() {
        val records = listOf(
            record(1, size = 100),
            record(2, folder = "Pictures/Screenshots/", size = 50, name = "Screenshot_1.png"),
            record(3, size = 400, name = "IMG_3.DNG"),
            record(4, size = 1000, video = true, name = "VID_4.mp4", duration = 90_000L),
            // A video in Screenshots is a screen recording, never a screenshot.
            record(5, folder = "Pictures/Screenshots/", size = 10, video = true, name = "rec.mp4",
                duration = 30_000L)
        )
        val insights = computeLibraryInsights(records, utc)
        assertEquals(1560L, insights.totalBytes)
        assertEquals(5, insights.totalCount)
        val byType = insights.types.associateBy { (it.scope as InsightsScope.Type).type }
        assertEquals(550L, byType.getValue(InsightsType.PHOTOS).bytes)
        assertEquals(3, byType.getValue(InsightsType.PHOTOS).count)
        assertEquals(1010L, byType.getValue(InsightsType.VIDEOS).bytes)
        assertEquals(1, byType.getValue(InsightsType.SCREENSHOTS).count)
        assertEquals(400L, byType.getValue(InsightsType.RAW).bytes)
        // Photos + Videos are exclusive and cover the headline; subgroups are extra rows.
        assertEquals(insights.totalBytes,
            byType.getValue(InsightsType.PHOTOS).bytes + byType.getValue(InsightsType.VIDEOS).bytes)
        assertEquals(listOf("Photos", "Videos", "Screenshots", "RAW"), insights.types.map { it.label })
        assertEquals(120_000L, insights.videoDurationMillis)
        assertTrue(insights.videoDurationExact)
    }

    @Test fun missingVideoDurationMakesTheSumInexact() {
        val insights = computeLibraryInsights(listOf(
            record(1, video = true, duration = 60_000L), record(2, video = true, duration = 0L)), utc)
        assertEquals(60_000L, insights.videoDurationMillis)
        assertFalse(insights.videoDurationExact)
    }

    @Test fun emptyTypeGroupsAreLeftOut() {
        val insights = computeLibraryInsights(listOf(record(1)), utc)
        assertEquals(listOf("Photos"), insights.types.map { it.label })
    }

    @Test fun foldersRankBytesThenCountThenNormalisedPathAndNeverFold() {
        val records = listOf(
            record(1, "B/", size = 500), record(2, "a/", size = 500),
            record(3, "C/", size = 300), record(4, "C/", size = 200),
            record(5, "D/", size = 10_000)
        )
        val folders = computeLibraryInsights(records, utc).folders
        // D (10000) first; then C (500 bytes, 2 items) beats a/ and B/ (500, 1 item each);
        // a/ before B/ because normalised paths compare case-insensitively.
        assertEquals(listOf("D", "C", "a", "B"), folders.map { it.label })
        assertEquals(5, folders.sumOf { it.count })
    }

    @Test fun sameNamedFolderOnAnotherVolumeIsAnotherFolder() {
        val folders = computeLibraryInsights(listOf(
            record(1, volume = "external_primary"), record(2, volume = "0123-4567")), utc).folders
        assertEquals(2, folders.size)
        assertEquals(setOf("external_primary", "0123-4567"),
            folders.map { (it.scope as InsightsScope.Folder).volumeName }.toSet())
    }

    @Test fun unknownFolderIsOnlyForBlankPaths() {
        val folders = computeLibraryInsights(listOf(record(1, folder = "")), utc).folders
        assertEquals("Unknown folder", folders.single().label)
        assertNull(folders.single().fullPath)
        assertEquals("Camera", computeLibraryInsights(listOf(record(2)), utc).folders.single().label)
    }

    @Test fun yearsAreNewestFirstWithUnknownDateLastAndNeverInvented() {
        val insights = computeLibraryInsights(listOf(
            record(1, taken = millis(2021)), record(2, taken = millis(2024)),
            record(3, taken = millis(2024)), record(4, taken = 0L)), utc)
        assertEquals(listOf("2024", "2021", "Unknown date"), insights.years.map { it.label })
        assertEquals(2, insights.years.first().count)
        assertEquals(InsightsScope.Year(null), insights.years.last().scope)
    }

    @Test fun yearUsesTheViewersTimeZone() {
        val nearMidnight = ZonedDateTime.of(2023, 12, 31, 23, 30, 0, 0, utc).toInstant().toEpochMilli()
        val inUtc = computeLibraryInsights(listOf(record(1, taken = nearMidnight)), utc)
        val inTokyo = computeLibraryInsights(listOf(record(1, taken = nearMidnight)),
            ZoneId.of("Asia/Tokyo"))
        assertEquals("2023", inUtc.years.single().label)
        assertEquals("2024", inTokyo.years.single().label)
    }

    @Test fun negativeSizeCountsAsZeroBytesButStillCountsTheItem() {
        val insights = computeLibraryInsights(listOf(record(1, size = -5), record(2, size = 7)), utc)
        assertEquals(7L, insights.totalBytes)
        assertEquals(2, insights.totalCount)
    }

    @Test fun sixtyFourBitSumsDoNotOverflow() {
        val huge = 3_000_000_000L
        val insights = computeLibraryInsights(List(2000) { record(it.toLong(), size = huge) }, utc)
        assertEquals(huge * 2000L, insights.totalBytes)
        assertEquals(huge * 2000L, insights.folders.single().bytes)
    }

    @Test fun everyRowOpensExactlyTheRecordsItCounted() {
        val records = listOf(
            record(1, "A/", 100, millis(2020)), record(2, "A/", 200, millis(2021), name = "x.arw"),
            record(3, "B/", 300, millis(2021), video = true, duration = 1000),
            record(4, "Pictures/Screenshots/", 40, 0L, name = "Screenshot_a.png"),
            record(5, "B/", 50, millis(2021)))
        val insights = computeLibraryInsights(records, utc)
        for (row in insights.folders + insights.types + insights.years) {
            val matching = records.filter { row.scope.matches(it, utc) }
            assertEquals("${row.label} count", row.count, matching.size)
            assertEquals("${row.label} bytes", row.bytes, matching.sumOf { it.sizeBytes })
        }
    }

    @Test fun typeAndYearScopesRouteThroughTheExistingAlbumRoute() {
        val photo = record(1, taken = millis(2022))
        val video = record(2, taken = millis(2022), video = true)
        val route = { scope: InsightsScope ->
            AlbumRoute(INSIGHTS_ROUTE_PREFIX + scope.encode(), AlbumMediaFilter.ALL, INSIGHTS_ROUTE_VOLUME)
        }
        assertTrue(matchesAlbumRoute(photo, route(InsightsScope.Type(InsightsType.PHOTOS))))
        assertFalse(matchesAlbumRoute(video, route(InsightsScope.Type(InsightsType.PHOTOS))))
        assertTrue(matchesAlbumRoute(video, route(InsightsScope.Year(2022))))
        assertFalse(matchesAlbumRoute(video, route(InsightsScope.Year(2023))))
        // A damaged saved route never matches anything.
        assertFalse(matchesAlbumRoute(photo,
            AlbumRoute(INSIGHTS_ROUTE_PREFIX + "garbage", AlbumMediaFilter.ALL, INSIGHTS_ROUTE_VOLUME)))
        // And a real folder is never mistaken for an insights scope.
        assertTrue(matchesAlbumRoute(photo,
            AlbumRoute("DCIM/Camera/", AlbumMediaFilter.ALL, "external_primary")))
    }

    @Test fun scopesSurviveSavedStateRoundTrip() {
        val scopes = listOf(
            InsightsScope.Folder("0123-4567", "Pictures/Café Ünï/"), InsightsScope.Type(InsightsType.RAW),
            InsightsScope.Year(1999), InsightsScope.Year(null))
        scopes.forEach { assertEquals(it, InsightsScope.decode(it.encode())) }
        assertNull(InsightsScope.decode("t:nonsense"))
        assertNull(InsightsScope.decode(""))
        assertEquals("Unknown date", insightsScopeTitle(InsightsScope.Year(null).encode()))
        assertEquals("RAW", insightsScopeTitle(InsightsScope.Type(InsightsType.RAW).encode()))
    }

    @Test fun barFractionKeepsRelativeScaleAndANonzeroFloor() {
        assertEquals(1f, insightsBarFraction(100, 100), 0f)
        assertEquals(0.35f, insightsBarFraction(35, 100), 0.0001f)
        assertEquals(0f, insightsBarFraction(0, 100), 0f)
        assertEquals(0f, insightsBarFraction(5, 0), 0f)
        assertTrue(insightsBarFraction(1, 1_000_000_000_000L) > 0f)
    }

    @Test fun durationText() {
        assertNull(insightsDuration(0))
        assertEquals("1 min", insightsDuration(10_000))
        assertEquals("19 h 24 min", insightsDuration((19 * 60 + 24) * 60_000L))
        assertEquals("2 h", insightsDuration(120 * 60_000L))
    }

    @Test fun spokenSizeAndCountsForTalkBack() {
        assertEquals("25.4 gigabytes", spokenSize("25.4 GB"))
        assertEquals("1 item", countText(1, "item", "items", java.util.Locale.US))
        assertEquals("6,920 items", countText(6920, "item", "items", java.util.Locale.US))
    }

    @Test fun aggregationOf250kRecordsIsCorrectAndWithinTheTimeBound() {
        val n = 250_000
        val records = ArrayList<MediaRecord>(n)
        for (i in 0 until n) {
            records += record(i.toLong(), folder = "Pictures/F${i % 30_000}/", size = 1_000L + i % 7,
                taken = millis(2000 + i % 25), video = i % 10 == 0, duration = 1000L,
                name = if (i % 50 == 0) "r$i.dng" else "p$i.jpg")
        }
        val started = System.nanoTime()
        val insights = computeLibraryInsights(records, utc)
        val seconds = (System.nanoTime() - started) / 1e9
        // Correctness is asserted apart from speed so a slow runner cannot hide a wrong total.
        assertEquals(n, insights.totalCount)
        assertEquals(records.sumOf { it.sizeBytes }, insights.totalBytes)
        assertEquals(30_000, insights.folders.size)
        assertEquals(n, insights.folders.sumOf { it.count })
        assertEquals(25, insights.years.size)
        assertEquals(n, insights.years.sumOf { it.count })
        assertEquals(n / 10, insights.types.first { it.label == "Videos" }.count)
        println("INSIGHT-1 aggregation of $n records and 30000 folders: ${"%.3f".format(seconds)} s")
        assertTrue("aggregation took $seconds s (target <= 5 s)", seconds <= 5.0)
    }

    @Test fun cancellationCheckpointAbortsLongPasses() {
        val records = List(5000) { record(it.toLong()) }
        var calls = 0
        try {
            computeLibraryInsights(records, utc) { if (++calls == 2) throw IllegalStateException("stop") }
            throw AssertionError("expected the checkpoint to abort the pass")
        } catch (expected: IllegalStateException) {
            assertEquals(2, calls)
        }
    }
}
