package nl.atlas.gallery.featurevisual.nls.tokenizer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Explicit setup status; encoding must never initiate a download. */
sealed interface TokenizerSetupState {
    data object Absent : TokenizerSetupState
    data object Downloading : TokenizerSetupState
    data object Ready : TokenizerSetupState
    data object Corrupt : TokenizerSetupState
    data object NetworkUnavailable : TokenizerSetupState
    data object ChecksumFailure : TokenizerSetupState
}

/**
 * Explicit-call installer for the externally hosted Gemma SentencePiece model.
 *
 * This class deliberately has no scheduler, lifecycle hook, or encode method. The only network
 * entry point is [setup], which is intended to be called by a user-initiated setup action. A
 * consumer must call [verifiedModel] and operate entirely on the returned local file.
 */
class GemmaTokenizerSetup(
    private val cacheDirectory: File,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpsURLConnection
    },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000
) {
    companion object {
        const val MODEL_URL = "https://storage.googleapis.com/big_vision/gemma_tokenizer.model"
        const val MODEL_SHA256 = "6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef"
        const val MODEL_SIZE_BYTES = 4_241_111L
        const val MODEL_FILE_NAME = "gemma_tokenizer.model"
        private const val PART_FILE_NAME = "$MODEL_FILE_NAME.part"
    }

    private val modelFile get() = File(cacheDirectory, MODEL_FILE_NAME)
    private val partFile get() = File(cacheDirectory, PART_FILE_NAME)

    init {
        require(connectTimeoutMs in 1..120_000)
        require(readTimeoutMs in 1..300_000)
    }

    /** Checks only private local storage. This method never opens a network connection. */
    fun state(): TokenizerSetupState {
        val file = modelFile
        if (!file.exists()) return TokenizerSetupState.Absent
        return if (verified(file)) TokenizerSetupState.Ready else TokenizerSetupState.Corrupt
    }

    /** Returns the pinned file only after re-verifying it; never downloads or repairs it. */
    fun verifiedModel(): File? = modelFile.takeIf { it.isFile && verified(it) }

    /**
     * Downloads once, synchronously, from the single pinned HTTPS URL. Redirects, oversized or
     * short bodies, non-200 responses, and digest mismatches are refused. A failed attempt never
     * replaces a previously verified file and leaves no partial bytes behind.
     */
    fun setup(onState: (TokenizerSetupState) -> Unit = {}): TokenizerSetupState {
        if (state() == TokenizerSetupState.Ready) return TokenizerSetupState.Ready
        onState(TokenizerSetupState.Downloading)
        cacheDirectory.mkdirs()
        if (!cacheDirectory.isDirectory) return TokenizerSetupState.NetworkUnavailable
        makePrivate(cacheDirectory, directory = true)
        partFile.delete()

        val connection = try {
            connectionFactory(URL(MODEL_URL))
        } catch (_: IOException) {
            return TokenizerSetupState.NetworkUnavailable
        }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "ATLAS-Gallery")
            if (connection.responseCode != 200) return TokenizerSetupState.NetworkUnavailable
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            if (declared != -1L && declared != MODEL_SIZE_BYTES) {
                return TokenizerSetupState.ChecksumFailure
            }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                partFile.outputStream().buffered().use { output -> copyBounded(input, output, digest) }
            }
            makePrivate(partFile, directory = false)
            if (partFile.length() != MODEL_SIZE_BYTES || digest.hex() != MODEL_SHA256) {
                return TokenizerSetupState.ChecksumFailure
            }
            try {
                Files.move(
                    partFile.toPath(), modelFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(partFile.toPath(), modelFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            makePrivate(modelFile, directory = false)
            return TokenizerSetupState.Ready
        } catch (_: OversizedModelException) {
            return TokenizerSetupState.ChecksumFailure
        } catch (_: IOException) {
            return TokenizerSetupState.NetworkUnavailable
        } finally {
            partFile.delete()
            connection.disconnect()
        }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, digest: MessageDigest) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MODEL_SIZE_BYTES) throw OversizedModelException()
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
        }
    }

    private fun verified(file: File): Boolean {
        if (!file.isFile || file.length() != MODEL_SIZE_BYTES) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.hex() == MODEL_SHA256
        } catch (_: IOException) {
            false
        }
    }

    private fun makePrivate(file: File, directory: Boolean) {
        try {
            val permissions = if (directory) {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            Files.setPosixFilePermissions(file.toPath(), permissions)
        } catch (_: UnsupportedOperationException) {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            if (directory) file.setExecutable(true, true)
        } catch (_: IOException) {
            // Verification, size bounds, and private app cache placement remain mandatory.
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class OversizedModelException : IOException()
}
