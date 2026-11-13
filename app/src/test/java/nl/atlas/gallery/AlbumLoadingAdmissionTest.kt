package nl.atlas.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumLoadingAdmissionTest {
    @Test fun failureShowsRetryInsteadOfPermanentSpinner() {
        assertFalse(shouldWaitForAlbumProjection(true, 150_000, false, true))
    }

    @Test fun unfinishedValidCatalogueStillShowsProgress() {
        assertTrue(shouldWaitForAlbumProjection(true, 150_000, false, false))
    }

    @Test fun completedCatalogueDoesNotSpin() {
        assertFalse(shouldWaitForAlbumProjection(true, 150_000, true, false))
    }

    @Test fun hiddenAlbumsDoNotComputeOrDisplayLoadingStatus() {
        assertFalse(shouldWaitForAlbumProjection(false, 150_000, false, false))
    }

    @Test fun emptyAuthorizedCatalogueDoesNotWaitForever() {
        assertFalse(shouldWaitForAlbumProjection(true, 0, false, false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleNegativeInventoryMustNotBeSilentlyAdmitted() {
        shouldWaitForAlbumProjection(true, -1, false, false)
    }
}
