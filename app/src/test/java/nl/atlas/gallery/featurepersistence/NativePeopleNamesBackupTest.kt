package nl.atlas.gallery.featurepersistence

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class NativePeopleNamesBackupTest {
    private val faceA = "a".repeat(32)
    private val faceB = "b".repeat(32)
    private val person = "native-" + faceA

    private val data = NativePeopleNamesSnapshot(
        names = mapOf(person to "Ada 🪻"),
        hidden = setOf(person),
        confirmed = mapOf(faceA to person),
        cannotLink = setOf(faceA to faceB),
        rejected = setOf(faceB to person),
        sameUnknown = emptySet(),
        faceDecisions = mapOf(faceB to "LATER"),
        coverOverrides = mapOf(person to faceA)
    )

    @Test fun roundTripOnlyHumanMetadataAndNoMediaFields() {
        val bytes = NativePeopleNamesBackupCodec.encode(data)
        val changedOrder = data.copy(names = data.names.toList().reversed().toMap())
        assertArrayEquals(bytes, NativePeopleNamesBackupCodec.encode(changedOrder))
        assertEquals(data, NativePeopleNamesBackupCodec.decode(bytes))
        assertEquals(1, NativePeopleNamesBackupCodec.preview(bytes).names)
        assertEquals(5, NativePeopleNamesBackupCodec.preview(bytes).reviewDecisions)
        // The names format must never serialize the legacy path-bearing
        // PeopleBackupCodec schema, photos, vectors, filenames or URIs.
        val raw = String(bytes, Charsets.ISO_8859_1)
        listOf("image_path", "/storage/", "content://", "file://",
            "IMG_0001.jpg", "embedding", "vector").forEach {
            assertFalse("The names backup leaked a media field: $it", raw.contains(it))
        }
        assertFalse(raw.startsWith("ATLAS-PEOPLE-BACKUP"))
    }

    @Test fun checksumVersionAndKeysFailClosed() {
        val original = NativePeopleNamesBackupCodec.encode(data)
        val altered = original.clone().also {
            it[32] = (it[32].toInt() xor 3).toByte()
        }
        val truncated = original.copyOf(original.size - 1)
        val tooShort = byteArrayOf(1, 2, 3)
        for (input in listOf(altered, truncated, tooShort)) rejected {
            NativePeopleNamesBackupCodec.decode(input)
        }
        rejected {
            NativePeopleNamesBackupCodec.encode(
                data.copy(names = mapOf("content://private/1" to "Ada"))
            )
        }
        rejected {
            NativePeopleNamesBackupCodec.encode(
                data.copy(coverOverrides = mapOf(person to "content://forbidden"))
            )
        }
        rejected {
            NativePeopleNamesBackupCodec.encode(
                data.copy(faceDecisions = mapOf(faceB to "AUTO_SAME"))
            )
        }
    }

    @Test fun emptyBackupRoundTripsAndHasNoPhotos() {
        val empty = NativePeopleNamesSnapshot(
            emptyMap(), emptySet(), emptyMap(), emptySet(), emptySet(),
            emptySet(), emptyMap()
        )
        assertEquals(empty,
            NativePeopleNamesBackupCodec.decode(NativePeopleNamesBackupCodec.encode(empty)))
    }

    // HUB-1A (#210): mixed groups, version 3, legacy fixtures.
    private val printA = "0123456789abcdef".repeat(4)
    private val printB = "fedcba9876543210".repeat(4)
    private val mixed = data.copy(mixedGroups = setOf(faceA to printA, faceA to printB))

    /**
     * A document written the way earlier builds wrote it: magic, version,
     * then sections (`M` map, `S` set, `P` pairs) and a SHA-256 trailer.
     * Built by hand here so the legacy fixtures do not depend on [encode].
     */
    private fun legacy(version: Int, vararg sections: Any): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { w ->
            w.write("ATLAS-NATIVE-PEOPLE-NAMES\n".toByteArray(Charsets.US_ASCII))
            w.writeInt(version)
            for (section in sections) when (section) {
                is Map<*, *> -> { w.writeInt(section.size)
                    section.forEach { (k, v) -> w.writeUTF(k as String); w.writeUTF(v as String) } }
                is List<*> -> { w.writeInt(section.size)
                    section.forEach { p -> val (a, b) = p as Pair<*, *>
                        w.writeUTF(a as String); w.writeUTF(b as String) } }
                is Set<*> -> { w.writeInt(section.size)
                    section.forEach { w.writeUTF(it as String) } }
                else -> error("unknown section")
            }
        }
        val body = out.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    private fun v2Sections(): Array<Any> = arrayOf(
        mapOf(person to "Ada 🪻"), setOf(person), mapOf(faceA to person),
        listOf(faceA to faceB), listOf(faceB to person), emptyList<Pair<String, String>>(),
        mapOf(faceB to "LATER"), mapOf(person to faceA))

    @Test fun legacyVersion1And2BackupsStillImport() {
        val v1 = legacy(1, *v2Sections().copyOf(7).requireNoNulls())
        assertEquals(data.copy(coverOverrides = emptyMap()), NativePeopleNamesBackupCodec.decode(v1))
        val v2 = legacy(2, *v2Sections())
        assertEquals(data, NativePeopleNamesBackupCodec.decode(v2))
        // Without mixed groups a backup is still written as version 2, byte
        // for byte, so a build from before HUB-1A imports it unchanged.
        assertArrayEquals(v2, NativePeopleNamesBackupCodec.encode(data))
    }

    @Test fun mixedGroupsRoundTripAsVersion3() {
        val bytes = NativePeopleNamesBackupCodec.encode(mixed)
        assertArrayEquals(legacy(3, *v2Sections(), listOf(faceA to printA, faceA to printB)), bytes)
        assertEquals(mixed, NativePeopleNamesBackupCodec.decode(bytes))
        val reordered = mixed.copy(mixedGroups = mixed.mixedGroups.reversed().toSet())
        assertArrayEquals(bytes, NativePeopleNamesBackupCodec.encode(reordered))
        assertEquals(7, NativePeopleNamesBackupCodec.preview(bytes).reviewDecisions)
        // Only opaque face ids and fixed-size fingerprints: no media fields.
        val raw = String(bytes, Charsets.ISO_8859_1)
        listOf("content://", "file://", "/storage/", "embedding", "vector").forEach {
            assertFalse(raw.contains(it))
        }
    }

    @Test fun malformedDuplicateOrNewerMixedGroupBackupsFailClosed() {
        for (bad in listOf(printA.uppercase(), printA.dropLast(1), printA + "0", "", "g" + printA.drop(1))) {
            rejected { NativePeopleNamesBackupCodec.encode(data.copy(mixedGroups = setOf(faceA to bad))) }
            rejected { NativePeopleNamesBackupCodec.decode(legacy(3, *v2Sections(), listOf(faceA to bad))) }
        }
        rejected { NativePeopleNamesBackupCodec.encode(data.copy(mixedGroups = setOf("content://x" to printA))) }
        rejected { NativePeopleNamesBackupCodec.decode(legacy(3, *v2Sections(), listOf(faceA.uppercase() to printA))) }
        rejected { // the same fact twice
            NativePeopleNamesBackupCodec.decode(legacy(3, *v2Sections(), listOf(faceA to printA, faceA to printA)))
        }
        rejected { // a version-3 section where version 2 has none: trailing payload
            NativePeopleNamesBackupCodec.decode(legacy(2, *v2Sections(), listOf(faceA to printA)))
        }
        rejected { // a version 3 document without its mixed-group section
            NativePeopleNamesBackupCodec.decode(legacy(3, *v2Sections()))
        }
        rejected { // a newer format is refused whole, never half-imported
            NativePeopleNamesBackupCodec.decode(legacy(4, *v2Sections(), listOf(faceA to printA), emptyMap<String, String>()))
        }
        rejected { NativePeopleNamesBackupCodec.decode(legacy(0, *v2Sections().copyOf(7).requireNoNulls())) }
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected a rejected or corrupt names backup")
        } catch (_: IllegalArgumentException) { }
    }
}
