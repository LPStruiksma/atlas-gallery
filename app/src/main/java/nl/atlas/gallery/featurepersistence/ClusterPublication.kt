package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding

/**
 * Pure (no SQLite) half of D2-5p cluster publication (#419), shared by
 * [FaceIndexStore] and the JVM differential tests so both run the same code.
 *
 * Per photo, [FaceIndexStore.completeEmbeddings] only writes that photo's rows
 * and marks the generation dirty: its work does not depend on library size.
 * Cluster membership is published in batches:
 *
 *     next = FaceClusterer.update(restore(published membership), add = pending faces)
 *
 * Accepted deviation from the pre-D2-5p store, which ran one update per
 * completed photo: [FaceClusterer] documents that splitting a batch into
 * several updates may group borderline faces differently (never against a
 * review fact). A batch therefore equals the old store exactly when it holds
 * one photo, and otherwise equals the old store as if those photos had been
 * one photo. Unlike the old store, the result does not depend on the order in
 * which photos in a batch completed. ClusterPublicationDifferentialTest pins
 * this: identical clusters on gallery-like synthetic identities at realistic
 * noise, a few borderline faces split differently at heavy noise (no mixed
 * clusters either way), and on adversarial evenly spread directions the batch
 * is the more conservative one (one-photo updates chain them together).
 */
object ClusterPublication {

    /**
     * One publication: the published seed plus every pending face in one
     * update, then #579's consolidation pass (weak faces join the person they
     * resemble, fragments of one person are unioned).
     */
    fun publish(seed: FaceClusterState, pending: Collection<FaceEmbedding>): FaceClusterState =
        if (pending.isEmpty()) seed
        else FaceClusterer.consolidate(FaceClusterer.update(seed, add = pending))

    /**
     * Rows of `cluster_members` (face uid to anchor uid) that [state] implies.
     *
     * #580: a face matched to a named person ([FaceClusterState.matched]) is
     * unnamed for the store: it keeps a row of its own, anchored on itself,
     * so every face is still either confirmed or a member. Restoring such a
     * row gives a one-face group, and the update that follows matches it
     * again. No schema change, and a store read by an older build sees a
     * lone unnamed face.
     */
    fun membership(state: FaceClusterState): Map<String, String> {
        val rows = HashMap<String, String>(state.faceCount * 2)
        for (cluster in state.unknownClusters) {
            val anchor = cluster.id.anchor.localHex
            for (m in cluster.members) check(rows.put(m.localHex, anchor) == null) {
                "Face in two clusters"
            }
        }
        for (face in state.matched.keys) check(rows.put(face.localHex, face.localHex) == null) {
            "Matched face in a cluster"
        }
        return rows
    }

    /** The row changes that turn [old] into [new]; unchanged rows are not written. */
    class Delta(val delete: List<String>, val upsert: Map<String, String>) {
        val rows: Int get() = delete.size + upsert.size
    }

    fun delta(old: Map<String, String>, new: Map<String, String>): Delta {
        val delete = old.keys.filter { it !in new }.sorted()
        val upsert = new.filter { (face, anchor) -> old[face] != anchor }.toSortedMap()
        return Delta(delete, upsert)
    }
}

/**
 * When to publish (#419 "name the cadence"). A dirty generation is published
 * when the scan finishes, or once at least
 * `max(MIN_BATCH_PHOTOS, publishedFaces / GROWTH_DIVISOR)` photos completed
 * since the last publication.
 *
 * Why this caps total work: publication k reads the N(k-1) faces published
 * before it plus the faces of its own p(k) photos (at most 32 each), and
 * N(k-1) <= GROWTH_DIVISOR * p(k) by the rule. So each publication reads at
 * most (GROWTH_DIVISOR + 32) faces per photo it covers, and a whole scan of L
 * photos reads at most (GROWTH_DIVISOR + 32) * L faces, plus one final
 * publication. Library-wide work grows linearly, not quadratically.
 * [FaceClusterer] CPU itself is outside this bound: its worst case (all
 * strangers) is quadratic whatever the cadence (see FaceClustererBenchmarkTest).
 */
object ClusterPublicationCadence {
    const val MIN_BATCH_PHOTOS = 256
    const val GROWTH_DIVISOR = 4

    fun due(dirty: Boolean, pendingPhotos: Int, publishedFaces: Int, scanFinished: Boolean): Boolean {
        require(pendingPhotos >= 0 && publishedFaces >= 0)
        if (!dirty) return false
        if (scanFinished) return true
        return pendingPhotos >= maxOf(MIN_BATCH_PHOTOS, publishedFaces / GROWTH_DIVISOR)
    }
}

/**
 * #580: the key of one picture for [FaceEmbedding.frame]: a photo, or one
 * keyframe of a video. 64-bit FNV-1a of the media key and frame time, never
 * 0 (0 means unknown). Two faces with the same key were found in the same
 * picture; a chance collision between different pictures (about 1 in 10^8
 * pairs at 400,000 pictures) can only keep two faces apart, never join them.
 */
object ClusterFrameKey {
    fun of(mediaKey: String, frameMs: Long?): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        fun mix(b: Int) { h = (h xor (b and 0xff).toLong()) * 0x100000001b3L }
        for (ch in mediaKey) { mix(ch.code); mix(ch.code ushr 8) }
        mix(0xff)
        val at = frameMs ?: -1L
        for (shift in 0 until 64 step 8) mix((at ushr shift).toInt())
        return if (h == 0L) 1L else h
    }
}
