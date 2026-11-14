package nl.atlas.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featurecuration.PhotoDnaCopy
import nl.atlas.gallery.featurecuration.CurationAsset
import nl.atlas.gallery.featurecuration.CurationByteDigest
import nl.atlas.gallery.featurecuration.CurationExactGroup
import nl.atlas.gallery.featurecuration.CurationPairEvidence
import nl.atlas.gallery.featurecuration.CurationPairKind
import nl.atlas.gallery.featurecuration.CurationReviewPlan
import nl.atlas.gallery.featurecuration.CurationReviewRequest
import nl.atlas.gallery.featurecuration.CurationVisualHash
import nl.atlas.gallery.featurecuration.PhotoDnaReviewScreen
import nl.atlas.gallery.featurecuration.curationDHash
import nl.atlas.gallery.featurecuration.findCurationVisualPairs
import nl.atlas.gallery.featurecuration.hashCurationBytes
import nl.atlas.gallery.featuredesign.AtlasSpace
import nl.atlas.gallery.featurepersistence.CurationKeepStore
import nl.atlas.gallery.featurepersistence.KeepReceipt
import nl.atlas.gallery.featurepersistence.KeepReceiptContract
import nl.atlas.gallery.featurepersistence.VerifiedKeepFamily
import nl.atlas.gallery.featurepersistence.VerifiedKeepMember
import java.io.IOException

/*
 * E2b (#442): host wiring that makes lane C Photo DNA reachable from the
 * Albums tab. The plan is derived from the already-authorized MediaRecord
 * publication plus full-byte digests of files that share an exact size. It
 * is held in memory; nothing here deletes or moves anything.
 *
 * E2d (#445): an explicit Keep tap is saved as a CurationKeepStore receipt,
 * only after a fresh full-byte read of the whole family. A receipt that still
 * matches the live family marks that keeper as "Previously marked Keep",
 * which only PROTECTS it; it never selects or authorizes anything for Trash.
 *
 * E2e (#450): "possible visual matches" come from a 64-bit dHash of each
 * authorized image's small, orientation-corrected MediaStore thumbnail,
 * computed off main after the byte plan is shown and kept in memory only.
 * Pairs are found through hash bands, never an all-pairs scan, and each one
 * is evidence stamped with the scope epoch and both revisions. A visual pair
 * only offers "Compare photos"; it never stages anything for Trash.
 *
 * E2g (#456): a thumbnail decode that fails is remembered per URI and
 * revision for the session (memory only, same grant binding as the hashes),
 * so it no longer takes a decode slot on every pass. While images are still
 * deferred, Photo DNA keeps running bounded passes while it is open, and an
 * unfinished chain resumes on the next opening without a new publication.
 */

/** One scope token per authorized publication and source list. */
internal data class PhotoDnaTokens(val scopeEpoch: String)

/** Null whenever the gallery is not currently authorized: then no plan is shown. */
internal fun photoDnaTokensOrNull(
    grant: GalleryMediaGrant,
    publicationEpoch: Long,
    authorized: Boolean,
    source: List<MediaRecord>
): PhotoDnaTokens? {
    if (!authorized || !grant.hasAny || publicationEpoch < 0L) return null
    val scope = buildString {
        if (grant.images) append('i')
        if (grant.videos) append('v')
        if (grant.selected) append('s')
    }
    // Identity, not List.equals: never compare 150k records on the main thread.
    return PhotoDnaTokens("dna-$publicationEpoch-$scope-${source.size}-" +
        System.identityHashCode(source))
}

/** Bounds for one planning pass. A class of equal-size files is hashed whole or not at all. */
internal data class PhotoDnaBudget(
    val maxFiles: Int = 2_000,
    val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxFileBytes: Long = 512L * 1024 * 1024
)

/** Metadata that a fresh full-byte digest is bound to. Any change makes it stale. */
internal fun photoDnaRevision(record: MediaRecord): String =
    "${record.id}:${record.sizeBytes}:${record.modifiedAtSeconds}:${record.generationModified}"

/**
 * Only plain MediaStore items with trustworthy size and modification time can
 * reach the final Trash preflight, so only those are worth hashing. Trashed,
 * pending and dot-folder rows are never offered.
 */
internal fun photoDnaEligible(record: MediaRecord, budget: PhotoDnaBudget = PhotoDnaBudget()): Boolean {
    if (record.sizeBytes <= 0L || record.sizeBytes > budget.maxFileBytes ||
        record.modifiedAtSeconds <= 0L || record.id <= 0L) return false
    if (record.name.startsWith(".trashed-") || record.name.startsWith(".pending-")) return false
    if (record.relativePath.split('/').any { it.startsWith(".") }) return false
    return record.uri.startsWith("content://" + MediaStore.AUTHORITY + "/")
}

internal data class PhotoDnaCandidates(
    /** Files to hash, grouped by size class (each class has at least two files). */
    val records: List<MediaRecord>,
    /** Files that shared a size but did not fit this pass's budget. */
    val deferredFiles: Int
)

/**
 * Ignore every occurrence of a URI that the catalogue repeats. Even if two
 * copies agree on metadata, including both would make the review plan reject
 * duplicate URIs and crash its uncaught UI planning coroutine. If they disagree,
 * choosing one would be unsafe for a later destructive preflight. Fail closed:
 * neither occurrence can be offered until a fresh catalogue is consistent.
 * Also used for visual hash candidates, whose pair keys require unique URIs.
 */
internal fun photoDnaDistinctEligible(
    source: List<MediaRecord>,
    budget: PhotoDnaBudget = PhotoDnaBudget(),
    checkpoint: () -> Unit = {}
): List<MediaRecord> {
    val seen = HashSet<String>()
    val duplicated = HashSet<String>()
    source.forEachIndexed { index, record ->
        if ((index and 1023) == 0) checkpoint()
        if (!seen.add(record.uri)) duplicated.add(record.uri)
    }
    val eligible = ArrayList<MediaRecord>()
    source.forEachIndexed { index, record ->
        if ((index and 1023) == 0) checkpoint()
        if (record.uri !in duplicated && photoDnaEligible(record, budget)) eligible.add(record)
    }
    return eligible
}

/**
 * Two files can only be byte-identical when their sizes match. Classes are
 * taken largest-potential-saving first and only while the whole class fits
 * the budget, so a class is never half-checked.
 */
internal fun photoDnaCandidates(
    source: List<MediaRecord>,
    budget: PhotoDnaBudget = PhotoDnaBudget(),
    checkpoint: () -> Unit = {}
): PhotoDnaCandidates {
    val bySize = HashMap<Long, MutableList<MediaRecord>>()
    photoDnaDistinctEligible(source, budget, checkpoint).forEachIndexed { index, record ->
        if ((index and 1023) == 0) checkpoint()
        bySize.getOrPut(record.sizeBytes) { ArrayList(2) }.add(record)
    }
    val classes = bySize.entries.filter { it.value.size > 1 }
        .sortedWith(compareByDescending<Map.Entry<Long, MutableList<MediaRecord>>> {
            it.key * (it.value.size - 1)
        }.thenByDescending { it.key })
    val chosen = ArrayList<MediaRecord>()
    var bytes = 0L
    var deferred = 0
    for ((size, members) in classes) {
        val classBytes = size * members.size
        if (chosen.size + members.size > budget.maxFiles || classBytes > budget.maxTotalBytes - bytes) {
            deferred += members.size
            continue
        }
        members.sortBy { it.uri }
        chosen += members
        bytes += classBytes
    }
    return PhotoDnaCandidates(chosen, deferred)
}

/** A digest counts only when it covered exactly the bytes the catalogue reported. */
internal fun photoDnaAsset(record: MediaRecord, digest: CurationByteDigest?): CurationAsset {
    val revision = photoDnaRevision(record)
    val bound = digest?.takeIf { it.bytes == record.sizeBytes }
    return CurationAsset(
        uri = record.uri,
        revision = revision,
        bytes = record.sizeBytes,
        title = record.name.ifBlank { "Untitled" },
        sha256 = bound?.sha256,
        sha256AtRevision = bound?.let { revision },
        favorite = record.isFavorite && record.favoriteKnown
    )
}

internal class PhotoDnaResult(
    val tokens: PhotoDnaTokens,
    plan: CurationReviewPlan,
    /** Catalogue rows behind the plan, for thumbnails and the final recheck. */
    records: Map<String, MediaRecord>,
    val checkedFiles: Int,
    val deferredFiles: Int
) {
    /**
     * Replaced only by [markKept] and [addVisualPairs] (main thread), so a
     * saved Keep shows at once and on reopening. Readers take one snapshot
     * (`val plan = result.plan`).
     */
    var plan: CurationReviewPlan by mutableStateOf(plan, referentialEqualityPolicy())
        private set

    var records: Map<String, MediaRecord> by mutableStateOf(records, referentialEqualityPolicy())
        private set

    /** True once a visual pass ran for these tokens (even one that found nothing). */
    var visualPlanned: Boolean = false
        private set

    /** Images the last visual pass left for a later pass (decode budget reached). */
    var visualDeferred: Int = 0
        private set

    /** Another visual pass is due: none ran yet, or the last one left images waiting. */
    val needsVisualPass: Boolean get() = !visualPlanned || visualDeferred > 0

    private var visualEvidence: List<CurationPairEvidence> = emptyList()
    private var visualAssets: List<CurationAsset> = emptyList()

    /**
     * After a receipt was saved for this exact group: mark that keeper, and
     * only it, as kept. The group's digest and members are unchanged, so
     * groups and keys stay the same; a group that is gone is left alone.
     * Visual pairs are kept: their evidence still names the same revisions.
     */
    fun markKept(groupKey: String, keeperUri: String): Boolean {
        val current = plan
        val group = current.exactGroups.singleOrNull { it.key == groupKey } ?: return false
        if (group.members.none { it.uri == keeperUri }) return false
        val members = group.members.mapTo(HashSet()) { it.uri }
        plan = rebuild(current) { asset ->
            if (asset.uri in members) asset.copy(userKept = asset.uri == keeperUri) else asset
        }
        return true
    }

    /** Adds one visual pass's pairs; it must come from this result's tokens and plan. */
    fun addVisualPairs(pass: PhotoDnaVisualPass) {
        visualPlanned = true
        visualDeferred = pass.deferredFiles
        if (pass.evidence.isEmpty()) return
        visualEvidence = pass.evidence
        visualAssets = pass.assets
        records = records + pass.records
        plan = rebuild(plan) { it }
    }

    private fun rebuild(current: CurationReviewPlan, map: (CurationAsset) -> CurationAsset): CurationReviewPlan {
        val assets = LinkedHashMap<String, CurationAsset>()
        current.exactGroups.forEach { group -> group.members.forEach { assets.putIfAbsent(it.uri, map(it)) } }
        visualAssets.forEach { assets.putIfAbsent(it.uri, map(it)) }
        return CurationReviewPlan.build(current.scopeEpoch, assets.values.toList(), visualEvidence)
    }
}

/** Bounds for one visual pass: new thumbnail decodes, pairs shown and Hamming checks. */
internal data class PhotoDnaVisualBudget(
    val maxDecodes: Int = 2_000,
    val maxPairs: Int = 200,
    val maxComparisons: Long = 25_000_000L
)

/**
 * In-memory perceptual hashes per URI, valid only for the revision they were
 * computed at, plus the URIs whose decode failed at a revision (#456). Bound
 * to one media grant: a different grant clears both, and a revoke (no
 * tokens) clears them too. Never written to disk.
 */
internal class PhotoDnaVisualCache(private val maxEntries: Int = 250_000) {
    private class Entry(val revision: String, val hash: CurationVisualHash)
    private val entries = HashMap<String, Entry>()
    private val failures = HashMap<String, String>()
    private var grant: GalleryMediaGrant? = null

    @Synchronized fun bindGrant(current: GalleryMediaGrant) {
        if (current != grant) {
            entries.clear()
            failures.clear()
            grant = current
        }
    }

    @Synchronized fun clear() {
        entries.clear()
        failures.clear()
        grant = null
    }

    /** True while [record]'s decode failed at its current revision; an edit retries it. */
    @Synchronized fun failed(record: MediaRecord): Boolean =
        failures[record.uri] == photoDnaRevision(record)

    @Synchronized fun putFailure(record: MediaRecord) {
        if (failures.size >= maxEntries && record.uri !in failures) return
        entries.remove(record.uri)
        failures[record.uri] = photoDnaRevision(record)
    }

    val failedSize: Int @Synchronized get() = failures.size

    @Synchronized fun get(record: MediaRecord): CurationVisualHash? =
        entries[record.uri]?.takeIf { it.revision == photoDnaRevision(record) }?.hash

    @Synchronized fun put(record: MediaRecord, hash: CurationVisualHash) {
        if (entries.size >= maxEntries && record.uri !in entries) return
        failures.remove(record.uri)
        entries[record.uri] = Entry(photoDnaRevision(record), hash)
    }

    val size: Int @Synchronized get() = entries.size
}

internal class PhotoDnaVisualPass(
    val evidence: List<CurationPairEvidence>,
    /** The assets the evidence names (exact-group members reuse their hashed asset). */
    val assets: List<CurationAsset>,
    val records: Map<String, MediaRecord>,
    val hashedFiles: Int,
    val decodedFiles: Int,
    val deferredFiles: Int,
    val comparisons: Long,
    val truncated: Boolean
)

/**
 * One cancellable, bounded visual pass over the authorized images in
 * [source]. Hashes come from [cache] when they match the live revision, else
 * from [decode] (at most [PhotoDnaVisualBudget.maxDecodes] per pass, newest
 * first; the rest wait for a later pass). A decode that returns null is
 * remembered in [cache] for that revision and skipped by later passes, but
 * only when the grant still holds at the end of the pass (a revoke mid-pass
 * is not a decode failure). If the grant changed by the end
 * ([stillAuthorized] false), nothing is returned. Byte-identical members of
 * one exact group are not repeated as a pair.
 */
internal fun planPhotoDnaVisual(
    source: List<MediaRecord>,
    tokens: PhotoDnaTokens,
    plan: CurationReviewPlan,
    cache: PhotoDnaVisualCache,
    decode: (MediaRecord) -> CurationVisualHash?,
    stillAuthorized: () -> Boolean,
    budget: PhotoDnaVisualBudget = PhotoDnaVisualBudget(),
    checkpoint: () -> Unit = {}
): PhotoDnaVisualPass {
    val none = PhotoDnaVisualPass(emptyList(), emptyList(), emptyMap(), 0, 0, 0, 0L, false)
    if (plan.scopeEpoch != tokens.scopeEpoch) return none
    val images = ArrayList(photoDnaDistinctEligible(source, checkpoint = checkpoint)
        .filterNot { it.isVideo })
    images.sortWith(compareByDescending<MediaRecord> { it.takenAtMillis }.thenBy { it.uri })
    val hashed = ArrayList<MediaRecord>()
    val values = ArrayList<Long>()
    val failed = ArrayList<MediaRecord>()
    var decoded = 0
    var deferred = 0
    images.forEachIndexed { index, record ->
        if ((index and 255) == 0) checkpoint()
        val hash = cache.get(record) ?: if (cache.failed(record)) {
            null
        } else if (decoded < budget.maxDecodes) {
            checkpoint()
            decoded++
            decode(record)?.also { cache.put(record, it) } ?: run {
                failed.add(record)
                null
            }
        } else {
            deferred++
            null
        }
        if (hash != null && hash.informative) {
            hashed.add(record)
            values.add(hash.value)
        }
    }
    checkpoint()
    if (!stillAuthorized()) return none
    failed.forEach(cache::putFailure)
    val members = HashMap<String, CurationAsset>()
    val groupOf = HashMap<String, String>()
    plan.exactGroups.forEach { group ->
        group.members.forEach {
            members[it.uri] = it
            groupOf[it.uri] = group.key
        }
    }
    val found = findCurationVisualPairs(values.toLongArray(), maxPairs = budget.maxPairs,
        maxComparisons = budget.maxComparisons,
        skip = { i, j ->
            val group = groupOf[hashed[i].uri]
            group != null && group == groupOf[hashed[j].uri]
        },
        checkpoint = checkpoint)
    val evidence = ArrayList<CurationPairEvidence>(found.matches.size)
    val assets = LinkedHashMap<String, CurationAsset>()
    val records = HashMap<String, MediaRecord>()
    found.matches.forEach { match ->
        val left = hashed[match.left]
        val right = hashed[match.right]
        evidence.add(CurationPairEvidence(left.uri, right.uri, CurationPairKind.GEOMETRIC_CANDIDATE,
            tokens.scopeEpoch, photoDnaRevision(left), photoDnaRevision(right)))
        listOf(left, right).forEach { record ->
            records[record.uri] = record
            assets.getOrPut(record.uri) { members[record.uri] ?: photoDnaAsset(record, null) }
        }
    }
    return PhotoDnaVisualPass(evidence, assets.values.toList(), records, hashed.size, decoded,
        deferred, found.comparisons, found.truncated)
}

/**
 * Whether a bounded chain of visual passes should run one more pass: images
 * are still deferred, the last pass made progress (fewer deferred than the
 * one before; a full cache would otherwise loop), and the per-opening cap
 * is not reached. Each pass stays capped by [PhotoDnaVisualBudget].
 */
internal fun photoDnaVisualChainContinues(
    previousDeferred: Int?,
    pass: PhotoDnaVisualPass,
    passesThisOpening: Int,
    maxPassesPerOpening: Int = PHOTO_DNA_MAX_PASSES_PER_OPENING
): Boolean = pass.deferredFiles > 0 && passesThisOpening < maxPassesPerOpening &&
    (previousDeferred == null || pass.deferredFiles < previousDeferred)

/** 10 x 2,000 decodes per opening; an unfinished library resumes on the next one. */
internal const val PHOTO_DNA_MAX_PASSES_PER_OPENING = 10

/** Process-wide, memory-only visual hash cache. */
private object PhotoDnaVisualHashes {
    val cache = PhotoDnaVisualCache()
}

/** Thumbnail edge for the perceptual hash: plenty for a 9x8 grid, cheap to decode. */
private val VISUAL_THUMBNAIL = Size(96, 96)

/** A misbehaving thumbnail provider must not allocate a full-resolution pixel buffer. */
internal fun photoDnaThumbnailWithinBudget(width: Int, height: Int): Boolean =
    width in 1..256 && height in 1..256

/**
 * The perceptual hash of one already-authorized image, from Android's own
 * thumbnail: ContentResolver.loadThumbnail returns a small bitmap and rotates
 * it by the provider's EXTRA_ORIENTATION, so EXIF orientation is normalized
 * (https://developer.android.com/reference/android/content/ContentResolver#loadThumbnail(android.net.Uri,%20android.util.Size,%20android.os.CancellationSignal)).
 * No new permission and no write access; the grant is checked before and
 * after the read, and any provider error, revoke or video yields null.
 */
internal fun visualHashAuthorizedImage(
    resolver: ContentResolver,
    record: MediaRecord,
    stillAuthorized: () -> Boolean,
    checkCancelled: () -> Unit
): CurationVisualHash? {
    if (record.isVideo) return null
    checkCancelled()
    if (!stillAuthorized()) return null
    val bitmap = try {
        resolver.loadThumbnail(Uri.parse(record.uri), VISUAL_THUMBNAIL, CancellationSignal())
    } catch (_: IOException) {
        return null
    } catch (_: SecurityException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    // The provider is asked for 96x96, but a faulty implementation may ignore
    // the request. Never copy a full-size HARDWARE bitmap or allocate its pixels.
    if (!photoDnaThumbnailWithinBudget(bitmap.width, bitmap.height)) {
        bitmap.recycle()
        return null
    }
    val software = if (bitmap.config == Bitmap.Config.HARDWARE)
        bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    return try {
        checkCancelled()
        if (software == null || !stillAuthorized()) return null
        val pixels = IntArray(software.width * software.height)
        software.getPixels(pixels, 0, software.width, 0, 0, software.width, software.height)
        curationDHash(software.width, software.height, pixels)
    } finally {
        if (software !== bitmap) software?.recycle()
        bitmap.recycle()
    }
}

/** The family a Keep receipt is bound to: digest, size and every member's revision. */
internal fun photoDnaKeepFamily(group: CurationExactGroup): VerifiedKeepFamily {
    val bytes = group.members.first().bytes
    return VerifiedKeepFamily("sha256:$bytes:${group.key}", group.members.map {
        VerifiedKeepMember(mediaKey = it.uri, contentVersion = it.revision, byteSize = it.bytes,
            sha256 = it.sha256.orEmpty(), readableNow = true, digestVerified = true)
    })
}

/**
 * Mark the keeper of every group whose saved receipt still matches the live,
 * just-hashed family. [resolve] is CurationKeepStore.resolveCurrentlyValid;
 * it is asked in bounded batches and any failure means "no receipts".
 */
internal fun withKeepReceipts(
    plan: CurationReviewPlan,
    resolve: (List<VerifiedKeepFamily>) -> List<KeepReceipt>
): CurationReviewPlan {
    if (plan.exactGroups.isEmpty()) return plan
    val families = plan.exactGroups.map { it to photoDnaKeepFamily(it) }
    val kept = HashMap<String, String>()
    var batch = ArrayList<Pair<CurationExactGroup, VerifiedKeepFamily>>()
    var batchMembers = 0L
    fun flush() {
        if (batch.isEmpty()) return
        val byKey = batch.associateBy { it.second.familyKey }
        val receipts = try { resolve(batch.map { it.second }) } catch (_: RuntimeException) { emptyList() }
        receipts.forEach { receipt ->
            val (group, family) = byKey[receipt.familyKey] ?: return@forEach
            // Re-check here too: a receipt counts only for the family it names.
            if (KeepReceiptContract.remainsValid(receipt, family)) kept[group.key] = receipt.keeperKey
        }
        batch = ArrayList()
        batchMembers = 0L
    }
    families.forEach { entry ->
        val size = entry.second.members.size
        if (batch.size >= KeepReceiptContract.MAX_BATCH ||
            batchMembers + size > KeepReceiptContract.MAX_MEMBERS) flush()
        batch.add(entry)
        batchMembers += size
    }
    flush()
    if (kept.isEmpty()) return plan
    val assets = plan.exactGroups.flatMap { group ->
        val keeper = kept[group.key]
        group.members.map { if (keeper != null) it.copy(userKept = it.uri == keeper) else it }
    }
    return CurationReviewPlan.build(plan.scopeEpoch, assets, emptyList())
}

/** One cancellable, bounded planning pass. [hash] returns null for anything unreadable. */
internal fun planPhotoDna(
    source: List<MediaRecord>,
    tokens: PhotoDnaTokens,
    hash: (MediaRecord) -> CurationByteDigest?,
    budget: PhotoDnaBudget = PhotoDnaBudget(),
    checkpoint: () -> Unit = {},
    keepReceipts: (List<VerifiedKeepFamily>) -> List<KeepReceipt> = { emptyList() }
): PhotoDnaResult {
    val candidates = photoDnaCandidates(source, budget, checkpoint)
    val assets = candidates.records.map { record ->
        checkpoint()
        photoDnaAsset(record, hash(record))
    }
    checkpoint()
    val plan = withKeepReceipts(
        CurationReviewPlan.build(tokens.scopeEpoch, assets, emptyList()), keepReceipts)
    return PhotoDnaResult(tokens, plan, candidates.records.associateBy { it.uri },
        candidates.records.size, candidates.deferredFiles)
}

/** The result belongs to the live tokens; anything else is never rendered or acted on. */
internal fun currentPhotoDna(result: PhotoDnaResult?, tokens: PhotoDnaTokens?): PhotoDnaResult? =
    result?.takeIf { tokens != null && it.tokens == tokens }

internal sealed interface PhotoDnaTrashCheck {
    /** Live rows to hand to DeclutterTrashPreflight and then Android's own Trash dialog. */
    data class Ready(val keeper: MediaRecord, val selected: List<MediaRecord>) : PhotoDnaTrashCheck
    data class Refused(val reason: String) : PhotoDnaTrashCheck
}

/**
 * The click-time recheck CurationReviewPlan requires: the live tokens still
 * match the plan, every group member is still in the live catalogue with the
 * same metadata, and a FRESH full-byte read of every member (keeper included)
 * still gives the group's digest. Any difference refuses the whole review.
 * Call off main: it reads every member's bytes again.
 */
internal fun recheckPhotoDnaTrash(
    result: PhotoDnaResult,
    request: CurationReviewRequest,
    liveTokens: PhotoDnaTokens?,
    liveSource: List<MediaRecord>,
    hash: (MediaRecord) -> CurationByteDigest?,
    checkpoint: () -> Unit = {}
): PhotoDnaTrashCheck {
    val plan = result.plan
    if (liveTokens == null || liveTokens != result.tokens ||
        request.scopeEpoch != plan.scopeEpoch)
        return PhotoDnaTrashCheck.Refused("Your library changed. Review again; nothing was trashed.")
    val group = plan.exactGroups.singleOrNull { it.key == request.exactGroupKey }
        ?: return PhotoDnaTrashCheck.Refused("This group is no longer current. Nothing was trashed.")
    val memberUris = group.members.mapTo(HashSet()) { it.uri }
    val live = HashMap<String, MediaRecord>(memberUris.size)
    liveSource.forEachIndexed { index, record ->
        if ((index and 1023) == 0) checkpoint()
        if (record.uri in memberUris) live[record.uri] = record
    }
    val liveAssets = group.members.mapNotNull { member ->
        checkpoint()
        // A Keep mark only protects (it can never be staged), so it is carried over.
        live[member.uri]?.let { photoDnaAsset(it, hash(it)).copy(userKept = member.userKept) }
    }
    val review = try {
        plan.prepareManualTrashReview(request.exactGroupKey, request.keeperUri,
            request.explicitlySelectedUris, liveTokens.scopeEpoch, liveAssets)
    } catch (_: IllegalArgumentException) {
        return PhotoDnaTrashCheck.Refused(
            "These files changed or are no longer identical. Nothing was trashed.")
    }
    val keeper = live[review.keeperUri]
        ?: return PhotoDnaTrashCheck.Refused("The kept copy is unavailable. Nothing was trashed.")
    return PhotoDnaTrashCheck.Ready(keeper, review.selectedUris.map { live.getValue(it) })
}

internal sealed interface PhotoDnaKeepOutcome {
    data class Saved(val groupKey: String, val keeperUri: String) : PhotoDnaKeepOutcome
    data class Refused(val reason: String) : PhotoDnaKeepOutcome
}

/**
 * Save an explicit Keep choice. Every member of the group (keeper included)
 * is read again in full and must still give the group's digest at the planned
 * revision, and [unchanged] (MediaStore metadata) must hold, before the
 * receipt goes to [save] (CurationKeepStore.saveVerified). Call off main.
 */
internal fun savePhotoDnaKeeper(
    result: PhotoDnaResult,
    groupKey: String,
    keeperUri: String,
    hash: (MediaRecord) -> CurationByteDigest?,
    unchanged: (List<MediaRecord>) -> Boolean,
    save: (VerifiedKeepFamily, String) -> Unit,
    checkpoint: () -> Unit = {}
): PhotoDnaKeepOutcome {
    val changed = PhotoDnaKeepOutcome.Refused("These files changed. Keep was not saved.")
    val group = result.plan.exactGroups.singleOrNull { it.key == groupKey }
        ?: return PhotoDnaKeepOutcome.Refused("This group is no longer current. Keep was not saved.")
    if (group.members.none { it.uri == keeperUri }) return changed
    val records = group.members.map { result.records[it.uri] ?: return changed }
    val fresh = group.members.zip(records).map { (planned, record) ->
        checkpoint()
        val asset = photoDnaAsset(record, hash(record))
        if (asset.revision != planned.revision || asset.sha256 != groupKey ||
            asset.sha256AtRevision != asset.revision) return changed
        asset
    }
    checkpoint()
    if (!records.chunked(200).all(unchanged)) return changed
    val family = photoDnaKeepFamily(group.copy(members = fresh))
    return try {
        save(family, keeperUri)
        PhotoDnaKeepOutcome.Saved(groupKey, keeperUri)
    } catch (_: IllegalArgumentException) {
        changed
    } catch (_: IllegalStateException) {
        PhotoDnaKeepOutcome.Refused("Couldn't save Keep. Nothing was changed.")
    } catch (_: android.database.SQLException) {
        PhotoDnaKeepOutcome.Refused("Couldn't save Keep. Nothing was changed.")
    }
}

/** One receipt database per process; the helper opens lazily on IO. */
private object PhotoDnaKeepStore {
    @Volatile private var store: CurationKeepStore? = null
    fun get(context: Context): CurationKeepStore = store ?: synchronized(this) {
        store ?: CurationKeepStore(context.applicationContext).also { store = it }
    }
}

/**
 * Full-byte digest of one already-authorized item. No new permission, no
 * write access; a provider error, a revoked grant or a size change yields null.
 */
internal fun hashAuthorizedMedia(
    resolver: ContentResolver,
    record: MediaRecord,
    stillAuthorized: () -> Boolean,
    checkCancelled: () -> Unit
): CurationByteDigest? = hashCurationBytes(
    expectedBytes = record.sizeBytes,
    openAuthorized = { resolver.openInputStream(Uri.parse(record.uri)) },
    stillAuthorized = stillAuthorized,
    checkCancelled = checkCancelled
)

/**
 * #42: how one Photo DNA planning step ended. Any throwable but a
 * cancellation, including a failed `require` in CurationReviewPlan and an
 * OutOfMemoryError on a large library, becomes [Failed] instead of reaching
 * the main looper and killing the app.
 */
internal sealed interface PhotoDnaOutcome<out T> {
    data class Done<T>(val value: T) : PhotoDnaOutcome<T>
    class Failed(val error: Throwable) : PhotoDnaOutcome<Nothing>
}

internal inline fun <T> photoDnaOutcome(step: () -> T): PhotoDnaOutcome<T> = try {
    PhotoDnaOutcome.Done(step())
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (failure: Throwable) {
    PhotoDnaOutcome.Failed(failure)
}

/**
 * The host's Photo DNA state: the plan (if any), whether planning failed
 * before a plan could be shown, and a Retry that runs planning again.
 */
@Stable
internal class PhotoDnaPlanning {
    var result: PhotoDnaResult? by mutableStateOf(null, referentialEqualityPolicy())
        internal set
    /** True when planning stopped on an error and no plan is on screen. */
    var failed: Boolean by mutableStateOf(false)
        internal set
    internal var attempt: Int by mutableIntStateOf(0)
        private set

    fun retry() {
        failed = false
        attempt++
    }
}

/**
 * Plans off main, only while [enabled], and only when the tokens or source
 * list change, never per recomposition. A result from older tokens is kept
 * but never rendered: every reader goes through [currentPhotoDna]. While
 * images are deferred the visual passes chain (bounded, each cancellable,
 * cancelled when Photo DNA closes); reopening resumes them (#456).
 */
@Composable
internal fun rememberPhotoDna(
    source: List<MediaRecord>,
    tokens: PhotoDnaTokens?,
    enabled: Boolean,
    hash: (MediaRecord, () -> Unit) -> CurationByteDigest?
): PhotoDnaPlanning {
    val context = LocalContext.current.applicationContext
    val state = remember { PhotoDnaPlanning() }
    LaunchedEffect(tokens, CatalogSourceKey(source), enabled, state.attempt) {
        if (tokens == null) {
            state.result = null
            state.failed = false
            PhotoDnaVisualHashes.cache.clear()
            return@LaunchedEffect
        }
        val current = currentPhotoDna(state.result, tokens)
        if (!enabled || current?.needsVisualPass == false) return@LaunchedEffect
        state.failed = false
        // #42: any failure in the byte plan or a visual pass (a `require` in
        // CurationReviewPlan, an OOM on a large library, a provider error)
        // ends here: recorded as Last crash, shown as Retry when no plan is
        // on screen, never thrown into the main looper.
        val outcome = photoDnaOutcome {
            planAndChainVisualPasses(context, source, tokens, current, hash,
                latest = { state.result }) { state.result = it }
        }
        if (outcome is PhotoDnaOutcome.Failed) {
            if (currentPhotoDna(state.result, tokens) == null) state.failed = true
            withContext(NonCancellable + Dispatchers.IO) {
                LastCrashRecorder.recordHandled(context, outcome.error)
            }
        }
    }
    return state
}

/** The body of [rememberPhotoDna]'s effect; it may throw, the caller catches. */
private suspend fun planAndChainVisualPasses(
    context: Context,
    source: List<MediaRecord>,
    tokens: PhotoDnaTokens,
    current: PhotoDnaResult?,
    hash: (MediaRecord, () -> Unit) -> CurationByteDigest?,
    latest: () -> PhotoDnaResult?,
    publish: (PhotoDnaResult) -> Unit
) {
    // Show the byte plan first; visual pairs follow when their pass ends.
    val planned = current ?: withContext(Dispatchers.IO) {
        val check = { ensureActive() }
        planPhotoDna(source, tokens, { hash(it, check) }, checkpoint = check,
            keepReceipts = { PhotoDnaKeepStore.get(context).resolveCurrentlyValid(it) })
    }.also(publish)
    val grant = readGalleryMediaGrant(context)
    if (!grant.images) return
    var previousDeferred: Int? = null
    var passes = 0
    while (true) {
        // The byte plan (with any Keep marks) is the base; visual pairs are rebuilt on it.
        val basePlan = planned.plan
        val pass = withContext(Dispatchers.IO) {
            val check = { ensureActive() }
            val authorized = { readGalleryMediaGrant(context) == grant }
            PhotoDnaVisualHashes.cache.bindGrant(grant)
            planPhotoDnaVisual(source, tokens, basePlan, PhotoDnaVisualHashes.cache,
                decode = { visualHashAuthorizedImage(context.contentResolver, it, authorized, check) },
                stillAuthorized = authorized, checkpoint = check)
        }
        passes++
        if (latest() !== planned || readGalleryMediaGrant(context) != grant) return
        planned.addVisualPairs(pass)
        if (!photoDnaVisualChainContinues(previousDeferred, pass, passes)) return
        previousDeferred = pass.deferredFiles
    }
}

/** The Photo DNA row on the Albums tab, under Stories. */
@Composable
internal fun PhotoDnaAlbumsEntry(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AtlasSpace.Gutter, vertical = 4.dp)
            .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("atlas-albums-photo-dna-entry"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondary)
        }
        Column(Modifier.weight(1f)) {
            Text(PhotoDnaCopy.TITLE, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(PhotoDnaCopy.ENTRY_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Photo DNA over the Albums tab: a back row, then progress, the review screen,
 * or (when the host has no current Photo DNA tokens, [accessible] false) a
 * no-access message instead of a spinner that would never finish.
 */
@Composable
internal fun PhotoDnaRoute(
    result: PhotoDnaResult?,
    verifying: Boolean,
    onBack: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    onReviewRequested: (CurationReviewRequest) -> Unit,
    modifier: Modifier = Modifier,
    accessible: Boolean = true,
    /** #42: planning failed before a plan could be shown; offer [onRetry]. */
    failed: Boolean = false,
    onRetry: () -> Unit = {},
    /** Tests only: replaces the fresh full-byte read under the grant held at the Keep tap. */
    hashForKeep: ((MediaRecord, () -> Unit) -> CurationByteDigest?)? = null,
    unchanged: (Context, List<MediaRecord>) -> Boolean = { context, records ->
        DeclutterTrashPreflight.unchanged(context.contentResolver, records)
    },
    keepStore: (Context) -> CurationKeepStore = PhotoDnaKeepStore::get
) {
    // System Back closes Photo DNA and lands on the Albums tab itself.
    BackHandler(onBack = onBack)
    val shown = result?.takeIf { accessible }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(shown)
    val saving = remember { HashMap<String, Job>() }
    // #449 receipt note 1: a Keep that is refused or ignored puts the radio
    // back on the stored or suggested keeper instead of the refused choice.
    val keeperResets = remember { mutableStateMapOf<String, Int>() }
    fun resetKeeper(groupKey: String) {
        keeperResets[groupKey] = (keeperResets[groupKey] ?: 0) + 1
    }
    fun keep(groupKey: String, keeperUri: String) {
        val held = latest ?: return resetKeeper(groupKey)
        val grant = readGalleryMediaGrant(context)
        val hashOne = hashForKeep ?: { record, check ->
            if (!grant.hasAny) null
            else hashAuthorizedMedia(context.contentResolver, record,
                stillAuthorized = { readGalleryMediaGrant(context) == grant },
                checkCancelled = check)
        }
        saving.remove(groupKey)?.cancel()
        saving[groupKey] = scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val worker = currentCoroutineContext()
                    val check = { worker.ensureActive() }
                    savePhotoDnaKeeper(held, groupKey, keeperUri,
                        hash = { hashOne(it, check) },
                        unchanged = { unchanged(context, it) },
                        save = { family, keeper ->
                            keepStore(context).saveVerified(listOf(family to keeper))
                        },
                        checkpoint = check)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                PhotoDnaKeepOutcome.Refused("Couldn't save Keep. Nothing was changed.")
            }
            // Only the plan still on screen (same tokens) takes the mark.
            when (outcome) {
                is PhotoDnaKeepOutcome.Saved ->
                    // #42: rebuilding the plan may throw; a refused mark is a reset, not a crash.
                    if (latest !== held || !runCatching {
                            held.markKept(outcome.groupKey, outcome.keeperUri)
                        }.getOrDefault(false)) resetKeeper(groupKey)
                is PhotoDnaKeepOutcome.Refused -> {
                    resetKeeper(groupKey)
                    Toast.makeText(context, outcome.reason, Toast.LENGTH_LONG).show()
                }
            }
            saving.remove(groupKey)
        }
    }
    // #73: this route replaces the Albums scaffold and must consume its own
    // top system inset under Android edge-to-edge, in either theme.
    Column(modifier.fillMaxSize().statusBarsPadding().testTag("atlas-photo-dna")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("atlas-photo-dna-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Albums")
            }
            Text(PhotoDnaCopy.TITLE,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).testTag("atlas-photo-dna-title"))
            // Only show a status when the route genuinely needs attention.
            if (!accessible || shown == null || verifying) {
                Text(when {
                    !accessible -> PhotoDnaCopy.NO_ACCESS_STATUS
                    shown == null && failed -> PhotoDnaCopy.FAILED_STATUS
                    shown == null -> PhotoDnaCopy.SEARCHING
                    else -> PhotoDnaCopy.RECHECKING
                }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("atlas-photo-dna-status"))
            }
        }
        if (!accessible) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    PhotoDnaCopy.NO_ACCESS_BODY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("atlas-photo-dna-no-access")
                )
            }
        } else if (shown == null && failed) {
            Column(
                Modifier.fillMaxSize().padding(28.dp).testTag("atlas-photo-dna-failed"),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(PhotoDnaCopy.FAILED_BODY, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onRetry,
                    modifier = Modifier.testTag("atlas-photo-dna-retry")) {
                    Text(PhotoDnaCopy.RETRY)
                }
            }
        } else if (shown == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.testTag("atlas-photo-dna-progress"))
            }
        } else {
            PhotoDnaReviewScreen(
                plan = shown.plan,
                thumbnail = { asset, thumbModifier ->
                    val record = shown.records[asset.uri]
                    if (record != null) {
                        MediaPreview(record,
                            thumbModifier.clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Image) { onOpenPhoto(asset.uri) }
                                .testTag("atlas-photo-dna-thumb"),
                            ContentScale.Crop, 512)
                    }
                },
                onReviewRequested = { if (!verifying) onReviewRequested(it) },
                // A visual pair only opens a photo; it never stages anything.
                onComparePair = { onOpenPhoto(it.left.uri) },
                modifier = Modifier.weight(1f),
                onKeeperChosen = { group, keeper ->
                    if (!verifying) keep(group, keeper) else resetKeeper(group)
                },
                keeperResets = keeperResets
            )
        }
    }
}
