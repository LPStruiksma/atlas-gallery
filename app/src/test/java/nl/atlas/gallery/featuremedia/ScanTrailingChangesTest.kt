package nl.atlas.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #676: a MediaStore change during a full scan (a screenshot during the
 * cold-start scan after an app update) no longer throws the scan away under
 * a full grant; it follows as the generation delta. Fictional provider
 * versions, generations and permission flags only.
 */
class ScanTrailingChangesTest {
    private val full = GalleryMediaGrant(images = true, videos = true, selected = false)
    private val imagesOnly = GalleryMediaGrant(images = true, videos = false, selected = false)
    private val selected = GalleryMediaGrant(images = false, videos = false, selected = true)
    private val v = mapOf("external_primary" to "v1")
    private val g = mapOf<String, Long?>("external_primary" to 100L)

    private fun advanced(
        vEnd: Map<String, String?> = v, gStart: Map<String, Long?> = g,
        gEnd: Map<String, Long?> = mapOf("external_primary" to 101L),
        vStart: Map<String, String?> = v
    ) = mediaProviderOnlyAdvancedDuringScan(vStart, vEnd, gStart, gEnd)

    @Test fun aForwardGenerationMoveOnTheSameVolumesMayFollowAsDelta() {
        assertTrue(advanced())
        val two = mapOf("external_primary" to "v1", "1234-abcd" to "v9")
        assertTrue(mediaProviderOnlyAdvancedDuringScan(two, two,
            mapOf("external_primary" to 100L, "1234-abcd" to 5L),
            mapOf("external_primary" to 100L, "1234-abcd" to 6L)))
    }

    @Test fun anUnchangedScanIsCoherentNotTrailing() {
        assertFalse(advanced(gEnd = g))
        assertTrue(sameMediaProviderScanSnapshot(v, v, g, g))
    }

    @Test fun versionVolumeOrBackwardChangesStillFailClosed() {
        assertFalse("provider reset", advanced(vEnd = mapOf("external_primary" to "v2")))
        assertFalse("volume mounted", advanced(
            vEnd = v + ("1234-abcd" to "v9"),
            gEnd = mapOf("external_primary" to 101L, "1234-abcd" to 1L)))
        assertFalse("generation went back", advanced(gEnd = mapOf("external_primary" to 99L)))
        assertFalse("generation unreadable", advanced(gEnd = mapOf("external_primary" to null)))
        assertFalse("invalid start", advanced(gStart = mapOf("external_primary" to null)))
        assertFalse("API 29 has no generations", advanced(gStart = emptyMap(), gEnd = emptyMap()))
        assertFalse("no version", advanced(vStart = mapOf("external_primary" to null),
            vEnd = mapOf("external_primary" to null)))
    }

    @Test fun onlyAFullGrantFollowsWithTheDelta() {
        assertTrue(galleryDeltaEligible(full))
        assertFalse(galleryDeltaEligible(imagesOnly))
        assertFalse(galleryDeltaEligible(selected))
    }

    @Test fun anObservedChangeDuringTheScanPublishesWhenItFollowsAsDelta() {
        assertTrue(mayPublishCompletedGalleryScan(true, false, full, full, 12L, 13L,
            changesFollowAsDelta = true))
        // Without a follow-up (partial grants) the old inventory is still refused.
        assertFalse(mayPublishCompletedGalleryScan(true, false, imagesOnly, imagesOnly, 12L, 13L))
        // A newer explicit request, a revoke or a grant change still wins.
        assertFalse(mayPublishCompletedGalleryScan(false, false, full, full, 12L, 13L, true))
        assertFalse(mayPublishCompletedGalleryScan(true, true, full, full, 12L, 13L, true))
        assertFalse(mayPublishCompletedGalleryScan(true, false, full, imagesOnly, 12L, 12L, true))
    }
}
