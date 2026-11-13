package nl.atlas.gallery.featuregrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GridLazySaveableKeyTest {
    @Test fun authorized150kPhotoGridUsesOnlyBoundedNumericKeys() {
        // The key function has no media-string parameter by construction.
        // These are safe to save in Compose's LazyLayout state registry.
        for (index in 0 until 150_000) {
            assertEquals(index, gridLazySaveablePositionKey(index))
        }
    }

    @Test fun reorderedOrRegrantedLayoutMustRekeyParentNotSerializeUris() {
        val firstPrivateUri = "content://media/external/images/media/123"
        val differentPrivateUri = "content://media/external/images/media/987"
        // Caller is required to remount under source/publication/density keys;
        // a positional child key is intentionally independent of either URI.
        assertEquals(21, gridLazySaveablePositionKey(21))
        assertEquals(gridLazySaveablePositionKey(21), gridLazySaveablePositionKey(21))
        assertEquals(false, gridLazySaveablePositionKey(21).toString().contains(firstPrivateUri))
        assertEquals(false, gridLazySaveablePositionKey(21).toString().contains(differentPrivateUri))
    }

    @Test fun impossibleNegativePositionsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            gridLazySaveablePositionKey(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            gridLazySaveablePositionKey(Int.MIN_VALUE)
        }
        assertEquals(Int.MAX_VALUE, gridLazySaveablePositionKey(Int.MAX_VALUE))
    }
}
