package nl.atlas.gallery.featurepeople.cluster

/**
 * #579 facts-first diagnostic: aggregates of one published clustering, small
 * enough for one `ATLAS_FACE` log line. It holds counts only: no face IDs,
 * vectors, crops, file names or person names, so the line can be copied off
 * the phone to calibrate [ClusterPolicy] on real faces.
 *
 * - [nearestNeighbour]: for a fixed, evenly spaced sample of up to
 *   [MAX_QUERIES] faces, the cosine to its most similar other face among an
 *   evenly spaced sample of up to [MAX_CORPUS] faces, in [NN_BIN_WIDTH] bins
 *   from 0 (bin 0 also holds negatives, the last bin everything >= 0.95).
 *   A same-person peak sits high, a stranger peak low; the valley between is
 *   where the join threshold belongs.
 * - [clusterSizes]: unknown clusters per size band ([SIZE_BANDS] lower bounds).
 */
class FaceClusterDiagnostics private constructor(
    val faces: Int,
    val confirmedFaces: Int,
    val clusters: Int,
    val singletons: Int,
    val facesInClustersOf3: Int,
    val clustersOf3: Int,
    val clusterSizes: IntArray,
    val nearestNeighbour: IntArray,
    val sampledQueries: Int,
    val sampledCorpus: Int,
    val policyFingerprint: String
) {
    val singletonShare: Double get() = if (clusters == 0) 0.0 else singletons.toDouble() / clusters

    /** One log line; keys are stable so lines from several runs compare. */
    fun logLine(): String = buildString {
        append("people_clusters policy=").append(policyFingerprint)
        append(" faces=").append(faces)
        append(" confirmed=").append(confirmedFaces)
        append(" clusters=").append(clusters)
        append(" singletons=").append(singletons)
        append(" singleton_share=").append("%.3f".format(java.util.Locale.ROOT, singletonShare))
        append(" clusters_3plus=").append(clustersOf3)
        append(" faces_in_3plus=").append(facesInClustersOf3)
        append(" sizes=")
        SIZE_BANDS.forEachIndexed { i, lo ->
            if (i > 0) append(',')
            append(lo).append(if (i == SIZE_BANDS.lastIndex) "+" else "").append(':')
                .append(clusterSizes[i])
        }
        append(" nn_cos(q=").append(sampledQueries).append(",n=").append(sampledCorpus).append(")=")
        nearestNeighbour.forEachIndexed { i, count ->
            if (i > 0) append(',')
            append("%.2f".format(java.util.Locale.ROOT, i * NN_BIN_WIDTH)).append(':').append(count)
        }
    }

    companion object {
        const val MAX_QUERIES = 256
        const val MAX_CORPUS = 50_000
        const val NN_BIN_WIDTH = 0.05
        private const val NN_BINS = 20
        val SIZE_BANDS = intArrayOf(1, 2, 3, 5, 10, 50, 200, 1000)

        /** Pure CPU, bounded by MAX_QUERIES x MAX_CORPUS dot products. */
        fun of(state: FaceClusterState): FaceClusterDiagnostics {
            val sizes = IntArray(SIZE_BANDS.size)
            var singletons = 0
            var in3 = 0
            var of3 = 0
            for (c in state.unknownClusters) {
                val n = c.members.size
                if (n == 1) singletons++
                if (n >= 3) { in3 += n; of3++ }
                var band = 0
                while (band + 1 < SIZE_BANDS.size && n >= SIZE_BANDS[band + 1]) band++
                sizes[band]++
            }
            val all = state.faces.values.sortedBy { it.observation.localHex }
            val corpus = evenly(all, MAX_CORPUS)
            val queries = evenly(all, MAX_QUERIES)
            val dim = state.dimension
            val units = FloatArray(corpus.size * dim)
            val scratch = FloatArray(dim)
            for ((i, f) in corpus.withIndex()) { f.unitInto(scratch); scratch.copyInto(units, i * dim) }
            val nn = IntArray(NN_BINS)
            var asked = 0
            if (corpus.size >= 2) for (q in queries) {
                q.unitInto(scratch)
                var best = Float.NEGATIVE_INFINITY
                for ((i, f) in corpus.withIndex()) {
                    if (f.observation == q.observation) continue
                    var dot = 0f
                    val base = i * dim
                    for (d in 0 until dim) dot += scratch[d] * units[base + d]
                    if (dot > best) best = dot
                }
                val bin = (best / NN_BIN_WIDTH).toInt().coerceIn(0, NN_BINS - 1)
                nn[bin]++
                asked++
            }
            val confirmed = state.reviews.confirmed.keys.count { it in state }
            return FaceClusterDiagnostics(state.faceCount, confirmed, state.unknownClusters.size,
                singletons, in3, of3, sizes, nn, asked, corpus.size, state.policy.fingerprint)
        }

        private fun <T> evenly(items: List<T>, max: Int): List<T> =
            if (items.size <= max) items
            else List(max) { items[(it.toLong() * items.size / max).toInt()] }
    }
}
