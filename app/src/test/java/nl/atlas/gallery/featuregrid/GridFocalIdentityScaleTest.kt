package nl.atlas.gallery.featuregrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scale is represented by a count; a valid implementation never traverses that count. */
class GridFocalIdentityScaleTest {
    @Test fun invalidCandidatesNeverReadTheCatalogue() {
        for (size in listOf(0, 1, 250_000)) {
            for (index in listOf(-1, 0, 1, size - 1, size, size + 1)) {
                for (key in listOf("", " ", "\t\n", "media:$index")) {
                    val admissible = size > 0 && index in 0 until size && key.isNotBlank()
                    if (admissible) continue
                    var calls = 0
                    val result = currentFocalIndexOrNull(index, key, size) {
                        calls++
                        error("Invalid candidate must not invoke callback")
                    }
                    assertNull("size=$size index=$index key=$key", result)
                    assertEquals(0, calls)
                }
            }
        }
    }

    @Test fun validBoundariesUseExactlyOneLookup() {
        for (size in listOf(1, 250_000)) {
            for (index in listOf(0, size - 1)) {
                var calls = 0
                val result = currentFocalIndexOrNull(index, "media:$index", size) { requested ->
                    calls++
                    assertEquals(index, requested)
                    "media:$requested"
                }
                assertEquals(index, result)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun staleOrReorderedKeyCancelsWithoutSearchingForItsNewPosition() {
        val size = 250_000
        val oldIndex = 42
        for (observed in listOf<String?>(null, "media:43", "media:249999")) {
            var calls = 0
            assertNull(currentFocalIndexOrNull(oldIndex, "media:42", size) { requested ->
                calls++
                assertEquals(oldIndex, requested)
                observed
            })
            assertEquals(1, calls)
        }
    }

    @Test fun matchingKeyPreservesCapturedIndexAtScale() {
        val size = 250_000
        val index = size - 1
        var calls = 0
        assertEquals(index, currentFocalIndexOrNull(index, "same", size) {
            calls++
            "same"
        })
        assertEquals(1, calls)
    }
}
