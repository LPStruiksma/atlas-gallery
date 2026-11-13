package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Synthetic lifecycle tests with no Android/MediaStore or user data. */
class ThumbnailBindingPolicyTest {
    private val still = MediaIdentity("content://media/external/images/media/7", "external_primary", false, 7L)
    private val movie = MediaIdentity("content://media/external/video/media/7", "external_primary", true, 7L)

    @Test fun gesturesNeverScheduleColdDecodesButRetainExistingPreview() {
        assertEquals(ThumbnailStage.MEMORY_OR_PLACEHOLDER, chooseThumbnailStage(true, true, false, false))
        assertEquals(ThumbnailStage.MEMORY_OR_PLACEHOLDER, chooseThumbnailStage(true, true, true, false))
        assertEquals(ThumbnailStage.MEMORY_OR_PLACEHOLDER, chooseThumbnailStage(false, false, true, false))
        assertEquals(ThumbnailStage.VISIBLE_DECODE, chooseThumbnailStage(false, true, false, false))
        assertEquals(ThumbnailStage.VISIBLE_SHARPEN, chooseThumbnailStage(false, true, true, false))
        assertEquals(ThumbnailStage.MEMORY_OR_PLACEHOLDER, chooseThumbnailStage(false, true, true, true))
    }

    @Test fun staleCellsPermissionsAndProviderEpochNeverReceiveOldBitmap() {
        val original = ThumbnailBindingTicket(11L, still, 90L, 1L)
        assertTrue(mayPublishThumbnailResult(original, original, true))
        assertFalse(mayPublishThumbnailResult(original, null, true))
        assertFalse(mayPublishThumbnailResult(original, original, false))
        assertFalse(mayPublishThumbnailResult(original, original.copy(identity = movie), true))
        assertFalse(mayPublishThumbnailResult(original, original.copy(slot = 12L), true))
        assertFalse(mayPublishThumbnailResult(original, original.copy(catalogueEpoch = 91L), true))
        assertFalse(mayPublishThumbnailResult(original, original.copy(sequence = 2L), true))
    }
}
