package nl.atlas.gallery.featureidentity.curate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceCuratorTest {
    private fun face(id: String, yaw: Float = 0f, similarity: Float = .8f, quality: Float? = .7f,
                     lighting: Float? = .5f, time: Long? = null) = ReferenceCandidate(
        id, time, similarity,
        listOf(0f to 0f, 1f to 0f, (.5f + yaw) to .5f, .2f to 1f, .8f to 1f),
        quality, false, lighting)

    @Test fun emptyAndFewerThanLimitAreReturnedSafely() {
        assertTrue(ReferenceCurator.curate(emptyList(), ReferenceCurationOptions(3)).selected.isEmpty())
        assertEquals(2, ReferenceCurator.curate(listOf(face("a"), face("b")), ReferenceCurationOptions(5)).selected.size)
    }

    @Test fun rejectsIdentityOutliersAndReportsWhy() {
        val result = ReferenceCurator.curate(listOf(face("good"), face("out", similarity = .2f)), ReferenceCurationOptions(4, .5f))
        assertEquals(listOf("good"), result.selected.map { it.id })
        assertEquals(RejectedReference("out", ReferenceRejection.BELOW_SIMILARITY_FLOOR), result.outliers.single())
    }

    @Test fun qualityWinsFirstSlotButDiversityCanWinNext() {
        val result = ReferenceCurator.curate(listOf(
            face("best", quality = 1f, lighting = .5f),
            face("duplicate", quality = .8f, lighting = .5f),
            face("diverse", yaw = .45f, quality = .8f, lighting = .1f)
        ), ReferenceCurationOptions(2))
        assertEquals(listOf("best", "diverse"), result.selected.map { it.id })
    }

    @Test fun selectionIsDeterministicAcrossInputOrder() {
        val input = listOf(face("c"), face("a"), face("b"))
        val options = ReferenceCurationOptions(3)
        assertEquals(
            ReferenceCurator.curate(input, options).selected.map { it.id },
            ReferenceCurator.curate(input.reversed(), options).selected.map { it.id })
    }

    @Test fun dateWindowAndRecencyAreExplicit() {
        val result = ReferenceCurator.curate(listOf(face("old", time = 10), face("new", time = 90), face("outside", time = 101)),
            ReferenceCurationOptions(2, capturedFromMillis = 0, capturedUntilMillis = 100, referenceTimeMillis = 100, recencyHalfLifeMillis = 20))
        assertEquals("new", result.selected.first().id)
        assertEquals(ReferenceRejection.OUTSIDE_DATE_WINDOW, result.outliers.single().reason)
    }
}
