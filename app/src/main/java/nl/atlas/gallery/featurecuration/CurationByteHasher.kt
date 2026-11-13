package nl.atlas.gallery.featurecuration

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Re-read the grant at most once per MiB of a file (E2d, #445). */
const val GRANT_CHECK_BYTES: Long = 1024L * 1024

/** A complete read-only byte digest. No photographed content is retained. */
data class CurationByteDigest(val sha256: String, val bytes: Long)

/**
 * Only the host may open an already-authorized media URI. No new permission is
 * requested and no write capability is exposed. Checks happen before, during
 * and after hashing. MediaStore size/revision/grant must be rechecked by the
 * caller before binding the digest to a CurationAsset. A change at any point
 * yields Unknown, not an exact-duplicate certificate.
 *
 * [stillAuthorized] can be a provider round trip, so it is read before the
 * source is opened, again before the first byte, then at most once per
 * [grantCheckBytes] read, and once more after the last byte. A grant lost
 * mid-file is therefore still caught before a digest is returned.
 */
fun hashCurationBytes(
    expectedBytes: Long,
    openAuthorized: () -> InputStream?,
    stillAuthorized: () -> Boolean,
    checkCancelled: () -> Unit = {},
    maxBytes: Long = 512L * 1024 * 1024,
    grantCheckBytes: Long = GRANT_CHECK_BYTES
): CurationByteDigest? {
    require(maxBytes in 1..(1024L * 1024 * 1024)) { "Invalid byte budget" }
    require(grantCheckBytes > 0L) { "Invalid grant check interval" }
    if (expectedBytes <= 0L || expectedBytes > maxBytes) return null
    checkCancelled()
    if (!stillAuthorized()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    return try {
        val source = openAuthorized() ?: return null
        source.use { input ->
            var total = 0L
            var zeroReads = 0
            var sinceGrantCheck = 0L
            checkCancelled()
            if (!stillAuthorized()) return null
            while (true) {
                checkCancelled()
                if (sinceGrantCheck >= grantCheckBytes) {
                    if (!stillAuthorized()) return null
                    sinceGrantCheck = 0L
                }
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    if (++zeroReads > 3) return null
                    continue
                }
                zeroReads = 0
                if (read > expectedBytes - total || read > maxBytes - total) return null
                digest.update(buffer, 0, read)
                total += read
                sinceGrantCheck += read
            }
            checkCancelled()
            if (!stillAuthorized() || total != expectedBytes) return null
            val hex = "0123456789abcdef"
            val sha = buildString(64) {
                digest.digest().forEach { byte ->
                    val value = byte.toInt() and 255
                    append(hex[value ushr 4])
                    append(hex[value and 15])
                }
            }
            CurationByteDigest(sha, total)
        }
    } catch (_: IOException) {
        null // Missing/changed source or I/O error is Unknown, not a match.
    } catch (_: SecurityException) {
        null // Permission revoked or provider refused; never widen access.
    }
}
