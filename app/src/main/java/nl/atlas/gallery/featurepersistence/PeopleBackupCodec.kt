package nl.atlas.gallery.featurepersistence

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** A single *confirmed* native assignment. Never a detection or identity suggestion. */
data class PeopleBackupRow(
    val faceId: Long,
    val personId: Long,
    val personName: String,
    val imagePath: String
)

/**
 * Original ATLAS-only, explicit, versioned People backup. This is NOT a V4 CSV
 * or a raw SQLite backup and contains no photos, preferences, SAF grants or keys.
 * SHA-256 detects truncation/accidental corruption; it is NOT encryption or
 * authentication. Callers must disclose SAF cloud-provider risk.
 *
 * Format: magic, format version, row count, bounded length-prefixed UTF-8 rows,
 * then 32-byte SHA-256 of everything preceding the digest.
 */
object PeopleBackupCodec {
    const val FORMAT_VERSION = 1
    const val MAX_ROWS = 500_000
    const val MAX_BYTES = 128 * 1024 * 1024
    private const val MAX_NAME_BYTES = 2048
    private const val MAX_PATH_BYTES = 8192
    private const val DIGEST_BYTES = 32
    private val magic = "ATLAS-PEOPLE-BACKUP\u000a".toByteArray(Charsets.US_ASCII)

    /**
     * Shared importer/exporter admission without materializing a second backup.
     * The additive SQLite merge uses this instead of encode(rows) merely to
     * validate already-decoded confirmed assignments.
     */
    internal fun checkedDocumentSize(rows: Collection<PeopleBackupRow>): Int {
        require(rows.size <= MAX_ROWS) { "People backup row limit exceeded" }
        validateRows(rows)
        var requiredBytes = magic.size.toLong() + 8L + DIGEST_BYTES
        for (row in rows) {
            val nameLength = row.personName.toByteArray(Charsets.UTF_8).size
            val pathLength = row.imagePath.toByteArray(Charsets.UTF_8).size
            requiredBytes += 24L + nameLength + pathLength
            require(requiredBytes <= MAX_BYTES) { "People backup is too large" }
        }
        return requiredBytes.toInt()
    }

    /**
     * Additive native import must not produce a destination that cannot be
     * exported using the same version-1 backup bounds. Both collections are
     * independently admitted; the merge planner already checks cross-set
     * face IDs and person-name collisions before invoking this.
     *
     * Avoid concatenating two potentially 500k-row collections merely to
     * check their combined serialized byte count.
     */
    internal fun checkedMergedDocumentSize(
        existing: Collection<PeopleBackupRow>,
        additions: Collection<PeopleBackupRow>
    ): Int {
        // Reject over-cap counts BEFORE iterating either source or allocating.
        require(existing.size <= MAX_ROWS - additions.size &&
            existing.size >= 0 && additions.size in 0..MAX_ROWS) {
            "Merged People database exceeds backup row limit"
        }
        val currentBytes = checkedDocumentSize(existing)
        val addedBytes = checkedDocumentSize(additions)
        val fixedBytes = magic.size + 8 + DIGEST_BYTES
        val mergedBytes = currentBytes.toLong() + addedBytes - fixedBytes
        require(mergedBytes <= MAX_BYTES) {
            "Merged People database exceeds backup document size limit"
        }
        return mergedBytes.toInt()
    }

    fun encode(rows: Collection<PeopleBackupRow>): ByteArray {
        val document = ByteArray(checkedDocumentSize(rows))
        val ordered = rows.sortedBy { it.faceId }
        val payload = ByteBuffer.wrap(document)
        payload.put(magic)
        payload.putInt(FORMAT_VERSION)
        payload.putInt(rows.size)
        for (row in ordered) {
            payload.putLong(row.faceId)
            payload.putLong(row.personId)
            putText(payload, row.personName, MAX_NAME_BYTES)
            putText(payload, row.imagePath, MAX_PATH_BYTES)
        }
        val payloadEnd = document.size - DIGEST_BYTES
        check(payload.position() == payloadEnd) { "People backup size changed during encoding" }
        MessageDigest.getInstance("SHA-256").apply {
            update(document, 0, payloadEnd)
        }.digest().copyInto(document, payloadEnd)
        return document
    }

    fun decode(document: ByteArray): List<PeopleBackupRow> {
        require(document.size in (magic.size + 8 + DIGEST_BYTES)..MAX_BYTES) {
            "Invalid or oversized People backup"
        }
        val payloadEnd = document.size - DIGEST_BYTES
        // Hash the existing caller-owned bytes in place. At the 128 MiB cap,
        // copying the body would briefly double the byte-array footprint before
        // allocating decoded rows; that creates avoidable Android OOM pressure.
        val computed = MessageDigest.getInstance("SHA-256").apply {
            update(document, 0, payloadEnd)
        }.digest()
        val storedDigest = document.copyOfRange(payloadEnd, document.size)
        require(MessageDigest.isEqual(computed, storedDigest)) {
            "People backup checksum mismatch"
        }
        val rows = DataInputStream(
            ByteArrayInputStream(document, 0, payloadEnd)
        ).use { stream ->
            val header = ByteArray(magic.size)
            stream.readFully(header)
            require(header.contentEquals(magic)) { "Not an ATLAS People backup" }
            require(stream.readInt() == FORMAT_VERSION) {
                "Unsupported People backup version; no changes made"
            }
            val count = stream.readInt()
            require(count in 0..MAX_ROWS) { "Invalid People backup row count" }
            buildList(count) {
                repeat(count) {
                    val faceId = stream.readLong()
                    val personId = stream.readLong()
                    val personName = readText(stream, MAX_NAME_BYTES)
                    val imagePath = readText(stream, MAX_PATH_BYTES)
                    add(PeopleBackupRow(faceId, personId, personName, imagePath))
                }
                require(stream.available() == 0) { "Trailing People backup payload" }
            }
        }
        validateRows(rows)
        return rows
    }

    fun digestHex(document: ByteArray): String =
        sha256(document).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal fun validateRows(rows: Collection<PeopleBackupRow>) {
        val faces = HashSet<Long>()
        val namesById = HashMap<Long, String>()
        rows.forEach { row ->
            require(row.faceId >= 0 && row.personId >= 0) { "Invalid People identifier" }
            require(row.personName.isNotBlank() && '\u0000' !in row.personName) {
                "Invalid People name"
            }
            require(isNormalizedMediaPath(row.imagePath) &&
                '\u0000' !in row.imagePath) { "Unsupported or non-canonical People image path" }
            // Java/Kotlin's default UTF-8 encoding silently replaces an
            // unpaired UTF-16 surrogate. Never export altered confirmed names
            // or paths; require a lossless, well-formed Unicode scalar stream.
            require(wellFormedUtf16(row.personName) && wellFormedUtf16(row.imagePath)) {
                "People name or media path contains malformed Unicode"
            }
            require(row.personName.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES &&
                row.imagePath.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES) {
                "People field exceeds length limit"
            }
            require(faces.add(row.faceId)) { "Duplicate People face identifier" }
            val prior = namesById.putIfAbsent(row.personId, row.personName)
            require(prior == null || prior == row.personName) {
                "One People identifier has conflicting names"
            }
        }
    }

    /** Check surrogate pairing without materializing a second copy of the text. */
    private fun wellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate()) {
                if (++index >= value.length || !value[index].isLowSurrogate()) return false
            } else if (character.isLowSurrogate()) {
                return false
            }
            index++
        }
        return true
    }

    // Preserve only normalized absolute shared-storage paths. A prefix match
    // alone accepts /storage/emulated/0/DCIM/../../data, which could resolve
    // outside the expected photo namespace if used by a later media adapter.
    // Check segments without allocating lists for hundreds of thousands of rows.
    private fun isNormalizedMediaPath(path: String): Boolean {
        val prefix = "/storage/emulated/0/"
        if (!path.startsWith(prefix) || path.length == prefix.length) return false
        var segmentStart = prefix.length
        for (index in segmentStart..path.length) {
            if (index == path.length || path[index] == '/') {
                val length = index - segmentStart
                if (length == 0 ||
                    length == 1 && path[segmentStart] == '.' ||
                    length == 2 && path[segmentStart] == '.' &&
                        path[segmentStart + 1] == '.') return false
                segmentStart = index + 1
            }
        }
        return true
    }

    private fun putText(buffer: ByteBuffer, value: String, max: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= max) { "People field exceeds length limit" }
        buffer.putInt(bytes.size)
        buffer.put(bytes)
    }

    private fun readText(stream: DataInputStream, max: Int): String {
        val length = stream.readInt()
        require(length in 0..max) { "Invalid People field length" }
        val bytes = ByteArray(length)
        stream.readFully(bytes)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (invalid: CharacterCodingException) {
            // Corruption can be resealed by an untrusted source. Normalize
            // decoder failures into the same fail-closed input contract as
            // version, field bounds and row-identity checks.
            throw IllegalArgumentException("Malformed UTF-8 in People backup field", invalid)
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
