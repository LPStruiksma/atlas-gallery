package nl.atlas.gallery.featurevisual.onnx

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Joining the model's asset parts: order, whole-file pin, fail-closed deletion, re-verification. */
class SiglipModelFileTest {
    private val whole = ByteArray(250_000) { ((it * 31) xor (it shr 7)).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(whole).joinToString("") { "%02x".format(it) }
    private val dir: File = Files.createTempDirectory("siglip").toFile()

    private fun parts(bytes: ByteArray, cut: Int): List<() -> java.io.InputStream> = listOf(
        { ByteArrayInputStream(bytes.copyOfRange(0, cut)) },
        { ByteArrayInputStream(bytes.copyOfRange(cut, bytes.size)) },
    )

    private fun join(p: List<() -> java.io.InputStream>) =
        SiglipModelFile.materialize(dir, p, sha256 = sha, sizeBytes = whole.size.toLong(), fileName = "m.onnx")

    @Test
    fun joinsPartsInOrderAndVerifies() {
        val file = join(parts(whole, 100_000))
        assertArrayEquals(whole, file.readBytes())
        assertFalse(File(dir, "m.onnx.tmp").exists())
    }

    @Test
    fun reusesAVerifiedFileWithoutReadingParts() {
        join(parts(whole, 100_000))
        val file = join(listOf({ throw AssertionError("parts must not be read again") }))
        assertArrayEquals(whole, file.readBytes())
    }

    @Test
    fun swappedPartsFailClosedAndLeaveNoFile() {
        val swapped = parts(whole, 100_000).reversed()
        try { join(swapped); fail("expected integrity failure") } catch (_: SiglipModelIntegrityFailure) {}
        assertFalse(File(dir, "m.onnx").exists())
        assertFalse(File(dir, "m.onnx.tmp").exists())
    }

    @Test
    fun truncatedAndOversizedPartsFailClosed() {
        try { join(listOf({ ByteArrayInputStream(whole.copyOf(1000)) })); fail() } catch (_: SiglipModelIntegrityFailure) {}
        try { join(listOf({ ByteArrayInputStream(whole + ByteArray(1)) })); fail() } catch (_: SiglipModelIntegrityFailure) {}
        assertFalse(File(dir, "m.onnx").exists())
    }

    @Test
    fun tamperedFileOnDiskIsReplacedFromParts() {
        val file = join(parts(whole, 100_000))
        file.writeBytes(whole.copyOf().also { it[5] = (it[5] + 1).toByte() })
        val again = join(parts(whole, 100_000))
        assertArrayEquals(whole, again.readBytes())
        assertEquals(sha, SiglipModelFile.sha256Hex(again))
    }

    @Test
    fun tamperedFileWithBadPartsEndsWithNoFile() {
        val file = join(parts(whole, 100_000))
        file.writeBytes(ByteArray(3))
        try { join(listOf({ ByteArrayInputStream(ByteArray(3)) })); fail() } catch (_: SiglipModelIntegrityFailure) {}
        assertTrue(!file.exists())
    }

    @Test
    fun sampleSizeKeepsBothSidesAtLeast896() {
        assertEquals(1, SiglipImageEmbedder.sampleSizeFor(1200, 900))
        assertEquals(2, SiglipImageEmbedder.sampleSizeFor(4000, 3000))
        assertEquals(4, SiglipImageEmbedder.sampleSizeFor(8160, 6120))
        assertEquals(1, SiglipImageEmbedder.sampleSizeFor(20000, 500))
    }

    @Test
    fun outputCheckRejectsWrongSizeNonFiniteAndUnnormalised() {
        val unit = FloatArray(768).also { it[0] = 1f }
        assertEquals(1f, SiglipImageEmbedder.requireUnit(unit)[0], 0f)
        for (bad in listOf(FloatArray(767).also { it[0] = 1f }, unit.copyOf().also { it[1] = Float.NaN }, FloatArray(768))) {
            try { SiglipImageEmbedder.requireUnit(bad); fail() } catch (_: IllegalArgumentException) {}
        }
    }
}
