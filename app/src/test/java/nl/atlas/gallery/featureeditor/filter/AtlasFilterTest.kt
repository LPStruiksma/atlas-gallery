package nl.atlas.gallery.featureeditor.filter

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AtlasFilterTest {
    @Test fun identityAndZeroStrengthAreExact() {
        val lut = AtlasLut.identity(3)
        assertArrayEquals(doubleArrayOf(.17, .42, .91), lut.sample(.17, .42, .91), 1e-12)
        val black = AtlasLut(2, DoubleArray(24))
        assertArrayEquals(doubleArrayOf(.2, .4, .6), black.sample(.2, .4, .6, 0.0), 0.0)
        assertArrayEquals(doubleArrayOf(.1, .2, .3), black.samplePercent(.2, .4, .6, 50), 1e-12)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), black.samplePercent(.2, .4, .6, 100), 1e-12)
    }

    @Test fun trilinearInterpolationUsesAllEightCorners() {
        val samples = DoubleArray(24)
        for (corner in 0..7) for (channel in 0..2) samples[corner * 3 + channel] = corner.toDouble()
        assertArrayEquals(doubleArrayOf(3.5, 3.5, 3.5), AtlasLut(2, samples).sample(.5, .5, .5), 1e-12)
    }

    @Test fun bundledCatalogueIsCompleteAndTamperEvident() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull(File::isDirectory) ?: error("packaged asset directory is missing")
        assertEquals(14, AtlasFilterCatalogue.looks.size)
        assertEquals(14, AtlasFilterCatalogue.looks.map { it.id }.toSet().size)
        for (look in AtlasFilterCatalogue.looks) {
            assertTrue(look.previewStrength in 0.0..0.68)
            val lut = requireNotNull(AtlasFilterCatalogue.load(look.id) {
                File(assets, it).inputStream()
            })
            assertEquals(33, lut.size)
            val pixel = lut.sample(.6, .45, .38, look.previewStrength)
            assertTrue(pixel.all { it in 0.0..1.0 })
        }
        val clear = requireNotNull(AtlasFilterCatalogue.find("atlas-clear"))
        val bytes = File(assets, "editor/luts/atlas-clear.lut").readBytes()
        bytes[10] = (bytes[10].toInt() xor 1).toByte()
        try {
            AtlasFilterCatalogue.load(clear.id) { ByteArrayInputStream(bytes) }
            fail("altered LUT should be rejected")
        } catch (_: IllegalArgumentException) {
            // The digest is bound to the source catalogue, not merely a file extension.
        }
        assertNull(AtlasFilterCatalogue.load("not-a-look") { error("should not open") })
    }

    @Test fun syntheticLabTransferDerivesLut() {
        val sourcePixels = listOf(doubleArrayOf(.2,.25,.3), doubleArrayOf(.5,.55,.6), doubleArrayOf(.8,.75,.7))
        val referencePixels = listOf(doubleArrayOf(.35,.2,.15), doubleArrayOf(.65,.45,.3), doubleArrayOf(.9,.7,.5))
        val source = LabColourTransfer.statistics(sourcePixels)
        val reference = LabColourTransfer.statistics(referencePixels)
        val moved = LabColourTransfer.transfer(sourcePixels[1], source, reference)
        assertTrue(moved[0] > moved[2])
        assertTrue(moved.all { it in 0.0..1.0 })
        val bytes = FilterFromPhoto.derive(source, reference, 3)
        val lut = AtlasLut.fromBytes(bytes)
        assertEquals(3, lut.size)
        assertTrue(lut.sample(.5,.5,.5).all { it in 0.0..1.0 })
        bytes[0] = 0
        try {
            AtlasLut.fromBytes(bytes)
            fail("invalid magic should be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun namedPrivateStoreDeduplicatesAndRejectsCorruption() {
        val root = Files.createTempDirectory("atlas-filter-test").toFile()
        try {
            val store = CustomFilterStore(root)
            val bytes = "synthetic cube".toByteArray()
            val first = store.save(bytes)
            val second = store.save(bytes)
            assertEquals(first.sha256, second.sha256)
            assertEquals(1, root.listFiles()!!.size)
            assertArrayEquals(bytes, store.read(first.sha256))
            val named = store.save(bytes, "Evening custom")
            assertEquals(first.sha256, named.sha256)
            assertEquals("Evening custom", store.readName(named.sha256))
            first.file.appendText("corrupt")
            assertNull(store.read(first.sha256))
            assertNull(store.read("../escape"))
            try {
                store.save(bytes, "invalid\nname")
                fail("control characters must be rejected")
            } catch (_: IllegalArgumentException) {}
        } finally {
            root.deleteRecursively()
        }
    }
}
