package nl.atlas.gallery.featurevisual.nls.tokenizer

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaTokenizerSetupTest {
    private val root = Files.createTempDirectory("gemma-tokenizer").toFile()

    @After fun cleanUp() { root.deleteRecursively() }

    @Test fun localStateAndLookupNeverOpenTheNetwork() {
        var opens = 0
        val setup = GemmaTokenizerSetup(root, connectionFactory = { opens++; error("network") })
        assertSame(TokenizerSetupState.Absent, setup.state())
        assertNull(setup.verifiedModel())
        assertEquals(0, opens)

        File(root, GemmaTokenizerSetup.MODEL_FILE_NAME).writeText("not the model")
        assertSame(TokenizerSetupState.Corrupt, setup.state())
        assertNull(setup.verifiedModel())
        assertEquals(0, opens)
    }

    @Test fun setupUsesOnlyThePinnedUrlAndDoesNotFollowRedirects() {
        var requested: URL? = null
        val connection = FakeConnection(302, ByteArray(0))
        val setup = GemmaTokenizerSetup(root, connectionFactory = { requested = it; connection })

        assertSame(TokenizerSetupState.NetworkUnavailable, setup.setup())
        assertEquals(GemmaTokenizerSetup.MODEL_URL, requested.toString())
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(File(root, GemmaTokenizerSetup.MODEL_FILE_NAME).exists())
        assertFalse(File(root, "${GemmaTokenizerSetup.MODEL_FILE_NAME}.part").exists())
    }

    @Test fun shortOrOversizedBodiesAreRejectedAndPartialsRemoved() {
        for (body in listOf(ByteArray(8), ByteArray(GemmaTokenizerSetup.MODEL_SIZE_BYTES.toInt() + 1))) {
            val setup = GemmaTokenizerSetup(root, connectionFactory = { FakeConnection(200, body, -1) })
            assertSame(TokenizerSetupState.ChecksumFailure, setup.setup())
            assertFalse(File(root, GemmaTokenizerSetup.MODEL_FILE_NAME).exists())
            assertFalse(File(root, "${GemmaTokenizerSetup.MODEL_FILE_NAME}.part").exists())
        }
    }

    @Test fun declaredWrongLengthIsRejectedBeforeReadingBody() {
        val connection = FakeConnection(200, ByteArray(0), 12)
        val setup = GemmaTokenizerSetup(root, connectionFactory = { connection })
        assertSame(TokenizerSetupState.ChecksumFailure, setup.setup())
        assertEquals(0, connection.bodyReads)
    }

    @Test fun callbackMakesTheExplicitDownloadingTransitionVisible() {
        val states = mutableListOf<TokenizerSetupState>()
        val setup = GemmaTokenizerSetup(root, connectionFactory = { FakeConnection(503, ByteArray(0)) })
        assertSame(TokenizerSetupState.NetworkUnavailable, setup.setup { states += it })
        assertEquals(listOf(TokenizerSetupState.Downloading), states)
        assertTrue(root.isDirectory)
    }

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray,
        private val length: Long = body.size.toLong()
    ) : HttpURLConnection(URL("https://example.invalid")) {
        var bodyReads = 0
        override fun getResponseCode(): Int = status
        override fun getHeaderFieldLong(name: String?, default: Long): Long =
            if (name.equals("Content-Length", ignoreCase = true)) length else default
        override fun getInputStream() = ByteArrayInputStream(body).also { bodyReads++ }
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }
}
