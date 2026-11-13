package nl.atlas.gallery

/**
 * A reversible, in-memory review plan. No operation here modifies MediaStore.
 * The UI must still request the Android system's Trash confirmation separately.
 */
data class DeclutterDecision(val media: MediaRecord, val stageForTrash: Boolean) {
    val mediaUri: String get() = media.uri
}

/**
 * Created ONLY after the folder builder has eliminated duplicate URIs and
 * produced an immutable copy. A normal List supplied by a caller is NOT
 * trusted and receives its own one-time snapshot and duplicate index.
 */
private class VerifiedUniqueDeclutterDeck(
    private val ordered: List<MediaRecord>
) : AbstractList<MediaRecord>() {
    override val size: Int get() = ordered.size
    override fun get(index: Int): MediaRecord = ordered[index]
}

/**
 * Stable source identity, never a mutable index attached to a historical
 * session. The production deck is already deduplicated on Dispatchers.Default,
 * so it needs no second index. An arbitrary caller's raw List is snapshotted
 * and indexed just once; a changed or swapped deck fails closed.
 */
private class DeclutterSource(
    val deck: List<MediaRecord>,
    val firstOccurrence: Map<String, Int>?,
    val frozen: List<MediaRecord>?
) {
    companion object {
        fun from(deck: List<MediaRecord>): DeclutterSource {
            if (deck is VerifiedUniqueDeclutterDeck)
                return DeclutterSource(deck, null, null)
            val stable = deck.toList()
            val seen = HashMap<String, Int>()
            stable.forEachIndexed { index, media -> seen.putIfAbsent(media.uri, index) }
            return DeclutterSource(deck, seen, stable)
        }
    }

    fun check(deckNow: List<MediaRecord>, position: Int, media: MediaRecord) {
        require(deckNow === deck) { "Review deck changed during a session" }
        if (frozen != null) {
            require(position in frozen.indices && frozen[position] == media) {
                "Review deck changed during a session"
            }
        }
        require(firstOccurrence == null || firstOccurrence[media.uri] == position) {
            "Duplicate media in a review deck"
        }
    }
}

/** Exact previous pointer: immutable decision history needs no repeated copying. */
private class ReviewedStep(
    val decision: DeclutterDecision,
    val previous: ReviewedStep?,
    val depth: Int
)

/**
 * Reversible, in-memory decision plan. The actual Android OS Trash dialog is
 * always required separately. Ordinary production Keep/Stage/Undo update only
 * a few references. No per-swipe URI tree or full-history list is retained.
 */
class DeclutterSession private constructor(
    private val tail: ReviewedStep?,
    private val source: DeclutterSource?,
    val unmarked: Set<String>,
    val stagedCount: Int,
    private val stagedByteTotal: Long
) {
    constructor() : this(null, null, emptySet(), 0, 0L)

    val position: Int get() = tail?.depth ?: 0

    /** Explicit O(reviewed) inspection; not used on the high-frequency swipe path. */
    val history: List<DeclutterDecision>
        get() {
            val rows = ArrayList<DeclutterDecision>(position)
            var current = tail
            while (current != null) {
                rows.add(current.decision)
                current = current.previous
            }
            rows.reverse()
            return rows
        }

    fun decide(deck: List<MediaRecord>, discard: Boolean): DeclutterSession {
        require(position in deck.indices) { "No unseen media remains" }
        val checkedSource = source ?: DeclutterSource.from(deck)
        val media = deck[position]
        checkedSource.check(deck, position, media)
        return DeclutterSession(
            ReviewedStep(DeclutterDecision(media, discard), tail, position + 1),
            checkedSource, unmarked,
            stagedCount + if (discard) 1 else 0,
            stagedByteTotal +
                if (discard) media.sizeBytes.coerceAtLeast(0L) else 0L
        )
    }

    fun undo(): DeclutterSession {
        val last = tail ?: return this
        val counted = last.decision.stageForTrash &&
            last.decision.mediaUri !in unmarked
        return DeclutterSession(
            last.previous,
            // Once fully rewound, a new folder/deck is allowed next time.
            if (last.previous == null) null else source,
            unmarked - last.decision.mediaUri,
            stagedCount - if (counted) 1 else 0,
            stagedByteTotal -
                if (counted) last.decision.media.sizeBytes.coerceAtLeast(0L) else 0L
        )
    }

    fun unstage(uri: String): DeclutterSession {
        var current = tail
        var stagedDecision: DeclutterDecision? = null
        while (current != null) {
            if (current.decision.mediaUri == uri && current.decision.stageForTrash) {
                stagedDecision = current.decision
                break
            }
            current = current.previous
        }
        requireNotNull(stagedDecision) {
            "Can unstage only media that was reviewed for Trash"
        }
        if (uri in unmarked) return this
        return DeclutterSession(
            tail, source, unmarked + uri,
            stagedCount - 1,
            stagedByteTotal - stagedDecision.media.sizeBytes.coerceAtLeast(0L)
        )
    }

    // Materialize only while explicitly inspecting/reviewing the pending files.
    fun staged(): List<MediaRecord> {
        val selected = ArrayList<MediaRecord>(stagedCount)
        var current = tail
        while (current != null) {
            val decision = current.decision
            if (decision.stageForTrash && decision.mediaUri !in unmarked)
                selected.add(decision.media)
            current = current.previous
        }
        selected.reverse()
        return selected
    }

    fun stagedBytes(): Long = stagedByteTotal
}

/** A *single exact physical folder*, no recursion, no implicit all-library session. */
fun folderDeclutterDeck(all: List<MediaRecord>, folder: String): List<MediaRecord> {
    require(folder.isNotBlank() && folder.endsWith('/')) { "Choose a physical folder first" }
    // The immutable wrapper certifies only the output of this exact builder.
    // Duplicate elimination is already done off the Compose thread by the
    // caller; the review screen need not retain historical URI AVL roots.
    return VerifiedUniqueDeclutterDeck(sortMedia(
        all.asSequence().filter { it.relativePath == folder }
            .distinctBy { it.uri }.toList(),
        SortOrder.LARGEST,
        0L
    ))
}


/**
 * Two adjacent previews max, independent of the archive size.
 * Both still images and video posters can warm via Coil; no video player
 * creation, full-resolution decoding or off-folder MediaStore access here.
 */
internal fun nextDeclutterPreviewIndices(position: Int, deckSize: Int): List<Int> {
    if (position < 0 || position >= deckSize - 1) return emptyList()
    val next = position + 1
    return if (next + 1 < deckSize) listOf(next, next + 1) else listOf(next)
}


/**
 * Opportunistic cache warmup must never interrupt review for one bad/stale
 * MediaStore item. Preserve coroutine cancellation so a fast swipe stops
 * obsolete decoding; retry later items when an individual preview fails.
 */
internal suspend fun prewarmDeclutterPreviewIndices(
    indices: List<Int>, fetch: suspend (Int) -> Unit
) {
    for (index in indices) {
        try { fetch(index) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preview is optional, review remains usable. */ }
    }
}

/**
 * Heavy media must be mounted only for a stationary current card.
 * The guard handles a drag event synchronously, before a canceled Compose
 * effect would otherwise clear the previous card's settled flag.
 */
internal fun shouldRenderDeclutterHeavy(
    settled: Boolean, dragging: Boolean, reviewing: Boolean, verifying: Boolean
): Boolean = settled && !dragging && !reviewing && !verifying
