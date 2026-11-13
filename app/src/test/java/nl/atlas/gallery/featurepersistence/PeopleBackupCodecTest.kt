package nl.atlas.gallery.featurepersistence

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PeopleBackupCodecTest {
    private val sample = listOf(
        PeopleBackupRow(7, 17, "Zoë, \"Friend\"", "/storage/emulated/0/DCIM/2020/école.jpg"),
        PeopleBackupRow(3, 19, "Suze", "/storage/emulated/0/Pictures/scan-2.png")
    )

    @Test fun deterministicRoundTripAndNoV4Confusion() {
        val first = PeopleBackupCodec.encode(sample)
        val second = PeopleBackupCodec.encode(sample.reversed())
        assertArrayEquals(first, second)
        assertEquals(sample.sortedBy { it.faceId }, PeopleBackupCodec.decode(first))
        assertEquals(64, PeopleBackupCodec.digestHex(first).length)
        assertFalse(String(first, Charsets.ISO_8859_1).startsWith("person_id"))
        assertTrue(PeopleBackupCodec.decode(PeopleBackupCodec.encode(emptyList())).isEmpty())
    }

    @Test fun newSingleAllocationEncoderMatchesOriginalWireFormatExactly() {
        // Independent, bounded legacy DataOutputStream oracle. This protects
        // existing version-1 backups against silent endian/layout changes.
        fun oldWireFormat(rows: List<PeopleBackupRow>): ByteArray {
            val body = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(body).use { stream ->
                stream.write("ATLAS-PEOPLE-BACKUP\n".toByteArray(Charsets.US_ASCII))
                stream.writeInt(1)
                stream.writeInt(rows.size)
                for (row in rows.sortedBy { it.faceId }) {
                    stream.writeLong(row.faceId)
                    stream.writeLong(row.personId)
                    for (value in listOf(row.personName, row.imagePath)) {
                        val bytes = value.toByteArray(Charsets.UTF_8)
                        stream.writeInt(bytes.size)
                        stream.write(bytes)
                    }
                }
            }
            val bytes = body.toByteArray()
            return bytes + MessageDigest.getInstance("SHA-256").digest(bytes)
        }
        val unusual = listOf(
            PeopleBackupRow(9223372036854775807L, 1L, "漢字 / Zoë 🎵",
                "/storage/emulated/0/Pictures/.hidden/IMG_0001.jpg"),
            PeopleBackupRow(0L, 0L, "N", "/storage/emulated/0/DCIM/a.jpg")
        )
        for (rows in listOf(emptyList(), sample, sample.reversed(), unusual)) {
            assertArrayEquals(oldWireFormat(rows), PeopleBackupCodec.encode(rows))
        }
    }

    @Test fun sharedMergePreflightEnforcesExactWireSizeWithoutSerializing() {
        val original = PeopleBackupCodec.encode(sample)
        assertEquals(original.size, PeopleBackupCodec.checkedDocumentSize(sample))
        assertEquals(PeopleBackupCodec.encode(emptyList()).size,
            PeopleBackupCodec.checkedDocumentSize(emptyList()))
        expectRejected {
            PeopleBackupCodec.checkedDocumentSize(listOf(sample[0], sample[0]))
        }
        expectRejected {
            PeopleBackupCodec.checkedDocumentSize(listOf(
                sample[0].copy(imagePath="/storage/emulated/0/DCIM/../../bad.jpg")))
        }
        // Reuse a single near-max-size fictional path to prove that the
        // 128 MiB limit is checked BEFORE allocating an oversized document.
        val path = "/storage/emulated/0/DCIM/" + "q".repeat(8100)
        val oversized = List(16_700) { n ->
            PeopleBackupRow(n.toLong(), 1, "Fictional", path)
        }
        expectRejected { PeopleBackupCodec.checkedDocumentSize(oversized) }
    }

    @Test fun allPartialOrDamagedDocumentsFailClosed() {
        val original = PeopleBackupCodec.encode(sample)
        val damaged = original.clone().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 4).toByte() }
        val digestTamper = original.clone().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        listOf(
            original.copyOfRange(0, 8), original.copyOfRange(0, original.size - 1),
            original + byteArrayOf(0), damaged, digestTamper,
            "person_id,person_name,image_path,face_id,confidence".toByteArray()
        ).forEach { input ->
            expectRejected { PeopleBackupCodec.decode(input) }
        }
    }

    @Test fun schemaAndRowCountTamperingAreDetectedEvenWithResealedHash() {
        val original = PeopleBackupCodec.encode(sample)
        val magicLength = "ATLAS-PEOPLE-BACKUP\n".toByteArray().size
        val wrongVersion = original.copyOf().also { it[magicLength + 3] = 2 }
        val wrongCount = original.copyOf().also { it[magicLength + 7] = 3 }
        val wrongMagic = original.copyOf().also { it[0] = 0 }
        for (bytes in listOf(wrongVersion, wrongCount, wrongMagic)) {
            reseal(bytes)
            expectRejected { PeopleBackupCodec.decode(bytes) }
        }
    }

    @Test fun duplicateConflictsAndUnsafeFieldsNeverEncode() {
        expectRejected { PeopleBackupCodec.encode(listOf(sample[0], sample[0])) }
        expectRejected {
            PeopleBackupCodec.encode(listOf(sample[0], sample[0].copy(personId = 18)))
        }
        expectRejected {
            PeopleBackupCodec.encode(listOf(sample[0], sample[1].copy(personId = 17)))
        }
        expectRejected {
            PeopleBackupCodec.encode(listOf(sample[0].copy(imagePath = "../unscoped")))
        }
        expectRejected {
            PeopleBackupCodec.encode(listOf(sample[0].copy(personName = "")))
        }
        expectRejected {
            PeopleBackupCodec.encode(listOf(sample[0].copy(faceId = -1)))
        }
        expectRejected {
            PeopleBackupCodec.encode(listOf(sample[0].copy(personName = "x".repeat(2049))))
        }
        expectRejected {
            PeopleBackupCodec.encode(listOf(sample[0].copy(imagePath = "/storage/emulated/0/" + "x".repeat(8193))))
        }
    }

    @Test fun malformedUtf16CannotSilentlyChangeConfirmedNameOrMediaPath() {
        val badText = listOf(
            "\uD800", "\uDC00", "prefix\uD800suffix",
            "\uD800\uD800", "\uDC00\uD800", "\uD83D"
        )
        badText.forEach { bad ->
            expectRejected {
                PeopleBackupCodec.encode(listOf(sample[0].copy(personName = "Name" + bad)))
            }
            expectRejected {
                PeopleBackupCodec.checkedDocumentSize(listOf(
                    sample[0].copy(imagePath = "/storage/emulated/0/DCIM/" + bad + ".jpg")
                ))
            }
        }
        val good = sample[0].copy(
            personName = "Zoe 🎵 𐐷",
            imagePath = "/storage/emulated/0/Pictures/📸/🥳.jpg"
        )
        assertEquals(listOf(good), PeopleBackupCodec.decode(PeopleBackupCodec.encode(listOf(good))))
    }

    @Test fun resealedInvalidUtf8FieldsAreValidationErrorsNotLossyDecode() {
        val row = PeopleBackupRow(1L, 2L, "A", "/storage/emulated/0/DCIM/a.jpg")
        val original = PeopleBackupCodec.encode(listOf(row))
        val headerLength = "ATLAS-PEOPLE-BACKUP\n".toByteArray().size + 8
        val firstNameByte = headerLength + 8 + 8 + 4
        // Checksum is valid after modification, but the single UTF-8 byte
        // 0xff can never be decoded to a Unicode scalar value.
        for (position in listOf(firstNameByte, firstNameByte + 1 + 4)) {
            val forged = original.clone()
            forged[position] = 0xff.toByte()
            reseal(forged)
            expectRejected { PeopleBackupCodec.decode(forged) }
        }
    }

    @Test fun pathTraversalNonCanonicalSegmentsAndEmptyMediaNameFailClosed() {
        val invalid = listOf(
            "/storage/emulated/0/",
            "/storage/emulated/0//DCIM/photo.jpg",
            "/storage/emulated/0/DCIM/../private/photo.jpg",
            "/storage/emulated/0/DCIM/./photo.jpg",
            "/storage/emulated/0/DCIM/.",
            "/storage/emulated/0/DCIM/..",
            "/storage/emulated/0/DCIM/",
            "/storage/emulated/0/DCIM/photo.jpg/.."
        )
        invalid.forEach { path ->
            expectRejected {
                PeopleBackupCodec.encode(listOf(sample[0].copy(imagePath = path)))
            }
        }
        val validHidden = sample[0].copy(
            imagePath = "/storage/emulated/0/Pictures/.hidden/portrait..jpg")
        assertEquals(validHidden,
            PeopleBackupCodec.decode(PeopleBackupCodec.encode(listOf(validHidden))).single())
    }

    @Test fun decodedRowIdentifiersCannotBeDuplicatedAfterResealing() {
        val rows = listOf(
            PeopleBackupRow(1, 11, "A", "/storage/emulated/0/DCIM/a.jpg"),
            PeopleBackupRow(2, 12, "B", "/storage/emulated/0/DCIM/b.jpg")
        )
        val payload = PeopleBackupCodec.encode(rows)
        // Row 2 face ID begins after magic + version/count + row 1's fixed and variable fields.
        val firstRowBytes = 16 + 4 + 1 + 4 + "/storage/emulated/0/DCIM/a.jpg".toByteArray().size
        val index = "ATLAS-PEOPLE-BACKUP\n".toByteArray().size + 8 + firstRowBytes
        payload[index + 7] = 1
        reseal(payload)
        expectRejected { PeopleBackupCodec.decode(payload) }
    }

    @Test fun largeSyntheticDocumentDecodesWithoutChangingCallerOwnedBytes() {
        // Exercise a nontrivial buffer without source images or private People data.
        val fictional = (0 until 20_000).map { n ->
            PeopleBackupRow(n.toLong(), (n % 300).toLong(),
                "Synthetic person " + (n % 300),
                "/storage/emulated/0/DCIM/fictional/" + n + ".jpg")
        }
        val document = PeopleBackupCodec.encode(fictional)
        val before = document.clone()
        assertTrue(document.size > 1_000_000)
        val decoded = PeopleBackupCodec.decode(document)
        assertEquals(fictional, decoded)
        assertArrayEquals(before, document)
        assertArrayEquals(document, PeopleBackupCodec.encode(decoded))
    }

    @Test fun resealedPayloadWithTruncatedRowNeverReadsDigestAsPayload() {
        val original = PeopleBackupCodec.encode(sample)
        // Replace some of row 2 with a still-valid SHA seal. The parser must
        // be bounded by payloadEnd, not the entire document containing digest.
        val shortDocument = original.copyOf(original.size - 5)
        reseal(shortDocument)
        expectRejected { PeopleBackupCodec.decode(shortDocument) }
    }

    private fun reseal(bytes: ByteArray) {
        val boundary = bytes.size - 32
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes.copyOfRange(0, boundary))
        digest.copyInto(bytes, boundary)
    }

    private fun expectRejected(block: () -> Unit) {
        try {
            block()
            fail("Accepted invalid backup")
        } catch (_: IllegalArgumentException) {
            // Intentional validation refusal.
        } catch (_: java.io.EOFException) {
            // Truncated but correctly resealed input fails without any database access.
        }
    }
}
