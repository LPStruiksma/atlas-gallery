package nl.atlas.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceSourceSkipped
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.PersonPageTarget
import nl.atlas.gallery.featurepeopleui.needsSplittingLists
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.typeText
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * HUB-1C (#212 v3) host-backed navigation journey in the REAL MainActivity at
 * the owner's S24+ size. Invented [SyntheticFace] drawings are written to
 * MediaStore (deleted afterwards) and scanned by a [PeopleDiscoveryHost] over
 * an app-private test database, exactly as in [PeoplePrototypeJourneyTest].
 * Review facts go through the host's real reducer.
 *
 * Proves: People ⋮ → Needs splitting (N) → unnamed group page → Back returns to
 * the visible list; the same for the named person; hiding that person moves
 * its mark to Hidden only (N = 0 in People), and People → Hidden → Needs
 * splitting (H) → hidden person page → Back → Hidden list → Back → Hidden →
 * Back → People. Unhiding while the Hidden list is open moves the row back.
 */
@RunWith(AndroidJUnit4::class)
class Hub1CNeedsSplittingJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val dir = File(target.cacheDir, "hub1c-journey-" + UUID.randomUUID())
    private val inserted = mutableListOf<Uri>()
    private val specs = HashMap<Long, SyntheticFace.Spec>()
    private val stores = mutableListOf<FaceIndexStore>()
    private val optIn = object : PeopleOptInMemory {
        @Volatile var value: PeopleOptIn? = null
        override fun load() = value
        override fun save(value: PeopleOptIn?) { this.value = value }
    }
    private lateinit var host: PeopleDiscoveryHost

    private val setup = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                    .forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
                clearGalleryInventoryCacheForTest(target)
                PeopleScanWorkRuntime.clear()
                try {
                    insertFaces()
                    installHost()
                    base.evaluate()
                } finally {
                    PeopleDiscoveryHost.replaceAppForTest(null)
                    PeopleScanWorkRuntime.clear()
                    stores.forEach { runCatching { it.close() } }
                    dir.deleteRecursively()
                    inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                }
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule()).around(S24DisplayRule())
        .around(setup).around(compose)

    private fun insertFaces() {
        val now = System.currentTimeMillis()
        for (i in 1..PHOTOS) {
            val spec = SyntheticFace.Spec(480, 480, 0.4f + 0.02f * i, 0.5f, 0.8f + 0.01f * i)
            val taken = now - i * 86_400_000L
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_HUB1C_JOURNEY_$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$FOLDER/")
                    put(MediaStore.Images.Media.DATE_TAKEN, taken)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })) { "Could not create synthetic face fixture $i" }
            inserted += uri
            val bitmap = SyntheticFace.bitmap(spec)
            try {
                requireNotNull(target.contentResolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            target.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
            }, null, null)
            specs[ContentUris.parseId(uri)] = spec
        }
    }

    private fun installHost() {
        val file = File(dir.apply { mkdirs() }, "faces.db")
        val context: Context = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        val store = FaceIndexStore(context).also { stores += it }
        host = PeopleDiscoveryHost(
            store = store,
            photoGrantCurrent = { true },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    val id = Uri.parse(directory.authorizedUri(lease.media)).lastPathSegment
                        ?.toLongOrNull()
                    val spec = id?.let { specs[it] } ?: throw FaceSourceSkipped("NOT_A_FIXTURE")
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(spec))
                }
            },
            power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() = Unit
                override fun later() = Unit
                override fun cancel() = Unit
            },
            faceCropDecoder = MediaStoreFaceCropDecoder(target.contentResolver),
            optInMemory = optIn
        )
        PeopleDiscoveryHost.replaceAppForTest(host)
    }

    @Test fun needsSplittingRowsOpenGroupsAndBackReturnsToTheFilteredList() {
        openPeople()
        startDiscovery()
        val epoch = drainScan()

        // 1. Unnamed: mark the scanned group "not one person" through the reducer.
        val first = requireNotNull(host.liveReview(epoch) ?: host.reviewState(epoch))
        val group = first.clusters.unknownClusters.maxBy { it.members.size }
        assertTrue("unnamed group marked through the host reducer",
            host.review(PeopleClusterReviewCommand(first,
                PeopleReviewAction.MarkMixedSides(setOf(PersonPageTarget.Unnamed(group.id))))))
        openPeopleMenuItem("Needs splitting (1)")
        compose.waitForNode(hasTestTag("atlas-needs-splitting"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText("Unnamed group"), WAIT_MS, useUnmergedTree = true)
        saveUiEvidence("hub1c-journey-visible-list.png")
        openPersonAndAssertSample("atlas-needs-splitting-row", "Add name", "unnamed split row")
        dismissWithBack(compose, hasTestTag("atlas-person-page"))
        // Back from the group returns to the visible list, not to People.
        compose.waitForNode(hasTestTag("atlas-needs-splitting"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText("Unnamed group"), WAIT_MS, useUnmergedTree = true)
        saveUiEvidence("hub1c-journey-back-to-visible-list.png")
        dismissWithBack(compose, hasTestTag("atlas-needs-splitting"))
        compose.waitForNode(hasTestTag("atlas-people-home"), WAIT_MS, useUnmergedTree = true)

        // 2. Named: the same members, now a named person, keep a visible mark.
        val unnamed = requireNotNull(host.liveReview(epoch))
        val person = PersonKey("native-" + group.id.anchor.localHex)
        assertTrue("group named through the host reducer",
            host.review(PeopleClusterReviewCommand(unnamed,
                PeopleReviewAction.NameCluster(group.id, person, NAME))))
        val named = requireNotNull(host.liveReview(epoch))
        if (needsSplittingLists(named).visible.none { it.target == PersonPageTarget.Named(person) }) {
            assertTrue("named person marked through the host reducer",
                host.review(PeopleClusterReviewCommand(named,
                    PeopleReviewAction.MarkMixedSides(setOf(PersonPageTarget.Named(person))))))
        }
        assertEquals(listOf(PersonPageTarget.Named(person)),
            needsSplittingLists(host.liveReview(epoch)).visible.map { it.target })
        openPeopleMenuItem("Needs splitting (1)", evidence = "hub1c-journey-people-menu.png")
        compose.waitForNode(hasTestTag("atlas-needs-splitting"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText(NAME), WAIT_MS, useUnmergedTree = true)
        openPersonAndAssertSample("atlas-needs-splitting-row", NAME, "named split row")
        dismissWithBack(compose, hasTestTag("atlas-person-page"))
        compose.waitForNode(hasTestTag("atlas-needs-splitting"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText(NAME), WAIT_MS, useUnmergedTree = true)
        dismissWithBack(compose, hasTestTag("atlas-needs-splitting"))
        compose.waitForNode(hasTestTag("atlas-people-home"), WAIT_MS, useUnmergedTree = true)

        // 3. Hidden: hiding the person moves its mark to Hidden only.
        val visible = requireNotNull(host.liveReview(epoch))
        assertTrue("person hidden through the host reducer",
            host.review(PeopleClusterReviewCommand(visible,
                PeopleReviewAction.SetPersonHidden(person, true))))
        val hiddenLists = needsSplittingLists(host.liveReview(epoch))
        assertTrue("no visible row for a hidden person", hiddenLists.visible.isEmpty())
        assertEquals(listOf(PersonPageTarget.Named(person)), hiddenLists.hidden.map { it.target })
        // People's own count never includes the hidden mark.
        compose.tap(hasTestTag("atlas-header-more"))
        compose.awaitThat("People ⋮ shows Needs splitting (0)") {
            compose.exists(hasText("Needs splitting (0)"), useUnmergedTree = true)
        }
        compose.tap(hasTestTag("atlas-people-hidden-open"))
        compose.waitForNode(hasTestTag("atlas-people-hidden"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText("Needs splitting (1)"), WAIT_MS, useUnmergedTree = true)
        saveUiEvidence("hub1c-journey-hidden-filter.png")
        openPersonAndAssertSample("atlas-people-hidden-person", NAME, "Hidden People direct row")
        dismissWithBack(compose, hasTestTag("atlas-person-page"))
        compose.waitForNode(hasTestTag("atlas-people-hidden"), WAIT_MS, useUnmergedTree = true)
        compose.tap(hasTestTag("atlas-hidden-needs-splitting-open"))
        compose.waitForNode(hasTestTag("atlas-hidden-needs-splitting"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText(NAME), WAIT_MS, useUnmergedTree = true)
        saveUiEvidence("hub1c-journey-hidden-list.png")
        openPersonAndAssertSample("atlas-needs-splitting-row", NAME, "hidden named split row")
        dismissWithBack(compose, hasTestTag("atlas-person-page"))
        // Back returns to Hidden's marked filter, which revalidates.
        compose.waitForNode(hasTestTag("atlas-hidden-needs-splitting"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText(NAME), WAIT_MS, useUnmergedTree = true)
        saveUiEvidence("hub1c-journey-back-to-hidden-list.png")

        // 4. Unhide while the Hidden list is open: the row leaves it at once.
        val hidden = requireNotNull(host.liveReview(epoch))
        assertTrue("person shown again through the host reducer",
            host.review(PeopleClusterReviewCommand(hidden,
                PeopleReviewAction.SetPersonHidden(person, false))))
        compose.awaitThat("the unhidden row leaves Hidden's list") {
            !compose.exists(hasTestTag("atlas-needs-splitting-row"), useUnmergedTree = true)
        }
        dismissWithBack(compose, hasTestTag("atlas-hidden-needs-splitting"))
        compose.waitForNode(hasTestTag("atlas-people-hidden"), WAIT_MS, useUnmergedTree = true)
        dismissWithBack(compose, hasTestTag("atlas-people-hidden"))
        compose.waitForNode(hasTestTag("atlas-people-home"), WAIT_MS, useUnmergedTree = true)
        openPeopleMenuItem("Needs splitting (1)")
        compose.waitForNode(hasTestTag("atlas-needs-splitting"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText(NAME), WAIT_MS, useUnmergedTree = true)

        // Rename from the actual album page: MainActivity's onRetarget must
        // start a fresh A10 even while a person destination is already open.
        openPersonAndAssertSample("atlas-needs-splitting-row", NAME, "reopened named split row")
        compose.tap(hasTestTag("atlas-person-name"))
        compose.waitForNode(hasTestTag("atlas-person-name-field"), WAIT_MS, useUnmergedTree = true)
        compose.onNodeWithTag("atlas-person-name-field").performTextClearance()
        compose.typeText(hasTestTag("atlas-person-name-field"), "Fictional Bea")
        val beforeRetarget = a10Count()
        compose.tap(hasTestTag("atlas-person-name-save"))
        compose.waitForNode(hasTestTag("atlas-person-page"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText("Fictional Bea"), WAIT_MS, useUnmergedTree = true)
        assertA10(beforeRetarget, "native album retarget after rename")
    }

    private fun a10Count() = PerformanceSession.aggregate.snapshot("action_a10_ms").count

    private fun openPersonAndAssertSample(tag: String, destination: String, action: String) {
        val before = a10Count()
        compose.tap(hasTestTag(tag))
        compose.waitForNode(hasTestTag("atlas-person-page"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasText(destination), WAIT_MS, useUnmergedTree = true)
        assertA10(before, action)
    }

    private fun assertA10(before: Int, action: String) {
        compose.awaitThat("$action records one A10 sample", WAIT_MS) { a10Count() == before + 1 }
        compose.waitForIdle()
        assertEquals(action, before + 1, a10Count())
    }

    private fun openPeopleMenuItem(label: String, evidence: String? = null) {
        compose.waitForNode(hasTestTag("atlas-people-home"), WAIT_MS, useUnmergedTree = true)
        compose.tap(hasTestTag("atlas-header-more"))
        compose.awaitThat("People ⋮ shows $label") {
            compose.exists(hasText(label), useUnmergedTree = true)
        }
        if (evidence != null) saveUiEvidence(evidence)
        compose.tap(hasTestTag("atlas-people-needs-splitting-open"))
    }

    private fun openPeople() {
        compose.tap(hasTestTag("atlas-nav-people"))
        compose.waitForNode(hasTestTag("atlas-people-home"), WAIT_MS, useUnmergedTree = true)
    }

    private fun startDiscovery() {
        val emptyPage = compose.exists(hasTestTag("atlas-people-empty"), useUnmergedTree = true) &&
            compose.exists(hasText("Discover faces"), useUnmergedTree = true)
        if (emptyPage) {
            compose.tap(hasText("Discover faces"), timeoutMs = WAIT_MS) { scanShown() }
            return
        }
        compose.tap(hasTestTag("atlas-header-more"))
        compose.awaitThat("People ⋮ menu with Discover faces") {
            compose.exists(hasTestTag("atlas-people-start"), useUnmergedTree = true)
        }
        compose.tap(hasTestTag("atlas-people-start"), timeoutMs = WAIT_MS) { scanShown() }
    }

    private fun scanShown(): Boolean {
        if (compose.exists(hasTestTag("atlas-people-progress-line"), useUnmergedTree = true)) return true
        val empty = compose.exists(hasTestTag("atlas-people-empty"), useUnmergedTree = true)
        return empty && !compose.exists(hasText("Discover faces"), useUnmergedTree = true)
    }

    private fun liveScanEpoch(): String? = PeopleScanWorkRuntime.acquire(target)?.use { it.epoch }

    /** As in [PeoplePrototypeJourneyTest]: drain until the live epoch reports NO_PENDING. */
    private fun drainScan(): String {
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        var batches = 0
        var moves = 0
        while (true) {
            val before = liveScanEpoch()
            val result = before?.let { runBlocking { runPeopleScanBatch(target) { false } } }
            if (result == null) {
                val left = deadline - SystemClock.elapsedRealtime()
                check(left > 0) { "no scan session within 60 s (epoch moved $moves times)" }
                compose.awaitThat("a scan session within 60 s", left) { liveScanEpoch() != null }
                continue
            }
            if (result.stop == FaceBatchStop.NO_PENDING) {
                val after = liveScanEpoch()
                if (after == before) {
                    compose.waitForIdle()
                    return after
                }
                check(++moves <= 5) { "the gallery kept republishing: epoch moved $moves times" }
                continue
            }
            check(++batches <= 600) { "scan did not finish: ${result.stop}" }
        }
    }

    private companion object {
        const val PHOTOS = 6
        const val NAME = "Fictional Ada"
        const val FOLDER = "ATLAS-HUB1C-Journey"
        const val WAIT_MS = 60_000L
    }
}
