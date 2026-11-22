package nl.atlas.gallery.featureaiedit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFingerprintTest {
    // Synthetic key assembled at runtime. guard: allow-synthetic-secret
    private val key = listOf("synthetic", "key", "0001").joinToString("-")
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun stableShortHexAndNeverContainsTheKey() {
        val fp = KeyFingerprint.of(salt, key)
        assertEquals(fp, KeyFingerprint.of(salt, key))
        assertTrue(Regex("[0-9a-f]{16}").matches(fp))
        assertFalse(fp.contains("synthetic"))
        assertTrue(KeyFingerprint.isValid(fp))
    }

    @Test fun saltAndKeyBothMatter() {
        val other = ByteArray(16) { (it + 1).toByte() }
        assertNotEquals(KeyFingerprint.of(salt, key), KeyFingerprint.of(other, key))
        assertNotEquals(KeyFingerprint.of(salt, key), KeyFingerprint.of(salt, key + "x"))
    }

    @Test fun replacedOrRemovedKeyNeverMatches() {
        val stored = KeyFingerprint.of(salt, key)
        assertTrue(KeyFingerprint.matches(stored, salt, key))
        assertFalse("replaced", KeyFingerprint.matches(stored, salt, key + "2"))
        assertFalse("removed", KeyFingerprint.matches(stored, salt, null))
        assertFalse("no stored print", KeyFingerprint.matches(null, salt, key))
        assertFalse("malformed", KeyFingerprint.matches("XYZ", salt, key))
        assertFalse("short salt", KeyFingerprint.matches(stored, ByteArray(4), key))
    }
}
