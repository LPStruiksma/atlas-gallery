package nl.atlas.gallery

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.security.MessageDigest
import android.database.Cursor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CancellationException
import android.os.Looper
import android.os.CancellationSignal
import android.os.Handler
import android.database.ContentObserver
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryRepository(private val context: Context) {
    /** Aggregate count from the most recent completed scan, not media identity. */
    @Volatile var lastReusedCount: Int? = null
        private set
    /** Read-only MediaStore inventory. In particular, does not use all-files access. */
    fun load(
        cancellationSignal: CancellationSignal? = null,
        /**
         * The inventory on screen while this scan runs. Rows that did not
         * change are answered with its objects, so the heap does not hold the
         * library twice ([GalleryScanRecordReuse]).
         */
        previous: List<MediaRecord> = emptyList(),
        checkActive: () -> Unit = {}
    ): List<MediaRecord> = failGalleryScanOnHeapExhaustion {
        lastReusedCount = null
        loadInventory(cancellationSignal, previous, checkActive)
    }

    private fun loadInventory(
        cancellationSignal: CancellationSignal?,
        previous: List<MediaRecord>,
        checkActive: () -> Unit
    ): List<MediaRecord> {
        val result = ArrayList<MediaRecord>(maxOf(4096, previous.size))
        val reuse = GalleryScanRecordReuse(previous)
        // Snapshot versions once per mounted *specific* volume, never once per row.
        // An unknown provider version disables persistent presentation caching.
        val versionsAtStart = mediaProviderVersions()
        // Normal edits may increment generation without resetting version.
        // Snapshot each mounted volume ONCE, never per media row.
        val generationsAtStart = mediaProviderGenerations(versionsAtStart.keys)
        // A single timezone snapshot is consistent and avoids 150k lookups.
        val scanZone = java.time.ZoneId.systemDefault()
        // The provider query itself can be expensive, even before the first
        // cursor row. Cancellation checks are cheap and do not touch media.
        checkActive()
        appendMediaTypeOrRollbackOnDenial(result) {
            readMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, result,
                cancellationSignal, checkActive, versionsAtStart, scanZone, reuse)
        }
        checkActive()
        appendMediaTypeOrRollbackOnDenial(result) {
            readMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, result,
                cancellationSignal, checkActive, versionsAtStart, scanZone, reuse)
        }
        checkActive()
        // Refuse an internally mixed catalogue: ordinary edits change
        // generation, provider resets change version, and mount/eject changes
        // the volume key set. No partial results are ever published.
        val versionsAtEnd = mediaProviderVersions()
        val generationsAtEnd = mediaProviderGenerations(versionsAtEnd.keys)
        if (!sameMediaProviderScanSnapshot(
                versionsAtStart, versionsAtEnd, generationsAtStart, generationsAtEnd))
            throw GalleryProviderSnapshotChanged()
        lastReusedCount = reuse.reused
        // Counts only: how much of the previous inventory this scan shared.
        android.util.Log.i("ATLAS_SCAN", "event=scan_rows rows=${result.size} " +
            "reused=${reuse.reused} ${heapUseLogFields()}")
        return result
    }

    private fun mediaProviderVersions(): Map<String, String?> =
        // Failure to enumerate volumes must fail the whole scan. Returning an
        // empty map on provider IPC failure used to silently publish an empty
        // gallery even when photos still existed.
        MediaStore.getExternalVolumeNames(context).associateWith { volume ->
            // Android documents null version when the volume is unmounted.
            // Swallowing that failure into two equal null snapshots allowed
            // a false successful catalogue. Let IPC exceptions propagate.
            val version = MediaStore.getVersion(context, volume)
            if (version.isNullOrBlank())
                throw GalleryProviderVersionUnavailable()
            version
        }

    private fun mediaProviderGenerations(volumeNames: Set<String>): Map<String, Long?> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            volumeNames.associateWith { name ->
                // A generation IPC failure invalidates the scan. Treating it
                // as zero/null would make a torn catalogue look consistent.
                MediaStore.getGeneration(context, name)
            }
        else emptyMap()

    private fun readMedia(
        uri: android.net.Uri, video: Boolean, target: MutableList<MediaRecord>,
        cancellationSignal: CancellationSignal?, checkActive: () -> Unit,
        providerVersions: Map<String, String?>,
        scanZone: java.time.ZoneId,
        reuse: GalleryScanRecordReuse
    ) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            "datetaken"
        )
        if (video) projection.add(MediaStore.Video.Media.DURATION)
        if (Build.VERSION.SDK_INT >= 30) {
            projection.add(MediaStore.MediaColumns.GENERATION_MODIFIED)
            projection.add(MediaStore.MediaColumns.IS_FAVORITE)
        }
        // The external collection is a union of mounted volumes. Resolve
        // every row to its actual volume-specific item URI, otherwise a
        // repeated numeric _ID can address the wrong storage device.
        // Precompute one collection URI per volume, not per catalogue row.
        val specificCollections = providerVersions.keys.associateWith { volume ->
            if (video) MediaStore.Video.Media.getContentUri(volume)
            else MediaStore.Images.Media.getContentUri(volume)
        }
        val cursor = requireMediaCatalogueCursor(
            context.contentResolver.query(uri, projection.toTypedArray(), null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC", cancellationSignal))
        cursor.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val idxName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val idxPath = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val idxSize = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val idxModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val idxDate = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val idxTaken = cursor.getColumnIndex("datetaken")
            val idxVolume = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            val idxWidth = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val idxHeight = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val idxOrientation = cursor.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
            val idxGeneration = if (Build.VERSION.SDK_INT >= 30)
                cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED) else -1
            val idxFavorite = if (Build.VERSION.SDK_INT >= 30)
                cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            val idxDuration = if (video) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
            var rowIndex = 0
            while (cursor.moveToNext()) {
                checkGalleryScanCancellation(rowIndex++, checkActive)
                // NULL getLong() becomes zero; zero/negative IDs are not
                // addressable MediaStore items. Reject the entire cursor
                // rather than publish a fabricated URI or People identity.
                val id = requireMediaStoreItemId(cursor.getLong(idxId))
                val name = cursor.stringOr(idxName, "")
                val folder = reuse.shared(cursor.stringOr(idxPath, ""))
                val modified = cursor.longOr(idxModified)
                val volume = reuse.shared(requireMountedMediaVolume(
                    cursor.stringOr(idxVolume, ""), providerVersions.keys))
                // A missing/migrating mounted-volume mapping invalidates the
                // entire scan. Never silently publish a partial People album.
                val itemCollection = specificCollections[volume]
                    ?: throw IllegalStateException("Media volume unavailable during catalogue scan")
                val dates = resolveMediaDate(
                    cursor.longOr(idxTaken), name, modified, cursor.longOr(idxDate), scanZone)
                // A provider's absent/NULL orientation is unknown, not 0°.
                // Do not fabricate landscape ratio rows from missing metadata.
                val orientationPresent =
                    idxOrientation >= 0 && !cursor.isNull(idxOrientation)
                target.add(reuse.canonical(MediaRecord(
                    id = id,
                    uri = ContentUris.withAppendedId(itemCollection, id).toString(),
                    name = name, relativePath = folder,
                    sizeBytes = cursor.longOr(idxSize), takenAtMillis = dates.millis,
                    isVideo = video, durationMillis = cursor.longOr(idxDuration),
                    modifiedAtSeconds = modified,
                    volumeName = volume,
                    providerVersion = providerVersions[volume],
                    generationModified = cursor.longOr(idxGeneration),
                    widthPixels = mediaPixelDimensionOrUnknown(cursor.longOr(idxWidth)),
                    heightPixels = mediaPixelDimensionOrUnknown(cursor.longOr(idxHeight)),
                    orientationDegrees = mediaOrientationDegreesOrUnknown(
                        if (orientationPresent) cursor.getLong(idxOrientation) else 0L,
                        orientationPresent),
                    isFavorite = cursor.longOr(idxFavorite) != 0L,
                    favoriteKnown = idxFavorite >= 0 && !cursor.isNull(idxFavorite),
                    dateSource = dates.source
                )))
            }
        }
    }

    private fun Cursor.longOr(index: Int): Long =
        if (index < 0 || isNull(index)) 0L else getLong(index)

    private fun Cursor.stringOr(index: Int, fallback: String): String =
        if (index < 0 || isNull(index)) fallback else getString(index)
}

class GalleryViewModel(application: Application): AndroidViewModel(application) {
    private val repo = GalleryRepository(application)
    private val peopleRepo = PeopleRepository(application)
    private val v4LinkPrefs = application.getSharedPreferences("atlas_v4_live_sync",Context.MODE_PRIVATE)
    private val peopleCsvGate = PeopleCsvSyncCoordinator()
    private var v4SyncRunning = false
    private val _v4SyncState = MutableStateFlow(
        if (v4LinkPrefs.contains("uri")) "V4 live export linked" else "No V4 live export linked"
    )
    val v4SyncState = _v4SyncState.asStateFlow()
    private val _people = MutableStateFlow<List<PersonEntry>>(emptyList())
    val people = _people.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val refreshQueue = GalleryRefreshQueue()
    private val activeScan = GalleryScanSupersession()
    private var clearing = false
    private var mediaAccessRevoked = false
    private val _publishedInventory = MutableStateFlow(
        GalleryPublishedInventory(epoch = 0L, items = emptyList())
    )
    internal val publishedInventory = _publishedInventory.asStateFlow()
    private val _failedInventoryEpoch = MutableStateFlow(0L)
    val failedInventoryEpoch = _failedInventoryEpoch.asStateFlow()
    // #505: cold start opens straight into the last catalogue from disk.
    private val inventoryCache = GalleryInventoryCacheFile(
        java.io.File(application.noBackupFilesDir, "gallery-inventory.bin"))
    private var inventoryCacheWrite: Job? = null
    @Volatile private var inventoryDirty = true
    private val mediaChangeVersion = AtomicLong(0L)
    // Prevent an unbounded automatic rescan loop on a busy import/reindex.
    // A second provider change requires a later explicit refresh/resume.
    private val automaticRetryBudget = GalleryAutomaticRetryBudget()
    // Main-dispatcher-owned and cancellable; never persist failed-scan ownership.
    private var pendingSnapshotRetry: Job? = null

    // MediaStore notifications avoid querying every file on every resume.
    // The explicit Refresh button still forces a full inventory rebuild.
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            mediaChangeVersion.incrementAndGet()
            inventoryDirty = true
        }
    }

    // #41: the cold-start scan waits for this before it picks the rows to
    // share, or cache and scan would each hold a full copy of the library.
    private var cachedInventoryLoad: Job? = null

    init {
        application.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )
        application.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )
        refreshPeople()
        loadCachedInventory()
    }

    /**
     * Publishes the on-disk catalogue only while no scan has published yet
     * and Android still grants media access. The normal resume/permission
     * flow starts the real scan in parallel, which then replaces it.
     */
    private fun loadCachedInventory() {
        cachedInventoryLoad = viewModelScope.launch {
            val cached = try {
                withContext(Dispatchers.IO) {
                    val readContext = currentCoroutineContext()
                    inventoryCache.read { readContext.ensureActive() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null } ?: return@launch
            val current = _publishedInventory.value
            val liveGrant = readGalleryMediaGrant(getApplication<Application>())
            if (mayShowCachedGalleryInventory(current, liveGrant, mediaAccessRevoked,
                    cached.grant)) {
                _publishedInventory.value = GalleryPublishedInventory(
                    current.epoch, cached.items, liveGrant, fromCache = true)
                android.util.Log.i("ATLAS_SCAN", "cache published items=${cached.items.size}")
            }
        }
    }

    private fun saveInventoryCache(grant: GalleryMediaGrant, items: List<MediaRecord>) {
        inventoryCacheWrite?.cancel()
        inventoryCacheWrite = viewModelScope.launch(Dispatchers.IO) {
            val writeContext = currentCoroutineContext()
            inventoryCache.write(grant, items) { writeContext.ensureActive() }
        }
    }

    fun refreshIfChanged() {
        if (!mediaAccessRevoked && inventoryDirty) refresh()
    }

    override fun onCleared() {
        clearing = true
        pendingSnapshotRetry?.cancel()
        pendingSnapshotRetry = null
        // Abort a blocked provider query BEFORE clearing its coroutine scope.
        activeScan.abortAll()
        getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        super.onCleared()
    }

    /**
     * Full access loss is not just a hidden Compose screen: drop references
     * to the previously allowed inventory, abort its provider query and
     * prevent any queued read until Android grants fresh access.
     * Called only from the UI main thread, safe to repeat on recreation.
     */
    fun revokeMediaAccess() {
        if (mediaAccessRevoked) return
        pendingSnapshotRetry?.cancel()
        pendingSnapshotRetry = null
        mediaAccessRevoked = true
        inventoryCacheWrite?.cancel()
        inventoryCacheWrite = null
        inventoryCache.invalidate()
        // A delayed revoke cleanup must not delete bytes from a later grant.
        viewModelScope.launch(Dispatchers.IO) { inventoryCache.deleteIfRevoked() }
        val ticket = refreshQueue.pauseForAccessLoss()
        // The epoch-first snapshot equality never scans previous 150k items.
        _publishedInventory.value = GalleryPublishedInventory(ticket, emptyList())
        _failedInventoryEpoch.value = 0L
        _error.value = null
        inventoryDirty = true
        activeScan.abortOlderThan(ticket)
    }

    /**
     * An Activity recreated AFTER access was restored sees its new permission
     * bits as "unchanged", even if its retained ViewModel was paused by the
     * old Activity on full revocation. Restart that paused queue explicitly
     * and return the NEW ticket so Compose can gate the empty old catalog
     * until a fresh authorized read completes.
     *
     * Caller must have independently checked the live Android grant hasAny.
     * Idempotent across both ON_RESUME and the already-resumed catch-up effect.
     */
    internal fun restoreRevokedMediaAccessIfGranted(currentGrant: GalleryMediaGrant): Long? =
        if (needsRetainedGalleryRegrantRecovery(currentGrant, mediaAccessRevoked))
            refreshForAccessChange()
        else null

    /** The returned ticket identifies the earliest safe grant-scope result. */
    fun refreshForAccessChange(): Long {
        pendingSnapshotRetry?.cancel()
        pendingSnapshotRetry = null
        mediaAccessRevoked = false
        inventoryCache.restore()
        val ticket = refreshQueue.request()
        // Unlike ordinary refresh, a new grant invalidates the old scan.
        // Cancel both its provider IPC and its IO coroutine immediately.
        activeScan.abortOlderThan(ticket)
        startRefreshIfIdle()
        return ticket
    }

    fun refresh() {
        if (mediaAccessRevoked) return
        pendingSnapshotRetry?.cancel()
        pendingSnapshotRetry = null
        refreshQueue.request()
        startRefreshIfIdle()
    }

    private fun startRefreshIfIdle() {
        // Called on the UI thread; the pure gate makes overlapping requests
        // coalesce into exactly one follow-up rather than disappearing.
        val ticket = refreshQueue.begin() ?: return
        val versionAtStart = mediaChangeVersion.get()
        // Only a monotonic scan duration enters diagnostics, never timestamps
        // or provider row fields. This does NOT retry a failure speculatively.
        val scanStartedNanos = System.nanoTime()
        fun scanElapsedMilliseconds(): Long =
            ((System.nanoTime() - scanStartedNanos) / 1_000_000L).coerceAtLeast(0L)
        var diagnosticOutcome = "SUPERSEDED"
        var diagnosticRows: Int? = null
        var diagnosticReused: Int? = null
        // The scope belongs to the scan, not whichever Activity grant happens
        // to be visible when an asynchronous MediaStore cursor finishes.
        val observedGrantAtStart = readGalleryMediaGrant(getApplication<Application>())
        inventoryDirty = false
        val providerCancellation = CancellationSignal()
        // Lazy start is essential with Dispatchers.Main.immediate: register
        // scan ownership BEFORE a synchronous fast-path or canceled job can
        // reach its finally cleanup.
        val scanJob: Job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var settleFailedSnapshot = false
            _busy.value = true
            try {
                // The inventory on screen once the disk cache has settled;
                // unchanged rows share its objects. On a cold start the cache
                // decode and this scan start together: reading the screen
                // before the cache is published would find it empty.
                val shownAtStart = inventoryShownAfterCacheSettles(cachedInventoryLoad) {
                    _publishedInventory.value.items
                }
                val inventory = withContext(Dispatchers.IO) {
                    val scanContext = currentCoroutineContext()
                    repo.load(providerCancellation, shownAtStart) { scanContext.ensureActive() }
                }
                // We are back on the owning main dispatcher. There is no
                // suspension between the latest-request check and the atomic
                // epoch+catalog publication. A request queued while IO ran
                // makes the old result ineligible to become visible at all.
                // Epoch-first equality costs O(1), even for 150k photos.
                // MediaStore can notify after repo.load() has completed its
                // provider version/generation checks but BEFORE the main
                // thread publishes. Recheck the observer and live grant here
                // with no suspension between admission and publication.
                val latestRequest = refreshQueue.mayPublish(ticket)
                if (latestRequest) {
                    val grantAtPublication =
                        readGalleryMediaGrant(getApplication<Application>())
                    val changeVersionAtPublication = mediaChangeVersion.get()
                    if (mayPublishCompletedGalleryScan(
                            latestRequest, mediaAccessRevoked,
                            observedGrantAtStart, grantAtPublication,
                            versionAtStart, changeVersionAtPublication)) {
                        _publishedInventory.value = GalleryPublishedInventory(
                            ticket, inventory, observedGrantAtStart
                        )
                        _error.value = null
                        if (observedGrantAtStart.hasAny)
                            saveInventoryCache(observedGrantAtStart, inventory)
                        diagnosticRows = inventory.size
                        diagnosticReused = repo.lastReusedCount
                        diagnosticOutcome = "SUCCESS"
                        // A prior failed scan followed by this exact authorized
                        // publication is now observable without logging media.
                        android.util.Log.i("ATLAS_SCAN",
                            galleryScanPublicationLogLine(
                                ticket, scanElapsedMilliseconds()))
                    } else {
                        // Preserve the last successfully published catalogue;
                        // the old scan must not become a new permission epoch.
                        inventoryDirty = true
                        if (shouldRetryCompletedGalleryScan(
                                latestRequest, mediaAccessRevoked,
                                observedGrantAtStart, grantAtPublication,
                                versionAtStart, changeVersionAtPublication,
                                oneRetryStillAvailable =
                                    automaticRetryBudget.availableFor(ticket) && !clearing)) {
                            automaticRetryBudget.recordFollowup(refreshQueue.request())
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // ViewModel disposal is not an inventory error. Never publish
                // a misleading retry banner or spin up another canceled scan.
                diagnosticOutcome = "CANCELLED"
                throw cancelled
            } catch (oom: OutOfMemoryError) {
                diagnosticOutcome = "OUT_OF_MEMORY"
                throw oom
            } catch (e: Exception) {
                diagnosticOutcome = if (refreshQueue.mayPublish(ticket))
                    atlasScanFailureOutcome(e) else "SUPERSEDED"
                inventoryDirty = true
                // Ignore errors from a request superseded by a newer access
                // scope: its result is no longer authoritative either.
                if (refreshQueue.mayPublish(ticket)) {
                    // The MediaProvider may expose private file paths/SQL in
                    // exception messages. Emit ONLY a whitelisted category,
                    // observer/grant/ticket flags and monotonic scan duration.
                    // Never pass the Throwable as a Log argument.
                    val stillGranted = try {
                        readGalleryMediaGrant(getApplication<Application>()) ==
                            observedGrantAtStart
                    } catch (_: Exception) { null }
                    val evidence = galleryScanFailureEvidence(
                        e, observerChanged =
                            mediaChangeVersion.get() != versionAtStart,
                        grantAtStartStillMatches = stillGranted,
                        latestRequest = refreshQueue.mayPublish(ticket),
                        explicitlyRevoked = mediaAccessRevoked,
                        elapsedMilliseconds = scanElapsedMilliseconds())
                    android.util.Log.w("ATLAS_SCAN", evidence.safeLogLine(ticket))
                    // A scan's typed version/generation mismatch is the ONLY
                    // exception eligible for a delayed, single retry. Other
                    // provider exceptions remain terminal, with safe messages.
                    settleFailedSnapshot = shouldRetryFailedGalleryScan(
                        e, latestRequest = refreshQueue.mayPublish(ticket),
                        accessExplicitlyRevoked = mediaAccessRevoked,
                        grantAtScanStart = observedGrantAtStart,
                        grantAtFailure = if (stillGranted == true)
                            observedGrantAtStart else null,
                        oneRetryStillAvailable =
                            !clearing && automaticRetryBudget.availableFor(ticket))
                    if (!settleFailedSnapshot) {
                        _error.value = galleryScanFailureMessage(e)
                        _failedInventoryEpoch.value = ticket
                    } else {
                        // No transient error screen for a retryable first
                        // failure; only the actual second failure is terminal.
                        android.util.Log.i("ATLAS_SCAN",
                            galleryScanRetryLogLine("settling", ticket))
                    }
                }
            } finally {
                AtlasDiagnosticsRing.scan(scanElapsedMilliseconds(), diagnosticRows,
                    diagnosticOutcome, reused = diagnosticReused)
                // A notification during IO marks the inventory dirty. Explicit
                // new requests (not arbitrary continuous observer churn) get
                // coalesced into a guaranteed follow-up read.
                if (mediaChangeVersion.get() != versionAtStart) inventoryDirty = true
                _busy.value = false
                activeScan.finish(ticket)
                val newerRequestQueued = refreshQueue.finish(ticket)
                // The current job was canceled on grant supersession, but
                // the owning ViewModel scope is still alive and MUST launch
                // the latest queued scan. Never restart after onCleared().
                if (newerRequestQueued && !clearing && viewModelScope.isActive) {
                    // Explicit request supersedes our failed scan, so its
                    // newest scope gets normal immediate processing.
                    startRefreshIfIdle()
                } else if (settleFailedSnapshot && !clearing &&
                    !mediaAccessRevoked && viewModelScope.isActive) {
                    // No new ticket has been issued yet: this avoids holding a
                    // pending owner slot across the settle delay. Any new
                    // permission request can run immediately and supersede it.
                    pendingSnapshotRetry = viewModelScope.launch {
                        val eligible = awaitGallerySnapshotRetrySettle {
                            val currentlyEligible =
                                refreshQueue.mayRetryAfterFinished(ticket) &&
                                !clearing && !mediaAccessRevoked &&
                                automaticRetryBudget.availableFor(ticket)
                            val liveGrant = if (currentlyEligible) try {
                                readGalleryMediaGrant(getApplication<Application>())
                            } catch (_: Exception) { null } else null
                            currentlyEligible &&
                                observedGrantAtStart.hasAny &&
                                liveGrant == observedGrantAtStart
                        }
                        // No suspensions from final admission to request():
                        // authorization, queue ownership and shared budget
                        // were all checked on the Main dispatcher above.
                        if (eligible) {
                            val next = refreshQueue.request()
                            automaticRetryBudget.recordFollowup(next)
                            android.util.Log.i("ATLAS_SCAN",
                                galleryScanRetryLogLine("started", ticket, next))
                            startRefreshIfIdle()
                        } else {
                            android.util.Log.i("ATLAS_SCAN",
                                galleryScanRetryLogLine("aborted", ticket))
                            // A superseding request/regrant or revoke owns UI
                            // state. Only the still-current failed ticket can
                            // surface a terminal prompt after the pause.
                            if (refreshQueue.mayRetryAfterFinished(ticket) &&
                                !clearing && !mediaAccessRevoked) {
                                _error.value =
                                    "Photo access was interrupted. Check Photos permission and refresh."
                                _failedInventoryEpoch.value = ticket
                            }
                        }
                    }
                }
            }
        }
        // The Main dispatcher serializes this registration with all grant
        // callbacks and with the canceled job's finally cleanup.
        activeScan.registerThenStart(
            ticket,
            abort = {
                try { providerCancellation.cancel() }
                finally { scanJob.cancel() }
            },
            start = { scanJob.start() }
        )
    }

    private fun refreshPeople() {
        // An earlier IO read must never paint over a later manual import.
        val epoch = peopleCsvGate.nextPeopleSnapshotEpoch()
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { peopleRepo.people() }
            if (peopleCsvGate.acceptsPeopleSnapshot(epoch)) _people.value = snapshot
        }
    }

    private suspend fun readCsv(uri: Uri): String {
        // Every manual and linked V4 import runs inside the exclusive IO
        // worker. Stop a replaced read promptly, and never deserialize an
        // unbounded cloud/SAF provider response into the People transaction.
        val readContext = currentCoroutineContext()
        val source = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: error("Unable to read V4 document")
        return readBoundedV4Csv(source) { readContext.ensureActive() }
    }

    private fun contentDigest(csv: String): String =
        MessageDigest.getInstance("SHA-256").digest(csv.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b.toInt() and 255) }

    /** One-time SAF document pairing. Never changes V4 source files. */
    fun linkV4LiveCsv(uri: Uri, onResult: (String) -> Unit) {
        val app = getApplication<Application>()
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ex: SecurityException) {
            onResult(v4DocumentFailureMessage(V4DocumentOperation.LINK, ex))
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    peopleCsvGate.exclusive {
                        val body = validatedLiveV4Csv(readCsv(uri))
                        val count = peopleRepo.replaceFromV4Export(body)
                        v4LinkPrefs.edit()
                            .putString("uri", uri.toString())
                            .putString("sha256", contentDigest(body))
                            .apply()
                        count
                    }
                }
            }
            result.onSuccess {
                _v4SyncState.value = "Linked V4 live export · updates on gallery resume"
                refreshPeople()
                onResult("Linked V4 export: imported $it confirmed face assignments")
            }.onFailure {
                onResult(v4DocumentFailureMessage(V4DocumentOperation.LINK, it))
            }
        }
    }

    /** Read-only SAF polling on resume, with checksum-sealed and transactional import. */
    fun refreshLinkedV4Csv() {
        if (v4LinkPrefs.getString("uri", null) == null || v4SyncRunning) return
        v4SyncRunning = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        peopleCsvGate.exclusive {
                            // IMPORTANT: Re-read the active link *after* obtaining
                            // the lock. A completed manual import may have removed it.
                            val target = v4LinkPrefs.getString("uri", null)
                            if (target == null) null
                            else {
                                val body = validatedLiveV4Csv(readCsv(Uri.parse(target)))
                                val digest = contentDigest(body)
                                if (digest == v4LinkPrefs.getString("sha256", null)) false
                                else {
                                    peopleRepo.replaceFromV4Export(body)
                                    v4LinkPrefs.edit().putString("sha256", digest).apply()
                                    true
                                }
                            }
                        }
                    }
                }
                result.onSuccess { changed ->
                    if (changed == true) refreshPeople()
                    _v4SyncState.value = when (changed) {
                        true -> "V4 People refreshed from linked export"
                        false -> "V4 live export linked · no changes"
                        null -> "Manual V4 import · live linking disabled"
                    }
                }.onFailure {
                    // NEVER clear the last successfully imported People records.
                    _v4SyncState.value = "V4 link unavailable or incomplete · keeping last good People"
                }
            } finally {
                // A cancelled refresh must never permanently disable future polls.
                v4SyncRunning = false
            }
        }
    }

    fun importV4Csv(uri: android.net.Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    peopleCsvGate.exclusive {
                        val count = peopleRepo.replaceFromV4Export(readCsv(uri))
                        // Replace assignments and disable old live link together,
                        // so a queued stale refresh cannot overwrite this choice.
                        v4LinkPrefs.edit().remove("uri").remove("sha256").apply()
                        count
                    }
                }
            }
            result.onSuccess { count ->
                _v4SyncState.value = "Manual V4 import · live linking disabled"
                refreshPeople()
                onResult("Imported $count confirmed face assignments")
            }
                .onFailure { onResult(v4DocumentFailureMessage(V4DocumentOperation.IMPORT, it)) }
        }
    }
}
