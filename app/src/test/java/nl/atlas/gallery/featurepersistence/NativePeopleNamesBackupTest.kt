package nl.atlas.gallery.featurepersistence

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
        faceDecisions = mapOf(faceB to "LATER")
    )

    @Test fun roundTripOnlyHumanMetadataAndNoMediaFields() {
        val bytes = NativePeopleNamesBackupCodec.encode(data)
        val changedOrder = data.copy(names = data.names.toList().reversed().toMap())
        assertArrayEquals(bytes, NativePeopleNamesBackupCodec.encode(changedOrder))
        assertEquals(data, NativePeopleNamesBackupCodec.decode(bytes))
        assertEquals(1, NativePeopleNamesBackupCodec.preview(bytes).names)
        assertEquals(4, NativePeopleNamesBackupCodec.preview(bytes).reviewDecisions)
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

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected a rejected or corrupt names backup")
        } catch (_: IllegalArgumentException) { }
    }
}
