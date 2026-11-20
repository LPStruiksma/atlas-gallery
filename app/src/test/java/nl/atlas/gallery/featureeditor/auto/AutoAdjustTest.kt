package nl.atlas.gallery.featureeditor.auto

import nl.atlas.gallery.featureeditor.engine.AdjustParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAdjustTest {
    @Test fun darkAndBrightHistogramsMoveExposureTowardMiddleGrey() {
        val dark = values(AutoAdjust.suggest(gradient(0, 100)))
        val bright = values(AutoAdjust.suggest(gradient(155, 255)))
        assertTrue(dark.getValue(AdjustParam.EXPOSURE) > 0.15)
        assertTrue(dark.getValue(AdjustParam.HIGHLIGHTS) > 0.1)
        assertTrue(bright.getValue(AdjustParam.EXPOSURE) < -0.15)
        assertTrue(bright.getValue(AdjustParam.SHADOWS) < -0.1)
    }

    @Test fun neutralFullGradientNeedsAlmostNoCorrection() {
        values(AutoAdjust.suggest(gradient(0, 255))).values.forEach { assertEquals(0.0, it, 0.025) }
    }

    @Test fun colourCastsReceiveOpposingTemperatureAndTint() {
        val cool = channelStats(red = 90, green = 128, blue = 180)
        val green = channelStats(red = 128, green = 180, blue = 128)
        assertTrue(values(AutoAdjust.suggest(cool)).getValue(AdjustParam.TEMPERATURE) > 0.2)
        assertTrue(values(AutoAdjust.suggest(green)).getValue(AdjustParam.TINT) < -0.2)
    }

    @Test fun operationsAreStableBoundedAndDeterministic() {
        val stats = channelStats(0, 255, 0)
        val first = AutoAdjust.suggest(stats)
        assertEquals(first, AutoAdjust.suggest(stats))
        assertEquals(listOf("auto-exposure", "auto-contrast", "auto-highlights", "auto-shadows", "auto-temperature", "auto-tint"), first.map { it.id })
        first.forEach { assertTrue(it.value.isFinite() && it.value in it.param.min..it.param.max) }
    }

    @Test fun statisticsCopyInputAndRejectInvalidData() {
        val bins = LongArray(256).also { it[127] = 4 }
        val stats = ToneStatistics(bins, bins, bins)
        bins[127] = 0
        assertEquals(4, stats.red[127])
        assertFails { ToneStatistics(LongArray(255), LongArray(256), LongArray(256)) }
        assertFails { ToneStatistics(LongArray(256), LongArray(256), LongArray(256)) }
        assertFails { ToneStatistics(LongArray(256).also { it[0] = -1 }, LongArray(256), LongArray(256)) }
        assertFails { ToneStatistics(LongArray(256).also { it[0] = 1 }, LongArray(256).also { it[0] = 2 }, LongArray(256).also { it[0] = 1 }) }
        assertFails { ToneStatistics.fromRgb(ByteArray(5), 1, 2) }
    }

    private fun gradient(min: Int, max: Int): ToneStatistics {
        val bins = LongArray(256)
        for (value in min..max) bins[value] = 10
        return ToneStatistics(bins, bins, bins)
    }

    private fun channelStats(red: Int, green: Int, blue: Int): ToneStatistics {
        fun at(value: Int) = LongArray(256).also { it[value] = 1_000 }
        return ToneStatistics(at(red), at(green), at(blue))
    }

    private fun values(operations: List<nl.atlas.gallery.featureeditor.engine.EditOperation.Adjust>) =
        operations.associate { it.param to it.value }

    private fun assertFails(block: () -> Unit) {
        try { block(); throw AssertionError("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }
}
