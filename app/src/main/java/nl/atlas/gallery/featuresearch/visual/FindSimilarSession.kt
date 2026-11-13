package nl.atlas.gallery.featuresearch.visual

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featurevisual.search.VisualHit
import nl.atlas.gallery.featurevisual.search.VisualPoseState
import nl.atlas.gallery.featurevisual.search.VisualQuery
import nl.atlas.gallery.featurevisual.search.VisualQueryEngine
import nl.atlas.gallery.featurevisual.search.VisualQueryResult
import nl.atlas.gallery.featurevisual.search.VisualQueryStatus
import nl.atlas.gallery.featurevisual.search.VisualReference
import nl.atlas.gallery.featurevisual.search.VisualSearchUiState
import nl.atlas.gallery.featurevisual.search.VisualVectorSource
import nl.atlas.gallery.featurevisual.store.VisualMedia

/** What "Find similar" starts from: a gallery item, at [frameMs] in a video. */
data class FindSimilarReference(val record: MediaRecord, val frameMs: Long? = null) {
    init {
        require(frameMs == null || (record.isVideo && frameMs >= 0)) { "A frame time needs a video" }
    }
}

/**
 * VS-S2b (#468): what "Find similar" needs from the app. Every method may
 * block: call it on a worker only. A scope is the live face-discovery epoch
 * the visual index rides on (VS-S1c); a revoked, replaced or never opted-in
 * scope answers null or false, never older data.
 */
interface FindSimilarPort {
    /** The SigLIP version [embed] produces; must equal the index's. */
    val modelVersion: String

    /** The live, granted scope, or null (discovery off, revoked, held). */
    fun liveScope(): String?

    /** True while [scope] is still the live, granted scope. */
    fun scopeCurrent(scope: String): Boolean

    /** Read-only vectors of [scope]'s published visual index; null while it is off. */
    fun source(scope: String): VisualVectorSource?

    /** [record]'s library identity under [scope]'s grant (frame null), or null. */
    fun media(scope: String, record: MediaRecord): VisualMedia?

    /** The current-grant URI of [media] (key and revision; frame ignored), or null. */
    fun authorizedUri(scope: String, media: VisualMedia): String?

    /**
     * A fresh SigLIP vector of [reference] (a photo, or a video frame). Null
     * or an exception means it could not be read. SecurityException: revoked.
     */
    fun embed(reference: FindSimilarReference): FloatArray?

    /** Pose descriptor of [reference]; null without a body. May throw (no pose model). */
    fun referencePose(reference: FindSimilarReference): FloatArray?

    /** Pose descriptor of a hit at its authorized [uri]; null without a body. */
    fun candidatePose(media: VisualMedia, uri: String): FloatArray?

    /** Frees the on-device models; the next call opens them again. */
    fun release() = Unit
}

/** One tile: the gallery's own row, and for a video the matching moment. */
data class SimilarHit(val record: MediaRecord, val frameMs: Long?, val hit: VisualHit)

/** Why a reference can't be searched yet; each has its own message (#478 follow-up). */
enum class FindSimilarProblem {
    /** Face discovery or the "Visual search index" opt-in (Library status & settings) is off. */
    INDEX_OFF,
    /** The index has no vectors yet, or it was being republished (twice). */
    INDEX_NOT_READY,
    /** The reference photo or video frame could not be decoded or embedded. */
    REFERENCE_UNREADABLE
}

/** Typed states of the Find similar route; none of them is an open-ended spinner. */
sealed interface FindSimilarState {
    data object Idle : FindSimilarState
    data object Searching : FindSimilarState
    data class NotSearchable(val problem: FindSimilarProblem) : FindSimilarState
    data object VersionMismatch : FindSimilarState
    /** Photo access or the scope was revoked or replaced; results were dropped. */
    data object Revoked : FindSimilarState
    data object Failed : FindSimilarState
    data class Results(
        val scope: String,
        val reference: FindSimilarReference,
        /** Resolved tiles in the shown order (semantic, or pose re-ranked). */
        val hits: List<SimilarHit>,
        /** The semantic result over exactly [hits]' rows, in semantic order. */
        val semantic: VisualQueryResult,
        /** What the screen shows: [semantic], or its pose re-rank. */
        val shown: VisualQueryResult,
        val poseEnabled: Boolean
    ) : FindSimilarState {
        /** The viewer's list: exactly the shown tiles, in shown order. */
        val records: List<MediaRecord> = hits.map { it.record }

        /** Where each video hit starts playing: its best matching moment. */
        val startPositions: Map<String, Long> =
            hits.mapNotNull { h -> h.frameMs?.let { h.record.uri to it } }.toMap()

        fun hitFor(media: VisualMedia): SimilarHit? =
            hits.firstOrNull { it.hit.media == media }
    }

    /** The stateless VisualSearchResultsScreen's state, or null for a typed problem. */
    fun ui(): VisualSearchUiState? = when (this) {
        Idle, Searching -> VisualSearchUiState.Loading
        VersionMismatch -> VisualSearchUiState.VersionMismatch
        Revoked -> VisualSearchUiState.Revoked
        is Results -> VisualSearchUiState.Results(shown)
        is NotSearchable, Failed -> null
    }
}

/**
 * One grant-scoped Find similar session over [VisualQueryEngine]. [search]
 * reads the index, embeds the reference when needed and resolves URIs on
 * [worker]; nothing blocks the caller's thread. A result is published only
 * while its scope is live, a newer [search] or [clear] wins over an older one,
 * and [revalidate] drops results as soon as their scope is gone. A snapshot
 * superseded mid-query (STALE) is queried once more on the new one. Holds no
 * vectors or pixels between searches; only record references and scores.
 *
 * [records] maps authorized URIs to the gallery's own current rows (the
 * permission-gated list the grid shows); a URI it does not know is left out.
 */
class FindSimilarSession(
    private val port: FindSimilarPort,
    private val records: (Set<String>) -> Map<String, MediaRecord>,
    private val worker: CoroutineDispatcher = Dispatchers.IO
) {
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow<FindSimilarState>(FindSimilarState.Idle)
    val state: StateFlow<FindSimilarState> get() = mutableState

    suspend fun search(reference: FindSimilarReference, limit: Int = VisualQuery.DEFAULT_LIMIT) {
        val request = generation.incrementAndGet()
        mutableState.value = FindSimilarState.Searching
        val outcome = guarded(request) { run(reference, limit) }
        if (generation.get() == request) mutableState.value = outcome
    }

    /**
     * The "Emphasise pose" switch. On: re-ranks the semantic top 50 by body
     * pose (the screen shows "Matching body poses…" meanwhile); off: the
     * semantic order again, at once. Nothing happens without results.
     */
    suspend fun setPose(enabled: Boolean) {
        val current = mutableState.value as? FindSimilarState.Results ?: return
        if (current.poseEnabled == enabled) return
        val request = generation.incrementAndGet()
        val semantic = current.copy(hits = reorder(current.hits, current.semantic),
            shown = current.semantic, poseEnabled = enabled)
        mutableState.value = semantic
        if (!enabled) return
        val outcome = guarded(request) {
            val byMedia = current.hits.associateBy { it.hit.media }
            val engine = VisualQueryEngine(NoVectors(port.modelVersion)) { port.scopeCurrent(current.scope) }
            val ranked = engine.emphasisePose(current.semantic,
                referencePose = { port.referencePose(current.reference) },
                candidatePose = { media ->
                    byMedia[media]?.let { port.candidatePose(media, it.record.uri) }
                })
            when {
                ranked.status == VisualQueryStatus.REVOKED || !port.scopeCurrent(current.scope) ->
                    FindSimilarState.Revoked
                else -> semantic.copy(hits = reorder(current.hits, ranked), shown = ranked)
            }
        }
        if (generation.get() == request) mutableState.value = outcome
    }

    /**
     * Blocking (call on a worker): drop results whose scope is gone. Called on
     * every host change and gallery publication, and before opening a result.
     */
    fun revalidate(): Boolean {
        val scope = (mutableState.value as? FindSimilarState.Results)?.scope ?: return true
        val live = try { port.scopeCurrent(scope) && port.liveScope() == scope }
            catch (_: SecurityException) { false }
        if (!live) {
            generation.incrementAndGet()
            mutableState.value = FindSimilarState.Revoked
        }
        return live
    }

    /** Closed route or revoke: forget results and free the models; a late search cannot publish. */
    fun clear() {
        generation.incrementAndGet()
        mutableState.value = FindSimilarState.Idle
        try { port.release() } catch (_: RuntimeException) { }
    }

    private suspend fun guarded(request: Long, block: suspend () -> FindSimilarState): FindSimilarState =
        try {
            withContext(worker) { block() }
        } catch (cancelled: CancellationException) {
            if (generation.get() == request) mutableState.value = FindSimilarState.Idle
            throw cancelled
        } catch (_: SecurityException) {
            FindSimilarState.Revoked
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            FindSimilarState.Failed
        } catch (_: Exception) {
            // A corrupt index or a broken query is a failure, never "nothing similar".
            FindSimilarState.Failed
        }

    private fun run(reference: FindSimilarReference, limit: Int): FindSimilarState {
        val scope = port.liveScope()
            ?: return FindSimilarState.NotSearchable(FindSimilarProblem.INDEX_OFF)
        val own = port.media(scope, reference.record)
        val referenceMedia = own?.copy(frameMs = reference.frameMs)
        var attempts = 0
        while (true) {
            val source = port.source(scope)
                ?: return if (port.scopeCurrent(scope))
                    FindSimilarState.NotSearchable(FindSimilarProblem.INDEX_OFF)
                    else FindSimilarState.Revoked
            var unreadable = false
            val embed: () -> FloatArray? = {
                val vector = try { port.embed(reference) }
                    catch (revoked: SecurityException) { throw revoked }
                    catch (_: RuntimeException) { null }
                if (vector == null) unreadable = true
                vector
            }
            val engine = VisualQueryEngine(source) { port.scopeCurrent(scope) }
            val result = engine.search(VisualQuery(port.modelVersion,
                VisualReference(referenceMedia, embed), limit))
            when (result.status) {
                VisualQueryStatus.OK -> return resolve(scope, reference, own, result)
                VisualQueryStatus.NOT_INDEXED -> return FindSimilarState.NotSearchable(
                    if (unreadable) FindSimilarProblem.REFERENCE_UNREADABLE
                    else FindSimilarProblem.INDEX_NOT_READY)
                VisualQueryStatus.MODEL_MISMATCH -> return FindSimilarState.VersionMismatch
                VisualQueryStatus.REVOKED -> return FindSimilarState.Revoked
                // A rescan replaced the snapshot mid-query: once more on the new one.
                VisualQueryStatus.STALE -> if (++attempts > MAX_STALE_RETRIES)
                    return FindSimilarState.NotSearchable(FindSimilarProblem.INDEX_NOT_READY)
            }
        }
    }

    private fun resolve(
        scope: String, reference: FindSimilarReference, own: VisualMedia?, result: VisualQueryResult
    ): FindSimilarState {
        // One tile per item (a video at its best moment), never the reference itself.
        val seen = HashSet<String>()
        own?.let { seen += it.key }
        val picked = ArrayList<Pair<VisualHit, String>>()
        for (hit in result.hits) {
            if (!seen.add(hit.media.key)) continue
            val uri = port.authorizedUri(scope, hit.media) ?: continue
            picked += hit to uri
        }
        if (reference.record.uri.isNotEmpty()) picked.removeAll { it.second == reference.record.uri }
        val rows = if (picked.isEmpty()) emptyMap() else records(picked.mapTo(HashSet()) { it.second })
        val hits = picked.mapNotNull { (hit, uri) ->
            val record = rows[uri] ?: return@mapNotNull null
            // A keyframe hit must land on a video row, an image hit on a photo.
            if (record.isVideo != (hit.media.frameMs != null)) return@mapNotNull null
            SimilarHit(record, hit.media.frameMs, hit)
        }
        if (!port.scopeCurrent(scope)) return FindSimilarState.Revoked
        val semantic = result.copy(hits = hits.map { it.hit })
        return FindSimilarState.Results(scope, reference, hits, semantic, semantic, poseEnabled = false)
    }

    /** [hits] in [order]'s hit order, carrying [order]'s (pose) scores. */
    private fun reorder(hits: List<SimilarHit>, order: VisualQueryResult): List<SimilarHit> {
        val byMedia = hits.associateBy { it.hit.media }
        return order.hits.mapNotNull { h -> byMedia[h.media]?.copy(hit = h) }
    }

    /** emphasisePose reads no vectors; it only needs the grant check. */
    private class NoVectors(override val modelVersion: String) : VisualVectorSource {
        override fun page(after: nl.atlas.gallery.featurevisual.store.VisualVectorCursor?, limit: Int) =
            emptyList<nl.atlas.gallery.featurevisual.store.VisualIndexedVector>()
        override fun lookup(media: VisualMedia): FloatArray? = null
    }

    companion object {
        /** A snapshot superseded mid-query is retried this many times. */
        const val MAX_STALE_RETRIES = 1
    }
}

/** True while the shown result is still the semantic one and pose is on: re-ranking. */
val FindSimilarState.Results.poseRunning: Boolean
    get() = poseEnabled && shown.pose == VisualPoseState.OFF
