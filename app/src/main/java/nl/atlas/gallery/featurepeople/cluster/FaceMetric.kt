package nl.atlas.gallery.featurepeople.cluster

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.sqrt

/**
 * #580: turns a raw face vector into what grouping compares.
 *
 * Measured on about 9,500 faces of strangers with the shipped YuNet + SFace
 * int8 pair (docs/people/PEOPLE_BENCH.md):
 * - SFace vectors share a direction: two strangers score 0.075 on average and
 *   the median face scores 0.28 against the mean of all faces. A group that
 *   holds a few different people therefore resembles everyone. [apply]
 *   subtracts that mean and re-normalises, after which strangers average 0.
 * - Some regions are crowded: babies and toddlers of different families score
 *   0.5 to 0.65 with each other. [Face.crowd] says how much closer a face is
 *   to its nearest prototype of such regions than a typical face is (0 for a
 *   typical face). [ClusterLinkage.MEAN_PAIR] subtracts it from every score
 *   the face takes part in, so a crowded face needs that much more evidence.
 *
 * The constants are averages over many different people (see
 * [SFaceMetricData]); they are not anyone's face vector. Pure Kotlin; no
 * pixels, IDs or names pass through here.
 */
class FaceMetric internal constructor(
    /** Changes whenever the constants do; part of the grouping fingerprint. */
    val version: String,
    private val mean: FloatArray,
    /** Unit prototypes, row major. */
    private val prototypes: FloatArray,
    private val baseline: Float
) {
    val dimension: Int get() = mean.size

    class Face(val vector: FloatArray, val crowd: Float)

    /** [raw] in any scale; returns a new centred unit vector and its crowd value. */
    fun apply(raw: FloatArray): Face {
        require(raw.size == dimension) { "Face vector dimension differs from the metric" }
        var sq = 0.0
        for (x in raw) { require(x.isFinite()) { "Non-finite face vector" }; sq += x.toDouble() * x }
        val norm = sqrt(sq)
        require(norm > 1e-6) { "Face vector has no direction" }
        val out = FloatArray(dimension)
        var centredSq = 0.0
        for (i in out.indices) {
            val c = (raw[i] / norm).toFloat() - mean[i]
            out[i] = c
            centredSq += c.toDouble() * c
        }
        val centredNorm = sqrt(centredSq)
        // A face exactly at the mean has no identity signal; keep it unit and maximally crowded.
        if (centredNorm < 1e-6) return Face(FloatArray(dimension).also { it[0] = 1f }, MAX_CROWD)
        for (i in out.indices) out[i] = (out[i] / centredNorm).toFloat()
        var best = -1f
        val rows = prototypes.size / dimension
        for (r in 0 until rows) {
            val base = r * dimension
            var dot = 0f
            for (i in 0 until dimension) dot += out[i] * prototypes[base + i]
            if (dot > best) best = dot
        }
        return Face(out, (best - baseline).coerceIn(0f, MAX_CROWD))
    }

    companion object {
        const val MAX_CROWD = 1f

        /** The metric for `face_recognition_sface_2021dec_int8`, fitted by tools/people_bench/fit_metric.py. */
        val SFACE: FaceMetric by lazy { decode(SFaceMetricData.BASE64, SFaceMetricData.DIMENSION,
            SFaceMetricData.PROTOTYPES, SFaceMetricData.BASELINE, SFaceMetricData.SHA256) }

        internal fun decode(base64: String, dimension: Int, rows: Int, baseline: Float, sha256: String): FaceMetric {
            val bytes = Base64.getDecoder().decode(base64)
            require(bytes.size == dimension * 4 + rows * 4 + rows * dimension) { "Face metric data has the wrong size" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(digest == sha256) { "Face metric data does not match its checksum" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val mean = FloatArray(dimension) { buffer.float }
            val scales = FloatArray(rows) { buffer.float }
            val prototypes = FloatArray(rows * dimension)
            for (r in 0 until rows) {
                var sq = 0.0
                for (i in 0 until dimension) {
                    val x = buffer.get() * scales[r]
                    prototypes[r * dimension + i] = x
                    sq += x.toDouble() * x
                }
                val n = sqrt(sq)
                require(n > 1e-6) { "Empty face metric prototype" }
                for (i in 0 until dimension) prototypes[r * dimension + i] = (prototypes[r * dimension + i] / n).toFloat()
            }
            return FaceMetric("sface-metric:" + sha256.take(12), mean, prototypes, baseline)
        }

        /** For tests: a metric with explicit constants. */
        fun of(version: String, mean: FloatArray, prototypes: List<FloatArray>, baseline: Float): FaceMetric {
            require(prototypes.isNotEmpty() && prototypes.all { it.size == mean.size })
            val flat = FloatArray(prototypes.size * mean.size)
            prototypes.forEachIndexed { r, p ->
                var sq = 0.0
                for (x in p) sq += x.toDouble() * x
                val n = sqrt(sq)
                require(n > 1e-6)
                for (i in p.indices) flat[r * mean.size + i] = (p[i] / n).toFloat()
            }
            return FaceMetric(version, mean.copyOf(), flat, baseline)
        }
    }
}
