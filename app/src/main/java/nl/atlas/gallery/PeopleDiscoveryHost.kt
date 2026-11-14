package nl.atlas.gallery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.SQLException
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featureface.FaceAuthorizedMediaDirectory
import nl.atlas.gallery.featureface.FaceBatchResult
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceCatalogPublisher
import nl.atlas.gallery.featureface.FaceCatalogPublication
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.durableFaceKeyOrNull
import nl.atlas.gallery.featureface.FaceScanConditions
import nl.atlas.gallery.featureface.FaceScanEngine
import nl.atlas.gallery.featureface.FaceScanPhase
import nl.atlas.gallery.featureface.FaceUnknownInboxBridge
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.OnDeviceFaceLocator
import nl.atlas.gallery.featureface.AndroidMediaFaceFrameReader
import nl.atlas.gallery.featureface.AuthorizedVideoFrames
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.embed.PersistedFaceEmbeddingIndexPort
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.YuNetModel
import nl.atlas.gallery.featureface.toDurableFaceKey
import nl.atlas.gallery.featureface.toFaceVisibleMedia
import nl.atlas.gallery.featuremedia.video.VideoKeyframeResult
import nl.atlas.gallery.featureface.search.FaceSearchCorpus
import nl.atlas.gallery.featuresearch.face.FaceSearchPort
import nl.atlas.gallery.featuresearch.face.QueryFaceCapture
import nl.atlas.gallery.featuresearch.face.QueryFaceSource
import nl.atlas.gallery.featuremedia.video.VideoKeyframeSampler
import nl.atlas.gallery.featuremedia.video.VideoMediaRevision
import nl.atlas.gallery.featuremedia.video.VideoSkipReason
import nl.atlas.gallery.featureface.work.PeopleScanRuntimeProvider
import nl.atlas.gallery.featureface.work.PeopleScanSession
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.PeopleScanWorkScheduler
import nl.atlas.gallery.featureface.work.PeopleVisualScanStep
import nl.atlas.gallery.featurevisual.ImageEmbedder
import nl.atlas.gallery.featurevisual.VisualEmbedOutcome
import nl.atlas.gallery.featurevisual.VisualKeyframe
import nl.atlas.gallery.featurevisual.VisualScanResult
import nl.atlas.gallery.featurevisual.VisualScanStage
import nl.atlas.gallery.featurevisual.VisualScanStop
import nl.atlas.gallery.featurevisual.VisualStepPlan
import nl.atlas.gallery.featurevisual.VisualVideoBudget
import nl.atlas.gallery.featurevisual.VisualVideoFrames
import nl.atlas.gallery.featurevisual.VisualWindowScan
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder
import nl.atlas.gallery.featurevisual.onnx.SiglipModel
import nl.atlas.gallery.featurevisual.search.StoreVisualVectorSource
import nl.atlas.gallery.featurevisual.search.VisualVectorSource
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurepeopleui.DetectedFaceReviewCandidate
import nl.atlas.gallery.featurepeopleui.DetectedFaceReviewKey
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.NativeFaceReviewCommand
import nl.atlas.gallery.featurepeopleui.NativeFaceReviewQueue
import nl.atlas.gallery.featurepeopleui.NativeFaceScanDisplayEvidence
import nl.atlas.gallery.featurepeopleui.NativeReviewActionSnapshot
import nl.atlas.gallery.featurepeopleui.NativeReviewScope
import nl.atlas.gallery.featurepeopleui.UnknownFacesReviewScreen
import nl.atlas.gallery.featurepeopleui.PeopleActionAvailability
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.PeopleHomeGroup
import nl.atlas.gallery.featurepeopleui.PeopleHomePerson
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.ReviewReferenceKey
import nl.atlas.gallery.featurepeopleui.applyCurrentPeopleReviewCommand
import nl.atlas.gallery.featurepeopleui.presentNativeFaceScan
import nl.atlas.gallery.featurepeopleui.reviewTally
import nl.atlas.gallery.featurepeople.identity.PeopleNumbers
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedFacePageCursor
import nl.atlas.gallery.featurepersistence.IndexedMedia

/** Battery and storage facts for [FaceScanConditions]; injectable for tests. */
internal data class PeoplePowerState(
    val charging: Boolean,
    val batteryNotLow: Boolean,
    val storageNotLow: Boolean
)

/** Where the worker's bounded batches go; WorkManager in the app, a fake in tests. */
internal interface PeopleScanScheduling {
    /** Starts the chain, and (#43) keeps a periodic re-arm while opted in. */
    fun start()
    fun next()
    /** The next batch after a hold, with an initial delay. */
    fun later()
    fun cancel()
    /** #43: the user stopped (off, pause) or access is gone: no re-arm either. */
    fun disarm() = Unit
}

/** Everything People's discovery panel needs from one consistent read. */
internal data class PeopleDiscoveryStatus(
    val ui: FaceDiscoveryUiState,
    /** The token PeopleHomeScreen compares with [FaceDiscoveryUiState.observedPermissionEpoch]. */
    val displayEpoch: String?,
    val actions: PeopleActionAvailability,
    /**
     * Native reviewed people for People home (#434): only names that still
     * hold a confirmed face in the published clusters. Null while there is no
     * clean, current review snapshot (not opted in, revoked, or mid-publication).
     */
    val people: PeopleHomeSummary? = null,
    /** The separate "Visual search index" opt-in (VS-S1c); null when unavailable. */
    val visual: VisualIndexStatus? = null,
    /** Non-identifying diagnostics for the active face catalogue. */
    val provenance: FaceCatalogPublication? = null
)

/**
 * The visual search index panel's facts. It rides on the face discovery run
 * (same worker, guards and grant), so [mayEnable] needs a started run.
 */
internal data class VisualIndexStatus(
    val enabled: Boolean,
    val mayEnable: Boolean,
    /** Photos and videos indexed or skipped as unreadable, of [total]; null before publication. */
    val done: Int? = null,
    val total: Int? = null,
    val skipped: Int = 0,
    /**
     * #631: photos left out of this publication because MediaStore gives them
     * no fixed volume and ID (face discovery omits the same photos).
     */
    val leftOut: Int = 0,
    /** Index rows or the joined model copy may still be on the device. */
    val mayDelete: Boolean = false,
    val failure: String? = null
)

/** One page of the unknown-face review route, read in one host call. */
internal class PeopleReviewPage(
    val queue: NativeFaceReviewQueue,
    /** Published unknown groups with the durable identity review (#434). */
    val clusters: PeopleClusterReviewState?,
    val nextCursor: IndexedFacePageCursor?
)

/**
 * People home lists a reviewed name only while that person still has a
 * confirmed face in the published clusters. A confirmation lives on a face,
 * so a rescan or edit of its photo drops it; the bare name is then hidden
 * rather than shown as an empty album (#435 review note).
 */
internal fun peopleWithPresentFaces(
    state: PeopleClusterReviewState
): PeopleClusterReviewState {
    val present = state.clusters.reviews.confirmed
        .filterKeys { it in state.clusters }.values.toHashSet()
    val names = state.names.filterKeys { it in present }
    return if (names.size == state.names.size) state
    else PeopleClusterReviewState(state.permissionEpoch, state.sourceGeneration,
        state.clusters, names, hidden = state.hidden.filterTo(HashSet()) { it in names })
}

/**
 * #551: group face UIDs by the authorized URI of their medium, resolving each
 * medium once; [resolve] returns null for a medium outside the grant.
 */
internal inline fun facesByAuthorizedUri(
    faceMedia: Map<String, IndexedMedia>,
    resolve: (IndexedMedia) -> String?
): Map<String, Set<NativeFaceObservationId>> {
    val uris = HashMap<IndexedMedia, String?>()
    val out = HashMap<String, MutableSet<NativeFaceObservationId>>()
    for ((face, media) in faceMedia) {
        val uri = if (media in uris) uris[media] else resolve(media).also { uris[media] = it }
        if (uri != null) out.getOrPut(uri) { HashSet() } += NativeFaceObservationId(face)
    }
    return out
}

/**
 * #551 person page: the permission-gated gallery records that hold the
 * person's faces, photos and videos alike, newest first like Photos.
 */
internal fun selectPersonPageMedia(
    sorted: List<MediaRecord>,
    authorizedUris: Set<String>,
    cancellationCheckpoint: () -> Unit = {}
): List<MediaRecord> {
    if (authorizedUris.isEmpty()) return emptyList()
    return sorted.filterIndexed { index, record ->
        if ((index and 511) == 0) cancellationCheckpoint()
        record.uri in authorizedUris
    }.sortedWith(compareByDescending<MediaRecord> { it.takenAtMillis }
        .thenByDescending { it.id }.thenBy { it.uri })
        .also { cancellationCheckpoint() }
}

/** #543: a kept "Discover faces" choice; absent = off. */
internal data class PeopleOptIn(val paused: Boolean)

/**
 * #31: the last face catalogue publication. [grant] is the photo grant it was
 * published under (null grants are never stored), [inventory] a digest of the
 * media list it staged. Opaque tokens and a hash only: no URI, ID or name.
 */
/**
 * #31: the full-library photo grant as a token for [PeopleDiscoveryHost];
 * null without image access or with selected-photos access only.
 */
internal fun galleryPhotoGrantToken(context: Context): String? =
    readGalleryMediaGrant(context).let { g ->
        if (!g.images || g.selectionScoped) null
        else "images" + (if (g.videos) "+videos" else "")
    }

internal data class PublishedFaceScope(val epoch: String, val grant: String, val inventory: String)

/** Visual publication is bound to its face epoch, full grant and inventory. */
internal data class PublishedVisualScope(
    val faceEpoch: String, val epoch: String, val grant: String,
    val inventory: String, val leftOutPhotos: Int
)

/**
 * #31: a stable digest of what a publication would stage. Equal digests mean
 * the same media, revisions and order, so the catalogue needs no restage.
 */
internal fun faceInventoryDigest(newestFirst: List<FaceVisibleMedia>, salt: String = ""): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(salt.toByteArray(Charsets.UTF_8))
    digest.update(0)
    val longs = java.nio.ByteBuffer.allocate(8 * 8)
    for (m in newestFirst) {
        digest.update(m.uri.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(m.volumeName.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update((m.providerVersion ?: "").toByteArray(Charsets.UTF_8))
        digest.update(0)
        longs.clear()
        longs.putLong(m.id).putLong(m.generationModified).putLong(m.sizeBytes)
            .putLong(m.modifiedAtSeconds).putLong(m.editorRevision).putLong(m.durationMs)
            .putLong(m.takenAtMillis)
            .putInt(m.widthPixels).putInt(m.heightPixels)
        digest.update(longs.array(), 0, longs.position())
        digest.update(byteArrayOf(if (m.isVideo) 1 else 0, (m.orientationDegrees / 90).toByte()))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** #543: where the host keeps [PeopleOptIn] across process restarts. */
internal interface PeopleOptInMemory {
    fun load(): PeopleOptIn?
    fun save(value: PeopleOptIn?)
    /** Separate user choice; still fenced by the current Android photo grant. */
    fun loadVisual(): Boolean = false
    fun saveVisual(enabled: Boolean) = Unit
    /** #31: which epoch was last published, under which grant, for which inventory. */
    fun loadPublished(): PublishedFaceScope? = null
    fun savePublished(value: PublishedFaceScope?) = Unit
    fun loadPublishedVisual(): PublishedVisualScope? = null
    fun savePublishedVisual(value: PublishedVisualScope?) = Unit

    companion object {
        /** Forget on process death (tests, and the pre-#543 behaviour). */
        val NONE = object : PeopleOptInMemory {
            override fun load(): PeopleOptIn? = null
            override fun save(value: PeopleOptIn?) = Unit
        }

        /** App-private SharedPreferences; holds two booleans, no media or identity. */
        fun preferences(context: Context): PeopleOptInMemory = object : PeopleOptInMemory {
            private val prefs = context.getSharedPreferences("atlas_people_discovery",
                Context.MODE_PRIVATE)
            override fun load(): PeopleOptIn? =
                if (prefs.getBoolean("opted_in", false))
                    PeopleOptIn(prefs.getBoolean("paused", false)) else null
            override fun save(value: PeopleOptIn?) {
                prefs.edit().putBoolean("opted_in", value != null)
                    .putBoolean("paused", value?.paused == true).commit()
            }
            override fun loadVisual(): Boolean = prefs.getBoolean("visual_index_opted_in", false)
            override fun saveVisual(enabled: Boolean) {
                prefs.edit().putBoolean("visual_index_opted_in", enabled).commit()
            }
            override fun loadPublished(): PublishedFaceScope? {
                val epoch = prefs.getString("published_epoch", null) ?: return null
                val grant = prefs.getString("published_grant", null) ?: return null
                val inventory = prefs.getString("published_inventory", null) ?: return null
                return PublishedFaceScope(epoch, grant, inventory)
            }
            override fun savePublished(value: PublishedFaceScope?) {
                prefs.edit().putString("published_epoch", value?.epoch)
                    .putString("published_grant", value?.grant)
                    .putString("published_inventory", value?.inventory).commit()
            }
            override fun loadPublishedVisual(): PublishedVisualScope? {
                val face = prefs.getString("published_visual_face", null) ?: return null
                val epoch = prefs.getString("published_visual_epoch", null) ?: return null
                val grant = prefs.getString("published_visual_grant", null) ?: return null
                val inventory = prefs.getString("published_visual_inventory", null) ?: return null
                val leftOut = prefs.getInt("published_visual_left_out", 0)
                return PublishedVisualScope(face, epoch, grant, inventory, leftOut)
            }
            override fun savePublishedVisual(value: PublishedVisualScope?) {
                prefs.edit().putString("published_visual_face", value?.faceEpoch)
                    .putString("published_visual_epoch", value?.epoch)
                    .putString("published_visual_grant", value?.grant)
                    .putString("published_visual_inventory", value?.inventory)
                    .putInt("published_visual_left_out", value?.leftOutPhotos ?: 0).commit()
            }
        }
    }
}

/**
 * The native person album: the permission-gated gallery list, in its own
 * order, narrowed to [authorizedUris] (photos the host resolved through the
 * current grant) and optionally to the B&W folder, like a V4 person album.
 */
internal fun selectNativePersonAlbum(
    sorted: List<MediaRecord>,
    authorizedUris: Set<String>,
    blackAndWhiteFolderOnly: Boolean = false,
    cancellationCheckpoint: () -> Unit = {}
): List<MediaRecord> {
    if (authorizedUris.isEmpty()) return emptyList()
    return sorted.filterIndexed { index, record ->
        if ((index and 511) == 0) cancellationCheckpoint()
        !record.isVideo && record.uri in authorizedUris &&
            (!blackAndWhiteFolderOnly || isInBlackAndWhiteFolder(record.relativePath))
    }.also { cancellationCheckpoint() }
}

/**
 * Pure phase choice for a started run, separated for JVM tests. A user pause
 * wins; only a worker's actual batch (or an in-flight DB row) is RUNNING.
 * A pending queue without an active batch is QUEUED, not a fictitious scan;
 * a drained catalog shows REVIEW_AVAILABLE or COMPLETE.
 */
internal fun startedScanPhase(
    pausedByUser: Boolean, pending: Int, running: Int,
    awaitingReview: Int, hold: FaceScanPhase?, grouping: Boolean = false,
    activeBatch: Boolean = false
): FaceScanPhase = when {
    pausedByUser -> FaceScanPhase.PAUSED
    hold == FaceScanPhase.PERMISSION_REQUIRED -> FaceScanPhase.PERMISSION_REQUIRED
    grouping && hold == null -> FaceScanPhase.GROUPING
    pending + running > 0 -> hold ?: if (activeBatch || running > 0)
        FaceScanPhase.RUNNING else FaceScanPhase.QUEUED
    awaitingReview > 0 -> FaceScanPhase.REVIEW_AVAILABLE
    else -> FaceScanPhase.COMPLETE
}

/**
 * #508: the face catalogue's order, newest capture first (then the newest
 * MediaStore id). Stable, so equal rows keep the gallery's order.
 */
internal fun newestFirst(inventory: List<FaceVisibleMedia>): List<FaceVisibleMedia> =
    inventory.sortedWith(compareByDescending<FaceVisibleMedia> { it.takenAtMillis }
        .thenByDescending { it.id })

/** Batches that ended on a budget or one failed photo are followed by another. */
internal enum class NextBatch { NOW, LATER, NONE }

/**
 * What follows a batch of a still opted-in, unpaused, granted run. A budget
 * stop or one failed photo continues at once. A hold (thermal, unplugged,
 * battery, detector busy or stalled) retries after a delay, so the run never
 * ends silently while People still says it is scanning (#436 review F1).
 * A drained queue or a stale scope queues nothing.
 */
internal fun nextBatchAfter(stop: FaceBatchStop): NextBatch = when (stop) {
    FaceBatchStop.PHOTO_LIMIT, FaceBatchStop.TIME_LIMIT, FaceBatchStop.PHOTO_FAILED -> NextBatch.NOW
    FaceBatchStop.HELD, FaceBatchStop.DETECTOR_PENDING, FaceBatchStop.DETECTOR_STALLED -> NextBatch.LATER
    FaceBatchStop.NO_PENDING, FaceBatchStop.PERMISSION_STALE -> NextBatch.NONE
}

/**
 * The visual stage's own next batch (VS-S1c). Progress continues at once; a
 * guard or held step waits; nothing claimed ends the stage unless queued items
 * remain (then it retries later instead of spinning). Null: the stage did not run.
 */
internal fun nextVisualBatchAfter(result: VisualScanResult?, stillQueued: Boolean): NextBatch =
    when (result?.stop) {
        null -> NextBatch.NONE
        VisualScanStop.COMPLETED -> NextBatch.NOW
        VisualScanStop.BUDGET_OR_GUARD ->
            if (result.indexed + result.skipped > 0) NextBatch.NOW else NextBatch.LATER
        VisualScanStop.HELD -> NextBatch.LATER
        VisualScanStop.NOTHING_CLAIMED -> if (stillQueued) NextBatch.LATER else NextBatch.NONE
    }

/** Faces and visual vectors progress independently; the more urgent one wins. */
internal fun combineNextBatch(faces: NextBatch, visual: NextBatch): NextBatch = when {
    faces == NextBatch.NOW || visual == NextBatch.NOW -> NextBatch.NOW
    faces == NextBatch.LATER || visual == NextBatch.LATER -> NextBatch.LATER
    else -> NextBatch.NONE
}

/**
 * The app-side owner (lane I) of native face discovery, #433. Holds the only
 * opt-in, which lives in this process and is never persisted: after a restart
 * or a total photo-access loss the user must press "Discover faces" again, and
 * nothing scans on boot or app launch. Each catalogue publication gets a fresh,
 * single-use permission epoch; the store hides every older one.
 *
 * Batches: the worker scans at most 8 photos or 2 s, then [Session.afterBatch]
 * publishes clusters on [nl.atlas.gallery.featurepersistence.ClusterPublicationCadence]
 * (scanFinished when the queue drains) and queues the next batch while the run
 * is still opted in, unpaused and granted. The catalogue is published newest
 * first and the scan claims newest first (#508); a library above
 * [maxCatalogPhotos] scans its newest [maxCatalogPhotos] photos.
 *
 * Every blocking method runs on an IO thread, never on main.
 */
internal class PeopleDiscoveryHost(
    private val store: FaceIndexStore,
    private val photoGrantCurrent: () -> Boolean,
    private val inferenceFactory: () -> OnDeviceFaceLocator,
    private val readerFor: (FaceAuthorizedMediaDirectory<FaceVisibleMedia>) -> FaceFrameReader,
    private val power: () -> PeoplePowerState,
    private val scheduler: PeopleScanScheduling,
    private val detectorVersion: String = YuNetModel.DETECTOR_VERSION,
    private val maxCatalogPhotos: Int = MAX_CATALOG_PHOTOS,
    /** Review face crops (#438); none (neutral icons) unless the app supplies a decoder. */
    private val faceCropDecoder: FaceCropDecoder = FaceCropDecoder { _, _ -> null },
    /** VS-S1c visual search index; null leaves the opt-in unavailable. */
    private val visualStore: VisualIndexStore? = null,
    /** Opened on the worker thread, once per scan run (open re-hashes 103 MB). */
    private val visualEmbedderFactory: () -> ImageEmbedder = {
        throw IllegalStateException("No image embedder installed")
    },
    /** Decodes an authorised image; throws SecurityException when it is not. */
    private val visualDecoderFor:
        (FaceAuthorizedMediaDirectory<FaceVisibleMedia>) -> (VisualMedia) -> Bitmap? =
        { { null } },
    /**
     * VS-V2: samples an authorised video's VS-V1a keyframes; throws
     * SecurityException when it is not authorised. Null: videos stay out of
     * the visual index (photos only, as in VS-S1c).
     */
    private val visualVideoFramesFor:
        ((FaceAuthorizedMediaDirectory<FaceVisibleMedia>) -> (VisualMedia) ->
            VisualVideoFrames<Bitmap>)? = null,
    private val visualVideoBudget: VisualVideoBudget = VisualVideoBudget(),
    private val visualModelVersion: String = SiglipModel.MODEL_VERSION,
    private val visualDimension: Int = SiglipModel.DIMENSION,
    /** Deletes the joined model copy in no-backup storage; true when gone. */
    private val deleteVisualModelCopy: () -> Boolean = { true },
    private val visualModelCopyExists: () -> Boolean = { false },
    private val clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    /** Cluster publication hook (#508 review): the store's; tests inject failures. */
    private val publishClusters: (epoch: String, scanFinished: Boolean) -> Boolean =
        store::publishClustersIfDue,
    /**
     * #543: the user's "Discover faces" choice (and pause), kept across
     * process restarts in app-private storage. Never a permission: a restored
     * choice still waits for the first current gallery publication and a
     * fresh epoch. In memory only unless the app supplies storage.
     */
    private val optInMemory: PeopleOptInMemory = PeopleOptInMemory.NONE,
    /**
     * #31: the live Android photo grant as a token, or null when it may not
     * carry a published catalogue across a publication: no access, or
     * selected-photos access, whose set can change without the token
     * changing. Equal non-null tokens let the last catalogue stay readable
     * (also after a process restart) while the next one stages. Null (the
     * default) keeps the strict behaviour: every publication restages blind.
     */
    private val photoGrantToken: () -> String? = { null }
) {
    companion object {
        /**
         * #508: newest photos face discovery covers. Measured at 300k photos
         * in FaceIndexStoreScaleAndroidTest; 400k photos plus the video
         * budget fit the store's 500k catalogue.
         */
        const val MAX_CATALOG_PHOTOS = 400_000
        /** Queued visual candidates read per window (#482). */
        const val VISUAL_WINDOW = 64
        /** Windows one visual step may read before it hands over (#482 follow-up). */
        const val VISUAL_WINDOWS_PER_STEP = 16
        /**
         * #508 review: delayed retries of a failed publication after the photo
         * queue drained, in a row. Past it the run stops retrying; the next
         * gallery publication or start runs it again.
         */
        const val MAX_PUBLICATION_RETRIES = 3
        /** #580: parts of [FaceIndexStore.MAX_PUBLICATION_FACES] one regroup runs at once. */
        const val MAX_REGROUP_PARTS = 64

        @Volatile private var app: PeopleDiscoveryHost? = null

        fun forApp(context: Context): PeopleDiscoveryHost = app ?: synchronized(this) {
            app ?: create(context.applicationContext).also { app = it }
        }

        /**
         * #600: an instrumented test installs a host over synthetic media
         * before MainActivity starts, so the real Activity drives it; null
         * lets the next [forApp] build the app's own again. App code never calls it.
         */
        @androidx.annotation.VisibleForTesting
        internal fun replaceAppForTest(host: PeopleDiscoveryHost?) {
            synchronized(this) { app = host }
        }

        private fun create(context: Context): PeopleDiscoveryHost = PeopleDiscoveryHost(
            optInMemory = PeopleOptInMemory.preferences(context),
            photoGrantToken = { galleryPhotoGrantToken(context) },
            store = FaceIndexStore(context),
            photoGrantCurrent = { readGalleryMediaGrant(context).let { it.images || it.selected } },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(context.assets),
                    OnnxFaceEmbedder.fromAssets(context.assets))
            },
            readerFor = { directory ->
                // VS-V1b: videos are scanned through bounded VS-V1a keyframes.
                AndroidMediaFaceFrameReader(context.contentResolver,
                    { android.net.Uri.parse(directory.authorizedUri(it)) },
                    videos = AuthorizedVideoFrames(VideoKeyframeSampler(context),
                        directory::authorizedSource))
            },
            power = { devicePower(context) },
            faceCropDecoder = MediaStoreFaceCropDecoder(context.contentResolver),
            visualStore = VisualIndexStore(context),
            visualEmbedderFactory = { SiglipImageEmbedder.open(context) },
            visualDecoderFor = { directory ->
                { media ->
                    // Same gate as faces: only a URI the live grant resolves.
                    val uri = directory.authorizedUri(FaceMediaKey(media.key, media.revision))
                    SiglipImageEmbedder.decodeOriented(ImageDecoder.createSource(
                        context.contentResolver, android.net.Uri.parse(uri)))
                }
            },
            visualVideoFramesFor = { directory ->
                val sampler = VideoKeyframeSampler(context)
                val sample: (VisualMedia) -> VisualVideoFrames<Bitmap> =
                    { media -> sampleVisualVideo(sampler, directory::authorizedSource, media) }
                sample
            },
            deleteVisualModelCopy = { SiglipImageEmbedder.deleteModelCopy(context) },
            visualModelCopyExists = {
                java.io.File(context.noBackupFilesDir, SiglipImageEmbedder.MODEL_DIR).exists()
            },
            scheduler = object : PeopleScanScheduling {
                override fun start() {
                    PeopleScanWorkScheduler.enqueueAfterVerifiedOptIn(context)
                    PeopleScanWorkScheduler.enqueueRearm(context)
                }
                override fun disarm() = PeopleScanWorkScheduler.cancelRearm(context)
                override fun next() = PeopleScanWorkScheduler.enqueueNextBatch(context)
                override fun later() = PeopleScanWorkScheduler.enqueueNextBatch(context, delayed = true)
                override fun cancel() = PeopleScanWorkScheduler.cancel(context)
            }
        )

        private fun devicePower(context: Context): PeoplePowerState {
            val battery = context.registerReceiver(null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
            return PeoplePowerState(
                charging = plugged,
                batteryNotLow = plugged || percent > 15,
                storageNotLow = context.filesDir.usableSpace > 512L * 1024 * 1024
            )
        }
    }

    private class Scope(
        val epoch: String,
        val galleryGrant: String,
        val directory: FaceAuthorizedMediaDirectory<FaceVisibleMedia>,
        /** The same list the directory holds, for a later visual publication. */
        val inventory: List<FaceVisibleMedia>,
        /** Null for a catalogue revived from a previous process (#31). */
        val provenance: FaceCatalogPublication?
    )

    /**
     * A visual catalogue published for one face scope, under its own epoch.
     * [cursor] is where the next step's candidate window starts (#482
     * follow-up). A revived scope starts with a fresh in-memory cursor.
     * Staging behind the readable face catalogue holds the worker.
     */
    private class VisualScope(
        val faceEpoch: String,
        val epoch: String,
        /** #631: photos left out because Android gives them no fixed ID. */
        val leftOutPhotos: Int = 0,
        val staging: Boolean = false
    ) {
        val cursor = AtomicReference<VisualMedia?>(null)
    }

    private data class Snapshot(
        val optedIn: Boolean, val paused: Boolean, val scope: Scope?, val failure: String?
    )

    /** Failed publications in a row after a drained queue (#508 review). */
    private val publicationFailures = java.util.concurrent.atomic.AtomicInteger()
    private val lock = Any()
    private var optedIn = false
    private var pausedByUser = false
    private var scope: Scope? = null
    private var failure: String? = null
    private var failedGrant: String? = null
    init {
        // #543: re-arm a choice the user made before this process started.
        optInMemory.load()?.let { optedIn = true; pausedByUser = it.paused }
    }
    /** Epoch the store may serve right now; any change holds old engines. */
    @Volatile private var liveEpoch: String? = null
    /**
     * #31: a publication staging behind a still-readable [liveEpoch]. The scan
     * worker holds while it is set; reads keep serving [liveEpoch].
     */
    @Volatile private var stagingEpoch: String? = null
    /** True only while a scan batch is actually publishing groups for this epoch. */
    @Volatile private var groupingEpoch: String? = null
    /** Real granted worker-session window, never inferred from queued rows. */
    @Volatile private var scanBatchEpoch: String? = null
    private var inference: OnDeviceFaceLocator? = null
    private val reviewGeneration = AtomicLong()

    // The user's separate opt-in survives host/process recreation. Stored choice
    // is never authorization: every read and publication still checks live grant.
    private var visualOptedIn = optedIn && optInMemory.loadVisual()
    private var visualFailure: String? = null
    /** Rows or the model copy may remain after an opt-out without delete. */
    private var visualDataKept = false
    @Volatile private var visualScope: VisualScope? = null
    private val embedderLock = Any()
    private var visualEmbedder: ImageEmbedder? = null
    private val faceCrops = FaceCropCache<Bitmap>()

    /**
     * The identity-review snapshot last handed out, with the store generation
     * it was read at. The same object is reused while that generation holds,
     * so the review screen and its commands compare one reference;
     * a commit replaces it with the reducer's result, which keeps
     * the in-memory Undo journal. A new epoch or a cluster publication starts
     * a fresh snapshot (and drops Undo), as PeopleClusterReviewState requires.
     */
    private class LiveReview(
        val state: PeopleClusterReviewState,
        val storeGeneration: String
    )
    private class LiveHome(val state: PeopleHomeSummary, val storeGeneration: String)
    @Volatile private var liveHome: LiveHome? = null
    private val reviewLock = Any()
    @Volatile private var liveReview: LiveReview? = null
    private val _changes = MutableStateFlow(0L)

    /** Bumped after every state change and every batch; Compose rereads [status]. */
    val changes: StateFlow<Long> get() = _changes
    private fun changed() { _changes.value = _changes.value + 1 }

    private val provider = PeopleScanRuntimeProvider { acquire() }

    /**
     * #658: one catalogue publication at a time. Each publication starts its
     * own store snapshot, which wipes any other one still staging; two at once
     * (an Activity recreate, "Discover faces" racing the next refresh) made
     * the earlier one fail its next stage write and turned discovery off.
     */
    private val publishLock = Any()

    /** A stored choice is safe to expose as status, not as permission to read faces. */
    fun hasSavedScanOptIn(): Boolean = synchronized(lock) { optedIn }
    fun hasSavedVisualOptIn(): Boolean = synchronized(lock) { visualOptedIn }

    /** The token to pass as PeopleHomeScreen's currentPermissionEpoch. */
    fun displayEpoch(galleryGrant: String?): String? = synchronized(lock) {
        scope?.takeIf { it.galleryGrant == galleryGrant && optedIn }?.epoch
            ?: galleryGrant?.let { "atlas-people-gallery:$it" }
    }

    /** #31: true while a readable catalogue already serves [galleryGrant]. */
    fun serves(galleryGrant: String?): Boolean = galleryGrant != null && synchronized(lock) {
        scope?.let { it.galleryGrant == galleryGrant && liveEpoch == it.epoch } == true
    }

    /** True while [epoch] is the live, granted scope of an opted-in run. */
    fun scopeCurrent(epoch: String): Boolean =
        liveEpoch == epoch && synchronized(lock) { optedIn } && photoGrantCurrent()

    /** The user's explicit "Discover faces". Blocking; IO only. */
    fun start(galleryGrant: String, inventory: List<FaceVisibleMedia>) {
        if (!photoGrantCurrent()) return
        synchronized(lock) {
            optedIn = true
            pausedByUser = false
            failure = null
        }
        optInMemory.save(PeopleOptIn(paused = false))
        try {
            if (synchronized(lock) { inference } == null) {
                val built = inferenceFactory()
                synchronized(lock) { inference = built }
            }
        } catch (e: RuntimeException) {
            fail("The on-device face model could not be loaded. No photo was changed.",
                galleryGrant)
            return
        }
        if (synchronized(publishLock) { publish(galleryGrant, inventory) }) {
            PeopleScanWorkRuntime.installForOptedInHost(provider)
            scheduler.start()
        }
        changed()
    }

    fun pause() {
        synchronized(lock) { if (!optedIn) return; pausedByUser = true }
        optInMemory.save(PeopleOptIn(paused = true))
        scheduler.cancel()
        scheduler.disarm()
        changed()
    }

    fun resume() {
        val ok = synchronized(lock) {
            (optedIn && scope != null && liveEpoch == scope?.epoch).also {
                if (it) pausedByUser = false
            }
        }
        if (ok) optInMemory.save(PeopleOptIn(paused = false))
        if (ok && photoGrantCurrent()) {
            PeopleScanWorkRuntime.installForOptedInHost(provider)
            scheduler.start()
        }
        changed()
    }

    /**
     * Photo access lost or the user turned discovery off: stop the worker,
     * refuse new sessions and hide every observation in the same store call.
     * Keeps the private index for reconciliation; never touches V4 People.
     */
    fun revoke() {
        synchronized(lock) {
            optedIn = false
            pausedByUser = false
            scope = null
            liveEpoch = null
            stagingEpoch = null
        }
        // Explicit off or real loss of photo access: the next start is a new opt-in.
        optInMemory.save(null)
        optInMemory.savePublished(null)
        optInMemory.savePublishedVisual(null)
        liveReview = null
        liveHome = null
        faceCrops.clear()
        PeopleScanWorkRuntime.clear()
        scheduler.cancel()
        scheduler.disarm()
        store.invalidatePermissionScope()
        stopVisual(keepOptIn = false)
        changed()
    }

    /**
     * Stops the visual stage now: hides the visual scope, fences every lease
     * (store invalidation) and closes the run's embedder. A running step then
     * holds; nothing it computed can be published.
     */
    private fun stopVisual(keepOptIn: Boolean) {
        synchronized(lock) {
            if (!keepOptIn) {
                if (visualOptedIn) visualDataKept = true
                visualOptedIn = false
            }
        }
        if (!keepOptIn) optInMemory.saveVisual(false)
        optInMemory.savePublishedVisual(null)
        visualScope = null
        try { visualStore?.invalidatePermissionScope() } catch (_: SQLException) { }
        closeEmbedder()
    }

    private fun closeEmbedder() {
        val open = synchronized(embedderLock) { visualEmbedder.also { visualEmbedder = null } }
        try { open?.close() } catch (_: RuntimeException) { }
    }

    /**
     * The user's "Visual search index" switch. On: publishes the visual
     * catalogue for the live face scope and starts the worker if the run is
     * not paused; refused (false) without a started face run. Off: stops the
     * stage at once; [deleteData] also deletes the visual index and the
     * joined model copy (about 103 MB). Blocking; IO only.
     */
    fun setVisualIndex(enabled: Boolean, deleteData: Boolean = false): Boolean {
        val store = visualStore ?: return false
        if (!enabled) {
            stopVisual(keepOptIn = false)
            if (deleteData) deleteVisualData(store)
            changed()
            return true
        }
        val s = synchronized(lock) {
            if (!optedIn) return false
            visualOptedIn = true
            visualFailure = null
            scope?.takeIf { liveEpoch == it.epoch }
        }
        // Commit before the potentially long catalogue stage. If it cannot
        // run now, the next authorised publication can recover the choice.
        optInMemory.saveVisual(true)
        val published = s != null && photoGrantCurrent() &&
            (visualScope?.faceEpoch == s.epoch || publishVisual(s.epoch, s.inventory))
        if (published && !synchronized(lock) { pausedByUser }) {
            PeopleScanWorkRuntime.installForOptedInHost(provider)
            scheduler.start()
        }
        changed()
        return true
    }

    /** Opt-out cleanup: the index rows and the joined model copy. Blocking; IO only. */
    fun deleteVisualIndex() {
        val store = visualStore ?: return
        if (synchronized(lock) { visualOptedIn }) return
        deleteVisualData(store)
        changed()
    }

    private fun deleteVisualData(store: VisualIndexStore) {
        closeEmbedder()
        val rows = try { store.deleteIndex(); true } catch (_: SQLException) { false }
        val model = try { deleteVisualModelCopy() } catch (_: RuntimeException) { false }
        synchronized(lock) { visualDataKept = !(rows && model) }
    }

    /**
     * Publishes the visual catalogue (the face keys and revisions of photos
     * and, with a video sampler, of up to [VisualVideoBudget.MAX_CATALOG_VIDEOS]
     * complete videos in inventory order; later videos are deferred) under a
     * fresh single-use epoch for [faceEpoch]. Unchanged items keep their
     * vectors, moments and skips; changed ones are queued again.
     *
     * #631: a photo without a fixed MediaStore volume and ID is left out and
     * counted, exactly as face discovery omits it ([FaceCatalogAdmission]);
     * it never switches the index off for every other photo.
     */
    private fun visualCatalogueSalt(): String =
        "${visualModelVersion}|${visualDimension}|${visualVideoFramesFor != null}|" +
            VisualVideoBudget.MAX_CATALOG_VIDEOS.toString()

    /** Only a carried face scope under the same full grant can revive visual reads. */
    private fun reviveVisual(faceEpoch: String, token: String,
                             inventory: List<FaceVisibleMedia>): Boolean {
        val store = visualStore ?: return false
        if (!synchronized(lock) { visualOptedIn } || !scopeCurrent(faceEpoch) ||
            photoGrantToken() != token) return false
        val saved = optInMemory.loadPublishedVisual() ?: return false
        if (saved.faceEpoch != faceEpoch || saved.grant != token ||
            saved.inventory != faceInventoryDigest(inventory, visualCatalogueSalt())) return false
        val revived = visualScope?.takeIf {
            it.faceEpoch == faceEpoch && it.epoch == saved.epoch && !it.staging
        }?.epoch ?: try { store.reviveDormantEpoch(visualModelVersion, visualDimension) }
            catch (_: SQLException) { null }
        if (revived != saved.epoch || !scopeCurrent(faceEpoch) || photoGrantToken() != token) {
            if (revived != null) try { store.invalidateSnapshotIfCurrent(revived) }
                catch (_: SQLException) { }
            optInMemory.savePublishedVisual(null)
            return false
        }
        visualScope = VisualScope(faceEpoch, revived, saved.leftOutPhotos)
        return true
    }

    private fun publishVisual(faceEpoch: String, inventory: List<FaceVisibleMedia>): Boolean {
        val store = visualStore ?: return false
        if (!synchronized(lock) { visualOptedIn }) return false
        val epoch = "atlas-visual-" + UUID.randomUUID()
        val current = {
            liveEpoch == faceEpoch && synchronized(lock) { optedIn && visualOptedIn } &&
                photoGrantCurrent()
        }
        var begun = false
        var done = false
        try {
            store.beginSnapshot(epoch)
            begun = true
            val batch = ArrayList<VisualMedia>(VisualIndexStore.MAX_BATCH)
            val withVideos = visualVideoFramesFor != null
            var videos = 0
            var leftOutPhotos = 0
            for (row in inventory) {
                // VS-V2: videos spend their own budget and never fail the photos.
                // #631: neither does a photo without a fixed ID; it is counted.
                val key = if (!row.isVideo) {
                    val photo = row.durableFaceKeyOrNull()
                    if (photo == null) { leftOutPhotos++; continue }
                    photo
                } else if (!withVideos || videos >= VisualVideoBudget.MAX_CATALOG_VIDEOS) continue
                    else row.durableFaceKeyOrNull()?.also { videos++ } ?: continue
                batch += VisualMedia(key.canonicalMediaKey, key.sourceRevision)
                if (batch.size == VisualIndexStore.MAX_BATCH) {
                    check(current()) { "Visual catalogue scope changed" }
                    store.stageBatch(epoch, batch)
                    batch.clear()
                }
            }
            check(current()) { "Visual catalogue scope changed" }
            if (batch.isNotEmpty()) store.stageBatch(epoch, batch)
            store.commitSnapshot(epoch, visualModelVersion, visualDimension)
            check(current()) { "Visual catalogue scope changed" }
            visualScope = VisualScope(faceEpoch, epoch, leftOutPhotos)
            val token = photoGrantToken()
            optInMemory.savePublishedVisual(
                if (token != null && current()) PublishedVisualScope(faceEpoch, epoch, token,
                    faceInventoryDigest(inventory, visualCatalogueSalt()), leftOutPhotos)
                else null)
            if (leftOutPhotos > 0) {
                Log.i("ATLAS_VISUAL", "Visual catalogue left out $leftOutPhotos photos without a fixed ID")
            }
            synchronized(lock) { visualDataKept = true }
            done = true
            return true
        } catch (_: IllegalStateException) {
            return false // a newer publication, pause or revoke won the race
        } catch (_: IllegalArgumentException) {
            // The photo list contradicted itself (one photo listed twice with
            // different versions) or outgrew the index. Retrying re-reads it.
            failVisual("The visual index could not get a consistent list of your photos. " +
                "Turn it on again to retry. No photo was changed.")
            return false
        } catch (_: SQLException) {
            failVisual("The visual index could not be saved on this phone (storage may be " +
                "full). Turn it on again to retry. No photo was changed.")
            return false
        } catch (e: RuntimeException) {
            // A malformed provider/staging response must not crash Settings.
            // Only an exception class is logged; never source URIs or images.
            Log.w("ATLAS_VISUAL", "visual_catalogue_failed type=${e.javaClass.simpleName}")
            failVisual("The visual index encountered an error. Turn it off and on to retry.")
            return false
        } finally {
            if (begun && !done) {
                try { store.invalidateSnapshotIfCurrent(epoch) } catch (_: SQLException) { }
            }
        }
    }

    private fun failVisual(message: String) {
        // A failed write/model stage is not user opt-out. Fence every lease,
        // keep the durable choice and surface the error until retry.
        stopVisual(keepOptIn = true)
        synchronized(lock) { visualFailure = message }
        changed()
    }

    /** The run's embedder, opened on first use on the worker thread. */
    private fun embedderForRun(): ImageEmbedder? {
        synchronized(embedderLock) { visualEmbedder?.let { return it } }
        val opened = try {
            visualEmbedderFactory()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // a missing or bad model, including a checked OrtException
        } ?: run {
            failVisual("The on-device visual search model could not be loaded. No photo was changed.")
            return null
        }
        if (opened.modelVersion != visualModelVersion || opened.dimension != visualDimension) {
            opened.close()
            failVisual("The on-device visual search model does not match its index. No photo was changed.")
            return null
        }
        val kept = synchronized(embedderLock) {
            when {
                !synchronized(lock) { visualOptedIn } -> null
                visualEmbedder != null -> visualEmbedder
                else -> opened.also { visualEmbedder = it }
            }
        }
        if (kept !== opened) opened.close()
        return kept
    }

    /**
     * A new gallery publication (MediaStore refresh or a changed grant). While
     * a run is opted in, republish under a fresh epoch. A null gallery grant
     * means publication is pending only while Android still grants access.
     * An Android permission loss revokes even on a fresh process with no
     * in-memory scope. Blocking; IO only.
     */
    fun onGalleryPublication(galleryGrant: String?, inventory: () -> List<FaceVisibleMedia>) {
        val (active, current, paused) = synchronized(lock) {
            Triple(optedIn, scope?.galleryGrant, pausedByUser)
        }
        if (!active) {
            // A failed start is retryable from the next publication (receipt optional 3).
            val cleared = synchronized(lock) {
                (failure != null && failedGrant != galleryGrant).also { if (it) failure = null }
            }
            if (cleared) changed()
            return
        }
        // An OS permission check is authoritative across process death:
        // scope == null is also true after a real revoke while ATLAS was stopped.
        // Keeping a stored opt-in in that case would silently restart discovery
        // when access is granted again. The pending gallery-publication signal
        // below is distinct from the Android permission decision.
        if (!photoGrantCurrent()) {
            Log.i("ATLAS_FACE", "publication_revoke cause=android_grant_lost")
            revoke()
            return
        }
        if (galleryGrant == null) {
            Log.i("ATLAS_FACE", "publication_hold cause=gallery_pending")
            hold()
            return
        }
        if (current == galleryGrant && liveEpoch != null &&
            liveEpoch == synchronized(lock) { scope?.epoch }) return
        val started = clockElapsedMs()
        var mode = "restage"
        var items = 0
        val published = synchronized(publishLock) {
            // Re-check inside the lock: a publication that just finished may
            // already serve this grant, or the user may have turned it off.
            val (stillActive, nowCurrent) = synchronized(lock) { optedIn to scope?.galleryGrant }
            if (!stillActive) return@synchronized false
            if (nowCurrent == galleryGrant && liveEpoch != null &&
                liveEpoch == synchronized(lock) { scope?.epoch }) return@synchronized false
            val sorted = newestFirst(inventory())
            items = sorted.size
            val token = photoGrantToken()
            val digest = token?.let { faceInventoryDigest(sorted, catalogueSalt()) }
            val carry = if (token != null && digest != null)
                carryOver(galleryGrant, token, digest, sorted) else Carry.NONE
            when (carry) {
                Carry.UNCHANGED -> {
                    mode = "carried"
                    // The catalogue is exactly what this inventory would stage.
                    val epoch = synchronized(lock) { scope }!!.epoch
                    if (synchronized(lock) { visualOptedIn } && visualScope?.faceEpoch != epoch) {
                        // Restore visual progress before the potentially long face regroup.
                        if (token == null || !reviveVisual(epoch, token, sorted))
                            publishVisual(epoch, sorted)
                    }
                    regroupIfRulesChanged(epoch)
                    true
                }
                Carry.STAGE_BEHIND -> {
                    mode = "staged-behind"
                    // The previous visual catalogue may remain readable only
                    // behind this still-authorized face inventory; worker holds.
                    if (token != null && synchronized(lock) { visualOptedIn }) {
                        val saved = optInMemory.loadPublishedVisual()
                        val carriedEpoch = synchronized(lock) { scope?.epoch }
                        if (saved != null && saved.faceEpoch == carriedEpoch &&
                            saved.grant == token && carriedEpoch != null &&
                            scopeCurrent(carriedEpoch)) {
                            // A fresh publication is imminent: the old visual
                            // read remains gated by the carried face directory,
                            // while the scan worker is suspended.
                            val currentVisual = visualScope?.takeIf {
                                it.faceEpoch == carriedEpoch && it.epoch == saved.epoch
                            }?.epoch ?: try {
                                visualStore?.reviveDormantEpoch(visualModelVersion, visualDimension)
                            } catch (_: SQLException) { null }
                            if (currentVisual == saved.epoch && photoGrantCurrent() &&
                                photoGrantToken() == token) {
                                visualScope = VisualScope(saved.faceEpoch, currentVisual,
                                    saved.leftOutPhotos, staging = true)
                            } else if (currentVisual != null) {
                                try { visualStore?.invalidateSnapshotIfCurrent(currentVisual) }
                                    catch (_: SQLException) { }
                            }
                        }
                    }
                    changed() // the carried catalogue shows now; the restage follows
                    publish(galleryGrant, sorted, keepReadable = true,
                        known = token!! to digest!!)
                }
                Carry.NONE -> publish(galleryGrant, sorted,
                    known = if (token != null && digest != null) token to digest else null)
            }
        }
        Log.i("ATLAS_FACE", "publication_done mode=$mode items=$items " +
            "published=$published ms=${clockElapsedMs() - started}")
        if (published && !paused) {
            PeopleScanWorkRuntime.installForOptedInHost(provider)
            scheduler.start()
        }
        changed()
    }

    /**
     * #43: a background worker in a process without the Activity. Rebuilds the
     * scope from stored state only: the kept opt-in (not paused), a stored
     * publication, a full-library grant token and [inventory], the gallery's
     * own grant-checked cache. Goes through [onGalleryPublication], so a real
     * grant loss revokes exactly as in the app. True when a run is armed.
     * Blocking; IO only.
     */
    fun restoreForBackgroundWork(inventory: () -> List<FaceVisibleMedia>?): Boolean {
        val (wanted, served) = synchronized(lock) {
            (optedIn && !pausedByUser) to (scope != null && liveEpoch == scope?.epoch)
        }
        if (!wanted) return false
        if (!served) {
            if (!photoGrantCurrent()) {
                // Same rule as a publication: real loss of access revokes.
                onGalleryPublication(null) { emptyList() }
                return false
            }
            // Selected-photos access, nothing published yet or no trusted
            // inventory: this cannot run in the background. Drop the periodic
            // re-arm; the next foreground publication arms it again (#71 S2).
            if (photoGrantToken() == null || optInMemory.loadPublished() == null) {
                scheduler.disarm()
                return false
            }
            val items = inventory() ?: run { scheduler.disarm(); return false }
            onGalleryPublication("background-" + UUID.randomUUID()) { items }
        }
        val armed = synchronized(lock) {
            optedIn && !pausedByUser && scope != null && liveEpoch == scope?.epoch
        }
        Log.i("ATLAS_FACE", "background_restore armed=$armed reused=$served")
        if (armed) PeopleScanWorkRuntime.installForOptedInHost(provider)
        return armed
    }

    private enum class Carry { NONE, UNCHANGED, STAGE_BEHIND }

    /** Versions a stored catalogue was built with; any change forces a restage. */
    private fun catalogueSalt(): String =
        "$detectorVersion|${nl.atlas.gallery.featureface.onnx.SFaceModel.EMBEDDER_VERSION}|" +
            "$maxCatalogPhotos"

    /**
     * #31: serve [galleryGrant] from the catalogue already published under the
     * same full photo grant [token], in this process or (dormant) the last one,
     * so People and Settings read it at once instead of after a full restage.
     * Authorization still comes only from this publication: the directory is
     * built from [sorted], the inventory the live grant shows now, so media
     * that are gone resolve to nothing. Never for selected-photos access (no
     * token), after a revoke or a failure (no stored publication), or when the
     * token changed. Caller holds [publishLock].
     */
    private fun carryOver(
        galleryGrant: String, token: String, digest: String, sorted: List<FaceVisibleMedia>
    ): Carry {
        val saved = optInMemory.loadPublished() ?: return Carry.NONE
        if (saved.grant != token || stagingEpoch != null) return Carry.NONE
        val existing = synchronized(lock) { scope }
        var revived = false
        val epoch = if (existing != null) {
            // This process published it; it may only be held (pending refresh),
            // but a failed publication may also have hidden it in the store.
            if (existing.epoch != saved.epoch) return Carry.NONE
            val active = try { store.activeEpoch() } catch (_: SQLException) { null }
            if (active != existing.epoch) return Carry.NONE
            existing.epoch
        } else {
            val dormant = try { store.reviveDormantEpoch() } catch (_: SQLException) { null }
                ?: return Carry.NONE
            revived = true
            if (dormant != saved.epoch) {
                try { store.invalidateSnapshotIfCurrent(dormant) } catch (_: SQLException) { }
                return Carry.NONE
            }
            dormant
        }
        // The directory validates its epoch while it builds, before this
        // carry-over makes it live; afterwards it follows liveEpoch as usual.
        var building = true
        val directory = FaceAuthorizedMediaDirectory.build(epoch, sorted, { it },
            activeEpoch = { if (building) epoch else liveEpoch },
            providerSnapshotCurrent = photoGrantCurrent,
            maxPhotoRows = maxCatalogPhotos)
        building = false
        val carried = photoGrantCurrent() && photoGrantToken() == token && synchronized(lock) {
            if (!optedIn) return@synchronized false
            scope = Scope(epoch, galleryGrant, directory, sorted, existing?.provenance)
            liveEpoch = epoch
            true
        }
        if (!carried) {
            if (revived) try { store.invalidateSnapshotIfCurrent(epoch) } catch (_: SQLException) { }
            return Carry.NONE
        }
        Log.i("ATLAS_FACE", "publication_carry revived=$revived unchanged=${digest == saved.inventory}")
        return if (digest == saved.inventory) Carry.UNCHANGED else Carry.STAGE_BEHIND
    }

    /** Stop the worker and make every engine hold, keeping the opt-in and pause. */
    private fun hold() {
        if (liveEpoch == null) return
        liveEpoch = null
        scheduler.cancel()
        changed()
    }

    private fun fail(message: String, grant: String? = null) {
        synchronized(lock) {
            failure = message
            failedGrant = grant
            optedIn = false
            scope = null
            liveEpoch = null
            stagingEpoch = null
        }
        optInMemory.savePublished(null)
        optInMemory.savePublishedVisual(null)
        faceCrops.clear()
        PeopleScanWorkRuntime.clear()
        scheduler.cancel()
        scheduler.disarm()
        stopVisual(keepOptIn = false)
        changed()
    }

    /**
     * [keepReadable] (#31): the caller has just carried the current catalogue
     * over under an unchanged full grant, so it stays readable (and
     * [liveEpoch] stays) until this one commits; the worker holds meanwhile.
     * [known] is the grant token and inventory digest the caller computed.
     */
    private fun publish(
        galleryGrant: String,
        unordered: List<FaceVisibleMedia>,
        keepReadable: Boolean = false,
        known: Pair<String, String>? = null
    ): Boolean {
        // #508: newest first, so the photo cap keeps the newest photos and
        // the publisher, directory and visual catalogue all see one order.
        val inventory = newestFirst(unordered)
        val epoch = "atlas-face-" + UUID.randomUUID()
        val token = known?.first ?: photoGrantToken()
        val digest = known?.second ?: token?.let { faceInventoryDigest(inventory, catalogueSalt()) }
        // From here on, engines of the previous scope hold: their epoch is
        // stale, or (keepReadable) a newer one is staging.
        if (keepReadable) stagingEpoch = epoch else liveEpoch = epoch
        val owns = { e: String -> if (keepReadable) stagingEpoch == e else liveEpoch == e }
        val stillWanted = { e: String -> owns(e) && synchronized(lock) { optedIn } }
        return try {
            val publication = FaceCatalogPublisher(store,
                scopeCurrent = {
                    stillWanted(it) && photoGrantCurrent() &&
                        (!keepReadable || photoGrantToken() == token)
                },
                providerSnapshotCurrent = { true },
                maxPhotoRows = maxCatalogPhotos,
                keepActiveReadable = keepReadable
            ).publish(epoch, detectorVersion, inventory.asSequence())
            logPhotoProvenance(publication)
            // Staged behind (#31), the new epoch is committed in the store but
            // not yet live here: validate against it until the swap below.
            var building = keepReadable
            val directory = FaceAuthorizedMediaDirectory.build(epoch, inventory, { it },
                activeEpoch = { if (building) epoch else liveEpoch },
                providerSnapshotCurrent = photoGrantCurrent,
                maxPhotoRows = maxCatalogPhotos)
            building = false
            val published = synchronized(lock) {
                if (!owns(epoch) || !optedIn) return@synchronized false
                liveEpoch = epoch
                stagingEpoch = null
                scope = Scope(epoch, galleryGrant, directory, inventory, publication)
                true
            }
            if (published) {
                optInMemory.savePublished(
                    if (token != null && digest != null) PublishedFaceScope(epoch, token, digest)
                    else null)
            }
            // The visual catalogue follows every face publication, so both
            // stages always share one grant snapshot (and resume together).
            if (published) {
                Log.i("ATLAS_FACE", "publication_ready items=${inventory.size} " +
                    "paused=${synchronized(lock) { pausedByUser }}")
                regroupIfRulesChanged(epoch)
                visualScope = null
                if (synchronized(lock) { visualOptedIn }) publishVisual(epoch, inventory)
            }
            published
        } catch (e: IllegalStateException) {
            // A failed staged-behind publication also hid the carried catalogue
            // (the store fails closed), so nothing stays readable.
            if (owns(epoch)) { liveEpoch = null; stagingEpoch = null }
            false // permission or newer publication won the race; no failure to show
        } catch (e: IllegalArgumentException) {
            // A newer publication or a revoke replaced this epoch mid-stage:
            // the store refuses the stale snapshot. That is a lost race, not
            // a broken index (#658).
            if (!owns(epoch)) return false
            Log.e("ATLAS_FACE", "Face catalogue invariant failed", e)
            fail("The private face index could not be prepared. No photo was changed.",
                galleryGrant)
            false
        } catch (e: SQLException) {
            fail("The private face index could not be written. No photo was changed.",
                galleryGrant)
            false
        }
    }

    /**
     * #580: the grouping rules changed since the stored membership was
     * published. Regrouping needs no photo and no model, only the stored
     * vectors, so it runs here, with the first gallery publication after the
     * update, instead of leaving People blank until the next charging scan.
     * Bounded parts (#508). A failure keeps the membership marked, and the
     * scan worker's next publication regroups it.
     */
    private fun regroupIfRulesChanged(epoch: String) {
        try {
            if (!store.regroupPending(epoch)) return
            var parts = 0
            do {
                if (!store.materializeClusters(epoch)) return
                parts++
            } while (liveEpoch == epoch && parts < MAX_REGROUP_PARTS &&
                store.clusterPublicationContinues(epoch))
            Log.i("ATLAS_FACE", "people_regroup parts=$parts")
        } catch (e: RuntimeException) {
            Log.w("ATLAS_FACE", "people_regroup failed: ${e.javaClass.simpleName}")
        }
    }

    /** Field names and aggregate counts only: no MediaStore URI, ID or filename. */
    private fun logPhotoProvenance(publication: FaceCatalogPublication) {
        val total = publication.observedPhotoRows
        val fields = publication.missingPhotoFields.toSortedMap()
        val histogram = fields.entries.joinToString(", ") { (field, count) ->
            "$field=$count (${if (total == 0) "0.00" else "%.2f".format(java.util.Locale.US, count * 100.0 / total)}%)"
        }
        Log.i("ATLAS_FACE", "Photo provenance: checked=$total, weak=${publication.weakPhotoRows}, " +
            "missingIdentity=${publication.ignoredPhotoRows}; fields=[$histogram]")
    }

    private fun conditions(epoch: String): FaceScanConditions {
        val p = power()
        val (opted, paused) = synchronized(lock) { optedIn to pausedByUser }
        return FaceScanConditions(
            optedIn = opted,
            photosPermissionGranted = photoGrantCurrent(),
            permissionSnapshotCurrent = liveEpoch == epoch,
            batteryNotLow = p.batteryNotLow,
            storageNotLow = p.storageNotLow,
            charging = p.charging,
            pausedByUser = paused,
            backgroundMaintenance = true
        )
    }

    private fun acquire(): PeopleScanSession? {
        val wanted = synchronized(lock) {
            optedIn && !pausedByUser && scope != null && liveEpoch == scope?.epoch &&
                inference == null
        }
        if (wanted) {
            // #543: a restored opt-in loads the model on the worker, not at app start.
            val built = try { inferenceFactory() } catch (e: RuntimeException) {
                fail("The on-device face model could not be loaded. No photo was changed.",
                    synchronized(lock) { scope?.galleryGrant })
                return null
            }
            synchronized(lock) { if (inference == null) inference = built }
        }
        val (s, locator) = synchronized(lock) {
            if (!optedIn || pausedByUser) return null
            val s = scope ?: return null
            if (liveEpoch != s.epoch || stagingEpoch != null) return null
            s to (inference ?: return null)
        }
        if (!photoGrantCurrent()) return null
        return Session(s, locator).also {
            scanBatchEpoch = s.epoch
            changed()
        }
    }

    private inner class Session(
        private val owned: Scope, locator: OnDeviceFaceLocator
    ) : PeopleScanSession {
        override val epoch: String get() = owned.epoch
        override val engine = FaceScanEngine(
            scopeEpoch = { if (stagingEpoch == null) liveEpoch else null },
            currentConditions = { conditions(owned.epoch) },
            sourceRevisionCurrent = owned.directory::sourceRevisionCurrent,
            index = PersistedFaceEmbeddingIndexPort(store),
            reader = readerFor(owned.directory),
            locator = locator
        )

        override fun currentConditions(): FaceScanConditions = conditions(owned.epoch)
        override fun scopeCurrent(): Boolean = stagingEpoch == null && scopeCurrent(owned.epoch)

        override val visualStage: PeopleVisualScanStep?
            get() {
                if (visualStore == null || !synchronized(lock) { visualOptedIn }) return null
                if (visualScope?.faceEpoch != owned.epoch || visualScope?.staging == true) return null
                return PeopleVisualScanStep { canContinue -> runVisualStep(canContinue) }
            }

        /**
         * One bounded visual step: the next window of queued items of the
         * visual epoch, each resolved through this scope's authorized directory
         * first, then decoded (a video: sampled, VS-V2) and embedded with the
         * run's embedder. Photos go first; a window without photos runs one
         * video ([VisualStepPlan]). Unreadable items are recorded skips (#480);
         * a grant change holds the whole step.
         */
        private fun runVisualStep(canContinue: () -> Boolean): VisualScanResult? {
            val store = visualStore ?: return null
            val visual = visualScope?.takeIf { it.faceEpoch == owned.epoch } ?: return null
            // A guard that fails before any work still asks for a later batch.
            if (visual.staging || stagingEpoch != null || !canContinue()) {
                return VisualScanResult(0, 0, held = true, stop = VisualScanStop.BUDGET_OR_GUARD)
            }
            val window = try { authorizedWindow(store, visual) }
                catch (_: SQLException) { null } ?: return null
            if (window.isEmpty()) return VisualScanResult(0, 0, held = false)
            val selected = VisualStepPlan.select(window) { it.second }
            val videos = selected.filter { it.second }.mapTo(HashSet()) { it.first }
            val embedder = embedderForRun() ?: return null
            val decode = visualDecoderFor(owned.directory)
            val sample = visualVideoFramesFor?.invoke(owned.directory)
            val stage = VisualScanStage(store, embedder.modelVersion, embedder.dimension,
                { media ->
                    if (media !in videos) {
                        VisualScanStage.decodeAndEmbedWith(media, decode, embedder::embed,
                            Bitmap::recycle)
                    } else if (sample == null) {
                        VisualEmbedOutcome.Unreadable("VIDEO_UNSUPPORTED")
                    } else {
                        VisualScanStage.embedVideoWith(media, sample, embedder::embed,
                            Bitmap::recycle, visualVideoBudget, clockElapsedMs, canContinue)
                    }
                }, clockElapsedMs)
            val result = try {
                stage.runBatch(visual.epoch, selected.map { it.first }, canContinue = canContinue)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An SQLite error or an unexpected stage failure: the stage already
                // released its leases. Hold and retry later, and never let it skip
                // afterBatch, which keeps faces on their own cadence.
                VisualScanResult(0, 0, held = true, stop = VisualScanStop.HELD)
            }
            // Nothing in this window could be claimed: look past it next time.
            // Move only past what this step tried (review #486 note 4), so the
            // other authorized items of a video-only window keep their turn.
            if (result.stop == VisualScanStop.NOTHING_CLAIMED) {
                visual.cursor.set(selected.last().first)
            }
            return result
        }

        /**
         * #482 follow-up: the first window of up to [VISUAL_WINDOW] queued
         * items, from the scope's cursor on, with at least one item this
         * scope's directory resolves (paired with "is a video"). A window with
         * none moves the cursor past it, so items further in key order are
         * reached; the end wraps to the start once. At most
         * [VISUAL_WINDOWS_PER_STEP] windows are read per step; the cursor keeps
         * the place for the next one. Empty: nothing claimable was found. Null:
         * the visual epoch is no longer active.
         */
        private fun authorizedWindow(
            store: VisualIndexStore, visual: VisualScope
        ): List<Pair<VisualMedia, Boolean>>? {
            val scan = VisualWindowScan.next(visual.cursor.get(), VISUAL_WINDOWS_PER_STEP,
                read = { after -> store.queuedCandidates(visual.epoch, VISUAL_WINDOW, after) },
                resolve = { media ->
                    try {
                        owned.directory.authorizedSource(FaceMediaKey(media.key, media.revision))
                            .isVideo
                    } catch (_: SecurityException) { null }
                }) ?: return null
            visual.cursor.set(scan.cursor)
            return scan.window
        }

        override fun afterBatch(result: FaceBatchResult, visual: VisualScanResult?) {
            // #508: a split publication continues in the next run, also after
            // the photo queue drained, until every pending face is placed.
            val drained = result.stop == FaceBatchStop.NO_PENDING
            var publicationContinues = false
            var publicationFailed = false
            try {
                // This is a real grouping stage, not a queued scan; clear the marker
                // in finally even when publication is cancelled or fails.
                groupingEpoch = owned.epoch
                changed()
                publishClusters(owned.epoch, drained)
                publicationContinues = drained && store.clusterPublicationContinues(owned.epoch)
                publicationFailures.set(0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // An SQLite error or a failed consistency check: the transaction
                // rolled back, membership stays dirty and unreadable. A pending
                // photo retries with the next batch; a drained queue has none,
                // so it retries later, a bounded number of times (#508 review).
                publicationFailed = true
            } finally {
                if (groupingEpoch == owned.epoch) groupingEpoch = null
                changed()
            }
            val retryPublication = publicationFailed && drained &&
                publicationFailures.incrementAndGet() <= MAX_PUBLICATION_RETRIES
            val visualNext = nextVisualBatchAfter(visual, stillQueued = visualStillQueued())
            // A drained (or absent) visual queue frees the ~100 MB embedder; the
            // next run that has visual work opens it again.
            if (visualNext == NextBatch.NONE) closeEmbedder()
            val paused = synchronized(lock) { pausedByUser }
            if (!paused && scopeCurrent()) when (
                combineNextBatch(when {
                    publicationContinues -> NextBatch.NOW
                    retryPublication -> NextBatch.LATER
                    else -> nextBatchAfter(result.stop)
                }, visualNext)) {
                NextBatch.NOW -> scheduler.next()
                NextBatch.LATER -> scheduler.later()
                NextBatch.NONE -> Unit
            }
            changed()
        }

        private fun visualStillQueued(): Boolean {
            val visual = visualScope?.takeIf { it.faceEpoch == owned.epoch && !it.staging } ?: return false
            if (!synchronized(lock) { visualOptedIn }) return false
            val progress = try { visualStore?.progress(visual.epoch) }
                catch (_: SQLException) { null } ?: return false
            return progress.queued + progress.running > 0
        }

        override fun afterBatch(result: FaceBatchResult) = afterBatch(result, null)

        override fun close() {
            if (scanBatchEpoch == owned.epoch) scanBatchEpoch = null
            changed()
        }
    }

    /** One consistent read for the People panel. Blocking; IO only. */
    fun status(galleryGrant: String?): PeopleDiscoveryStatus {
        val display = displayEpoch(galleryGrant)
        val snap = synchronized(lock) { Snapshot(optedIn, pausedByUser, scope, failure) }
        if (galleryGrant == null || display == null) {
            return PeopleDiscoveryStatus(FaceDiscoveryUiState(), null, PeopleActionAvailability())
        }
        if (snap.failure != null) {
            return PeopleDiscoveryStatus(
                FaceDiscoveryUiState(FaceDiscoveryPhase.FAILED, errorMessage = snap.failure,
                    observedPermissionEpoch = display),
                display, PeopleActionAvailability())
        }
        val scoped = snap.scope?.takeIf { snap.optedIn && it.galleryGrant == galleryGrant }
        if (scoped == null) {
            // #543: a kept opt-in is being re-armed by the gallery publication;
            // show it queued, not a second "Discover faces".
            val restoring = snap.optedIn && photoGrantCurrent()
            val ui = presentNativeFaceScan(NativeFaceScanDisplayEvidence(
                (if (restoring) FaceScanPhase.QUEUED else FaceScanPhase.NEVER_STARTED).name,
                display, 0, 0, 0,
                nativeDetectorInstalled = true, realStartActionAvailable = photoGrantCurrent()
            ), display)
            return PeopleDiscoveryStatus(ui, display,
                PeopleActionAvailability(mayStartScan = ui.phase == FaceDiscoveryPhase.READY),
                visual = visualStatus(null))
        }
        // The cluster writer may hold SQLite for a long publication part.
        // Report its actual live stage without waiting for a DB read or
        // inventing new permission-scoped counters. Old home tiles are served
        // only through the same current-grant check as an ordinary home read.
        if (!snap.paused && groupingEpoch == scoped.epoch && photoGrantCurrent()) {
            val ui = FaceDiscoveryUiState(FaceDiscoveryPhase.GROUPING,
                observedPermissionEpoch = display)
            val visualOn = synchronized(lock) { visualOptedIn }
            return PeopleDiscoveryStatus(ui, display, PeopleActionAvailability(),
                people = liveHomePeople(scoped.epoch),
                visual = if (visualStore != null)
                    VisualIndexStatus(enabled = visualOn, mayEnable = false) else null,
                provenance = scoped.provenance)
        }
        val progress = store.progress(scoped.epoch)
        val counts = store.reviewCounts(scoped.epoch)
        if (progress == null || counts == null) {
            val ui = FaceDiscoveryUiState(FaceDiscoveryPhase.PERMISSION_REQUIRED,
                observedPermissionEpoch = display)
            return PeopleDiscoveryStatus(ui, display, PeopleActionAvailability())
        }
        val hold = conditions(scoped.epoch).holdReason()
        val phase = startedScanPhase(snap.paused, progress.pending, progress.running,
            counts.awaitingReview, hold, grouping = groupingEpoch == scoped.epoch,
            activeBatch = scanBatchEpoch == scoped.epoch)
        val total = progress.pending + progress.running + progress.processed + progress.failed
        val ui = presentNativeFaceScan(NativeFaceScanDisplayEvidence(
            phase.name, scoped.epoch, total, progress.processed + progress.failed,
            counts.awaitingReview, nativeDetectorInstalled = true, realStartActionAvailable = true
        ), scoped.epoch)
        val people = currentHome(scoped.epoch)
        return PeopleDiscoveryStatus(ui, display, PeopleActionAvailability(
            mayPauseScan = ui.phase == FaceDiscoveryPhase.SCANNING,
            mayResumeScan = ui.phase == FaceDiscoveryPhase.PAUSED,
            mayOpenReview = counts.awaitingReview > 0
        ), people, visualStatus(scoped.epoch), scoped.provenance)
    }

    /**
     * VS-S2b: read-only vectors of the published visual index for the live
     * face scope [faceEpoch], for Find similar; null while the opt-in is off,
     * the catalogue is unpublished or [faceEpoch] is not the live, granted
     * scope. The source is bound to that one visual epoch.
     */
    fun visualSearchSource(faceEpoch: String): VisualVectorSource? {
        val store = visualStore ?: return null
        if (!synchronized(lock) { visualOptedIn } || !scopeCurrent(faceEpoch)) return null
        val visual = visualScope?.takeIf { it.faceEpoch == faceEpoch } ?: return null
        return StoreVisualVectorSource(store, visual.epoch, visualModelVersion)
    }

    /** The visual index panel's facts for the live face scope [faceEpoch]. */
    fun visualStatus(faceEpoch: String?): VisualIndexStatus? {
        val store = visualStore ?: return null
        val enabled: Boolean
        val failure: String?
        val kept: Boolean
        val running: Boolean
        synchronized(lock) {
            enabled = visualOptedIn
            failure = visualFailure
            kept = visualDataKept
            running = optedIn && faceEpoch != null && scope?.epoch == faceEpoch
        }
        val visual = visualScope?.takeIf { enabled && it.faceEpoch == faceEpoch }
        val progress = visual?.let {
            try { store.progress(it.epoch) } catch (_: SQLException) { null }
        }
        val mayDelete = !enabled && (kept || try { visualModelCopyExists() }
            catch (_: RuntimeException) { false })
        return VisualIndexStatus(
            enabled = enabled,
            mayEnable = running && faceEpoch != null && scopeCurrent(faceEpoch),
            done = progress?.done, total = progress?.total, skipped = progress?.skipped ?: 0,
            leftOut = visual?.leftOutPhotos ?: 0,
            mayDelete = mayDelete, failure = failure
        )
    }

    /** No vector decode or full clustering on the People home path. */
    private fun currentHome(epoch: String): PeopleHomeSummary? = synchronized(reviewLock) {
        if (!scopeCurrent(epoch)) return null
        val generation = store.peopleReviewGeneration(epoch) ?: run {
            liveHome = null
            return null
        }
        liveHome?.takeIf {
            it.state.permissionEpoch == epoch && it.storeGeneration == generation
        }?.let { return it.state }
        val stored = store.peopleHome(epoch) ?: run {
            liveHome = null
            return null
        }
        PeopleHomeSummary(epoch, "$epoch|${stored.generation}",
            stored.people.mapValues { (_, person) ->
                PeopleHomePerson(person.name, person.faceCount, person.coverFaceUid)
            }, stored.unnamed.map { PeopleHomeGroup(it.anchorUid, it.faceCount) })
            .also { liveHome = LiveHome(it, stored.generation) }
    }

    /**
     * The current identity-review snapshot of [epoch], reusing the cached
     * object while the store generation is unchanged (one cheap read). Null
     * while the scope is gone or clusters are mid-publication. Blocking; IO only.
     */
    private fun currentReview(epoch: String): LiveReview? = synchronized(reviewLock) {
        if (!scopeCurrent(epoch)) return null
        val generation = store.peopleReviewGeneration(epoch) ?: return null
        liveReview?.takeIf {
            it.state.permissionEpoch == epoch && it.storeGeneration == generation
        }?.let { return it }
        val stored = store.peopleReview(epoch) ?: return null
        LiveReview(PeopleClusterReviewState(epoch, "$epoch|${stored.generation}",
            stored.clusters, stored.names, hidden = stored.hidden), stored.generation)
            .also { liveReview = it }
    }

    /**
     * The full identity-review snapshot of [epoch] (clusters, names, hidden),
     * loading it when the cache is cold. For the person page and Hidden
     * people (#551), which need more than the People home summary (#532).
     * Blocking; IO only.
     */
    fun reviewState(epoch: String): PeopleClusterReviewState? = currentReview(epoch)?.state

    /**
     * The live snapshot for an action-time identity check (button taps read
     * it on main). Never null-safe across scopes: a revoked or replaced epoch
     * returns null, so a stale button sends nothing.
     */
    fun liveReview(epoch: String?): PeopleClusterReviewState? =
        liveReview?.state?.takeIf { epoch != null && it.permissionEpoch == epoch &&
            liveEpoch == epoch }

    /** As [liveReview], for the People home snapshot (names with present faces). */
    fun liveHomePeople(epoch: String?): PeopleHomeSummary? =
        liveHome?.state?.takeIf { epoch != null && it.permissionEpoch == epoch &&
            liveEpoch == epoch && photoGrantCurrent() }

    /**
     * Apply one identity-review command and persist it atomically. Refused
     * (false, nothing written) unless the command was computed from the live
     * snapshot of the live scope and the store generation is unchanged; a
     * refused commit drops the cache so the screen reloads from the store.
     * Blocking; IO only.
     */
    fun review(command: PeopleClusterReviewCommand): Boolean {
        val epoch = command.expectedState.permissionEpoch
        if (!scopeCurrent(epoch)) return false
        val committed = synchronized(reviewLock) {
            val live = liveReview?.takeIf { it.state === command.expectedState }
                ?: return@synchronized false
            val next = applyCurrentPeopleReviewCommand(live.state, command)
                ?: return@synchronized false
            val generation = try {
                store.commitPeopleReview(epoch, live.storeGeneration, next.clusters,
                    next.names, next.hidden, reviewTally(live.state, command.action))
            } catch (_: SQLException) { null } catch (_: IllegalArgumentException) { null }
            if (generation == null) {
                liveReview = null
                liveHome = null
                false
            } else {
                liveReview = LiveReview(
                    next.copy(sourceGeneration = "$epoch|$generation"), generation)
                liveHome = null
                true
            }
        }
        changed()
        return committed
    }

    /**
     * #62: scoped native names backup, distinct from the old V4 path-bearing
     * format. Both calls are blocking and must run on Dispatchers.IO.
     */
    fun exportNamesBackup(galleryGrant: String?): ByteArray {
        val epoch = displayEpoch(galleryGrant)
            ?: error("People is unavailable; grant photo access and open People first")
        return synchronized(reviewLock) {
            require(scopeCurrent(epoch)) { "People permission changed" }
            val snapshot = store.exportNativeNamesSnapshot(epoch)
                ?: error("People groups are updating; retry after publication")
            require(scopeCurrent(epoch)) { "People permission changed" }
            nl.atlas.gallery.featurepersistence.NativePeopleNamesBackupCodec.encode(snapshot)
        }
    }

    fun importNamesBackup(
        galleryGrant: String?, bytes: ByteArray, approvedChecksum: String
    ): nl.atlas.gallery.featurepersistence.NativePeopleNamesImportResult {
        val codec = nl.atlas.gallery.featurepersistence.NativePeopleNamesBackupCodec
        require(codec.checksum(bytes) == approvedChecksum) {
            "Selected names backup changed after the preview"
        }
        val snapshot = codec.decode(bytes)
        val epoch = displayEpoch(galleryGrant)
            ?: error("People is unavailable; grant photo access and open People first")
        val result = synchronized(reviewLock) {
            require(scopeCurrent(epoch)) { "People permission changed" }
            val merged = store.importNativeNamesSnapshot(epoch, snapshot)
            liveReview = null
            liveHome = null
            if (merged.applied > 0 && store.regroupPending(epoch)) {
                var parts = 0
                do {
                    require(scopeCurrent(epoch)) { "People permission changed during restore" }
                    check(store.materializeClusters(epoch)) { "People grouping cannot finish" }
                    parts++
                } while (store.clusterPublicationContinues(epoch) &&
                    parts < MAX_REGROUP_PARTS)
            }
            merged
        }
        changed()
        return result
    }

    /**
     * #580: the People numbers of the current scope (see [PeopleNumbers]):
     * counts of faces, groups and review taps, nothing that names a face or a
     * person. Null while the scope is gone or groups are mid-publication.
     * Blocking and CPU-heavy on a large library; never on the main thread.
     */
    fun peopleNumbers(galleryGrant: String?): PeopleNumbers? {
        val epoch = displayEpoch(galleryGrant) ?: return null
        val state = currentReview(epoch)?.state ?: return null
        val taps = store.peopleReviewTally(epoch) ?: return null
        return PeopleNumbers.of(state.clusters, taps)
    }

    /**
     * URIs of [person]'s photos under the current grant: confirmed faces and
     * the ones grouping added on its own (#580, stored with membership, so no
     * review snapshot is needed), each resolved through the scope's
     * authorized directory (a photo outside
     * the grant or at another revision is left out). Null while the scope is
     * gone. Blocking; IO only.
     */
    fun personAlbumUris(galleryGrant: String?, person: PersonKey): Set<String>? {
        val s = synchronized(lock) {
            scope?.takeIf { optedIn && it.galleryGrant == galleryGrant }
        } ?: return null
        if (!scopeCurrent(s.epoch)) return null
        val uris = HashSet<String>()
        for (m in store.personMedia(s.epoch, person)) {
            try { uris += s.directory.authorizedUri(FaceMediaKey(m.key, m.revision)) }
            catch (_: SecurityException) { if (!scopeCurrent(s.epoch)) return null }
        }
        return uris
    }

    /**
     * #551 person page: the URIs, under the current grant, of the photos and
     * videos that hold [faces] (a person's confirmed faces or one unknown
     * group), each with the faces it holds, so "Remove from this person" can
     * turn selected photos into exact faces. A medium outside the grant or at
     * another revision is left out. Null while the scope is gone. Blocking; IO only.
     */
    fun personPageFaces(
        galleryGrant: String?, faces: Collection<NativeFaceObservationId>
    ): Map<String, Set<NativeFaceObservationId>>? {
        val s = synchronized(lock) {
            scope?.takeIf { optedIn && it.galleryGrant == galleryGrant }
        } ?: return null
        if (!scopeCurrent(s.epoch)) return null
        val media = store.faceMedia(s.epoch, faces.map { it.localHex })
        return facesByAuthorizedUri(media) { key ->
            try { s.directory.authorizedUri(FaceMediaKey(key.key, key.revision)) }
            catch (_: SecurityException) {
                if (!scopeCurrent(s.epoch)) return null
                null
            }
        }
    }

    /**
     * #553: who is in [photo], for the viewer's details sheet. One cheap
     * per-photo read ([FaceIndexStore.photoFaces]) under the live scope of
     * [galleryGrant]; it never starts a scan or rebuilds clusters. The photo
     * must resolve through the scope's authorized directory to the same URI
     * at the same revision, else nothing is shown. Null while the scope is
     * gone. Blocking; IO only.
     */
    fun photoPeople(galleryGrant: String?, photo: MediaRecord): List<PhotoPerson>? {
        val s = synchronized(lock) {
            scope?.takeIf { optedIn && it.galleryGrant == galleryGrant }
        } ?: return null
        if (!scopeCurrent(s.epoch)) return null
        if (photo.isVideo) return emptyList()
        // Same projection as the catalogue publication (rememberPeopleDiscovery).
        val key = try { photo.toFaceVisibleMedia(editorRevision = 0L).toDurableFaceKey() }
            catch (_: IllegalArgumentException) { return emptyList() }
            catch (_: IllegalStateException) { return emptyList() }
        val uri = try { s.directory.authorizedUri(key) } catch (_: SecurityException) { null }
        if (uri != photo.uri) return emptyList()
        val faces = try { store.photoFaces(s.epoch, key.canonicalMediaKey, key.sourceRevision) }
            catch (_: SQLException) { return emptyList() }
        // #580: a face grouping added to a named person already reads as that
        // person here (the store keeps the match with its membership row).
        return photoPeopleOf(s.epoch, faces).takeIf { scopeCurrent(s.epoch) }
    }

    /**
     * #553: name the unknown group behind a "+" in the viewer, as the review
     * screen's "Name this group" does (same person key, same reducer and
     * atomic commit through [review]). False when the scope, the group or
     * the name is no longer valid. Blocking; IO only.
     */
    fun nameUnknownCluster(target: PhotoPerson.Unnamed, rawName: String): Boolean {
        val name = photoPersonNameOrNull(rawName) ?: return false
        val anchor = try { NativeFaceObservationId(target.clusterAnchor) }
            catch (_: IllegalArgumentException) { return false }
        val live = currentReview(target.epoch)?.state ?: return false
        val cluster = FaceClusterId(anchor)
        if (live.clusters.unknownClusters.none { it.id == cluster }) return false
        val person = PersonKey("native-" + anchor.localHex)
        if (person in live.names) return false
        return review(PeopleClusterReviewCommand(live,
            PeopleReviewAction.NameCluster(cluster, person, name)))
    }

    /**
     * Unknown-face review page plus the published unknown groups and reviewed
     * people, editable through [review].
     * Null when the run's scope is gone. Blocking; IO only.
     */
    fun reviewPage(galleryGrant: String?, after: IndexedFacePageCursor? = null): PeopleReviewPage? {
        val s = synchronized(lock) {
            scope?.takeIf { optedIn && it.galleryGrant == galleryGrant }
        } ?: return null
        if (!scopeCurrent(s.epoch)) return null
        val bridge = FaceUnknownInboxBridge(store::pendingFaceReviewPage,
            s.directory::authorizedUri, ::scopeCurrent)
        val page = try { bridge.page(s.epoch, after, limit = 50) }
            catch (_: SecurityException) { return null }
        val counts = store.reviewCounts(s.epoch) ?: return null
        val clusters = currentReview(s.epoch)?.state
        // The queue shares the identity snapshot's generation so the screen
        // admits both; pages are still compared by reference.
        val generation = clusters?.sourceGeneration
            ?: "${s.epoch}#${reviewGeneration.incrementAndGet()}"
        val candidates = page.items.mapIndexed { i, face ->
            DetectedFaceReviewCandidate(
                key = DetectedFaceReviewKey(face.nativeFaceUid, face.media.canonicalMediaKey,
                    face.media.sourceRevision, face.detectorVersion, s.epoch, face.ordinal),
                title = "Detected face ${i + 1}",
                explanation = "Found on this device. Not a named person."
            )
        }
        val queue = NativeFaceReviewQueue(s.epoch, generation, candidates,
            totalVisibleObservations = maxOf(counts.awaitingReview, candidates.size),
            hasMore = page.nextCursor != null)
        return PeopleReviewPage(queue, clusters, page.nextCursor)
    }

    /** True while the saved user opt-in remains active in this host (a pending refresh keeps it). */
    fun discoveryOptedIn(): Boolean = synchronized(lock) { optedIn }

    /**
     * A small crop of one observation for the review screen (#438), or null.
     * The face's source is resolved through the live scope's authorized
     * directory (grant, key and revision) before any pixel is read, and the
     * scope is checked again after the decode, so a revoked or changed source
     * never yields a crop. Crops live only in a bounded in-memory cache that a
     * new epoch or a revoke empties. Blocking; IO only.
     */
    fun faceCrop(epoch: String?, nativeFaceUid: String): Bitmap? {
        if (epoch == null) return null
        val s = synchronized(lock) { scope?.takeIf { optedIn && it.epoch == epoch } }
            ?: return null
        if (!scopeCurrent(epoch)) return null
        val location = try { store.faceLocation(epoch, nativeFaceUid) }
            catch (_: SQLException) { null } ?: return null
        val media = FaceMediaKey(location.media.key, location.media.revision)
        val uri = try { s.directory.authorizedUri(media) }
            catch (_: SecurityException) { return null }
        val key = FaceCropKey(epoch, nativeFaceUid, media.canonicalMediaKey, media.sourceRevision)
        val crop = faceCrops.get(key) ?: decodeCrop(uri, location.box, location.frameMs)
            ?.also { faceCrops.put(key, it) } ?: return null
        // A revoke may have raced the decode; never hand out a crop past it.
        return crop.takeIf { scopeCurrent(epoch) }
    }

    // VS-V1c: a video face is cropped from its keyframe at frame_ms.
    private fun decodeCrop(uri: String, box: IndexedFaceBox, frameMs: Long?): Bitmap? = try {
        faceCropDecoder.decodeAt(uri, box, frameMs)
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        // A source that vanished or a decoder refusal: the neutral icon.
        null
    }

    /**
     * VS-F2b (#468): search by face over this run's scope, for the gallery
     * publication [galleryGrant]. Read-only: the store's epoch-gated corpus,
     * the scope's authorized directory for every URI, and the scan's own
     * detector/embedder for the query face (never a second model). Every
     * answer is null or empty once the scope is revoked, held or replaced,
     * or once [galleryGrant] (read at every call) names another publication.
     */
    fun faceSearchPort(galleryGrant: () -> String?): FaceSearchPort = SearchPort(galleryGrant)

    private inner class SearchPort(private val galleryGrant: () -> String?) : FaceSearchPort {
        private fun scope(epoch: String): Scope? {
            val grant = galleryGrant() ?: return null
            return synchronized(lock) {
                scope?.takeIf { optedIn && it.epoch == epoch && it.galleryGrant == grant }
            }?.takeIf { hostScopeCurrent(epoch) }
        }

        // Explicit receiver: inside this class an unqualified scopeCurrent is
        // the override below, which calls scope() again (unbounded recursion).
        private fun hostScopeCurrent(epoch: String) = this@PeopleDiscoveryHost.scopeCurrent(epoch)

        override fun liveEpoch(): String? {
            val grant = galleryGrant() ?: return null
            return synchronized(lock) {
                scope?.takeIf { optedIn && it.galleryGrant == grant }?.epoch
            }?.takeIf(::hostScopeCurrent)
        }

        override fun scopeCurrent(epoch: String): Boolean = scope(epoch) != null

        override fun corpus(epoch: String): FaceSearchCorpus? =
            if (scope(epoch) == null) null else store.faceSearchCorpus(epoch)

        override fun authorizedUri(epoch: String, media: IndexedMedia): String? {
            val s = scope(epoch) ?: return null
            return try { s.directory.authorizedUri(FaceMediaKey(media.key, media.revision)) }
            catch (_: SecurityException) { null }
            catch (_: IllegalArgumentException) { null }
        }

        override fun personName(epoch: String, person: PersonKey): String? =
            if (scope(epoch) == null) null else currentReview(epoch)?.state?.names?.get(person)

        override fun queryCapture(epoch: String): QueryFaceCapture? {
            val s = scope(epoch) ?: return null
            val locator = synchronized(lock) { inference } ?: return null
            return QueryFaceCapture(readerFor(s.directory), locator, sourceCurrent = { source ->
                source.permissionEpoch == epoch && scope(epoch) != null &&
                    s.directory.sourceRevisionCurrent(source.media)
            })
        }

        private fun key(s: Scope, photo: MediaRecord): FaceMediaKey? {
            if (photo.isVideo) return null
            // Same projection as the catalogue publication (rememberPeopleDiscovery).
            val key = try { photo.toFaceVisibleMedia(editorRevision = 0L).toDurableFaceKey() }
                catch (_: IllegalArgumentException) { return null }
                catch (_: IllegalStateException) { return null }
            val uri = try { s.directory.authorizedUri(key) } catch (_: SecurityException) { null }
            return key.takeIf { uri == photo.uri }
        }

        override fun querySource(epoch: String, photo: MediaRecord): QueryFaceSource? {
            val s = scope(epoch) ?: return null
            return key(s, photo)?.let { QueryFaceSource(it, epoch) }
        }

        override fun indexedFaceCount(epoch: String, photo: MediaRecord): Int {
            val s = scope(epoch) ?: return 0
            val key = key(s, photo) ?: return 0
            return try {
                store.embeddingRecords(epoch, key.canonicalMediaKey)
                    .count { it.mediaRevision == key.sourceRevision }
            } catch (_: SQLException) { 0 }
        }
    }

    /** Durable LATER / NOT_A_FACE / Undo; the store rechecks epoch and UID. Blocking; IO only. */
    fun decide(command: NativeFaceReviewCommand): Boolean =
        scopeCurrent(command.permissionEpoch) &&
            store.reviewObservation(command.permissionEpoch, command.nativeFaceUid,
                command.decision.name).also { if (it) changed() }
}

/** Compose binding shared by MainActivity and the API35 host test. */
internal class PeopleDiscoveryBinding(
    val status: PeopleDiscoveryStatus?,
    val onStart: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    /** The "Visual search index" switch; off can also delete its data. */
    val onVisualIndex: (enabled: Boolean) -> Unit = {},
    val onDeleteVisualIndex: () -> Unit = {}
)

/**
 * Status follows [PeopleDiscoveryHost.changes] and [galleryGrant]; a new
 * gallery publication is handed to the host off main. [inventory] is the same
 * permission-gated list the gallery shows; it is projected only on IO.
 */
@Composable
internal fun rememberPeopleDiscovery(
    host: PeopleDiscoveryHost,
    galleryGrant: String?,
    inventory: List<MediaRecord>
): PeopleDiscoveryBinding = rememberPeopleDiscoveryOf(host, galleryGrant, inventory) { list ->
    list.map { it.toFaceVisibleMedia(editorRevision = 0L) }
}

@Composable
internal fun <T> rememberPeopleDiscoveryOf(
    host: PeopleDiscoveryHost,
    galleryGrant: String?,
    inventory: List<T>,
    project: (List<T>) -> List<FaceVisibleMedia>
): PeopleDiscoveryBinding {
    val version by host.changes.collectAsState()
    val source = CatalogSourceKey(inventory)
    val status by produceState<PeopleDiscoveryStatus?>(null, galleryGrant, version, source) {
        // #31: show what the host can serve now first. A publication can take
        // a full restage; its result arrives as a later change.
        // While an opted-in catalogue is still being restored, keep the last
        // value (or "Restoring…") instead of flashing a not-started state.
        withContext(Dispatchers.IO) {
            host.status(galleryGrant).takeIf { host.serves(galleryGrant) || !host.discoveryOptedIn() }
        }?.let { value = it }
        value = withContext(Dispatchers.IO) {
            host.onGalleryPublication(galleryGrant) { project(inventory) }
            host.status(galleryGrant)
        }
    }
    val scope = rememberCoroutineScope()
    return PeopleDiscoveryBinding(
        status = status?.takeIf { galleryGrant != null },
        onStart = {
            val grant = galleryGrant
            if (grant != null) scope.launch(Dispatchers.IO) { host.start(grant, project(inventory)) }
        },
        onPause = { scope.launch(Dispatchers.IO) { host.pause() } },
        onResume = { scope.launch(Dispatchers.IO) { host.resume() } },
        onVisualIndex = { on -> scope.launch(Dispatchers.IO) { host.setVisualIndex(on) } },
        onDeleteVisualIndex = { scope.launch(Dispatchers.IO) { host.deleteVisualIndex() } }
    )
}

/**
 * The separate "Visual search index" opt-in (VS-S1c), off by default. It runs
 * in the same on-device scan as face discovery (charging, battery, thermal and
 * grant guards), shows how many images are done, and when off offers to delete
 * the index and the model copy. Hidden while the host has no visual index.
 */
@Composable
internal fun VisualSearchIndexPanel(
    status: VisualIndexStatus?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status == null) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().testTag("atlas-visual-index")
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Visual search index", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = status.enabled,
                    onCheckedChange = onToggle,
                    enabled = status.enabled || status.mayEnable,
                    modifier = Modifier.testTag("atlas-visual-index-switch")
                        .semantics { contentDescription = "Visual search index" }
                )
            }
            Text(visualIndexExplanation(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val total = status.total
            val done = status.done
            if (status.enabled && total != null && done != null) {
                if (total > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().testTag("atlas-visual-index-progress"))
                }
                Text(visualIndexProgressText(status),
                    modifier = Modifier.testTag("atlas-visual-index-count"),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!status.enabled && status.mayDelete) {
                OutlinedButton(onClick = onDelete,
                    modifier = Modifier.testTag("atlas-visual-index-delete")) {
                    Text("Delete visual index")
                }
            }
        }
    }
}

internal fun visualIndexExplanation(status: VisualIndexStatus): String = when {
    status.failure != null -> status.failure
    status.enabled -> "Runs on this device, with face discovery, while charging. " +
        "Nothing leaves your phone."
    status.mayEnable -> "Off. Turn on to let search find photos by what is in them. " +
        "Runs on this device while charging; uses about 100 MB."
    else -> "Start face discovery first: the visual index runs in the same on-device scan."
}

internal fun visualIndexProgressText(status: VisualIndexStatus): String {
    val base = "%,d of %,d photos done".format(status.done ?: 0, status.total ?: 0)
    val unreadable = if (status.skipped > 0) " · %,d unreadable, skipped".format(status.skipped)
        else ""
    return base + unreadable + visualIndexLeftOutText(status.leftOut)
}

/**
 * #631: why some photos are not in the index, in plain words. MediaStore
 * lists these without a specific volume and positive ID, so no durable key
 * can find them again later; face discovery leaves out the same photos.
 */
internal fun visualIndexLeftOutText(leftOut: Int): String = when {
    leftOut <= 0 -> ""
    leftOut == 1 -> ". 1 photo left out: Android gives it no fixed ID, so ATLAS can't find it again"
    else -> ". %,d photos left out: Android gives them no fixed ID, so ATLAS can't find them again"
        .format(leftOut)
}

/**
 * The unknown-face review route: detection decisions are durable (store
 * `face_reviews`), and so is identity review (#434): naming, assigning,
 * splitting, merging and Undo go through [PeopleDiscoveryHost.review], which
 * rechecks the live snapshot and commits names and facts in one transaction.
 */
@Composable
internal fun PeopleReviewRoute(
    host: PeopleDiscoveryHost,
    galleryGrant: String?,
    onClose: () -> Unit
) {
    val version by host.changes.collectAsState()
    val liveEpoch = remember(version, galleryGrant) { host.displayEpoch(galleryGrant) }
    var after by remember(galleryGrant) { mutableStateOf<IndexedFacePageCursor?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<PeopleReviewPage?>(null, referentialEqualityPolicy()) }
    LaunchedEffect(galleryGrant, liveEpoch, after, reload) {
        page = null
        page = withContext(Dispatchers.IO) { host.reviewPage(galleryGrant, after) }
    }
    val scope = rememberCoroutineScope()
    val current = page?.takeIf { it.queue.permissionEpoch == liveEpoch }
    UnknownFacesReviewScreen(
        queue = current?.queue,
        currentPermissionEpoch = liveEpoch,
        currentSourceGeneration = current?.queue?.sourceGeneration,
        liveReviewStateAtAction = {
            page?.takeIf { host.scopeCurrent(it.queue.permissionEpoch) }?.let {
                NativeReviewActionSnapshot(
                    NativeReviewScope(it.queue.permissionEpoch, it.queue.sourceGeneration),
                    it.queue)
            }
        },
        reviewBackendReady = current != null,
        onDecide = { command ->
            scope.launch {
                withContext(Dispatchers.IO) { host.decide(command) }
                reload++
            }
        },
        onClose = onClose,
        onNextPage = current?.nextCursor?.let { next -> { after = next } },
        clusterReview = current?.clusters,
        liveClusterReviewAtAction = { host.liveReview(liveEpoch) },
        onClusterReviewCommand = { command ->
            scope.launch {
                withContext(Dispatchers.IO) { host.review(command) }
                reload++
            }
        },
        clusterFacePreview = { face ->
            FaceCropPreview(host, liveEpoch, face.localHex, "Face preview unavailable")
        },
        sourcePreview = { candidate ->
            FaceCropPreview(host, candidate.key.permissionEpoch, candidate.key.nativeFaceUid,
                "Photo preview unavailable")
        }
    )
}

/**
 * VS-V2: an authorised video's VS-V1a keyframes for the visual index, through
 * the live grant only ([source] is the directory's gated lookup and throws
 * SecurityException once the video is gone). A revoke during sampling throws
 * SecurityException (the step holds); a corrupt, DRM or undecodable video is a
 * typed skip. The returned bitmaps belong to the caller.
 */
internal fun sampleVisualVideo(
    sampler: VideoKeyframeSampler,
    source: (FaceMediaKey) -> FaceVisibleMedia,
    media: VisualMedia
): VisualVideoFrames<Bitmap> {
    val key = FaceMediaKey(media.key, media.revision)
    val row = source(key)
    if (!row.isVideo) throw SecurityException("Not an authorised video")
    val sampled = sampler.sample(
        android.net.Uri.parse(row.uri), row.durationMs,
        VideoMediaRevision(row.providerVersion.orEmpty(), row.generationModified),
        isStillAuthorized = { stillAuthorized(source, key, row) },
        checkActive = { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
    )
    return when (sampled) {
        is VideoKeyframeResult.Skipped ->
            if (sampled.reason != VideoSkipReason.STALE_OR_REVOKED) {
                VisualVideoFrames.Skipped(sampled.reason.name)
            } else if (stillAuthorized(source, key, row)) {
                // The grant still resolves this video, yet the provider refused it
                // (review #486 note 3): the item's failure, counted, never a hold
                // that would stall the video lane for ever.
                VisualVideoFrames.Failed("PROVIDER_REFUSED")
            } else throw SecurityException("Video source revoked while sampling")
        is VideoKeyframeResult.Sampled ->
            VisualVideoFrames.Sampled(sampled.batch.frames.map { VisualKeyframe(it.frameMs, it.bitmap) })
    }
}

private fun stillAuthorized(
    source: (FaceMediaKey) -> FaceVisibleMedia, key: FaceMediaKey, row: FaceVisibleMedia
): Boolean = try { source(key).uri == row.uri } catch (_: SecurityException) { false }
