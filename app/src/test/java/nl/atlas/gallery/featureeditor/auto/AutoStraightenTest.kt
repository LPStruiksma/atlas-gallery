package nl.atlas.gallery.featureeditor.auto

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStraightenTest {
    @Test fun rotatedHorizontalGridIsRecoveredWithinHalfADegree() {
        for (angle in listOf(-12.0, -4.25, 3.5, 14.0)) {
            val suggestion = AutoStraighten.suggest(grid(320, 240, angle, horizontal = true), 320, 240)
            requireNotNull(suggestion)
            assertEquals(-angle, suggestion.degrees, 0.5)
            assertTrue(suggestion.confidence > 0.12)
            assertEquals(suggestion.degrees, suggestion.asOperation().degrees, 0.0)
        }
    }

    @Test fun rotatedVerticalGridAndSingleHorizonAreRecovered() {
        val vertical = AutoStraighten.suggest(grid(320, 240, -7.75, horizontal = false), 320, 240)
        assertEquals(7.75, requireNotNull(vertical).degrees, 0.5)
        val horizon = AutoStraighten.suggest(grid(320, 240, 6.0, horizontal = true, spacing = 10_000), 320, 240)
        assertEquals(-6.0, requireNotNull(horizon).degrees, 0.5)
    }

    @Test fun textureAndFlatPlanesAreAmbiguous() {
        var state = 123456789
        val noise = ByteArray(256 * 192) {
            state = state * 1103515245 + 12345
            (state ushr 16).toByte()
        }
        assertNull(AutoStraighten.suggest(noise, 256, 192))
        assertNull(AutoStraighten.suggest(ByteArray(256 * 192) { 127 }, 256, 192))
    }

    @Test fun invalidInputsAndOperationIdsAreRejected() {
        assertFails { AutoStraighten.suggest(ByteArray(8), 2, 4) }
        assertFails { AutoStraighten.suggest(ByteArray(100), 10, 11) }
        assertFails { StraightenSuggestion(0.0, 0.5).asOperation("") }
        assertFails { StraightenSuggestion(Double.NaN, 0.5) }
    }

    @Test fun resultIsDeterministic() {
        val image = grid(512, 384, 2.75, horizontal = true)
        assertEquals(AutoStraighten.suggest(image, 512, 384), AutoStraighten.suggest(image, 512, 384))
    }


    @Test fun denseEdgeVotesRespectEightThousandPointCap() {
        for (edgeCount in listOf(0, 1, 7_999, 8_000, 8_001, 15_999, 16_000, 16_001, 50_000, 512 * 384)) {
            val stride = AutoStraighten.voteSampleStride(edgeCount)
            val sampled = if (edgeCount == 0) 0 else (edgeCount - 1) / stride + 1
            assertTrue("sample count $sampled exceeds cap for $edgeCount edges", sampled <= 8_000)
        }
        val dense = ByteArray(512 * 384) { index ->
            if ((index % 512 / 2) % 2 == 0) 0.toByte() else 255.toByte()
        }
        assertEquals(
            AutoStraighten.suggest(dense, 512, 384),
            AutoStraighten.suggest(dense, 512, 384),
        )
    }

    private fun grid(width: Int, height: Int, angle: Double, horizontal: Boolean, spacing: Int = 32): ByteArray {
        val radians = angle * PI / 180.0
        val c = cos(radians); val s = sin(radians)
        return ByteArray(width * height) { index ->
            val x = index % width - width / 2.0
            val y = index / width - height / 2.0
            val coordinate = if (horizontal) -s * x + c * y else c * x + s * y
            val distance = if (spacing > height + width) abs(coordinate) else abs(coordinate - spacing * kotlin.math.round(coordinate / spacing))
            if (distance < 1.2) 235.toByte() else 20.toByte()
        }
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); throw AssertionError("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }
}
