package nl.atlas.gallery.featurevisual.nls.assets

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import nl.atlas.gallery.featurevisual.onnx.SiglipModelFile
import nl.atlas.gallery.featurevisual.onnx.SiglipModelIntegrityFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * NLS-1A (#216): the committed SigLIP 2 text-tower parts are exactly the NLS-0 `int8_gather`
 * graph. Each part matches its pin and stays below 50 MB, and the ordered join, streamed through
 * the app's bounded [SiglipModelFile.materialize], is byte-for-byte that graph.
 */
class SiglipTextAssetsTest {
    private val tmp: File = Files.createTempDirectory("siglip-text").toFile()

    @After
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    private val dir: File = listOf(File("src/main/assets/$ASSET_DIR"), File("app/src/main/assets/$ASSET_DIR"))
        .firstOrNull { it.isDirectory } ?: error("asset directory $ASSET_DIR not found")

    private fun part(i: Int) = File(dir, "$FILE_NAME.part$i")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Records the largest single read the joiner asks for, to prove it streams. */
    private class Probe(input: InputStream, val maxRead: IntArray) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len > maxRead[0]) maxRead[0] = len
            return super.read(b, off, len)
        }
    }

    @Test
    fun partsAreNumberedContiguouslyAndBelow50Mb() {
        val onDisk = dir.listFiles { f -> f.name.startsWith("$FILE_NAME.part") }!!.map { it.name }.sorted()
        assertEquals(PARTS.indices.map { "$FILE_NAME.part$it" }.sorted(), onDisk)
        PARTS.forEachIndexed { i, (bytes, _) ->
            assertEquals("part$i size", bytes, part(i).length())
            assertTrue("part$i must be below 50 MB", part(i).length() < 50_000_000L)
        }
        assertEquals(SIZE_BYTES, PARTS.sumOf { it.first })
    }

    @Test
    fun eachPartMatchesItsPin() {
        PARTS.forEachIndexed { i, (_, sha) -> assertEquals("part$i sha256", sha, sha256(part(i))) }
    }

    @Test
    fun manifestCarriesTheSamePins() {
        val manifest = File(dir, "manifest.json").readText()
        assertTrue(manifest.contains("\"sha256\": \"$SHA256\""))
        assertTrue(manifest.contains("\"bytes\": $SIZE_BYTES"))
        assertTrue(manifest.contains("\"sha256\": \"$CHECKPOINT_SHA256\""))
        PARTS.forEach { (bytes, sha) ->
            assertTrue(manifest.contains("\"bytes\": $bytes,\n      \"sha256\": \"$sha\""))
        }
        assertTrue(File(dir, "LICENSE").readText().contains("Apache License"))
    }

    @Test
    fun orderedJoinIsTheNls0GraphAndStreamsThroughABoundedBuffer() {
        val maxRead = IntArray(1)
        val joined = SiglipModelFile.materialize(
            tmp,
            PARTS.indices.map { i -> { Probe(part(i).inputStream(), maxRead) } },
            sha256 = SHA256,
            sizeBytes = SIZE_BYTES,
            fileName = FILE_NAME,
        )
        assertEquals(SIZE_BYTES, joined.length())
        assertEquals(SHA256, sha256(joined))
        assertTrue("joiner read ${maxRead[0]} bytes at once", maxRead[0] in 1..(1 shl 20))
    }

    @Test
    fun swappedPartsFailClosed() {
        val order = PARTS.indices.toMutableList().also { it[0] = 1; it[1] = 0 }
        try {
            SiglipModelFile.materialize(
                tmp,
                order.map { i -> { part(i).inputStream() } },
                sha256 = SHA256,
                sizeBytes = SIZE_BYTES,
                fileName = FILE_NAME,
            )
            fail("swapped parts must not verify")
        } catch (_: SiglipModelIntegrityFailure) {
        }
        assertFalse(File(tmp, FILE_NAME).exists())
        assertFalse(File(tmp, "$FILE_NAME.tmp").exists())
    }

    private companion object {
        const val ASSET_DIR = "models/siglip2_text"
        const val FILE_NAME = "siglip2_b16_224_text_int8.onnx"

        /** NLS-0 `int8_gather` (docs/visual-search/results/nls0_text_export_20261114.json). */
        const val SHA256 = "6fd97706a61981eb1a8d2444777209f0768162d3172c74a73134fbc1f78c0da6"
        const val SIZE_BYTES = 290_153_185L

        /** The pinned big_vision checkpoint, the same one the shipped image tower comes from. */
        const val CHECKPOINT_SHA256 = "74abfd2488a01f78c1cce5a38861dc929ea42aeabe8f622eaaa60ddfffeaad4e"

        val PARTS = listOf(
            48_000_000L to "ef9509fbe1722c01a3e7aef4e837cfa3ca02e591a6af8f4c826a7656aed5fa4f",
            48_000_000L to "549f64a949ea1ee4b3acf9177f504100a89028ffdd0e45cc1e70a9dfaf3dfc0c",
            48_000_000L to "132b3978c41226002abe413a33ba05d25ce551d74d7e7f947593bd27b892a72a",
            48_000_000L to "3701502fbfc98555170f0f31651ae2d8749b187115da49a78dc1342905342ed2",
            48_000_000L to "dd53c8b19b88290be1ac35364fc1e5914dedbf0f7156e928ddfe6eea58fe198f",
            48_000_000L to "bc20098961ba5f63b201e1543cb19bdc6b5efbfcb74fbe3cad575f254c0f3a82",
            2_153_185L to "a5530a5c7eb67c4ebccf8b32677ad27197e8e6acbe1885787e234d7b7619428e",
        )
    }
}
