package nl.atlas.gallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import kotlin.math.abs

/**
 * PERF-10 (#139): real API35 MediaProvider + ContentObserver + ViewModel +
 * rendered Pictures timeline. All pictures are invented PNGs on an emulator;
 * this test refuses physical devices BEFORE the fixture rule writes anything.
 *
 * Do not replace this with a fake provider test: callbacks/flags, generation
 * selections, permission bits and async Compose publication are the contract.
 */
@RunWith(AndroidJUnit4::class)
class GalleryMediaStoreObserverJourneyTest {
    private val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("PERF-10 writes disposable synthetic media only on API35 emulators",
                    Build.VERSION.SDK_INT == 35 &&
                        (Build.HARDWARE.contains("ranchu", true) ||
                            Build.HARDWARE.contains("goldfish", true)) &&
                        (Build.FINGERPRINT.contains("generic", true) ||
                            Build.PRODUCT.contains("sdk", true)))
                base.evaluate()
            }
        }
    }
    private val fixtures = SyntheticHeaderFixtures(sizes = List(72) { 48 to 48 })
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(emulatorOnly)
        .around(S24DisplayRule()).around(fixtures).around(compose)

    private val mediaTags = SemanticsMatcher("rendered timeline media tags") { node ->
        node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
            ?.startsWith("atlas-timeline-media-") == true
    }

    @Test fun realInsertP50BurstDeleteBusyFollowupAndScrollAnchor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val vm = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
        val disposable = mutableListOf<Uri>()
        // Observe the provider's real URI/flag shape, not a mocked callback.
        // Preserve the original callback counts. The diagnostic additionally retains
        // bounded, in-memory row hints only within the five measured insert windows.
        val notifications = Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        val activePhase = AtomicReference<InsertPhase?>(null)
        val trace = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val phase = activePhase.get()
                val at = if (phase != null) SystemClock.elapsedRealtimeNanos() else 0L
                notifications += 0 to false
                phase?.record(at, null, 0)
            }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val phase = activePhase.get()
                val at = if (phase != null) SystemClock.elapsedRealtimeNanos() else 0L
                val hint = uri?.let(::galleryItemHintOrNull)
                notifications += 0 to (hint != null)
                phase?.record(at, hint, 0)
            }
            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                val phase = activePhase.get()
                val at = if (phase != null) SystemClock.elapsedRealtimeNanos() else 0L
                notifications += flags to uris.any { galleryItemHintOrNull(it) != null }
                if (phase != null) {
                    // Per-URI observations, NOT additional callback counts. Include
                    // empty/unknown notifications; never retain URI strings.
                    if (uris.isEmpty()) phase.record(at, null, flags)
                    else for (uri in uris) {
                        if (!phase.record(at, galleryItemHintOrNull(uri), flags)) break
                    }
                }
            }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, trace)
        try {
            compose.awaitThat("the 72 synthetic images published with full gallery access", timeoutMs = 90_000L) {
                val publication = vm.publishedInventory.value
                !vm.busy.value && !publication.fromCache &&
                    publication.observedGrant?.images == true &&
                    publication.observedGrant?.videos == true &&
                    publication.items.count { it.name.startsWith("IMG_HEADER_FIXTURE_") } >= 72
            }
            // SORT-0 (#176): newest first is Date created + Descending; the
            // sheet closes after each choice, so pick key and direction apart.
            for (choice in listOf("atlas-media-sort-date_created",
                    "atlas-media-sort-direction-descending")) {
                compose.tap(hasTestTag("atlas-photos-sort"))
                compose.awaitThat("sort option $choice in the sheet", timeoutMs = 10_000L) {
                    compose.exists(hasTestTag(choice))
                }
                compose.tap(hasTestTag(choice))
                compose.awaitThat("sort sheet closed after $choice", timeoutMs = 10_000L) {
                    !compose.exists(hasTestTag("atlas-photos-sort-sheet"))
                }
            }
            compose.onNodeWithTag("atlas-timeline-grid").performScrollToIndex(0)
            compose.waitForIdle()

            // Five independent insert-to-RENDERED samples; no JVM fake or
            // publication-only stopwatch masquerades as an on-screen result.
            val samples = ArrayList<Long>()
            val phases = List(5) { InsertPhase(it + 1) }
            try {
                repeat(5) { index ->
                    val phase = phases[index]
                    phase.startedAt = SystemClock.elapsedRealtimeNanos()
                    activePhase.set(phase)
                    try {
                        val (uri, writtenAt) = insertFictionalImage(resolver, index)
                        disposable += uri
                        val id = ContentUris.parseId(uri)
                        phase.imageId = id
                        phase.writtenAt = writtenAt
                        // MediaProvider may send a collection-wide callback instead of an
                        // exact row change. The ViewModel then correctly falls back to
                        // a full scan, whose publication has incremental=false. A tile
                        // already on screen must not be ignored until another delta
                        // happens (CI shard 0: runs 44419277477 and 44433328673).
                        // Avoid reading Compose semantics until the provider has
                        // actually published this item.
                        compose.awaitThat("insert $index is in inventory and rendered",
                            timeoutMs = 12_000L) {
                            val pub = vm.publishedInventory.value
                            if (pub.items.any { it.id == id && !it.isVideo }) {
                                if (phase.inventoryPositiveAt == null) {
                                    phase.inventoryPositiveAt = SystemClock.elapsedRealtimeNanos()
                                }
                                val lookupStart = SystemClock.elapsedRealtimeNanos()
                                val first = phase.firstLookupStart == null
                                if (first) phase.firstLookupStart = lookupStart
                                val exists = compose.exists(hasTestTag("atlas-timeline-media-$id"))
                                val lookupEnd = SystemClock.elapsedRealtimeNanos()
                                if (first) {
                                    phase.firstLookupEnd = lookupEnd
                                    phase.firstLookupResult = exists
                                }
                                if (exists && phase.positiveLookupStart == null) {
                                    phase.positiveLookupStart = lookupStart
                                    phase.positiveLookupEnd = lookupEnd
                                }
                                exists
                            } else false
                        }
                        // Same stopwatch endpoints and integer conversion as before.
                        val returnedAt = SystemClock.elapsedRealtimeNanos()
                        samples += TimeUnit.NANOSECONDS.toMillis(returnedAt - writtenAt)
                        phase.returnedAt = returnedAt
                    } finally {
                        activePhase.set(null)
                        phase.stoppedAt = phase.returnedAt ?: SystemClock.elapsedRealtimeNanos()
                    }
                }
            } finally {
                // Never log on the measured path or mask an original test failure.
                // Include incomplete/not-started samples if an earlier wait failed.
                phases.forEach { phase -> runCatching { phase.log() } }
            }
            val medianMs = samples.sorted()[samples.size / 2]
            val observerTrace = synchronized(notifications) { notifications.toList() }
            val exactNotifications = observerTrace.count { it.second }
            Log.i("ATLAS_PERF10", "event=provider_observer callbacks=${observerTrace.size} " +
                "item_uris=$exactNotifications collection_or_unknown=${observerTrace.size - exactNotifications} " +
                "flags=${observerTrace.map { it.first }.distinct().sorted()}")
            assertTrue("API35 must notify at least one exact image URI for the delta path",
                exactNotifications > 0)
            Log.i("ATLAS_PERF10", "event=insert_to_rendered samples_ms=$samples p50_ms=$medianMs")
            assertTrue("API35 insert-to-rendered p50=$medianMs ms, samples=$samples; target <=1000 ms",
                medianMs <= 1_000L)
            saveUiEvidence("perf10-top-insert.png")

            // A new item above an already scrolled viewport must not replace
            // the first visible photo or shift its pixel position.
            compose.onNodeWithTag("atlas-timeline-grid").performScrollToIndex(48)
            compose.waitForIdle()
            val anchoredTag = compose.onAllNodes(mediaTags).fetchSemanticsNodes()
                .mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }
                .first()
            val beforeY = compose.onNodeWithTag(anchoredTag)
                .getUnclippedBoundsInRoot().top.value
            saveUiEvidence("perf10-scrolled-before.png")
            val (scrolledUri, _) = insertFictionalImage(resolver, 100)
            disposable += scrolledUri
            val scrolledId = ContentUris.parseId(scrolledUri)
            compose.awaitThat("scrolled insertion published incrementally",
                timeoutMs = 12_000L) {
                val pub = vm.publishedInventory.value
                pub.incremental && pub.items.any { it.id == scrolledId }
            }
            // Await Compose's asynchronous sort and anchor re-plan, not merely
            // the ViewModel flow emission.
            compose.awaitThat("the same scrolled tile stays rendered",
                timeoutMs = 12_000L) {
                val nodes = compose.onAllNodes(mediaTags).fetchSemanticsNodes()
                nodes.any { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == anchoredTag }
            }
            compose.waitForIdle()
            val afterY = compose.onNodeWithTag(anchoredTag)
                .getUnclippedBoundsInRoot().top.value
            assertTrue("An inserted top item moved scrolled anchor $anchoredTag by ${afterY - beforeY}dp",
                abs(afterY - beforeY) <= 12f)
            saveUiEvidence("perf10-scrolled-after.png")

            // Real provider generation filters after 50 pending->visible
            // changes; the final inventory must contain every unique ID.
            val burst = (200 until 250).map { index ->
                insertFictionalImage(resolver, index).first.also(disposable::add)
            }
            val burstIds = burst.map(ContentUris::parseId).toSet()
            assertEquals(50, burstIds.size)
            compose.awaitThat("all 50 burst IDs published incrementally",
                timeoutMs = 30_000L) {
                val pub = vm.publishedInventory.value
                pub.incremental &&
                    pub.items.filter { !it.isVideo && it.id in burstIds }
                        .map { it.id }.toSet() == burstIds
            }
            Log.i("ATLAS_PERF10", "event=burst50 published=true count=50")

            // Deleted media is absent from generation queries; only an exact
            // item probe or the conservative full-scan fallback may remove it.
            val deletedId = ContentUris.parseId(scrolledUri)
            assertEquals(1, resolver.delete(scrolledUri, null, null))
            disposable.remove(scrolledUri)
            compose.awaitThat("deleted image removed from the inventory",
                timeoutMs = 12_000L) {
                vm.publishedInventory.value.items.none { it.id == deletedId && !it.isVideo }
            }

            // A provider callback overlapping a manual full scan must queue
            // a bounded authoritative follow-up (not be silently dropped).
            val epochBefore = vm.publishedInventory.value.epoch
            instrumentation.runOnMainSync {
                vm.refresh()
                // Issued on the main thread while the full scan owns busy;
                // a real MediaProvider collection notification has no row ID.
                resolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    null, ContentResolver.NOTIFY_UPDATE)
            }
            compose.awaitThat("observer callback during busy scan gets a follow-up",
                timeoutMs = 30_000L) {
                !vm.busy.value && vm.publishedInventory.value.epoch >= epochBefore + 2L
            }
            Log.i("ATLAS_PERF10", "event=busy_observer_followup published=true")
        } finally {
            resolver.unregisterContentObserver(trace)
            disposable.forEach { uri ->
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    // Diagnostic only (#1043). The test observer executes on main; it is not
    // the product observer. The test thread never waits for it. One fixed buffer
    // per sample records callback entry timestamps (one entry per URI hint).
    private data class CallbackObservation(
        val at: Long,
        val imageId: Long?,
        val exact: Boolean,
        val flags: Int,
    )

    private class InsertPhase(private val ordinal: Int) {
        var startedAt: Long? = null
        var writtenAt: Long? = null
        var imageId: Long? = null
        var inventoryPositiveAt: Long? = null
        var firstLookupStart: Long? = null
        var firstLookupEnd: Long? = null
        var firstLookupResult: Boolean? = null
        var positiveLookupStart: Long? = null
        var positiveLookupEnd: Long? = null
        var returnedAt: Long? = null
        var stoppedAt: Long? = null

        private val entries = AtomicReferenceArray<CallbackObservation>(64)
        // Single producer (the existing main-handler observer). Publish a
        // reservation before the entry so a racing snapshot reports a missing
        // slot, rather than claiming that no observation was lost.
        @Volatile private var reserved = 0
        @Volatile private var overflow = false

        fun record(at: Long, hint: GalleryItemHint?, flags: Int): Boolean {
            val slot = reserved
            if (slot >= entries.length()) {
                overflow = true
                return false
            }
            reserved = slot + 1
            entries.set(slot, CallbackObservation(at, hint?.takeUnless { it.video }?.id,
                hint != null, flags))
            return true
        }

        fun log() {
            // All offsets are signed monotonic nanoseconds relative to the
            // original writtenAt; on a failed insert use start as a fallback.
            // No raw IDs, URI, file name, or absolute timestamps leave memory.
            val write = writtenAt
            val origin = write ?: startedAt
            fun offset(at: Long?): String =
                if (at == null || origin == null) "NA" else (at - origin).toString()

            val count = reserved
            val snapshot = (0 until count).map { entries.get(it) }
            val window = snapshot.filterNotNull().filter { entry ->
                startedAt?.let { entry.at >= it } == true &&
                    stoppedAt?.let { entry.at <= it } == true
            }
            val matching = window.filter { it.exact && imageId != null && it.imageId == imageId }
            val unknown = window.filter { !it.exact }
            fun observations(rows: List<CallbackObservation>, preWrite: Boolean): String {
                if (write == null) return "NA"
                return rows.filter { (it.at < write) == preWrite }
                    .joinToString(",", "[", "]") { "${offset(it.at)}:${it.flags}" }
            }
            fun unclassified(rows: List<CallbackObservation>): String =
                if (write != null) "NA" else rows.joinToString(",", "[", "]") {
                    "${offset(it.at)}:${it.flags}"
                }

            Log.i("ATLAS_PERF10_PHASE",
                "sample=$ordinal origin=${if (write != null) "written" else if (origin != null) "start" else "missing"} " +
                    "insert_start_ns=${offset(startedAt)} written_ns=${offset(write)} " +
                    "inventory_positive_ns=${offset(inventoryPositiveAt)} " +
                    "first_lookup_start_ns=${offset(firstLookupStart)} first_lookup_end_ns=${offset(firstLookupEnd)} " +
                    "first_lookup_result=${firstLookupResult ?: "NA"} " +
                    "positive_lookup_start_ns=${offset(positiveLookupStart)} positive_lookup_end_ns=${offset(positiveLookupEnd)} " +
                    "await_return_ns=${offset(returnedAt)} stop_ns=${offset(stoppedAt)} " +
                    "sample_ms=${if (write != null && returnedAt != null) TimeUnit.NANOSECONDS.toMillis(returnedAt!! - write) else "NA"} " +
                    "callback_exact_pre_ns_flags=${observations(matching, true)} " +
                    "callback_exact_post_ns_flags=${observations(matching, false)} " +
                    "callback_unknown_pre_ns_flags=${observations(unknown, true)} " +
                    "callback_unknown_post_ns_flags=${observations(unknown, false)} " +
                    "callback_exact_unclassified_ns_flags=${unclassified(matching)} " +
                    "callback_unknown_unclassified_ns_flags=${unclassified(unknown)} " +
                    "callback_other_exact_entries=${window.count { it.exact } - matching.size} " +
                    "callback_reserved=$count callback_missing_slots=${snapshot.count { it == null }} " +
                    "callback_outside_window=${snapshot.count { it != null } - window.size} callback_overflow=$overflow")
        }
    }

    /** A tiny invented PNG. Timing starts AFTER IS_PENDING becomes visible. */
    private fun insertFictionalImage(resolver: ContentResolver, index: Int): Pair<Uri, Long> {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,
                "IMG_PERF10_SYNTH_${SystemClock.elapsedRealtimeNanos()}_$index.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-PERF10-Disposable/")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis() + index)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(index % 255, 90, 150))
                requireNotNull(resolver.openOutputStream(uri)).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            } finally {
                bitmap.recycle()
            }
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            return uri to SystemClock.elapsedRealtimeNanos()
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
