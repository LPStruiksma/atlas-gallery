package nl.atlas.gallery.featureaiedit.engine

import java.security.MessageDigest

/**
 * A non-secret fingerprint of the key that submitted a request (#431 addendum A4): the first
 * 8 bytes of SHA-256(per-install random salt + key), as 16 hex digits. It lets "Check again" notice
 * that the key changed after submit without ever storing or logging the key itself. The salt is
 * never shared between installs, so the fingerprint can't be matched against another device's.
 */
object KeyFingerprint {
    const val SALT_BYTES = 16
    private val FORMAT = Regex("[0-9a-f]{16}")

    fun of(salt: ByteArray, key: String): String {
        require(salt.size >= SALT_BYTES) { "Salt too short" }
        require(key.isNotEmpty()) { "Empty key" }
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(key.toByteArray(Charsets.UTF_8))
        }.digest()
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isValid(fingerprint: String?): Boolean = fingerprint != null && FORMAT.matches(fingerprint)

    /**
     * True only when a well-formed stored fingerprint equals the current key's. A missing key, a
     * missing or malformed stored fingerprint, or any mismatch means "ATLAS can no longer check".
     */
    fun matches(stored: String?, salt: ByteArray, currentKey: String?): Boolean {
        if (!isValid(stored) || currentKey.isNullOrEmpty() || salt.size < SALT_BYTES) return false
        return MessageDigest.isEqual(stored!!.toByteArray(Charsets.US_ASCII),
            of(salt, currentKey).toByteArray(Charsets.US_ASCII))
    }
}
