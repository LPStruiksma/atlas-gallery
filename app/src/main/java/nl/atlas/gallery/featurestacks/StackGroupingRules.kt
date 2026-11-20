package nl.atlas.gallery.featurestacks

import java.util.Locale

/*
 * STACK-0 (#574): display-only stack grouping. Pure Kotlin, no Android, no
 * I/O. The caller hands an ordered list of already-known media records (for
 * example the Pictures grid order) plus any Photo DNA dHash it already has;
 * this file returns a view projection that collapses bursts, RAW companions,
 * edited copies and near-identical shots into one tile each.
 *
 * Nothing here moves, hides, deletes, edits or persists anything, and no
 * second similarity index is computed: the dHash is input only. Unstacking
 * is exact: [StackProjection.unstacked] returns the input ids in input order.
 *
 * The rules are deliberately conservative. A missed stack costs a little
 * scrolling; a wrong stack hides a distinct shot. Rules and thresholds are
 * described in docs/product/STACKS.md.
 */

enum class StackMediaKind { PHOTO, VIDEO }

/**
 * One media item as the grid already knows it.
 *
 * [directory] is the item's folder (relative path or bucket); files are only
 * ever stacked inside one directory. [captureTimeMs] is null when unknown,
 * and an unknown time never satisfies a time rule. [dHash] is the existing
 * Photo DNA 64-bit difference hash, and [dHashInformative] is false for flat
 * images (see CurationVisualHash), which never count as a visual match.
 * [sharpness] and [faceQuality] are optional 0..1 observations used only for
 * the top pick. [originalId] is explicit edit lineage recorded by an editor
 * (for example the ATLAS editor's save-as-copy).
 */
data class StackMediaRecord(
    val id: Long,
    val directory: String,
    val displayName: String,
    val kind: StackMediaKind,
    val captureTimeMs: Long?,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val dHash: Long? = null,
    val dHashInformative: Boolean = false,
    val sharpness: Float? = null,
    val faceQuality: Float? = null,
    val originalId: Long? = null
) {
    init {
        require(sizeBytes >= 0) { "Negative size" }
        require(width >= 0 && height >= 0) { "Negative dimensions" }
        require(sharpness == null || (sharpness.isFinite() && sharpness in 0f..1f)) { "Sharpness outside 0..1" }
        require(faceQuality == null || (faceQuality.isFinite() && faceQuality in 0f..1f)) { "Face quality outside 0..1" }
    }
}

enum class StackReason { RAW_COMPANION, EDITED_ORIGINAL, BURST, NEAR_IDENTICAL }

/** One tile in the stacked view. */
sealed class StackTile {
    /** Ids in input order. */
    abstract val memberIds: List<Long>

    /** The id the tile shows. */
    abstract val coverId: Long

    data class Single(val id: Long) : StackTile() {
        override val memberIds: List<Long> get() = listOf(id)
        override val coverId: Long get() = id
    }

    data class Stack(
        val reason: StackReason,
        override val memberIds: List<Long>,
        val topPickId: Long,
        /** True when [topPickId] came from the user's override. */
        val userPicked: Boolean
    ) : StackTile() {
        init {
            require(memberIds.size >= 2) { "A stack has at least two members" }
            require(topPickId in memberIds) { "Top pick must be a member" }
        }
        override val coverId: Long get() = topPickId
    }
}

data class StackProjection(
    /** Tiles in display order: each stack sits where its first member was. */
    val tiles: List<StackTile>,
    private val inputOrder: List<Long>
) {
    /** "Unstack everything": exactly the input ids, in input order. */
    fun unstacked(): List<Long> = inputOrder

    val stacks: List<StackTile.Stack> get() = tiles.filterIsInstance<StackTile.Stack>()
}

/** Aggregate counts only: no ids, names, times or hashes. */
data class StackAggregate(
    val originalTiles: Int,
    val stackCount: Int,
    val itemsInStacks: Int,
    val tilesSaved: Int
) {
    /** Share of original tiles that collapse away, 0..100. */
    val percentSaved: Double
        get() = if (originalTiles == 0) 0.0 else tilesSaved * 100.0 / originalTiles
}

object StackGroupingRules {
    /** RAW and its JPEG/HEIC companion must be captured this close together. */
    const val RAW_COMPANION_MAX_SKEW_MS: Long = 2_000

    /** Adjacent burst frames are at most this far apart... */
    const val BURST_MAX_GAP_MS: Long = 2_000

    /** ...and a whole burst spans at most this long. */
    const val BURST_MAX_SPAN_MS: Long = 30_000

    /** Near-identical: each new member within this many ms of the previous one... */
    const val NEAR_MAX_GAP_MS: Long = 3_000

    /** ...the whole stack within this span... */
    const val NEAR_MAX_SPAN_MS: Long = 10_000

    /**
     * ...and within this many dHash bits of EVERY member (complete linkage,
     * so A~B, B~C, A!~C never chains). Stricter than Photo DNA's 5-bit
     * "possible visual match" because a stack hides frames without review.
     */
    const val NEAR_MAX_DHASH_DISTANCE: Int = 4

    /** Aspect ratios (long/short edge) may differ by at most this fraction. */
    const val NEAR_MAX_ASPECT_DELTA: Double = 0.02

    /** An edited copy found by file name must stay this close to its original. */
    const val EDITED_MAX_DHASH_DISTANCE: Int = 16

    /** Larger near-identical runs are split; a stack stays reviewable. */
    const val MAX_STACK_SIZE: Int = 200

    val RAW_EXTENSIONS: Set<String> =
        setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "raf", "pef", "srw", "x3f", "rwl", "3fr", "iiq")
    val COMPANION_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "heic", "heif")

    /** Samsung burst frames: `20241105_143022_001.jpg`. */
    private val SAMSUNG_BURST = Regex("""^(\d{8}_\d{6})_(\d{3})$""")

    /** Google Camera bursts: `00001IMG_00001_BURST20241105143022123[_COVER].jpg`. */
    private val GOOGLE_BURST = Regex("""^\d+[A-Z]{3}_\d+_BURST(\d{17})(?:_COVER)?$""")

    private val EDITED_SUFFIX = Regex("""^(.+)[-_]edited$""", RegexOption.IGNORE_CASE)

    /**
     * Groups [records] (in display order) into tiles. Each record joins at
     * most one stack; rules are tried in [StackReason] order, so a RAW+JPEG
     * pair is claimed before burst or near-identical grouping. Videos never
     * stack. [topPickOverrides] maps any member id to the user's chosen cover;
     * it wins when the chosen id is a member of that stack.
     */
    fun group(
        records: List<StackMediaRecord>,
        topPickOverrides: Map<Long, Long> = emptyMap()
    ): StackProjection {
        val ids = records.map { it.id }
        require(ids.toSet().size == ids.size) { "Duplicate media id" }
        val order = HashMap<Long, Int>(records.size * 2)
        records.forEachIndexed { index, record -> order[record.id] = index }

        val claimed = HashSet<Long>()
        val groups = ArrayList<Pair<StackReason, List<StackMediaRecord>>>()
        fun take(reason: StackReason, members: List<StackMediaRecord>) {
            if (members.size < 2 || members.any { it.id in claimed }) return
            members.forEach { claimed += it.id }
            groups += reason to members.sortedBy { order.getValue(it.id) }
        }

        val photosByDirectory = records.filter { it.kind == StackMediaKind.PHOTO }.groupBy { it.directory }
        val byId = records.associateBy { it.id }
        for ((_, photos) in photosByDirectory) rawCompanions(photos).forEach { take(StackReason.RAW_COMPANION, it) }
        for ((_, photos) in photosByDirectory) {
            editedPairs(photos.filter { it.id !in claimed }, byId).forEach { take(StackReason.EDITED_ORIGINAL, it) }
        }
        for ((_, photos) in photosByDirectory) bursts(photos.filter { it.id !in claimed }).forEach { take(StackReason.BURST, it) }
        for ((_, photos) in photosByDirectory) {
            nearIdentical(photos.filter { it.id !in claimed }).forEach { take(StackReason.NEAR_IDENTICAL, it) }
        }

        val stackOfFirst = HashMap<Long, StackTile.Stack>()
        for ((reason, members) in groups) {
            val override = members.firstNotNullOfOrNull { topPickOverrides[it.id] }?.takeIf { pick -> members.any { it.id == pick } }
            val pick = override ?: topPick(reason, members)
            stackOfFirst[members.first().id] = StackTile.Stack(reason, members.map { it.id }, pick, override != null)
        }
        val tiles = ArrayList<StackTile>(records.size)
        for (record in records) {
            when {
                record.id in stackOfFirst -> tiles += stackOfFirst.getValue(record.id)
                record.id !in claimed -> tiles += StackTile.Single(record.id)
            }
        }
        return StackProjection(tiles, ids)
    }

    fun aggregate(projection: StackProjection): StackAggregate {
        val stacks = projection.stacks
        val inStacks = stacks.sumOf { it.memberIds.size }
        return StackAggregate(
            originalTiles = projection.unstacked().size,
            stackCount = stacks.size,
            itemsInStacks = inStacks,
            tilesSaved = inStacks - stacks.size
        )
    }

    /**
     * Cover choice: for RAW+companion the displayable JPEG/HEIC, for an edit
     * the edited copy; then the sharpest frame, then the best face quality,
     * then the largest file, then the smallest id. A missing observation
     * ranks below any measured value. Sharpness is compared at 0.01 steps so
     * that face quality can decide between equally sharp frames.
     */
    fun topPick(reason: StackReason, members: List<StackMediaRecord>): Long {
        require(members.isNotEmpty())
        val preferred = when (reason) {
            StackReason.RAW_COMPANION -> members.filter { extensionOf(it.displayName) in COMPANION_EXTENSIONS }
            StackReason.EDITED_ORIGINAL -> members.filter { isEditedCopy(it, members) }
            else -> members
        }.ifEmpty { members }
        val best = preferred.sortedWith(
            compareByDescending<StackMediaRecord> { it.sharpness?.let { s -> Math.round(s * 100.0) } ?: -1L }
                .thenByDescending { it.faceQuality ?: -1f }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.id }
        ).first()
        return best.id
    }

    // --- Rule (b): RAW + JPEG/HEIC with the same base name -----------------

    private fun rawCompanions(photos: List<StackMediaRecord>): List<List<StackMediaRecord>> =
        photos.groupBy { stemOf(it.displayName).lowercase(Locale.ROOT) }.values.mapNotNull { sameStem ->
            val raws = sameStem.filter { extensionOf(it.displayName) in RAW_EXTENSIONS }
            val companions = sameStem.filter { extensionOf(it.displayName) in COMPANION_EXTENSIONS }
            val members = raws + companions
            val times = members.map { it.captureTimeMs }
            when {
                raws.isEmpty() || companions.isEmpty() -> null
                times.any { it == null } -> null
                times.maxOf { it!! } - times.minOf { it!! } > RAW_COMPANION_MAX_SKEW_MS -> null
                else -> members
            }
        }

    // --- Rule (c): an edited copy with its original ------------------------

    private fun editedPairs(
        photos: List<StackMediaRecord>,
        byId: Map<Long, StackMediaRecord>
    ): List<List<StackMediaRecord>> {
        val result = ArrayList<List<StackMediaRecord>>()
        val used = HashSet<Long>()
        val photoIds = photos.mapTo(HashSet()) { it.id }
        // Explicit lineage first: the editor recorded which file it came from.
        for (copy in photos) {
            val original = copy.originalId?.let { byId[it] } ?: continue
            if (original.id !in photoIds || original.id == copy.id) continue
            if (copy.id in used || original.id in used) continue
            used += copy.id
            used += original.id
            result += listOf(original, copy)
        }
        // Then the documented "-edited"/"_edited" suffix, guarded by the dHash.
        val byStem = photos.filter { it.id !in used }.groupBy { stemOf(it.displayName).lowercase(Locale.ROOT) }
        for (copy in photos) {
            if (copy.id in used) continue
            val base = EDITED_SUFFIX.matchEntire(stemOf(copy.displayName))?.groupValues?.get(1) ?: continue
            val candidates = byStem[base.lowercase(Locale.ROOT)].orEmpty().filter { it.id !in used && it.id != copy.id }
            val original = candidates.singleOrNull() ?: continue // ambiguous base name: no stack
            if (!visuallyClose(original, copy, EDITED_MAX_DHASH_DISTANCE)) continue
            used += copy.id
            used += original.id
            result += listOf(original, copy)
        }
        return result
    }

    private fun isEditedCopy(record: StackMediaRecord, members: List<StackMediaRecord>): Boolean =
        (record.originalId != null && members.any { it.id == record.originalId }) ||
            EDITED_SUFFIX.matches(stemOf(record.displayName))

    // --- Rule (a): vendor burst --------------------------------------------

    private fun bursts(photos: List<StackMediaRecord>): List<List<StackMediaRecord>> {
        val byKey = photos.mapNotNull { record -> burstKey(record.displayName)?.let { it to record } }
            .groupBy({ it.first }, { it.second })
        val result = ArrayList<List<StackMediaRecord>>()
        for ((_, frames) in byKey) {
            val timed = frames.filter { it.captureTimeMs != null }.sortedWith(compareBy({ it.captureTimeMs }, { it.id }))
            result += splitRuns(timed, BURST_MAX_GAP_MS, BURST_MAX_SPAN_MS) { _, _ -> true }
        }
        return result
    }

    internal fun burstKey(displayName: String): String? {
        val stem = stemOf(displayName)
        SAMSUNG_BURST.matchEntire(stem)?.let { return "samsung:" + it.groupValues[1] }
        GOOGLE_BURST.matchEntire(stem)?.let { return "google:" + it.groupValues[1] }
        return null
    }

    // --- Rule (d): near-identical shots within N seconds --------------------

    private fun nearIdentical(photos: List<StackMediaRecord>): List<List<StackMediaRecord>> {
        val timed = photos.filter { it.captureTimeMs != null && it.dHash != null && it.dHashInformative }
            .sortedWith(compareBy({ it.captureTimeMs }, { it.id }))
        return splitRuns(timed, NEAR_MAX_GAP_MS, NEAR_MAX_SPAN_MS) { member, candidate ->
            visuallyClose(member, candidate, NEAR_MAX_DHASH_DISTANCE) && sameAspect(member, candidate)
        }
    }

    /**
     * Greedy runs over time-sorted records. A candidate joins the open run
     * when it is within [maxGap] of the run's last member, within [maxSpan] of
     * its first, and [compatible] with EVERY member (complete linkage). A
     * candidate that fails starts a new run, so nothing chains.
     */
    private fun splitRuns(
        sorted: List<StackMediaRecord>,
        maxGap: Long,
        maxSpan: Long,
        compatible: (StackMediaRecord, StackMediaRecord) -> Boolean
    ): List<List<StackMediaRecord>> {
        val runs = ArrayList<List<StackMediaRecord>>()
        var run = ArrayList<StackMediaRecord>()
        for (candidate in sorted) {
            val t = candidate.captureTimeMs!!
            val fits = run.isNotEmpty() &&
                run.size < MAX_STACK_SIZE &&
                t - run.last().captureTimeMs!! <= maxGap &&
                t - run.first().captureTimeMs!! <= maxSpan &&
                run.all { compatible(it, candidate) }
            if (!fits) {
                if (run.size >= 2) runs += run
                run = ArrayList()
            }
            run += candidate
        }
        if (run.size >= 2) runs += run
        return runs
    }

    private fun visuallyClose(a: StackMediaRecord, b: StackMediaRecord, maxDistance: Int): Boolean {
        val ha = a.dHash ?: return false
        val hb = b.dHash ?: return false
        if (!a.dHashInformative || !b.dHashInformative) return false
        return java.lang.Long.bitCount(ha xor hb) <= maxDistance
    }

    /** Unknown dimensions are allowed; known ones must share orientation and ratio. */
    private fun sameAspect(a: StackMediaRecord, b: StackMediaRecord): Boolean {
        if (a.width == 0 || a.height == 0 || b.width == 0 || b.height == 0) return true
        if ((a.width >= a.height) != (b.width >= b.height)) return false
        val ra = maxOf(a.width, a.height).toDouble() / minOf(a.width, a.height)
        val rb = maxOf(b.width, b.height).toDouble() / minOf(b.width, b.height)
        return kotlin.math.abs(ra - rb) / minOf(ra, rb) <= NEAR_MAX_ASPECT_DELTA
    }

    internal fun stemOf(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot <= 0) displayName else displayName.substring(0, dot)
    }

    internal fun extensionOf(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot <= 0 || dot == displayName.length - 1) "" else displayName.substring(dot + 1).lowercase(Locale.ROOT)
    }
}
