package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** Pure JVM tests: fictional names and metadata, zero private imagery. */
class MediaMetadataTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun synthetic(volume: String = "external_primary", video: Boolean = false) =
        MediaRecord(
            id = 42L, uri = "content://media/" + volume + "/" +
                (if (video) "video" else "images") + "/media/42",
            name = "IMG_20240229_235959.jpg", relativePath = "DCIM/FICTION/",
            sizeBytes = 2_000L, takenAtMillis = 1_700_000_000_000L,
            isVideo = video, modifiedAtSeconds = 1_700_000_000L,
            volumeName = volume, providerVersion = "version-A",
            generationModified = 500L,
            widthPixels = 4_000, heightPixels = 3_000, orientationDegrees = 90
        )

    @Test fun dateFallbackIsCalendarStrictAndRecordsItsSource() {
        assertEquals(MediaDateSource.TAKEN,
            resolveMediaDate(123L, "IMG_20240229_235959.jpg", 321L, 432L, zone).source)
        assertEquals(MediaDateSource.FILENAME_TIMESTAMP,
            resolveMediaDate(0L, "IMG_20240229_235959.jpg", 321L, 432L, zone).source)
        assertEquals(MediaDateSource.FILENAME_TIMESTAMP,
            resolveMediaDate(0L, "Screenshot_20240130-152433.png", 321L, 432L, zone).source)
        assertEquals(MediaDateSource.FILENAME_TIMESTAMP,
            resolveMediaDate(0L, "PXL_20240229_235959431.jpg", 321L, 432L, zone).source)
        assertEquals(MediaDateSource.FILENAME_DATE,
            resolveMediaDate(0L, "IMG-20200101-WA0019.jpg", 321L, 432L, zone).source)
        for (invalid in listOf("IMG_20230229_235959.jpg", "IMG_20241301_235959.jpg",
            "IMG_20240302_246199.jpg", "NOTIMG_20240229_121212.jpg",
            "IMG_20230326_023000.jpg", "IMG_19000101_120000.jpg",
            "PXL_20240101_1200001234.jpg")) {
            assertEquals("Unexpected date parsed for " + invalid, MediaDateSource.MODIFIED,
                resolveMediaDate(0L, invalid, 321L, 432L, zone).source)
        }
        assertEquals(MediaDateSource.ADDED,
            resolveMediaDate(0L, "untagged.jpeg", 0L, 432L, zone).source)
        assertEquals(MediaDateSource.UNKNOWN,
            resolveMediaDate(0L, "untagged.jpeg", 0L, 0L, zone).source)
    }

    @Test fun implausibleProviderEpochsNeverCreateFarFutureGalleryDates() {
        val endOf2100Millis = 4_133_980_800_000L
        val endOf2100Seconds = endOf2100Millis / 1000L
        assertTrue(isPlausibleMediaTakenMillis(endOf2100Millis - 1))
        assertFalse(isPlausibleMediaTakenMillis(endOf2100Millis))
        assertFalse(isPlausibleMediaTakenMillis(Long.MAX_VALUE))
        assertTrue(isPlausibleMediaEpochSeconds(endOf2100Seconds - 1))
        assertFalse(isPlausibleMediaEpochSeconds(endOf2100Seconds))
        assertFalse(isPlausibleMediaEpochSeconds(Long.MAX_VALUE))
        // Invalid DATE_TAKEN yields to a valid filename, not a future timeline.
        assertEquals(MediaDateSource.FILENAME_TIMESTAMP,
            resolveMediaDate(Long.MAX_VALUE, "IMG_20240229_235959.jpg",
                Long.MAX_VALUE, Long.MAX_VALUE, zone).source)
        // A missing camera date and invalid modified date can still use ADDED.
        val date = resolveMediaDate(Long.MAX_VALUE, "no-date.jpg",
            Long.MAX_VALUE, 1_700_000_000L, zone)
        assertEquals(MediaDateSource.ADDED, date.source)
        assertEquals(1_700_000_000_000L, date.millis)
        assertEquals(MediaDateSource.UNKNOWN,
            resolveMediaDate(Long.MAX_VALUE, "no-date.jpg",
                Long.MAX_VALUE, Long.MAX_VALUE, zone).source)
        assertEquals(MediaDateSource.MODIFIED,
            resolveMediaDate(0L, "no-date.jpg",
                endOf2100Seconds - 1L, Long.MAX_VALUE, zone).source)
    }

    @Test fun identityDoesNotConfuseMediaTypesVolumesOrRepeatedFilenames() {
        val photo = synthetic()
        assertNotEquals(photo.mediaIdentity(), synthetic(video = true).mediaIdentity())
        assertNotEquals(photo.mediaIdentity(), synthetic(volume = "0123-ABCD").mediaIdentity())
        assertEquals(photo.mediaIdentity(),
            photo.copy(name = "renamed.jpg", relativePath = "DCIM/NEW/").mediaIdentity())
    }

    @Test fun sameIdAndNameAcrossRemovableVolumeCannotLeakV4PersonMembership() {
        val primary = synthetic(volume = "external_primary")
        val sdcard = synthetic(volume = "1234-ABCD")
        val unknown = synthetic(volume = "unknown")
        assertEquals("content://media/external_primary/images/media/42", primary.uri)
        assertEquals("content://media/1234-ABCD/images/media/42", sdcard.uri)
        assertEquals("/storage/emulated/0/DCIM/FICTION/" + primary.name, primary.absolutePath)
        assertEquals("/storage/1234-ABCD/DCIM/FICTION/" + sdcard.name, sdcard.absolutePath)
        assertNotEquals(primary.absolutePath, sdcard.absolutePath)
        assertNotEquals(primary.absolutePath, unknown.absolutePath)
        val reviewedPrimaryOnly = setOf(primary.absolutePath)
        assertEquals(listOf(primary),
            selectPersonPhotos(listOf(primary, sdcard, unknown), reviewedPrimaryOnly))
        // A filename-only guessed V4 match would have returned all three.
        assertEquals(1, countAccessiblePersonPhotos(reviewedPrimaryOnly,
            setOf(primary.absolutePath, sdcard.absolutePath, unknown.absolutePath)))
    }

    @Test fun nullProviderCursorMustNotMasqueradeAsSuccessfulEmptyMediaKind() {
        val accessibleEmpty: List<String> = emptyList()
        assertSame(accessibleEmpty, requireMediaCatalogueCursor(accessibleEmpty))
        assertEquals("one", requireMediaCatalogueCursor("one"))
        try {
            requireMediaCatalogueCursor<String>(null)
            fail("Missing cursor is not evidence of an empty gallery")
        } catch (expected: IllegalStateException) {
            assertEquals("Media provider returned no catalogue cursor", expected.message)
        }
    }

    @Test fun providerVolumeMissingOrUnmountedRefusesSilentPartialCatalogues() {
        val mounted = setOf("external_primary", "1234-ABCD")
        assertEquals("external_primary",
            requireMountedMediaVolume("external_primary", mounted))
        assertEquals("1234-ABCD",
            requireMountedMediaVolume("1234-ABCD", mounted))
        for (bad in listOf<String?>(null, "", "unknown", "external", "EJECTED")) {
            try {
                requireMountedMediaVolume(bad, mounted)
                fail("Expected rejection for an unresolvable storage volume")
            } catch (expected: IllegalStateException) {
                assertEquals("Media volume unavailable during catalogue scan", expected.message)
            }
        }
        try {
            requireMountedMediaVolume("external_primary", emptySet())
            fail("Failed enumeration cannot masquerade as a successful empty scan")
        } catch (expected: IllegalStateException) {
            assertEquals("Media volume unavailable during catalogue scan", expected.message)
        }
    }

    @Test fun missingFavouriteStatusIsNotEvidenceOfSafeDeclutter() {
        val missing = synthetic()
        assertFalse(missing.isFavorite)
        assertFalse(missing.favoriteKnown)
        val explicitlyNo = missing.copy(favoriteKnown = true)
        assertTrue(explicitlyNo.favoriteKnown)
        assertFalse(explicitlyNo.isFavorite)
        val explicitlyYes = missing.copy(favoriteKnown = true, isFavorite = true)
        assertTrue(explicitlyYes.favoriteKnown)
        assertTrue(explicitlyYes.isFavorite)
    }

    @Test fun orientationProducesJustifiedGeometryAndRejectsUnknown() {
        assertEquals(0.75f, synthetic().displayAspectRatioOrNull()!!, 0.0001f)
        assertEquals(4f / 3f, synthetic().copy(orientationDegrees = 0).displayAspectRatioOrNull()!!, 0.0001f)
        assertNull(synthetic().copy(widthPixels = 0).displayAspectRatioOrNull())
        assertNull(synthetic().copy(heightPixels = -1).displayAspectRatioOrNull())
        assertEquals(0.75f, synthetic().copy(orientationDegrees = -90)
            .displayAspectRatioOrNull()!!, 0.0001f)
        assertEquals(0.75f, synthetic().copy(orientationDegrees = 450)
            .displayAspectRatioOrNull()!!, 0.0001f)
        assertNull(synthetic().copy(orientationDegrees = 45).displayAspectRatioOrNull())
        assertNull(synthetic().copy(orientationDegrees = 135).displayAspectRatioOrNull())
        assertNull(synthetic().copy(orientationDegrees = Int.MAX_VALUE).displayAspectRatioOrNull())
    }

    @Test fun orientationConversionDoesNotWrapCorruptProviderLongIntoValidDegrees() {
        for (valid in listOf(0L, 90L, 180L, 270L, -90L, 450L,
            Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong())) {
            assertEquals(valid.toInt(), mediaOrientationDegreesOrInvalid(valid))
        }
        val wrappedAsLandscape = (1L shl 32) // toInt() used to become 0
        val wrappedAsPortrait = wrappedAsLandscape + 90L
        val wrappedAsInverse = Long.MIN_VALUE // toInt() used to become 0
        for (invalid in listOf(wrappedAsLandscape, wrappedAsPortrait, wrappedAsInverse,
            Long.MAX_VALUE, Int.MAX_VALUE.toLong() + 1,
            Int.MIN_VALUE.toLong() - 1)) {
            val degrees = mediaOrientationDegreesOrInvalid(invalid)
            assertEquals(Int.MIN_VALUE, degrees)
            // #504: no usable rotation = unrotated stored pixels, never a fake quarter turn.
            assertEquals(4f / 3f, synthetic().copy(orientationDegrees = degrees)
                .displayAspectRatioOrNull()!!, 0.0001f)
        }
    }

    @Test fun missingOrientationMeansUnrotatedForRealRatioRows() {
        // Missing and SQL-NULL columns both arrive as present=false from the
        // repository cursor. The record keeps the unknown sentinel, but the
        // ratio grid reads it as "no rotation recorded" (PNGs, screenshots,
        // EXIF-less JPEGs), so real libraries get real ratios (#504).
        for (reported in listOf(0L, 90L, 180L, 270L)) {
            val unknown = mediaOrientationDegreesOrUnknown(reported, present = false)
            assertEquals(Int.MIN_VALUE, unknown)
            assertEquals(4f / 3f, synthetic().copy(orientationDegrees = unknown)
                .displayAspectRatioOrNull()!!, 0.0001f)
            assertEquals(9f / 16f, synthetic().copy(orientationDegrees = unknown,
                widthPixels = 1080, heightPixels = 1920).displayAspectRatioOrNull()!!, 0.0001f)
        }
        assertEquals(0, mediaOrientationDegreesOrUnknown(0L, present = true))
        assertEquals(90, mediaOrientationDegreesOrUnknown(90L, present = true))
        assertEquals(Int.MIN_VALUE,
            mediaOrientationDegreesOrUnknown(Long.MAX_VALUE, present = true))
    }

    @Test fun extremeProviderDimensionsAreUnknownNotGiantSyntheticPhotos() {
        for (valid in listOf(1L, 3000L, 12000L, Int.MAX_VALUE.toLong()))
            assertEquals(valid.toInt(), mediaPixelDimensionOrUnknown(valid))
        for (invalid in listOf(Long.MIN_VALUE, -1L, 0L,
                Int.MAX_VALUE.toLong() + 1L, 1L shl 32, Long.MAX_VALUE)) {
            assertEquals(0, mediaPixelDimensionOrUnknown(invalid))
            val media = synthetic().copy(
                widthPixels = mediaPixelDimensionOrUnknown(invalid),
                heightPixels = 3000)
            assertNull(media.displayAspectRatioOrNull())
        }
        // A valid orientation and size still preserves the true aspect ratio.
        assertEquals(0.75f, synthetic().copy(
            widthPixels = mediaPixelDimensionOrUnknown(4000L),
            heightPixels = mediaPixelDimensionOrUnknown(3000L)
        ).displayAspectRatioOrNull()!!, 0.0001f)
    }

    @Test fun keyChangesForSameSecondEditsProviderResetAndGrantEpoch() {
        val photo = synthetic()
        val base = photo.presentationCacheKeyOrNull(400, 18L)!!
        for (changed in listOf(
            photo.copy(generationModified = 501L),
            photo.copy(providerVersion = "version-B"),
            photo.copy(sizeBytes = 2_001L),
            photo.copy(widthPixels = photo.widthPixels + 1),
            photo.copy(heightPixels = photo.heightPixels + 1),
            photo.copy(orientationDegrees = 180),
            photo.copy(modifiedAtSeconds = photo.modifiedAtSeconds + 1L),
            photo.copy(volumeName = "0123-ABCD"),
            photo.copy(isVideo = true)
        )) assertNotEquals(base, changed.presentationCacheKeyOrNull(400, 18L))
        assertNotEquals(base, photo.presentationCacheKeyOrNull(400, 19L))
        assertNotEquals(base, photo.presentationCacheKeyOrNull(400, 18L, 1L))
        assertNotEquals(base, photo.presentationCacheKeyOrNull(800, 18L))
        assertNull(photo.copy(providerVersion = null).presentationCacheKeyOrNull(400, 18L))
        assertNull(photo.copy(generationModified = 0L).presentationCacheKeyOrNull(400, 18L))
        assertNull(photo.presentationCacheKeyOrNull(0, 18L))
    }

    @Test fun inventoryRequiresSameProviderVersionsAndGenerationsAcrossScan() {
        val beforeVersions = mapOf("external_primary" to "v1", "ABCD-1234" to "sd-v1")
        val beforeGeneration: Map<String, Long?> =
            mapOf("external_primary" to 401L, "ABCD-1234" to 99L)
        assertTrue(sameMediaProviderScanSnapshot(
            beforeVersions, beforeVersions.toMap(),
            beforeGeneration, beforeGeneration.toMap()))

        // File edits do not necessarily change the provider VERSION.
        assertFalse(sameMediaProviderScanSnapshot(
            beforeVersions, beforeVersions, beforeGeneration,
            beforeGeneration + ("external_primary" to 402L)))

        // Database recreation/reset may change the version and reset generation.
        assertFalse(sameMediaProviderScanSnapshot(
            beforeVersions, beforeVersions + ("external_primary" to "v2"),
            beforeGeneration, beforeGeneration))
        assertFalse(sameMediaProviderScanSnapshot(
            beforeVersions, beforeVersions - "ABCD-1234",
            beforeGeneration, beforeGeneration - "ABCD-1234"))
        assertFalse(sameMediaProviderScanSnapshot(
            beforeVersions, beforeVersions + ("EEEE-8888" to "new"),
            beforeGeneration, beforeGeneration))
        assertFalse(sameMediaProviderScanSnapshot(
            beforeVersions, beforeVersions,
            beforeGeneration, beforeGeneration - "ABCD-1234"))

        // On API29 getGeneration is unavailable: versions remain meaningful.
        assertTrue(sameMediaProviderScanSnapshot(
            beforeVersions, beforeVersions, emptyMap(), emptyMap()))
        assertFalse(sameMediaProviderScanSnapshot(
            emptyMap(), emptyMap(), emptyMap(), emptyMap()))
        // A reported generation becomes unavailable during the scan.
        assertFalse(sameMediaProviderScanSnapshot(
            beforeVersions, beforeVersions, beforeGeneration,
            beforeGeneration + ("external_primary" to null)))
    }

    @Test fun unavailableVersionsAndGenerationsCannotPretendToBeStable() {
        val primaryVersions: Map<String, String?> = mapOf("external_primary" to "v1")
        val primaryGeneration: Map<String, Long?> = mapOf("external_primary" to 0L)
        assertTrue(sameMediaProviderScanSnapshot(
            primaryVersions, primaryVersions, primaryGeneration, primaryGeneration))
        // API 29 really cannot read generations; don't reject that platform.
        assertTrue(sameMediaProviderScanSnapshot(
            primaryVersions, primaryVersions, emptyMap(), emptyMap()))
        for (unavailableVersion in listOf<String?>(null, "", "   ")) {
            val bad = mapOf("external_primary" to unavailableVersion)
            assertFalse("Unknown version must not equal a known one",
                sameMediaProviderScanSnapshot(bad, bad, primaryGeneration, primaryGeneration))
            assertFalse("Version-only API29 snapshot must still have version",
                sameMediaProviderScanSnapshot(bad, bad, emptyMap(), emptyMap()))
        }
        for (badGeneration in listOf<Long?>(null, -1L, Long.MIN_VALUE)) {
            val bad = mapOf("external_primary" to badGeneration)
            assertFalse("Missing or invalid generation is not proof of equality",
                sameMediaProviderScanSnapshot(primaryVersions, primaryVersions, bad, bad))
        }
        // A nonempty but partial generation record is not API29 fallback.
        val versions = primaryVersions + ("ABCD-1234" to "v2")
        assertFalse(sameMediaProviderScanSnapshot(
            versions, versions, primaryGeneration, primaryGeneration))
    }

    @Test fun decodedCacheReuseRequiresExactlySameGenerationVersionAndGrant() {
        assertTrue(canReuseMediaGeneration("A", "A", 100L, 100L, true))
        // Increment means content may have changed even if name/mtime did not.
        assertFalse(canReuseMediaGeneration("A", "A", 100L, 101L, true))
        assertFalse(canReuseMediaGeneration("A", "A", 100L, 99L, true))
        assertFalse(canReuseMediaGeneration("A", "B", 100L, 100L, true))
        assertFalse(canReuseMediaGeneration("A", "A", 100L, 100L, false))
        assertFalse(canReuseMediaGeneration(null, null, 100L, 100L, true))
        assertFalse(canReuseMediaGeneration("", "", 100L, 100L, true))
        assertFalse(canReuseMediaGeneration("A", "A", 0L, 0L, true))
    }

    @Test fun fictional150kRowsHaveNoAllCatalogPresentationCacheAllocation() {
        val source = (0 until 150_000).map { i ->
            synthetic().copy(id = i.toLong(), uri = "content://media/external/images/media/" + i)
        }
        val keys = listOf(0, 149, 50_000, 149_999).map {
            source[it].presentationCacheKeyOrNull(400, 1L)
        }
        assertEquals(4, keys.filterNotNull().toSet().size)
        assertEquals(150_000, source.size)
    }

    /** #577: CI UI-evidence screenshots must date 2001 by name, behind any test fixture. */
    @Test fun uiEvidenceNameDatesTheScreenshotOldEvenWithoutDateTaken() {
        val now = 1_800_000_000L
        val date = resolveMediaDate(0L, "IMG_20010101_000000_pinch-494-after-ratio.png",
            modifiedSeconds = now, addedSeconds = now, zone = zone)
        assertEquals(MediaDateSource.FILENAME_TIMESTAMP, date.source)
        assertEquals(java.time.LocalDate.of(2001, 1, 1),
            java.time.Instant.ofEpochMilli(date.millis).atZone(zone).toLocalDate())
        val fixture = resolveMediaDate(0L, "IMG_HEADER_FIXTURE_3.png",
            modifiedSeconds = now, addedSeconds = now, zone = zone)
        assertTrue(fixture.millis > date.millis)
    }
}
