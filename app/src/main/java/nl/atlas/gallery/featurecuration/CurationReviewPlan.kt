package nl.atlas.gallery.featurecuration

/**
 * Read-only Photo DNA → review handoff. Nothing here has MediaStore permissions.
 * Hashes must come from a trusted, grant-scoped full-byte reader. Geometry is
 * only a reason to open two photographs side by side, never a family or a
 * deletion instruction.
 */
data class CurationAsset(
    val uri: String,
    val revision: String,
    val bytes: Long,
    val title: String,
    val sha256: String? = null,
    val sha256AtRevision: String? = null,
    val favorite: Boolean = false,
    val userKept: Boolean = false,
    val peopleProtected: Boolean = false,
    val protectedFolder: Boolean = false
) {
    val protected: Boolean get() = favorite || userKept || peopleProtected || protectedFolder
}

enum class CurationPairKind { GEOMETRIC_CANDIDATE, GEOMETRIC_REVIEW, UNKNOWN }

/** A source-stamped direct observation; a cached match is never reusable after a grant or revision change. */
data class CurationPairEvidence(
    val leftUri: String,
    val rightUri: String,
    val kind: CurationPairKind,
    val observedScopeEpoch: String,
    val leftRevision: String,
    val rightRevision: String
)

data class CurationExactGroup(
    val key: String,
    val members: List<CurationAsset>,
    val suggestedKeeperUri: String,
    val keeperReason: String,
    val potentialBytes: Long
)

data class CurationVisualPair(val left: CurationAsset, val right: CurationAsset, val kind: CurationPairKind)

/** These URIs are for an *additional* final verification/OS consent flow, not a delete command. */
data class CurationTrashReview(
    val keeperUri: String,
    val selectedUris: List<String>,
    val selectedBytes: Long,
    val requiresFreshFullByteVerification: Boolean = true
)

class CurationReviewPlan private constructor(
    val scopeEpoch: String,
    val exactGroups: List<CurationExactGroup>,
    val visualPairs: List<CurationVisualPair>
) {
    /**
     * A user must explicitly pick every staged URI. Re-check the access scope,
     * entire group's live membership and revision metadata. The host MUST also
     * re-read every selected original and keeper to confirm fresh full-byte
     * equality, run DeclutterTrashPreflight, and ask Android for Trash consent.
     */
    fun prepareManualTrashReview(
        groupKey: String,
        keeperUri: String,
        explicitlySelectedUris: Set<String>,
        liveEpoch: String,
        liveAssets: List<CurationAsset>
    ): CurationTrashReview {
        require(liveEpoch == scopeEpoch) { "Media grant/catalogue changed; review again" }
        val group = exactGroups.singleOrNull { it.key == groupKey }
            ?: throw IllegalArgumentException("Unknown exact-byte group")
        require(group.members.any { it.uri == keeperUri }) { "Keeper must belong to this group" }
        require(explicitlySelectedUris.isNotEmpty() && explicitlySelectedUris.size <= 200) {
            "Select 1..200 files explicitly"
        }
        val current = liveAssets.associateBy { it.uri }
        require(current.size == liveAssets.size) { "Duplicate current URI" }
        val old = group.members.associateBy { it.uri }
        require(explicitlySelectedUris.all { it in old && it != keeperUri }) {
            "Invalid staged selection"
        }
        // Absent or changed items invalidate the WHOLE review, not just the
        // visible candidates, so no stale keeper comparison is silently used.
        for ((uri, previous) in old) {
            val now = current[uri] ?: throw IllegalArgumentException("Media unavailable")
            require(now == previous && validHash(now)) { "Media revision or protection changed" }
            require(now.sha256 == group.key) { "Full-byte evidence changed" }
        }
        require(explicitlySelectedUris.none { old.getValue(it).protected }) {
            "Protected media must never be staged from Photo DNA"
        }
        val selected = group.members.filter { it.uri in explicitlySelectedUris }
        val size = selected.fold(0L) { total, item -> checkedAdd(total, item.bytes) }
        return CurationTrashReview(keeperUri, selected.map { it.uri }, size)
    }

    companion object {
        private const val MAX_ITEMS = 250_000
        private const val MAX_PAIRS = 5_000

        fun build(
            scopeEpoch: String,
            media: List<CurationAsset>,
            suggestions: List<CurationPairEvidence>
        ): CurationReviewPlan {
            require(scopeEpoch.isNotBlank()) { "Grant-scoped catalogue epoch required" }
            require(media.size <= MAX_ITEMS && suggestions.size <= MAX_PAIRS) {
                "Curation work bound exceeded"
            }
            val byUri = HashMap<String, CurationAsset>(media.size)
            val byDigest = HashMap<String, MutableList<CurationAsset>>()
            for (item in media) {
                require(item.uri.isNotBlank() && item.revision.isNotBlank() &&
                    item.bytes >= 0L && item.title.isNotBlank()) { "Invalid media source" }
                require(byUri.putIfAbsent(item.uri, item) == null) { "Duplicate media URI" }
                if (validHash(item)) byDigest.getOrPut(item.sha256!!) { ArrayList() }.add(item)
            }
            val groups = byDigest.entries.asSequence().filter { it.value.size > 1 }.map { (digest, items) ->
                val members = items.sortedBy { it.uri }
                // Protected photos are kept ahead of ordinary copies. Within
                // each protection tier use stable URI, not a random/size sort.
                val keeper = members.firstOrNull { it.userKept }
                    ?: members.firstOrNull { it.peopleProtected }
                    ?: members.firstOrNull { it.favorite }
                    ?: members.firstOrNull { it.protectedFolder }
                    ?: members.first()
                val reason = when {
                    keeper.userKept -> PhotoDnaCopy.KEEPER_USER
                    keeper.peopleProtected -> PhotoDnaCopy.KEEPER_PEOPLE
                    keeper.favorite -> PhotoDnaCopy.KEEPER_FAVOURITE
                    keeper.protectedFolder -> PhotoDnaCopy.KEEPER_PROTECTED_FOLDER
                    else -> PhotoDnaCopy.KEEPER_DEFAULT
                }
                // Estimate only: never count protected or chosen keeper as
                // reclaimable, never imply that bytes have been freed.
                val potential = members.filter { it.uri != keeper.uri && !it.protected }
                    .fold(0L) { sum, item -> checkedAdd(sum, item.bytes) }
                CurationExactGroup(digest, members, keeper.uri, reason, potential)
            }.sortedWith(compareByDescending<CurationExactGroup> { it.potentialBytes }.thenBy { it.key }).toList()
            val seen = HashSet<Pair<String, String>>()
            val pairCards = ArrayList<CurationVisualPair>(suggestions.size)
            for (suggestion in suggestions) {
                require(suggestion.leftUri != suggestion.rightUri) { "Self pair" }
                val pair = listOf(suggestion.leftUri, suggestion.rightUri).sorted()
                require(seen.add(pair[0] to pair[1])) { "Duplicate visual pair" }
                val rawLeft = byUri[suggestion.leftUri] ?: throw IllegalArgumentException("Unknown left URI")
                val rawRight = byUri[suggestion.rightUri] ?: throw IllegalArgumentException("Unknown right URI")
                require(suggestion.observedScopeEpoch == scopeEpoch &&
                    suggestion.leftRevision == rawLeft.revision &&
                    suggestion.rightRevision == rawRight.revision) {
                    "Stale geometric evidence; recompute after the source or grant changed"
                }
                val left = byUri.getValue(pair[0])
                val right = byUri.getValue(pair[1])
                // Confirmed byte-identical photos already have an exact group.
                if (validHash(left) && validHash(right) && left.sha256 == right.sha256) continue
                pairCards.add(CurationVisualPair(left, right, suggestion.kind))
            }
            pairCards.sortWith(compareBy<CurationVisualPair> { it.kind.ordinal }
                .thenBy { it.left.uri }.thenBy { it.right.uri })
            return CurationReviewPlan(scopeEpoch, groups, pairCards)
        }
    }
}

private val SHA256_REGEX = Regex("[0-9a-f]{64}")
private fun validHash(item: CurationAsset): Boolean =
    item.sha256 != null && SHA256_REGEX.matches(item.sha256) &&
        item.sha256AtRevision == item.revision

private fun checkedAdd(a: Long, b: Long): Long =
    try { Math.addExact(a, b) }
    catch (_: ArithmeticException) { throw IllegalArgumentException("Curation byte total overflow") }
