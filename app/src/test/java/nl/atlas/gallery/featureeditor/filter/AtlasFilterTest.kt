package nl.atlas.gallery.featureeditor.filter

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AtlasFilterTest {
    @Test fun identityAndZeroStrengthAreExact() {
        val lut = AtlasLut.identity(3)
        assertArrayEquals(doubleArrayOf(.17, .42, .91), lut.sample(.17, .42, .91), 1e-12)
        val black = AtlasLut(2, DoubleArray(24))
        assertArrayEquals(doubleArrayOf(.2, .4, .6), black.sample(.2, .4, .6, 0.0), 0.0)
        assertArrayEquals(doubleArrayOf(.1, .2, .3), black.sample(.2, .4, .6, .5), 1e-12)
    }

    @Test fun trilinearInterpolationUsesAllEightCorners() {
        val samples = DoubleArray(24)
        for (corner in 0..7) for (channel in 0..2) samples[corner * 3 + channel] = corner.toDouble()
        assertArrayEquals(doubleArrayOf(3.5, 3.5, 3.5), AtlasLut(2, samples).sample(.5, .5, .5), 1e-12)
    }

    @Test fun catalogueHasFourteenStableLooks() {
        assertEquals(14, AtlasFilterCatalogue.looks.size)
        assertEquals(14, AtlasFilterCatalogue.looks.map { it.id }.toSet().size)
        assertEquals("Amber Hour", AtlasFilterCatalogue.find("atlas-amber")?.displayName)
    }

    @Test fun syntheticLabTransferMovesPaletteTowardReference() {
        val sourcePixels = listOf(doubleArrayOf(.2,.25,.3), doubleArrayOf(.5,.55,.6), doubleArrayOf(.8,.75,.7))
        val referencePixels = listOf(doubleArrayOf(.35,.2,.15), doubleArrayOf(.65,.45,.3), doubleArrayOf(.9,.7,.5))
        val source = LabColourTransfer.statistics(sourcePixels); val reference = LabColourTransfer.statistics(referencePixels)
        val moved = LabColourTransfer.transfer(sourcePixels[1], source, reference)
        assertTrue(moved[0] > moved[2])
        assertTrue(moved.all { it in 0.0..1.0 })
    }

    @Test fun storeDeduplicatesAndRejectsCorruption() {
        val root = Files.createTempDirectory("atlas-filter-test").toFile()
        try {
            val store = CustomFilterStore(root); val bytes = "synthetic cube".toByteArray()
            val first = store.save(bytes); val second = store.save(bytes)
            assertEquals(first.sha256, second.sha256); assertEquals(1, root.listFiles()!!.size)
            assertArrayEquals(bytes, store.read(first.sha256))
            first.file.appendText("corrupt")
            assertNull(store.read(first.sha256))
            assertNull(store.read("../escape"))
        } finally { root.deleteRecursively() }
    }
}
