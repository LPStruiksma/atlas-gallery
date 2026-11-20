package nl.atlas.gallery.featureidentity.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.atlas.gallery.FaceCropGeometry
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PeopleDiscoveryHost
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureface.durableFaceKeyOrNull
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.YuNetFace
import nl.atlas.gallery.featureface.toFaceVisibleMedia
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.IdentityEditEngine
import nl.atlas.gallery.featureidentity.engine.IdentityReferenceSet
import nl.atlas.gallery.featureidentity.engine.TargetFace
import nl.atlas.gallery.featureidentity.local.HyperSwapNetwork
import nl.atlas.gallery.featureidentity.local.IdentityModelManifest
import nl.atlas.gallery.featureidentity.local.IdentityModelStore
import nl.atlas.gallery.featureidentity.local.LocalFaceSwapEngine
import nl.atlas.gallery.featureidentity.local.ModelFetcher
import nl.atlas.gallery.featureidentity.profile.IdentityProfile
import nl.atlas.gallery.featureidentity.profile.IdentityProfileIds
import nl.atlas.gallery.featureidentity.profile.IdentityProfileRules
import nl.atlas.gallery.featureidentity.profile.IdentityProfileStore
import nl.atlas.gallery.featureidentity.profile.IdentityReference
import nl.atlas.gallery.featureidentity.profile.IdentityReferenceDraft
import nl.atlas.gallery.featureidentity.profile.PeopleFacesRead
import nl.atlas.gallery.featureidentity.profile.PeopleReferenceSource
import nl.atlas.gallery.featureidentity.profile.ReferenceFaceBox
import nl.atlas.gallery.featureidentity.profile.ReferenceOrigin
import nl.atlas.gallery.featureidentity.save.IdentityEditSave
import nl.atlas.gallery.featureidentity.target.TargetFaceCandidate
import nl.atlas.gallery.featureidentity.target.TargetFaceDetection
import nl.atlas.gallery.featureidentity.target.TargetFaceSelection
import nl.atlas.gallery.featureidentity.target.TargetFaceSelector
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * IDS-11 (#478): the "Swap person" launcher (design brief rev 2,
 * https://github.com/LPStruiksma/atlas-gallery/issues/478#issuecomment-7027593393).
 * The viewer ⋮ and single-photo selection More open [SwapPersonEntry]; it copies the source
 * photo into app cache, builds the production [AtlasSwapPersonHost] from existing ATLAS parts
 * (People, identity profiles, IDS-7 detection, the model store, IDS-10 save) and shows the
 * IDS-8 flow. No name, face box, path or pixel is logged anywhere in this file.
 */

/** Blocking, IO only: [SwapPersonEligibility] for one gallery item, cached per revision. */
object SwapPersonMediaCheck {
    private val cache = LruCache<String, Boolean>(256)

    fun eligible(context: Context, media: MediaRecord): Boolean {
        val uri = Uri.parse(media.uri)
        if (!SwapPersonEligibility.mediaStoreStill(media.isVideo, uri.scheme, uri.authority)) return false
        val key = "${media.uri}#${media.modifiedAtSeconds}#${media.sizeBytes}"
        cache.get(key)?.let { return it }
        val result = try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)
            val png = mime?.substringBefore(';')?.trim()?.lowercase() == "image/png"
            val header = resolver.openInputStream(uri)?.use {
                if (png) SwapPersonEligibility.pngChunkSkeleton(it) else readUpTo(it, SwapPersonEligibility.HEADER_BYTES)
            }
            header != null && SwapPersonEligibility.staticImage(mime, header)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        cache.put(key, result)
        return result
    }

    internal fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArray(limit)
        var n = 0
        while (n < limit) {
            val r = input.read(out, n, limit - n)
            if (r < 0) break
            n += r
        }
        return out.copyOf(n)
    }
}

/** What the gallery hands the launcher; read at call time, so a changed grant is seen at once. */
internal class SwapPersonEntryPorts(
    val discovery: PeopleDiscoveryHost,
    /** The gallery publication People serves (the viewer's People row uses the same token). */
    val galleryGrant: () -> String?,
    /** The authorized gallery list, newest first is not required. */
    val media: () -> List<MediaRecord>,
    /** Rescans the gallery so a saved copy appears. Called on the main thread. */
    val onRefresh: () -> Unit
)

private sealed interface SwapSource {
    data object Loading : SwapSource
    data object Failed : SwapSource
    data class Ready(val image: EditImage, val file: File) : SwapSource
}

/**
 * The per-launch source copy (and picker previews) under `cacheDir/atlas-identity-entry/<session>`.
 * Copy and removal take one lock, so a removal asked for during a copy (Back while preparing)
 * runs after the copy has stopped and deletes whatever it wrote. Removal runs on a process scope,
 * not the composition, so leaving the screen cannot cancel it. Starting a launch also clears any
 * other session's folder, so a folder orphaned by a killed process lives only until the next one.
 */
internal object SwapSourceFiles {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun root(cacheDir: File) = File(cacheDir, "atlas-identity-entry")

    fun dir(cacheDir: File, sessionId: String) =
        File(root(cacheDir), sessionId.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "session" })

    /**
     * Copies [open]'s bytes to `<session>/source` unless a complete copy is already there (kept
     * across rotation and process death). Returns null and leaves no folder behind on any failure;
     * on cancellation the partial copy is deleted before the lock is released.
     */
    suspend fun copy(cacheDir: File, sessionId: String, open: () -> InputStream?): File? = lock.withLock {
        withContext(Dispatchers.IO) {
            val dir = dir(cacheDir, sessionId)
            root(cacheDir).listFiles()?.filter { it.name != dir.name }?.forEach { it.deleteRecursively() }
            val file = File(dir, "source")
            if (file.isFile && file.length() > 0L) return@withContext file
            val tmp = File(dir, "source.part")
            var done = false
            try {
                if (!dir.isDirectory && !dir.mkdirs()) return@withContext null
                val input = open() ?: return@withContext null
                input.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                ensureActive()
                done = tmp.length() > 0L && tmp.renameTo(file)
                if (done) file else null
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } finally {
                if (!done) dir.deleteRecursively()
            }
        }
    }

    /** Removes one session's folder once no copy is running. Safe to call more than once. */
    fun discard(cacheDir: File, sessionId: String): Job =
        scope.launch { lock.withLock { dir(cacheDir, sessionId).deleteRecursively() } }
}

/** Copies the MediaStore photo (stored bytes, EXIF kept) so the flow and engine read one file. */
private suspend fun prepareSource(context: Context, media: MediaRecord, sessionId: String): SwapSource {
    val file = SwapSourceFiles.copy(context.cacheDir, sessionId) {
        context.contentResolver.openInputStream(Uri.parse(media.uri))
    } ?: return SwapSource.Failed
    return withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            SwapSourceFiles.discard(context.cacheDir, sessionId)
            return@withContext SwapSource.Failed
        }
        val format = when (bounds.outMimeType) {
            "image/png" -> ImageFormat.PNG
            "image/webp" -> ImageFormat.WEBP
            else -> ImageFormat.JPEG
        }
        SwapSource.Ready(EditImage(file.path, bounds.outWidth, bounds.outHeight, format), file)
    }
}

/**
 * The launcher. [source] is the photo; [sessionId] is minted per launch (and kept across process
 * death by the caller). [onClose] runs on ✕, Discard, Back or Not now; [onView] opens a saved copy
 * by its MediaStore URI through the caller's pending-focus path.
 */
@Composable
internal fun SwapPersonEntry(
    source: MediaRecord,
    sessionId: String,
    ports: SwapPersonEntryPorts,
    onClose: () -> Unit,
    onView: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext
    val latestPorts by rememberUpdatedState(ports)
    val stablePorts = remember(sessionId) {
        SwapPersonEntryPorts(
            discovery = ports.discovery,
            galleryGrant = { latestPorts.galleryGrant() },
            media = { latestPorts.media() },
            onRefresh = { latestPorts.onRefresh() }
        )
    }
    val vm: SwapPersonViewModel = viewModel(key = "swap-person:$sessionId")
    val prepared by produceState<SwapSource>(SwapSource.Loading, sessionId) {
        value = prepareSource(app, source, sessionId)
    }
    fun cleanUp() {
        SwapSourceFiles.discard(app.cacheDir, sessionId)
    }
    when (val p = prepared) {
        SwapSource.Loading -> {
            // The copy may still be running: discard waits for it, then removes what it wrote.
            BackHandler { cleanUp(); onClose() }
            Box(Modifier.fillMaxSize().background(SwapColors.Canvas).testTag("swap-person-preparing"))
        }
        SwapSource.Failed -> {
            BackHandler { cleanUp(); onClose() }
            Box(Modifier.fillMaxSize().background(SwapColors.Canvas).testTag("swap-person-unavailable"),
                contentAlignment = Alignment.Center) {
                CanvasMessage("This photo can't be opened for Swap person.",
                    primary = "Close" to { cleanUp(); onClose() }, tag = "swap-person-unavailable-message")
            }
        }
        is SwapSource.Ready -> {
            val host = remember(sessionId) { AtlasSwapPersonHost(app, stablePorts, source, p.file, SwapSourceFiles.dir(app.cacheDir, sessionId)) }
            val machine = remember(vm, sessionId) {
                vm.initialize(host, host.engine, p.image, app.cacheDir,
                    host.saver(vm, Uri.parse(source.uri)))
            }
            SwapPersonFlow(
                state = vm.ui,
                source = p.image,
                machine = machine,
                onFinished = { cleanUp(); onClose() },
                onView = { handle -> cleanUp(); onView(handle) }
            )
        }
    }
}

/**
 * The production [SwapPersonHost]. Every call runs on the flow's worker thread. People stays the
 * authority for who exists and their names; profiles and references live in the IDS-4 store.
 */
internal class AtlasSwapPersonHost(
    private val app: Context,
    private val ports: SwapPersonEntryPorts,
    private val sourceRecord: MediaRecord,
    private val sourceFile: File,
    private val sessionDir: File
) : SwapPersonHost {
    private val store by lazy { profileStore(app) }
    private val models = IdentityModelStore(app.noBackupFilesDir,
        // Downloads run from the IDS-19 installer (#619); this host never fetches.
        ModelFetcher { _, _ -> throw IOException("Model download is not available here") })
    val engine: IdentityEditEngine by lazy { LocalFaceSwapEngine(app, HyperSwapNetwork.engineConfig(models)) }
    private val recents = app.getSharedPreferences("atlas_swap_person_recents", Context.MODE_PRIVATE)
    private var detected: List<TargetFaceCandidate>? = null
    private val picks = HashMap<String, Pick>()

    private class Pick(val record: MediaRecord, val width: Int, val height: Int, val file: File,
                       val faces: List<YuNetFace>, val vectors: List<FloatArray?>)

    private fun epoch(): String? = ports.discovery.displayEpoch(ports.galleryGrant())

    // ---- S1 people ----

    override fun people(): List<SwapPersonChoice> {
        val epoch = epoch()
        val home = ports.discovery.liveHomePeople(epoch)
            ?: ports.discovery.status(ports.galleryGrant()).people?.takeIf { it.permissionEpoch == epoch }
        val named = home?.people?.map { (key, p) -> SwapNamedPerson(key.value, p.name, p.photoCount, p.coverFaceUid) }
            .orEmpty().sortedByDescending { it.photoCount }
        val avatars = named.take(MAX_AVATARS).mapTo(HashSet()) { it.personKey }
        return SwapPersonPeopleRules.choices(
            named = named,
            profiles = runCatching { store.profiles() }.getOrDefault(emptyList()),
            recents = readRecents(),
            personFace = { person -> if (person.personKey in avatars) avatar(epoch, person.coverFaceUid) else null },
            profileFace = { profile -> profile.variants.firstOrNull()?.usableReferences?.firstOrNull()?.let { crop(profile, it) }?.let { SwapFace(it, null) } }
        )
    }

    private fun avatar(epoch: String?, faceUid: String): SwapFace? {
        if (epoch == null) return null
        val file = File(app.cacheDir, "atlas-identity-avatars/" + digest("$epoch|$faceUid") + ".jpg")
        if (!file.isFile) {
            val bitmap = ports.discovery.faceCrop(epoch, faceUid) ?: return null
            if (!writeJpeg(bitmap, file, recycle = false)) return null
        }
        return imageOf(file)?.let { SwapFace(it, null) }
    }

    // ---- references ----

    /** The real profile behind [profileId]; a People placeholder becomes a profile only when [create]. */
    private fun resolve(profileId: String, create: Boolean): IdentityProfile? {
        val key = SwapPersonPeopleRules.placeholderKey(profileId)
        return when {
            key == null && IdentityProfileIds.valid(profileId) -> store.profile(profileId)
            key == null || !IdentityProfileRules.validPersonKey(key) -> null
            create -> store.profileForPeoplePerson(key)
            else -> store.findByPerson(key)
        }
    }

    override fun references(profileId: String, lookId: String?): SwapReferences {
        val profile = resolve(profileId, create = true) ?: return SwapReferences.TooFew(0)
        var variant = profile.variants.firstOrNull { it.id == lookId } ?: profile.variants.first()
        val person = profile.peoplePersonKey
        if (person != null && variant.usableReferences.size < SwapPersonPeopleRules.MAX_REFERENCES) {
            val reads = ports.discovery.identityPeopleReads(ports.galleryGrant())
            val faces = reads?.let { PeopleReferenceSource(it).faces(person) }
            if (faces is PeopleFacesRead.Faces) {
                val want = SwapPersonPeopleRules.MAX_REFERENCES - variant.usableReferences.size
                for (face in SwapPersonPeopleRules.suggest(faces.faces, variant.references, want)) {
                    try { store.addReference(variant.id, face.toDraft()) } catch (_: IllegalArgumentException) { }
                }
                variant = store.profile(profile.id)?.variants?.firstOrNull { it.id == variant.id } ?: variant
            }
        }
        val images = variant.usableReferences.mapNotNull { crop(profile, it) }
            .take(SwapPersonPeopleRules.MAX_REFERENCES)
        return if (images.size < SwapPersonPeopleRules.MIN_REFERENCES) SwapReferences.TooFew(images.size)
        else SwapReferences.Ready(IdentityReferenceSet(profile.id, variant.id, images))
    }

    /** The reference's face crop (derived data of its reference), made once from the authorized photo. */
    private fun crop(profile: IdentityProfile, reference: IdentityReference): EditImage? {
        val file = runCatching { store.derivedFile(profile.id, reference.id, CROP_NAME) }.getOrNull() ?: return null
        if (!file.isFile) {
            val uid = reference.peopleFaceUid
            val uri = if (uid != null) ports.discovery.identityFaceSource(epoch(), uid)?.first
                else mediaFor(reference.mediaKey, reference.revision)?.uri
            uri ?: return null
            val b = reference.box
            val bitmap = decodeCrop(Uri.parse(uri), IndexedFaceBox(b.left, b.top, b.right, b.bottom)) ?: return null
            if (!writeJpeg(bitmap, file)) return null
        }
        return imageOf(file)
    }

    private fun mediaFor(mediaKey: String, revision: String): MediaRecord? =
        ports.media().firstOrNull { record ->
            !record.isVideo && record.toFaceVisibleMedia(editorRevision = 0L).durableFaceKeyOrNull()
                ?.let { it.canonicalMediaKey == mediaKey && it.sourceRevision == revision } == true
        }

    private fun decodeCrop(uri: Uri, box: IndexedFaceBox): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            val plan = FaceCropGeometry.plan(info.size.width, info.size.height, box,
                margin = CROP_MARGIN, targetEdge = CROP_EDGE)
                ?: throw IOException("Photo has no usable size")
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(plan.decodeWidth, plan.decodeHeight)
            decoder.crop = Rect(plan.left, plan.top, plan.right, plan.bottom)
        }
    } catch (_: IOException) { null } catch (_: RuntimeException) { null }

    // ---- S2 target (IDS-7) ----

    private fun candidates(): List<TargetFaceCandidate> = detected ?: try {
        val degrees = sourceRecord.orientationDegrees.takeIf { it in setOf(0, 90, 180, 270) } ?: 0
        TargetFaceDetection.detect(ImageDecoder.createSource(sourceFile), degrees, engine.capabilities()) {
            OnnxFaceDetector.fromAssets(app.assets)
        }
    } catch (_: IOException) { emptyList() } catch (_: RuntimeException) { emptyList() }
        .also { detected = it }

    private fun sourceSize(): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    override fun findFaces(): TargetFaceSelection {
        val (w, h) = sourceSize()
        if (w <= 0 || h <= 0) return TargetFaceSelection.NoFace
        return TargetFaceSelector.select(candidates(), w, h, MIN_FACE_EDGE)
    }

    override fun usableFaces(): List<TargetFace> {
        val all = candidates().map { it.face }
        return all.filter { it.box.width >= MIN_FACE_EDGE && it.box.height >= MIN_FACE_EDGE }.ifEmpty { all }
    }

    // ---- S6 setup ----

    override fun setupStatus(): SwapSetupStatus =
        if (IdentityModelManifest.entries.all { models.installedFile(it) != null }) SwapSetupStatus.Ready
        else SwapSetupStatus.Needed(ceil(IdentityModelManifest.downloadBytes / 1_000_000.0).toInt())

    /**
     * The downloader is IDS-19 (#619). Until it lands this host never fakes a successful install:
     * it reports the download as not done, so the sheet stays honest.
     */
    override fun download(onProgress: (Float) -> Unit): Boolean = setupStatus() == SwapSetupStatus.Ready

    // ---- S8 choose photos ----

    override fun photosToPick(): List<SwapPickPhoto> {
        synchronized(picks) { picks.clear() }
        val dir = File(sessionDir, "pick").apply { deleteRecursively(); mkdirs() }
        val recent = ports.media().asSequence().filter { !it.isVideo }
            .sortedByDescending { it.takenAtMillis }.take(MAX_PICK_SCAN).toList()
        val out = ArrayList<SwapPickPhoto>()
        val detector = runCatching { OnnxFaceDetector.fromAssets(app.assets) }.getOrNull() ?: return out
        val embedder = runCatching { OnnxFaceEmbedder.fromAssets(app.assets) }.getOrNull()
        try {
            for (record in recent) {
                if (out.size >= MAX_PICK) break
                if (!SwapPersonMediaCheck.eligible(app, record)) continue
                val bitmap = decodeBounded(Uri.parse(record.uri), PICK_EDGE) ?: continue
                try {
                    val faces = runCatching { detector.detectFaces(bitmap) }.getOrDefault(emptyList())
                        .filter { it.width >= 12f && it.height >= 12f }
                    if (faces.isEmpty()) continue
                    val vectors = embedder?.let { e -> runCatching { e.embedAll(bitmap, faces).map { it?.vector } }.getOrNull() }
                        ?: List(faces.size) { null }
                    val file = File(dir, "${out.size}.jpg")
                    if (!writeJpeg(bitmap, file, recycle = false)) continue
                    val pick = Pick(record, bitmap.width, bitmap.height, file, faces, vectors)
                    synchronized(picks) { picks[record.uri] = pick }
                    out += SwapPickPhoto(record.uri, EditImage(file.path, bitmap.width, bitmap.height, ImageFormat.JPEG),
                        faces.map { boxOf(it, bitmap.width, bitmap.height) })
                } finally { bitmap.recycle() }
            }
        } finally {
            runCatching { detector.close() }
            runCatching { embedder?.close() }
        }
        return out
    }

    private fun boxOf(f: YuNetFace, w: Int, h: Int): FaceBox {
        val l = f.left.toInt().coerceIn(0, w - 1)
        val t = f.top.toInt().coerceIn(0, h - 1)
        return FaceBox(l, t, ceil((f.left + f.width).toDouble()).toInt().coerceIn(l + 1, w),
            ceil((f.top + f.height).toDouble()).toInt().coerceIn(t + 1, h))
    }

    private fun vectorFor(photo: SwapPickPhoto, box: FaceBox): FloatArray? {
        val pick = synchronized(picks) { picks[photo.id] } ?: return null
        val i = photo.faces.indexOf(box)
        return pick.vectors.getOrNull(i)
    }

    override fun matchFace(photo: SwapPickPhoto, chosen: List<Pair<SwapPickPhoto, FaceBox>>): Int? {
        val pick = synchronized(picks) { picks[photo.id] } ?: return null
        return SwapFaceMatch.index(chosen.mapNotNull { (p, b) -> vectorFor(p, b) }, pick.vectors)
    }

    override fun createProfile(faces: List<Pair<SwapPickPhoto, FaceBox>>, name: String?): SwapPersonChoice {
        val label = name?.trim()?.take(IdentityProfileRules.MAX_LABEL_LENGTH)?.trim()
            ?.takeIf(IdentityProfileRules::validLabel) ?: NEW_PERSON_LABEL
        val profile = store.createManualProfile(label)
        addReferences(profile.id, faces)
        val saved = store.profile(profile.id) ?: profile
        val first = saved.variants.firstOrNull()?.usableReferences?.firstOrNull()?.let { crop(saved, it) }
        return SwapPersonChoice(saved.id, name?.trim()?.ifEmpty { null }, saved.variants.sumOf { it.usableReferences.size },
            0, saved.variants.map { SwapLook(it.id, it.label) }, first?.let { SwapFace(it, null) })
    }

    override fun addReferences(profileId: String, faces: List<Pair<SwapPickPhoto, FaceBox>>) {
        val profile = resolve(profileId, create = true) ?: return
        val variant = profile.variants.firstOrNull() ?: return
        for ((photo, box) in faces) {
            val pick = synchronized(picks) { picks[photo.id] } ?: continue
            val key = pick.record.toFaceVisibleMedia(editorRevision = 0L).durableFaceKeyOrNull() ?: continue
            val normalized = runCatching {
                ReferenceFaceBox(box.left.toFloat() / pick.width, box.top.toFloat() / pick.height,
                    box.right.toFloat() / pick.width, box.bottom.toFloat() / pick.height)
            }.getOrNull() ?: continue
            val draft = IdentityReferenceDraft(key.canonicalMediaKey, key.sourceRevision, normalized, null, ReferenceOrigin.MANUAL)
            val reference = try { store.addReference(variant.id, draft) } catch (_: IllegalArgumentException) { null } ?: continue
            // The crop comes from the preview already decoded for the picker.
            val preview = BitmapFactory.decodeFile(pick.file.path) ?: continue
            try {
                val plan = FaceCropGeometry.plan(preview.width, preview.height,
                    IndexedFaceBox(normalized.left, normalized.top, normalized.right, normalized.bottom),
                    margin = CROP_MARGIN, targetEdge = CROP_EDGE, maxDecodeEdge = max(preview.width, preview.height)) ?: continue
                val sx = preview.width.toFloat() / plan.decodeWidth
                val sy = preview.height.toFloat() / plan.decodeHeight
                val l = (plan.left * sx).roundToInt().coerceIn(0, preview.width - 1)
                val t = (plan.top * sy).roundToInt().coerceIn(0, preview.height - 1)
                val r = (plan.right * sx).roundToInt().coerceIn(l + 1, preview.width)
                val b = (plan.bottom * sy).roundToInt().coerceIn(t + 1, preview.height)
                val cropped = Bitmap.createBitmap(preview, l, t, r - l, b - t)
                writeJpeg(cropped, store.derivedFile(profile.id, reference.id, CROP_NAME), recycle = cropped !== preview)
            } finally { preview.recycle() }
        }
    }

    // ---- S7 photos used ----

    override fun referencePhotos(profileId: String): List<SwapReferencePhoto> {
        val profile = resolve(profileId, create = false) ?: return emptyList()
        return profile.variants.flatMap { it.usableReferences }.mapNotNull { ref ->
            crop(profile, ref)?.let { SwapReferencePhoto(ref.id, SwapFace(it, null)) }
        }
    }

    override fun removeReference(profileId: String, referenceId: String) {
        if (IdentityProfileIds.valid(referenceId)) store.removeReference(referenceId)
    }

    override fun forget(profileId: String) {
        val profile = resolve(profileId, create = false)
        if (profile != null) store.deleteProfile(profile.id)
        val keys = setOfNotNull(profileId, profile?.let(SwapPersonPeopleRules::recentKey))
        writeRecents(readRecents().filterNot { it in keys })
    }

    override fun markUsed(profileId: String) {
        val key = if (SwapPersonPeopleRules.placeholderKey(profileId) != null) profileId
            else resolve(profileId, create = false)?.let(SwapPersonPeopleRules::recentKey) ?: profileId
        writeRecents(SwapPersonPeopleRules.touch(readRecents(), key))
    }

    private fun readRecents(): List<String> =
        recents.getString(RECENTS, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun writeRecents(list: List<String>) {
        recents.edit().putString(RECENTS, list.joinToString("\n")).apply()
    }

    // ---- S5 save (IDS-10) ----

    /** The real profile and look ids for provenance; a placeholder person was resolved by now. */
    private fun forSave(profileId: String, variantId: String?): Pair<String, String?>? {
        val profile = resolve(profileId, create = true) ?: return null
        val variant = profile.variants.firstOrNull { it.id == variantId } ?: profile.variants.firstOrNull()
        return profile.id to variant?.id
    }

    fun saver(vm: SwapPersonViewModel, originalUri: Uri): SwapPersonSaver =
        SwapPersonSaver { candidate, profileId, variantId, parent, done ->
            vm.viewModelScope.launch(Dispatchers.IO) {
                val outcome = try {
                    val ids = forSave(profileId, variantId) ?: error("Profile is gone")
                    val file = candidate.file ?: error("Version has no file")
                    val result = IdentityEditSave(app).save(
                        IdentityEditSave.Request(
                            originalUri = originalUri,
                            candidateUri = Uri.fromFile(file),
                            originalRevision = revision(originalUri),
                            candidate = candidate,
                            profileId = ids.first,
                            variantId = ids.second,
                            refinementParent = parent
                        ),
                        onRefresh = { android.os.Handler(android.os.Looper.getMainLooper()).post { ports.onRefresh() } }
                    )
                    SwapSaveOutcome.Saved(result.uri.toString())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SwapSaveOutcome.Failed
                }
                done(outcome)
            }
        }

    /** Same revision text AI Edit records for its original (DATE_MODIFIED and SIZE). */
    private fun revision(uri: Uri): String {
        require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
        return app.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE),
            null, null, null)?.use {
            check(it.moveToFirst()) { "Selected original disappeared" }
            "mediastore:${it.getLong(0)}:${it.getLong(1)}"
        } ?: error("Cannot verify original revision")
    }

    // ---- files ----

    private fun decodeBounded(uri: Uri, longEdge: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            val scale = minOf(1f, longEdge.toFloat() / max(info.size.width, info.size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        }
    } catch (_: IOException) { null } catch (_: RuntimeException) { null }

    private fun writeJpeg(bitmap: Bitmap, file: File, recycle: Boolean = true): Boolean = try {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val tmp = File(file.path + ".part")
        tmp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        tmp.renameTo(file)
    } catch (_: IOException) { false } catch (_: IllegalStateException) { false } finally {
        if (recycle) bitmap.recycle()
    }

    private fun imageOf(file: File): EditImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else EditImage(file.path, bounds.outWidth, bounds.outHeight, ImageFormat.JPEG)
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    companion object {
        @Volatile private var sharedStore: IdentityProfileStore? = null

        /** One profile database helper per process, however often the flow opens. */
        fun profileStore(context: Context): IdentityProfileStore = sharedStore ?: synchronized(this) {
            sharedStore ?: IdentityProfileStore(context.applicationContext).also { sharedStore = it }
        }

        /** Same floor as the engine's [LocalFaceSwapEngine.MIN_FACE_CROP_PX]. */
        const val MIN_FACE_EDGE = 48
        const val MAX_AVATARS = 60
        const val MAX_PICK = 36
        const val MAX_PICK_SCAN = 120
        const val PICK_EDGE = 1024
        const val CROP_EDGE = 512
        const val CROP_MARGIN = 0.6f
        const val CROP_NAME = "face.jpg"
        const val RECENTS = "recents"
        const val NEW_PERSON_LABEL = "New person"
    }
}
