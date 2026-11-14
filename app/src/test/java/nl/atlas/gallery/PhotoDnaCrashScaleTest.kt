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

    @Test fun providerThumbnailsMustFitAPixelBudgetBeforeAnyPixelAllocation() {
        assertTrue(photoDnaThumbnailWithinBudget(96, 96))
        assertTrue(photoDnaThumbnailWithinBudget(256, 256))
        assertTrue(photoDnaThumbnailWithinBudget(800, 80))
        assertTrue(photoDnaThumbnailWithinBudget(257, 96))
        assertFalse(photoDnaThumbnailWithinBudget(800, 100))
        assertFalse(photoDnaThumbnailWithinBudget(8000, 6000))
        assertFalse(photoDnaThumbnailWithinBudget(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(photoDnaThumbnailWithinBudget(96, 0))
    }

    @Test fun panoramaSourceDimensionsYieldABoundedInformativeHash() {
        val sourceWidth = 8_000
        val sourceHeight = 1_000
        val requestedEdge = photoDnaThumbnailEdge(sourceWidth, sourceHeight)
        assertEquals(90, requestedEdge)
        val decodedWidth = sourceWidth * requestedEdge / sourceHeight
        val decodedHeight = requestedEdge
        assertEquals(720, decodedWidth)
        assertTrue(photoDnaThumbnailWithinBudget(decodedWidth, decodedHeight))
        // Invented horizontal gradient, scaled to the expected provider thumbnail.
        val pixels = IntArray(decodedWidth * decodedHeight) { position ->
            val gray = 10 + (position % decodedWidth) * 230 / decodedWidth
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val hash = nl.atlas.gallery.featurecuration.curationDHash(
            decodedWidth, decodedHeight, pixels)
        assertTrue(hash.informative)
        assertEquals(96, photoDnaThumbnailEdge(1000, 1000))
        assertEquals(28, photoDnaThumbnailEdge(8_000, 100))
        assertEquals(96, photoDnaThumbnailEdge(0, 0))
    }
}
