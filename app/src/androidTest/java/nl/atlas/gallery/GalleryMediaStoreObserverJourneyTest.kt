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
import androidx.compose.ui.test.performClick
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
        .around(fixtures).around(compose)

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
        // Store ONLY the flag bits and whether a URI identifies an exact row.
        val notifications = Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        val trace = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                notifications += 0 to false
            }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                notifications += 0 to (uri != null && galleryItemHintOrNull(uri) != null)
            }
            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                notifications += flags to uris.any { galleryItemHintOrNull(it) != null }
            }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, trace)
        try {
            compose.waitUntil(90_000L) {
                val publication = vm.publishedInventory.value
                !vm.busy.value && !publication.fromCache &&
                    publication.observedGrant?.images == true &&
                    publication.observedGrant?.videos == true &&
                    publication.items.count { it.name.startsWith("IMG_HEADER_FIXTURE_") } >= 72
            }
            compose.onNodeWithTag("atlas-photos-sort").performClick()
            compose.onNodeWithTag("atlas-photos-sort-newest").performClick()
            compose.onNodeWithTag("atlas-timeline-grid").performScrollToIndex(0)
            compose.waitForIdle()

            // Five independent insert-to-RENDERED samples; no JVM fake or
            // publication-only stopwatch masquerades as an on-screen result.
            val samples = ArrayList<Long>()
            repeat(5) { index ->
                val (uri, writtenAt) = insertFictionalImage(resolver, index)
                disposable += uri
                val id = ContentUris.parseId(uri)
                compose.waitUntil(12_000L) {
                    val pub = vm.publishedInventory.value
                    pub.incremental && pub.items.any { it.id == id && !it.isVideo } &&
                        compose.onAllNodes(hasTestTag("atlas-timeline-media-$id"))
                            .fetchSemanticsNodes().isNotEmpty()
                }
                samples += TimeUnit.NANOSECONDS.toMillis(
                    SystemClock.elapsedRealtimeNanos() - writtenAt)
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
            compose.waitUntil(12_000L) {
                val pub = vm.publishedInventory.value
                pub.incremental && pub.items.any { it.id == scrolledId }
            }
            // Await Compose's asynchronous sort and anchor re-plan, not merely
            // the ViewModel flow emission.
            compose.waitUntil(12_000L) {
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
            compose.waitUntil(30_000L) {
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
            compose.waitUntil(12_000L) {
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
            compose.waitUntil(30_000L) {
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
