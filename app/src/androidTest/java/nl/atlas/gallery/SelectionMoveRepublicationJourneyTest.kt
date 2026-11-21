package nl.atlas.gallery

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import nl.atlas.gallery.testkit.DisplayPrefsRule
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
 * #783 (goal #437): a catalogue republication between selecting photos and
 * the Move target tap, in the real MainActivity at the S24+ size, on invented
 * PNGs this test draws, inserts and deletes. Copy → library reload → Move:
 *
 * - a republication before the picker opens and another while it is open keep
 *   the chosen items selected, and Move asks Android's write consent for
 *   exactly those items into the album the user tapped;
 * - consent cancel shows the existing "Move canceled" result and writes nothing;
 * - a republication that removes one chosen row moves only the survivor;
 * - one that removes every chosen row shows "Selection changed · select again"
 *   and asks no consent: the tap is never a silent no-op.
 *
 * Republications are real MediaStore changes this test makes at a chosen
 * moment; [SelectionRepublicationSeam] reports when each one has reached the
 * selection, so no step depends on a sleep or on the app's own retry timing.
 * Revoked access is covered on the JVM (BatchTransferGateTest): revoking a
 * runtime permission kills the instrumented process.
 */
@RunWith(AndroidJUnit4::class)
class SelectionMoveRepublicationLightJourneyTest : SelectionMoveRepublicationJourney(dark = false)

@RunWith(AndroidJUnit4::class)
class SelectionMoveRepublicationDarkJourneyTest : SelectionMoveRepublicationJourney(dark = true)

abstract class SelectionMoveRepublicationJourney(private val dark: Boolean) {
    private val theme = if (dark) "dark" else "light"
    private val fixtures = RaceFixtures(if (dark) "Dark" else "Light")
    private val compose = createAndroidComposeRule<MainActivity>()
    private val rebases = CopyOnWriteArrayList<SelectionRebase>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark)).around(RotationRule()).around(fixtures).around(SeamRule()).around(compose)

    @Test fun copyThenRepublishBeforeAndDuringThePickerMovesOnlyTheChosenItems() {
        val ids = fixtures.ids
        openPhotos()

        // 1. Copy two originals into the target album (no consent: new rows).
        step("select(ids[0], ids[1])")
        select(ids[0], ids[1])
        step("compose.tap(hasTestTag('atlas-selection-more'))")
        compose.tap(hasTestTag("atlas-selection-more"))
        shown(hasTestTag("atlas-selection-copy-to-album"))
        step("compose.tap(hasTestTag('atlas-selection-copy-to-album'))")
        compose.tap(hasTestTag("atlas-selection-copy-to-album"))
        shown(hasText(fixtures.targetAlbum))
        step("compose.tap(hasText(fixtures.targetAlbum))")
        compose.tap(hasText(fixtures.targetAlbum))
        compose.awaitThat("two copies in the target", timeoutMs = 60_000L) { fixtures.namesIn(fixtures.targetPath).size == 3 }
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[0]))
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[1]))
        // The library reload the copy causes publishes on its own schedule.
        val copies = fixtures.idsIn(fixtures.targetPath, setOf(fixtures.nameOf(0), fixtures.nameOf(1)))
        assertEquals(2, copies.size)
        copies.forEach { shown(hasTestTag("atlas-timeline-media-$it")) }
        compose.waitForIdle()

        // 2. Select two other originals, then republish BEFORE the picker opens.
        step("select(ids[2], ids[3])")
        select(ids[2], ids[3])
        step("republishAndAwait('before-open', expectedAfter = 2) ")
        republishAndAwait("before-open", expectedAfter = 2) { fixtures.insertTrigger() }
        shown(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        compose.waitForIdle()
        step("saveUiEvidence('move-783-theme-reloaded-selection.png')")
        saveUiEvidence("move-783-$theme-reloaded-selection.png")

        // Landscape keeps the same two selected through Activity recreation.
        step("rotateTo(landscape = true)")
        rotateTo(landscape = true)
        shown(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        compose.waitForIdle()
        step("saveUiEvidence('move-783-theme-reloaded-selection-landscape.png')")
        saveUiEvidence("move-783-$theme-reloaded-selection-landscape.png")
        step("rotateTo(landscape = false)")
        rotateTo(landscape = false)
        shown(hasTestTag("atlas-selection-count") and hasText("2 selected"))

        // 3. Open Move to album, then republish WHILE the picker is open.
        step("openMovePicker()")
        openMovePicker()
        step("republishAndAwait('picker-open', expectedAfter = 2) ")
        republishAndAwait("picker-open", expectedAfter = 2) { fixtures.insertTrigger() }
        shown(hasTestTag("atlas-album-picker"))
        shown(hasText(fixtures.targetAlbum))
        compose.waitForIdle()
        step("saveUiEvidence('move-783-theme-picker-after-reload.png')")
        saveUiEvidence("move-783-$theme-picker-after-reload.png")

        // 4. Tap the target, cancel Android's consent: nothing is written.
        step("compose.tap(hasText(fixtures.targetAlbum))")
        compose.tap(hasText(fixtures.targetAlbum))
        // While MediaProvider's window is in front the app has no Compose root:
        // only exists() (no root required) may look at the app here.
        step("answerWriteConsent(allow = false) ")
        answerWriteConsent(allow = false) {
            compose.exists(hasText("Move canceled · your photos are unchanged"))
        }
        shown(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[2]))
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[3]))
        compose.waitForIdle()
        step("saveUiEvidence('move-783-theme-consent-canceled.png')")
        saveUiEvidence("move-783-$theme-consent-canceled.png")

        // 5. Again, and allow: exactly ids[2] and ids[3] move into the tapped album.
        step("openMovePicker()")
        openMovePicker()
        shown(hasText(fixtures.targetAlbum))
        step("compose.tap(hasText(fixtures.targetAlbum))")
        compose.tap(hasText(fixtures.targetAlbum))
        step("answerWriteConsent(allow = true) ")
        answerWriteConsent(allow = true) {
            fixtures.relativePathOf(ids[2]) == fixtures.targetPath &&
                fixtures.relativePathOf(ids[3]) == fixtures.targetPath
        }
        resumed()
        shown(hasText("Moved 2 items to ${fixtures.targetAlbum}"))
        compose.waitForIdle()
        step("saveUiEvidence('move-783-theme-moved.png')")
        saveUiEvidence("move-783-$theme-moved.png")
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[0]))
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[1]))
        assertEquals(fixtures.nameOf(2), fixtures.displayNameOf(ids[2]))
        assertEquals(fixtures.nameOf(3), fixtures.displayNameOf(ids[3]))
        // Copies, the target's own photo and the trigger rows stayed put.
        assertEquals(5, fixtures.namesIn(fixtures.targetPath).size)
        assertEquals(2, fixtures.namesIn(fixtures.triggerPath).size)
        assertNoDroppedRebase()
    }

    @Test fun republicationThatRemovesChosenRowsWhilePickerIsOpenNeverActsSilently() {
        val ids = fixtures.ids
        openPhotos()

        // 1. Two chosen, one disappears while the picker is open: only the
        //    survivor is offered to Android's consent and moved.
        step("select(ids[0], ids[1])")
        select(ids[0], ids[1])
        step("openMovePicker()")
        openMovePicker()
        step("republishAndAwait('one-removed', expectedAfter = 1) ")
        republishAndAwait("one-removed", expectedAfter = 1) { fixtures.delete(ids[1]) }
        shown(hasText(fixtures.targetAlbum))
        step("compose.tap(hasText(fixtures.targetAlbum))")
        compose.tap(hasText(fixtures.targetAlbum))
        step("answerWriteConsent(allow = true) ")
        answerWriteConsent(allow = true) { fixtures.relativePathOf(ids[0]) == fixtures.targetPath }
        resumed()
        shown(hasText("Moved 1 item to ${fixtures.targetAlbum}"))
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[2]))
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[3]))
        assertEquals(2, fixtures.namesIn(fixtures.targetPath).size)

        // 2. Every chosen row disappears while the picker is open: the tap
        //    shows the existing result and asks for no consent at all.
        shown(hasTestTag("atlas-timeline-media-${ids[2]}"))
        step("select(ids[2])")
        select(ids[2])
        step("openMovePicker()")
        openMovePicker()
        step("republishAndAwait('all-removed', expectedAfter = 0) ")
        republishAndAwait("all-removed", expectedAfter = 0) { fixtures.delete(ids[2]) }
        shown(hasText(fixtures.targetAlbum))
        compose.waitForIdle()
        step("saveUiEvidence('move-783-theme-picker-selection-gone.png')")
        saveUiEvidence("move-783-$theme-picker-selection-gone.png")
        val transferStartsBefore = transferLines("start MOVE")
        val consentBefore = transferLines("consent requested")
        step("compose.tap(hasText(fixtures.targetAlbum))")
        compose.tap(hasText(fixtures.targetAlbum))
        shown(hasText("Selection changed · select again"))
        compose.waitForIdle()
        step("saveUiEvidence('move-783-theme-selection-changed.png')")
        saveUiEvidence("move-783-$theme-selection-changed.png")
        // The gate runs on the tap itself, before any start or consent line.
        assertEquals("no Move may start", transferStartsBefore, transferLines("start MOVE"))
        assertEquals("no consent may be asked", consentBefore, transferLines("consent requested"))
        assertEquals(fixtures.fixturePath, fixtures.relativePathOf(ids[3]))
        assertEquals(2, fixtures.namesIn(fixtures.targetPath).size)
        assertNoDroppedRebase()
    }

    // --- Steps -------------------------------------------------------------

    /** A marker in logcat (the stress lane prints ATLAS_TESTKIT) to place a failure. */
    private fun step(name: String) = Log.i(TAG, "step=$name")

    private fun openPhotos() {
        shown(hasTestTag("atlas-nav-photos"))
        fixtures.ids.forEach { shown(hasTestTag("atlas-timeline-media-$it")) }
        compose.waitForIdle()
    }

    private fun select(vararg ids: Long) {
        compose.onNodeWithTag("atlas-timeline-grid")
            .performScrollToNode(hasTestTag("atlas-timeline-media-${ids[0]}"))
        compose.onNodeWithTag("atlas-timeline-media-${ids[0]}").performTouchInput { longClick() }
        shown(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        ids.drop(1).forEachIndexed { i, id ->
            compose.onNodeWithTag("atlas-timeline-media-$id").performClick()
            shown(hasTestTag("atlas-selection-count") and hasText("${i + 2} selected"))
        }
    }

    private fun openMovePicker() {
        compose.tap(hasTestTag("atlas-selection-more"))
        shown(hasTestTag("atlas-selection-move-to-album"))
        compose.tap(hasTestTag("atlas-selection-move-to-album"))
        shown(hasTestTag("atlas-album-picker"))
    }

    /**
     * Makes one MediaStore change, then waits until a publication newer than
     * every one seen so far has been rebased onto a SETTLED list
     * (deferred = false) with [expectedAfter] items still selected.
     */
    private fun republishAndAwait(stage: String, expectedAfter: Int, change: () -> Unit) {
        val newestEpoch = rebases.maxOfOrNull { it.epoch } ?: Long.MIN_VALUE
        val mark = rebases.size
        change()
        compose.awaitThat("a settled republication reached the selection at $stage",
            timeoutMs = 60_000L) {
            rebases.drop(mark).any { !it.deferred && it.epoch > newestEpoch }
        }
        val settled = rebases.drop(mark).last { !it.deferred }
        Log.i(TAG, "stage=$stage rebases=${rebases.drop(mark)}")
        assertEquals("selected after republication at $stage; rebases=${rebases.drop(mark)}",
            expectedAfter, settled.after)
        compose.waitForIdle()
    }

    /** No rebase may drop an item that still exists: every drop matches a row this test deleted. */
    private fun assertNoDroppedRebase() {
        val drops = rebases.filter { !it.deferred && it.after < it.before }
        assertTrue("rebases dropped items: $drops (deleted rows ${fixtures.deletedCount})",
            drops.sumOf { it.before - it.after } <= fixtures.deletedCount)
    }

    /**
     * Taps MediaProvider's own Allow or Deny, never another app's button, on
     * each poll until [done]. A system "isn't responding" dialog for another
     * process only gets its Wait button; it never counts as an answer.
     */
    private fun answerWriteConsent(allow: Boolean, done: () -> Boolean) {
        val automation = automation()
        var clicks = 0
        compose.awaitThat("Android write consent answered (allow=$allow)", timeoutMs = 60_000L) {
            if (done()) return@awaitThat true
            val roots = automation.windows.mapNotNull { it.root } +
                listOfNotNull(automation.rootInActiveWindow)
            roots.firstOrNull { root ->
                root.findAccessibilityNodeInfosByText("isn't responding").isNotEmpty()
            }?.let { anr -> button(anr) { it.equals("Wait", true) }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            roots.filter {
                it.packageName?.toString()?.startsWith("com.android.providers.media") == true
            }.firstNotNullOfOrNull { root ->
                button(root) { label ->
                    if (allow) label.equals("Allow", true) || label.startsWith("Allow ", true)
                    else label.equals("Deny", true) || label.equals("Cancel", true) ||
                        label.equals("Don't allow", true)
                }
            }?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) clicks++ }
            false
        }
        Log.i(TAG, "consent allow=$allow clicks=$clicks log=${transferLog()}")
    }

    private fun automation() = InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    private fun button(root: AccessibilityNodeInfo, accept: (String) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 30) return null
            val label = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (label != null && accept(label)) {
                return generateSequence(node) { it.parent }.take(4).firstOrNull { it.isClickable }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return walk(root, 0)
    }

    private fun transferLog(): String = shell("logcat -d -t 2000 -s ATLAS_TRANSFER:V")
        .lines().filter { it.isNotBlank() && !it.startsWith("-----") }.takeLast(30)
        .joinToString(" | ") { it.take(200) }

    private fun transferLines(needle: String): Int =
        shell("logcat -d -s ATLAS_TRANSFER:V").lines().count { needle in it }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    private fun activityIdentity(): Int {
        var identity = 0
        compose.activityRule.scenario.onActivity { identity = System.identityHashCode(it) }
        return identity
    }

    /** Real rotation: MainActivity has no configChanges, so Android recreates it. */
    private fun rotateTo(landscape: Boolean) {
        val before = activityIdentity()
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(
            if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90
            else android.app.UiAutomation.ROTATION_FREEZE_0)
        compose.awaitThat("recreated after rotation", timeoutMs = 30_000L) {
            compose.activityRule.scenario.state == Lifecycle.State.RESUMED &&
                activityIdentity() != before
        }
        compose.awaitThat("landscape=$landscape layout", timeoutMs = 30_000L) {
            val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                .maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }?.boundsInRoot
            root != null && (root.width > root.height) == landscape
        }
        compose.waitForIdle()
    }

    private fun resumed() {
        compose.awaitThat("app resumed", timeoutMs = 60_000L) {
            compose.activityRule.scenario.state == Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
    }

    private fun shown(matcher: SemanticsMatcher) {
        compose.awaitThat("${matcher.description}; rebases=$rebases", timeoutMs = 60_000L) {
            compose.exists(matcher)
        }
    }

    /** Installs the observer before the Activity starts; always removes it. */
    private inner class SeamRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    rebases.clear()
                    SelectionRepublicationSeam.observer = { rebases += it }
                    try {
                        base.evaluate()
                    } catch (failure: Throwable) {
                        // The whole stack, under the tag the stress lane prints.
                        Log.e(TAG, "failed: ${description.methodName}", failure)
                        throw failure
                    } finally {
                        SelectionRepublicationSeam.observer = null
                        Log.i(TAG, "all rebases=$rebases")
                    }
                }
            }
    }

    private companion object {
        const val TAG = "ATLAS_TESTKIT"
    }
}

/** Rotation is unfrozen afterwards, also when the journey fails mid-landscape. */
private class RotationRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
                }
            }
        }
}

/**
 * Four invented squares in the fixture album dated three days ahead (top of
 * Newest first), one older square in the target album, and trigger rows in a
 * third folder that exist only to cause a republication. Every row in the
 * three folders is deleted afterwards.
 */
private class RaceFixtures(tag: String) : TestRule {
    val fixturePath = "Pictures/ATLAS-Race-Fixture-$tag/"
    val targetAlbum = "ATLAS-Race-Target-$tag"
    val targetPath = "Pictures/$targetAlbum/"
    val triggerPath = "Pictures/ATLAS-Race-Trigger-$tag/"
    private val uris = mutableListOf<Uri>()
    private val names = mutableListOf<String>()
    val ids: List<Long> get() = uris.take(4).map { ContentUris.parseId(it) }
    @Volatile var deletedCount = 0
        private set
    private var triggers = 0
    private lateinit var context: Context

    fun nameOf(index: Int) = names[index]

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                context = instrumentation.targetContext
                listOf(Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO).forEach {
                    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
                }
                clearGalleryInventoryCacheForTest(context)
                deleteFolders()
                try {
                    insertFixtures()
                    base.evaluate()
                } finally {
                    deleteFolders()
                }
            }
        }

    private fun deleteFolders() {
        listOf(fixturePath, targetPath, triggerPath).forEach { path ->
            runCatching {
                context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(path))
            }
        }
    }

    /** Deletes one fixture row this test (the owner) created: a stale/deleted item. */
    fun delete(id: Long) {
        val n = context.contentResolver.delete(
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null)
        check(n == 1) { "fixture row not deleted" }
        deletedCount++
    }

    /** One new row elsewhere: an unrelated library change that republishes the catalogue. */
    fun insertTrigger() {
        val taken = System.currentTimeMillis() - 86_400_000L * 30 - triggers * 60_000L
        insert("IMG_race_trigger_${triggers++}.png", triggerPath, taken, Color.rgb(90, 90, 90))
    }

    fun namesIn(path: String): List<String> = buildList {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf(path), null)?.use { while (it.moveToNext()) add(it.getString(0)) }
    }

    fun idsIn(path: String, wanted: Set<String>): List<Long> = buildList {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf(path), null)?.use {
            while (it.moveToNext()) if (it.getString(1) in wanted) add(it.getLong(0))
        }
    }

    private fun column(id: Long, column: String): String? =
        context.contentResolver.query(
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
            arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    fun relativePathOf(id: Long): String? = column(id, MediaStore.MediaColumns.RELATIVE_PATH)
    fun displayNameOf(id: Long): String? = column(id, MediaStore.MediaColumns.DISPLAY_NAME)

    private fun insertFixtures() {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val noon = java.time.LocalDate.now().plusDays(3).atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(5) { i ->
            val inTarget = i == 4
            val taken = noon - i * 60_000L - (if (inTarget) 3_600_000L else 0L)
            val name = "IMG_" + stamp.format(java.time.Instant.ofEpochMilli(taken)
                .atZone(java.time.ZoneId.systemDefault())) + "_race$i.png"
            names += name
            uris += insert(name, if (inTarget) targetPath else fixturePath, taken,
                Color.rgb(200 - 30 * i, 80 + 25 * i, 60 + 35 * i))
        }
        check(namesIn(fixturePath).size == 4)
    }

    private fun insert(name: String, path: String, taken: Long, color: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "Could not create $name" }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
        }, null, null)
        return uri
    }
}
