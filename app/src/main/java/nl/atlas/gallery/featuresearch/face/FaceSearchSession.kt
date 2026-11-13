package nl.atlas.gallery.featuresearch.face

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featureface.search.FaceMatchTier
import nl.atlas.gallery.featureface.search.FaceQuery
import nl.atlas.gallery.featureface.search.FaceQueryEngine
import nl.atlas.gallery.featureface.search.FaceQueryStatus
import nl.atlas.gallery.featureface.search.FaceSearchCorpus
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia

/**
 * VS-F2b (#468): what search by face needs from the app's face-discovery
 * owner (PeopleDiscoveryHost). Every method may block: call it on IO only.
 * Every method answers for ONE face permission epoch; a revoked, replaced or
 * never opted-in scope answers null, false or zero, never older data.
 */
interface FaceSearchPort {
    /** The live, granted face-discovery epoch, or null (not opted in, revoked, held). */
    fun liveEpoch(): String?

    /** True while [epoch] is still the live, granted scope. */
    fun scopeCurrent(epoch: String): Boolean

    /** The read-only search corpus of [epoch]; null while revoked or never committed. */
    fun corpus(epoch: String): FaceSearchCorpus?

    /** [media]'s URI under the current grant at that exact revision, else null. */
    fun authorizedUri(epoch: String, media: IndexedMedia): String?

    /** The reviewed name of [person] in [epoch]'s snapshot, or null. */
    fun personName(epoch: String, person: PersonKey): String?

    /**
     * Capture over the People scan's own detector/embedder and grant-gated
     * reader (VS-F2a). Null when [epoch] is no longer live.
     */
    fun queryCapture(epoch: String): QueryFaceCapture?

    /** The capture source for a gallery photo of [epoch], or null if not in its grant. */
    fun querySource(epoch: String, photo: MediaRecord): QueryFaceSource?

    /** Indexed faces on [photo] at its current revision (0 when unknown or stale). */
    fun indexedFaceCount(epoch: String, photo: MediaRecord): Int
}

/** One photo in the result grid, with the matched face boxes to highlight. */
data class FaceSearchHit(
    val record: MediaRecord,
    val tier: FaceMatchTier,
    val similarity: Float,
    /** Normalised (0..1) boxes of the faces that matched, best first. */
    val boxes: List<IndexedFaceBox>
)

/** Typed states of the result screen. Look-alikes are never part of any state. */
sealed interface FaceSearchState {
    data object Idle : FaceSearchState
    data object Searching : FaceSearchState
    /** Face discovery is off or has indexed no face yet: point to People's opt-in. */
    data object NotIndexed : FaceSearchState
    /** The query face came from another embedder version than the index. */
    data object VersionMismatch : FaceSearchState
    /** Photo access or the face scope was revoked or replaced; results were dropped. */
    data object Revoked : FaceSearchState
    data object Failed : FaceSearchState
    data class Empty(val epoch: String) : FaceSearchState
    data class Results(
        val epoch: String,
        /** SAME_PERSON first, then PROBABLE, each by similarity; nothing else. */
        val hits: List<FaceSearchHit>,
        /** A confirmed person the top matches belong to, and their reviewed name. */
        val person: PersonKey?,
        val personName: String?,
        /** More media matched than the engine's media limit. */
        val truncated: Boolean
    ) : FaceSearchState {
        /** The viewer's list: exactly the ranked results, in result order. */
        val records: List<MediaRecord> = hits.map { it.record }
        val sure: List<FaceSearchHit> get() = hits.filter { it.tier == FaceMatchTier.SAME_PERSON }
        val possible: List<FaceSearchHit> get() = hits.filter { it.tier == FaceMatchTier.PROBABLE }
        /** Face boxes per result URI, for the viewer's highlight. */
        val boxesByUri: Map<String, List<IndexedFaceBox>> = hits.associate { it.record.uri to it.boxes }
    }
}

/**
 * One grant-scoped search-by-face session. [search] runs the corpus read and
 * URI resolution on [io] and the brute-force engine on [cpu]; nothing blocks
 * the caller's thread. A result is published only while its epoch is live,
 * a newer [search] or [clear] wins over an older one, and [revalidate] drops
 * results as soon as their scope is revoked or replaced. Holds no corpus,
 * vectors or pixels between searches; only record references and boxes.
 *
 * [records] maps authorized URIs to the gallery's own current rows (the
 * permission-gated list the grid shows); a URI it does not know is left out.
 */
class FaceSearchSession(
    private val port: FaceSearchPort,
    private val records: (Set<String>) -> Map<String, MediaRecord>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default
) {
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow<FaceSearchState>(FaceSearchState.Idle)
    val state: StateFlow<FaceSearchState> get() = mutableState

    suspend fun search(query: QueryFace, mediaLimit: Int = MEDIA_LIMIT) {
        val request = generation.incrementAndGet()
        mutableState.value = FaceSearchState.Searching
        val outcome = try {
            withContext(io) { run(query, mediaLimit) }
        } catch (cancelled: CancellationException) {
            if (generation.get() == request) mutableState.value = FaceSearchState.Idle
            throw cancelled
        } catch (_: SecurityException) {
            FaceSearchState.Revoked
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            FaceSearchState.Failed
        } catch (_: Exception) {
            // A corrupt index or broken query is a failure, never "no matches".
            FaceSearchState.Failed
        }
        if (generation.get() == request) mutableState.value = outcome
    }

    /**
     * Blocking (call on IO): drop results whose scope is gone. Called on
     * every host change and gallery publication, and before opening a result.
     */
    fun revalidate(): Boolean {
        val epoch = when (val current = mutableState.value) {
            is FaceSearchState.Results -> current.epoch
            is FaceSearchState.Empty -> current.epoch
            else -> return true
        }
        val live = try { port.scopeCurrent(epoch) && port.liveEpoch() == epoch }
            catch (_: SecurityException) { false }
        if (!live) {
            generation.incrementAndGet()
            mutableState.value = FaceSearchState.Revoked
        }
        return live
    }

    /** Closed route or revoke: forget results; a late search cannot publish. */
    fun clear() {
        generation.incrementAndGet()
        mutableState.value = FaceSearchState.Idle
    }

    private suspend fun run(query: QueryFace, mediaLimit: Int): FaceSearchState {
        val epoch = port.liveEpoch() ?: return FaceSearchState.NotIndexed
        val corpus = port.corpus(epoch)
            ?: return if (port.scopeCurrent(epoch)) FaceSearchState.NotIndexed
                else FaceSearchState.Revoked
        if (corpus.faceCount == 0) return FaceSearchState.NotIndexed
        // The grid shows photos only: videos get no share, so a library full of
        // matching video moments can never crowd photos out of the limit.
        val faceQuery = FaceQuery(query.modelVersion, listOf(query.embedding),
            mediaLimit = mediaLimit, videoMediaLimit = 0)
        val result = withContext(cpu) { FaceQueryEngine.search(corpus, faceQuery) }
        when (result.status) {
            FaceQueryStatus.MODEL_MISMATCH -> return FaceSearchState.VersionMismatch
            FaceQueryStatus.UNAVAILABLE -> return FaceSearchState.Revoked
            FaceQueryStatus.OK -> Unit
        }
        if (!port.scopeCurrent(epoch)) return FaceSearchState.Revoked
        // Only media that still resolve through the live grant at their exact
        // indexed revision; an edited, deleted or unpermitted photo is left out.
        val uris = LinkedHashMap<String, String>()
        for (hit in result.hits) {
            if (hit.tier == FaceMatchTier.LOOKALIKE_EXCLUDED) continue
            val uri = port.authorizedUri(epoch, hit.media) ?: continue
            uris[hit.media.key] = uri
        }
        val rows = if (uris.isEmpty()) emptyMap() else records(uris.values.toHashSet())
        val hits = result.hits.mapNotNull { hit ->
            if (hit.tier == FaceMatchTier.LOOKALIKE_EXCLUDED) return@mapNotNull null
            val record = uris[hit.media.key]?.let(rows::get) ?: return@mapNotNull null
            if (record.isVideo) return@mapNotNull null
            FaceSearchHit(record, hit.tier, hit.bestSimilarity,
                hit.faces.filter { it.tier != FaceMatchTier.LOOKALIKE_EXCLUDED }.map { it.box })
        }
        val name = result.resolvedPerson?.let { port.personName(epoch, it) }
        if (!port.scopeCurrent(epoch)) return FaceSearchState.Revoked
        if (hits.isEmpty()) return FaceSearchState.Empty(epoch)
        return FaceSearchState.Results(epoch, hits,
            result.resolvedPerson.takeIf { name != null }, name, result.truncated)
    }

    companion object {
        /** Results are a browsing grid, not an export: the first 5,000 photos. */
        const val MEDIA_LIMIT = FaceQuery.DEFAULT_MEDIA_LIMIT
    }
}

/** Why a reference photo cannot start a search yet (#478 follow-up). */
enum class FaceReferenceProblem {
    /** Face discovery is off, revoked or replaced. */
    NOT_INDEXED,
    /**
     * The photo is not (yet) in face discovery's catalogue: added after the
     * last scan, edited since, or outside the current grant.
     */
    NOT_IN_CATALOGUE,
    /** The face models are not loaded in this process yet (no scan ran). */
    MODELS_NOT_READY,
    /** Reading the face scope failed; nothing was changed. */
    FAILED
}

/** Preparing the picker for one reference photo; every outcome is final. */
sealed interface FaceReferencePreparation {
    data object Loading : FaceReferencePreparation
    class Ready(val source: QueryFaceSource, val capture: QueryFaceCapture) : FaceReferencePreparation
    data class Unavailable(val problem: FaceReferenceProblem) : FaceReferencePreparation
}

/**
 * Blocking (IO): the picker's source and capture for [photo] under [epoch],
 * or the typed reason it can't be searched. Replaces the open-ended
 * "Preparing this photo…" spinner: a null answer is never "still loading".
 */
fun prepareFaceReference(port: FaceSearchPort, epoch: String?, photo: MediaRecord): FaceReferencePreparation {
    if (epoch == null) return FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_INDEXED)
    return try {
        if (!port.scopeCurrent(epoch))
            return FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_INDEXED)
        val source = port.querySource(epoch, photo)
            ?: return FaceReferencePreparation.Unavailable(
                if (port.scopeCurrent(epoch)) FaceReferenceProblem.NOT_IN_CATALOGUE
                else FaceReferenceProblem.NOT_INDEXED)
        val capture = port.queryCapture(epoch)
            ?: return FaceReferencePreparation.Unavailable(
                if (port.scopeCurrent(epoch)) FaceReferenceProblem.MODELS_NOT_READY
                else FaceReferenceProblem.NOT_INDEXED)
        FaceReferencePreparation.Ready(source, capture)
    } catch (_: SecurityException) {
        FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_INDEXED)
    } catch (_: RuntimeException) {
        FaceReferencePreparation.Unavailable(FaceReferenceProblem.FAILED)
    }
}
