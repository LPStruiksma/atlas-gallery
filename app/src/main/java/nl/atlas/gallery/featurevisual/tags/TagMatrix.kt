package nl.atlas.gallery.featurevisual.tags

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * TAGS-0 (#667): a fixed tag vocabulary encoded once on the host with the
 * shipped SigLIP 2 text tower (`tools/tag_vocab/tag_vocab.py`). Each row is a
 * unit vector in the same image–text space as the stored image embeddings, so
 * tagging a photo is one dot product per tag. No tokenizer, no model and no
 * second index run on the phone.
 *
 * [vectors] is row-major, `tags.size × dimension`, every row L2-normalised.
 */
class TagMatrix(
    val dimension: Int,
    /** The image index version the thresholds were calibrated for (`SiglipModel.MODEL_VERSION`). */
    val imageModelVersion: String,
    /** The text tower that produced the rows (`siglip2_b16_224.text.int8:…`). */
    val textModelVersion: String,
    /** First 12 hex of the vocabulary file's SHA-256: changes whenever a tag, phrase or threshold rule changes. */
    val vocabularyHash: String,
    val tags: List<TagSpec>,
    internal val vectors: FloatArray,
) {
    init {
        require(dimension > 0) { "Invalid tag dimension" }
        require(tags.isNotEmpty()) { "Empty tag matrix" }
        require(vectors.size == tags.size * dimension) { "Tag matrix size mismatch" }
        require(tags.map { it.id }.toSet().size == tags.size) { "Duplicate tag id" }
        require(imageModelVersion.isNotBlank() && textModelVersion.isNotBlank() && vocabularyHash.isNotBlank())
    }

    /**
     * Stored tag results are valid only for this exact triple. TAGS-1 keeps it
     * next to each tagged row and re-tags when it differs, the same way the
     * visual index is fenced by its model version.
     */
    val fence: String get() = "$imageModelVersion|$textModelVersion|$vocabularyHash"

    fun row(index: Int): FloatArray = vectors.copyOfRange(index * dimension, (index + 1) * dimension)
}

/**
 * One tag: its id (see `tags.tsv` for display names), cosine threshold, and
 * whether it is on by default: calibrated on enough licensed labels AND
 * passing the TAGS-0 gate (evaluation precision ≥ 0.6, recall ≥ 0.15).
 */
data class TagSpec(val id: String, val threshold: Float, val defaultOn: Boolean) {
    init {
        require(id.isNotBlank()) { "Blank tag id" }
        require(threshold.isFinite() && threshold > -1f && threshold < 1f) { "Invalid tag threshold" }
    }
}

/**
 * Binary format of `assets/models/siglip2_tags/tags.bin`, little-endian:
 *
 * ```
 * "ATLSTAG1" | i32 dim | i32 count | str imageModel | str textModel | str vocabHash
 * count × ( str id | f32 threshold | f32 scale | u8 defaultOn | i8[dim] )
 * str = u16 byte length + UTF-8
 * ```
 *
 * Rows are signed-int8 with one scale per row, the same symmetric scheme as
 * `VisualVectorCodec`; decode renormalises. Anything malformed is rejected.
 */
object TagMatrixCodec {
    private val MAGIC = "ATLSTAG1".toByteArray(Charsets.US_ASCII)
    private const val MAX_DIMENSION = 4096
    private const val MAX_TAGS = 65_536

    fun decode(bytes: ByteArray): TagMatrix {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            require(magic.contentEquals(MAGIC)) { "Not a tag matrix" }
            val dimension = buffer.int
            val count = buffer.int
            require(dimension in 1..MAX_DIMENSION && count in 1..MAX_TAGS) { "Invalid tag matrix header" }
            val imageModel = buffer.string()
            val textModel = buffer.string()
            val vocabularyHash = buffer.string()
            val tags = ArrayList<TagSpec>(count)
            val vectors = FloatArray(count * dimension)
            val raw = ByteArray(dimension)
            for (row in 0 until count) {
                val id = buffer.string()
                val threshold = buffer.float
                val scale = buffer.float
                val flags = buffer.get().toInt()
                require(flags == 0 || flags == 1) { "Invalid tag flags" }
                require(scale.isFinite() && scale > 0f) { "Invalid tag scale" }
                buffer.get(raw)
                var squared = 0.0
                for (i in 0 until dimension) {
                    val v = raw[i] * scale
                    vectors[row * dimension + i] = v
                    squared += v.toDouble() * v
                }
                require(squared.isFinite() && squared > 0.0) { "Zero tag vector" }
                val inverse = (1.0 / sqrt(squared)).toFloat()
                for (i in 0 until dimension) vectors[row * dimension + i] *= inverse
                tags += TagSpec(id, threshold, flags == 1)
            }
            require(!buffer.hasRemaining()) { "Trailing bytes after tag matrix" }
            return TagMatrix(dimension, imageModel, textModel, vocabularyHash, tags, vectors)
        } catch (e: java.nio.BufferUnderflowException) {
            throw IllegalArgumentException("Truncated tag matrix", e)
        }
    }

    /** Host-format writer, used by tests and by any later on-device vocabulary update. */
    fun encode(
        dimension: Int, imageModelVersion: String, textModelVersion: String, vocabularyHash: String,
        rows: List<Pair<TagSpec, FloatArray>>,
    ): ByteArray {
        require(rows.all { it.second.size == dimension })
        val strings = listOf(imageModelVersion, textModelVersion, vocabularyHash) + rows.map { it.first.id }
        val size = MAGIC.size + 8 + strings.sumOf { 2 + it.toByteArray(Charsets.UTF_8).size } +
            rows.size * (9 + dimension)
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC).putInt(dimension).putInt(rows.size)
        out.string(imageModelVersion).string(textModelVersion).string(vocabularyHash)
        for ((spec, vector) in rows) {
            var squared = 0.0
            for (v in vector) squared += v.toDouble() * v
            require(squared.isFinite() && squared > 0.0) { "Zero tag vector" }
            val inverse = (1.0 / sqrt(squared)).toFloat()
            val peak = vector.maxOf { kotlin.math.abs(it * inverse) }
            val scale = peak / 127f
            out.string(spec.id).putFloat(spec.threshold).putFloat(scale).put(if (spec.defaultOn) 1 else 0)
            for (v in vector) out.put(Math.round(v * inverse / scale).coerceIn(-127, 127).toByte())
        }
        return out.array()
    }

    private fun ByteBuffer.string(): String {
        val length = short.toInt() and 0xFFFF
        require(length in 1..remaining()) { "Invalid string in tag matrix" }
        val bytes = ByteArray(length).also { get(it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun ByteBuffer.string(value: String): ByteBuffer {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..0xFFFF)
        putShort(bytes.size.toShort())
        put(bytes)
        return this
    }
}
