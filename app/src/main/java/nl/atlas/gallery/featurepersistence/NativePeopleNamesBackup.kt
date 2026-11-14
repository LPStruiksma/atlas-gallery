package nl.atlas.gallery.featurepersistence

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * #62: standalone native People BACKUP, not the older V4/path-bearing backup.
 * Only human names, hidden flags and explicit human review facts leave the app.
 * Keys are local opaque IDs; there are no media keys, URIs, filenames, vectors,
 * crops, photos, model outputs or permissions in this format.
 */
data class NativePeopleNamesSnapshot(
    val names: Map<String, String>,
    val hidden: Set<String>,
    val confirmed: Map<String, String>,
    val cannotLink: Set<Pair<String, String>>,
    val rejected: Set<Pair<String, String>>,
    val sameUnknown: Set<Pair<String, String>>,
    val faceDecisions: Map<String, String>
)

data class NativePeopleNamesPreview(
    val names: Int, val reviewDecisions: Int, val checksum: String
)

data class NativePeopleNamesImportResult(val applied: Int, val skipped: Int)

object NativePeopleNamesBackupCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 250_000
    private const val VERSION = 1
    private const val DIGEST = 32
    private val magic = "ATLAS-NATIVE-PEOPLE-NAMES\n".toByteArray(Charsets.US_ASCII)
    private val face = Regex("[0-9a-f]{32}")
    // Native person IDs are local, non-path tokens. Never serialize arbitrary
    // DB strings that might accidentally be a URI or a file identifier.
    private val person = Regex("[a-zA-Z0-9_-]{1,128}")

    fun preview(document: ByteArray): NativePeopleNamesPreview {
        val decoded = decode(document)
        return NativePeopleNamesPreview(decoded.names.size,
            decisionCount(decoded), checksum(document))
    }

    fun checksum(document: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(document)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun decisionCount(s: NativePeopleNamesSnapshot): Int =
        s.confirmed.size + s.cannotLink.size + s.rejected.size +
            s.sameUnknown.size + s.faceDecisions.size

    fun encode(snapshot: NativePeopleNamesSnapshot): ByteArray {
        validate(snapshot)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.write(magic)
            writer.writeInt(VERSION)
            fun map(rows: Map<String, String>) {
                writer.writeInt(rows.size)
                rows.toSortedMap().forEach { (key, value) ->
                    writer.writeUTF(key); writer.writeUTF(value)
                }
            }
            fun set(rows: Set<String>) {
                writer.writeInt(rows.size)
                rows.sorted().forEach(writer::writeUTF)
            }
            fun pairs(rows: Set<Pair<String, String>>) {
                writer.writeInt(rows.size)
                rows.sortedWith(compareBy<Pair<String, String>> { it.first }
                    .thenBy { it.second }).forEach { (a, b) ->
                    writer.writeUTF(a); writer.writeUTF(b)
                }
            }
            map(snapshot.names)
            set(snapshot.hidden)
            map(snapshot.confirmed)
            pairs(snapshot.cannotLink)
            pairs(snapshot.rejected)
            pairs(snapshot.sameUnknown)
            map(snapshot.faceDecisions)
        }
        val body = output.toByteArray()
        require(body.size <= MAX_BYTES - DIGEST) { "Names backup is too large" }
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    fun decode(document: ByteArray): NativePeopleNamesSnapshot {
        require(document.size in (magic.size + 7 * 4 + 4 + DIGEST)..MAX_BYTES) {
            "Invalid or oversized names backup"
        }
        val end = document.size - DIGEST
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(document.copyOfRange(0, end))
        require(MessageDigest.isEqual(actual, document.copyOfRange(end, document.size))) {
            "Names backup checksum mismatch"
        }
        val result = try {
            DataInputStream(ByteArrayInputStream(document, 0, end)).use { reader ->
                val header = ByteArray(magic.size)
                reader.readFully(header)
                require(header.contentEquals(magic)) { "Not an ATLAS native names backup" }
                require(reader.readInt() == VERSION) {
                    "Unsupported names backup version; nothing was imported"
                }
                var total = 0
                fun count(): Int {
                    val n = reader.readInt()
                    require(n in 0..MAX_ENTRIES && total <= MAX_ENTRIES - n) {
                        "Too many names or review decisions"
                    }
                    total += n
                    return n
                }
                fun map(): Map<String, String> = buildMap {
                    repeat(count()) {
                        val key = reader.readUTF()
                        val value = reader.readUTF()
                        require(!containsKey(key)) { "Duplicate names backup key" }
                        put(key, value)
                    }
                }
                fun set(): Set<String> = buildSet {
                    repeat(count()) {
                        require(add(reader.readUTF())) { "Duplicate hidden key" }
                    }
                }
                fun pairs(): Set<Pair<String, String>> = buildSet {
                    repeat(count()) {
                        val p = reader.readUTF() to reader.readUTF()
                        require(add(p)) { "Duplicate review pair" }
                    }
                }
                NativePeopleNamesSnapshot(map(), set(), map(), pairs(), pairs(),
                    pairs(), map()).also {
                    require(reader.available() == 0) { "Trailing names backup payload" }
                }
            }
        } catch (error: java.io.IOException) {
            throw IllegalArgumentException("Invalid names backup document", error)
        }
        validate(result)
        return result
    }

    private fun validate(s: NativePeopleNamesSnapshot) {
        require(s.names.size + s.hidden.size + decisionCount(s) <= MAX_ENTRIES) {
            "Too many names or review decisions"
        }
        fun personKey(key: String) = require(person.matches(key)) {
            "Invalid or identifying native person key"
        }
        fun faceId(key: String) = require(face.matches(key)) {
            "Invalid native face key"
        }
        s.names.forEach { (key, name) ->
            personKey(key)
            require(PeopleReviewRecords.validName(name)) { "Invalid person name" }
        }
        s.hidden.forEach(::personKey)
        require(s.hidden.all { it in s.names }) { "Hidden person has no name" }
        s.confirmed.forEach { (face, person) -> faceId(face); personKey(person) }
        s.cannotLink.forEach { (a, b) ->
            faceId(a); faceId(b)
            require(a < b) { "Unordered cannot-link pair" }
        }
        s.rejected.forEach { (face, person) -> faceId(face); personKey(person) }
        s.sameUnknown.forEach { (a, b) ->
            faceId(a); faceId(b)
            require(a < b) { "Unordered same-person pair" }
        }
        s.faceDecisions.forEach { (face, decision) ->
            faceId(face)
            require(decision == "LATER" || decision == "NOT_A_FACE") {
                "Invalid native review decision"
            }
        }
    }
}
