package nl.atlas.gallery

import java.io.ByteArrayInputStream
import nl.atlas.gallery.featurecuration.CurationVisualHash
import nl.atlas.gallery.featurecuration.hashCurationBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #33: large synthetic Photo DNA fixture, including an ambiguous duplicate URI.
 * It exercises both the byte planner and visual candidate path without touching
 * MediaStore or real photos. The reported S24+ crash still needs a device trace.
 */
class PhotoDnaCrashScaleTest {
    private fun photo(id: Long) = MediaRecord(
        id = id,
        uri = "content://media/external/images/media/$id",
        name = "synthetic-$id.jpg",
        relativePath = "DCIM/Test/",
        sizeBytes = 64L,
        takenAtMillis = id * 1_000L,
        isVideo = false,
        modifiedAtSeconds = 1_700_000_000L
    )

    @Test fun duplicateCatalogUriIsExcludedBeforeBothPlannersAtTwoThousandScale() {
        val source = (1L..2_000L).map(::photo) + photo(777L)
        val tokens = PhotoDnaTokens("synthetic-epoch")
        val bytes = ByteArray(64) { 7 }
        val candidates = photoDnaCandidates(source)
        assertEquals(1_999, candidates.records.size)
        assertTrue(candidates.records.none { it.id == 777L })
        assertEquals(candidates.records.size,
            candidates.records.map { it.uri }.toSet().size)

        val result = planPhotoDna(source, tokens, hash = { record ->
            hashCurationBytes(record.sizeBytes,
                openAuthorized = { ByteArrayInputStream(bytes) },
                stillAuthorized = { true })
        })
        assertEquals(1_999, result.checkedFiles)
        assertEquals(1, result.plan.exactGroups.size)
        assertEquals(1_999, result.plan.exactGroups.single().members.size)

        val pass = planPhotoDnaVisual(
            source, tokens, result.plan, PhotoDnaVisualCache(),
            decode = { CurationVisualHash(it.id, informative = true) },
            stillAuthorized = { true },
            budget = PhotoDnaVisualBudget(maxDecodes = 2_000, maxPairs = 1,
                maxComparisons = 1_000L)
        )
        assertEquals(1_999, pass.decodedFiles)
        assertEquals(1_999, pass.hashedFiles)
        assertTrue(pass.evidence.none { it.leftUri == it.rightUri })
    }

    @Test fun providerThumbnailsMustBeSmallBeforeAnyPixelAllocation() {
        assertTrue(photoDnaThumbnailWithinBudget(96, 96))
        assertTrue(photoDnaThumbnailWithinBudget(256, 256))
        assertFalse(photoDnaThumbnailWithinBudget(8000, 6000))
        assertFalse(photoDnaThumbnailWithinBudget(257, 96))
        assertFalse(photoDnaThumbnailWithinBudget(96, 0))
    }
}
