package nl.atlas.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * #568 Samsung selection More and hidden albums in the real Activity at the
 * S24+ size, on invented PNGs this test draws, inserts and deletes:
 * More → Copy to album into an existing album (originals stay), More → Move
 * to album into a new album (Android's write consent is accepted if shown),
 * then Albums ⋮ → Hide albums hides the fixture album from Photos and unhides
 * it again. Screenshots go to the emulator-only UI-evidence folder (UI_EVIDENCE_DEVICE_DIR).
 */
@RunWith(AndroidJUnit4::class)
class SelectionMoreJourneyTest {
    private val fixtures = MoreFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(S24DisplayRule()).around(fixtures).around(compose)

    @Test fun copyToAlbumMoveToNewAlbumThenHideAndUnhideTheAlbum() {
        val ids = fixtures.ids
        waitFor(hasTestTag("atlas-nav-photos"))
        waitFor(hasTestTag("atlas-timeline-media-${ids[3]}"))
        compose.waitForIdle()

        // 1. Select two items; the bar's More lists Copy to album, Move to album.
        compose.onNodeWithTag("atlas-timeline-media-${ids[0]}").performTouchInput { longClick() }
        waitFor(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-timeline-media-${ids[1]}").performClick()
        waitFor(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        compose.onNodeWithTag("atlas-selection-more").performClick()
        waitFor(hasTestTag("atlas-selection-copy-to-album"))
        waitFor(hasTestTag("atlas-selection-move-to-album"))
        compose.waitForIdle()
        saveUiEvidence("more-568-menu.png")

        // 2. Copy to album opens Samsung's album picker with Create album first.
        compose.onNodeWithTag("atlas-selection-copy-to-album").performClick()
        waitFor(hasTestTag("atlas-album-picker-create"))
        waitFor(hasText(TARGET_ALBUM))
        compose.waitForIdle()
        saveUiEvidence("more-568-picker.png")
        compose.onNodeWithText(TARGET_ALBUM).performClick()

        // The two copies land in the target; the originals are untouched.
        waitUntil("two copies in $TARGET_ALBUM") { fixtures.namesIn(TARGET_PATH).size == 3 }
        assertEquals(setOf(fixtures.nameOf(0), fixtures.nameOf(1), fixtures.targetName),
            fixtures.namesIn(TARGET_PATH).toSet())
        assertEquals(FIXTURE_PATH, fixtures.relativePathOf(ids[0]))
        assertEquals(FIXTURE_PATH, fixtures.relativePathOf(ids[1]))
        waitFor(hasTestTag("atlas-nav-photos")) // selection ended
        compose.waitForIdle()
        saveUiEvidence("more-568-copied.png")

        // 3. Move one item into a new album made from the picker.
        waitFor(hasTestTag("atlas-timeline-media-${ids[2]}"))
        compose.onNodeWithTag("atlas-timeline-media-${ids[2]}").performTouchInput { longClick() }
        waitFor(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-selection-more").performClick()
        waitFor(hasTestTag("atlas-selection-move-to-album"))
        compose.onNodeWithTag("atlas-selection-move-to-album").performClick()
        waitFor(hasTestTag("atlas-album-picker-create"))
        compose.onNodeWithTag("atlas-album-picker-create").performClick()
        waitFor(hasTestTag("atlas-create-album-name"))
        compose.onNodeWithTag("atlas-create-album-name").performTextInput(NEW_ALBUM)
        compose.waitForIdle()
        saveUiEvidence("more-568-create-album.png")
        // On the full emulator shard the open IME can intercept the Create tap.
        // Dismiss it explicitly before exercising the actual dialog action.
        compose.runOnUiThread {
            val activity = compose.activity
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as
                android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-create-album-confirm")
            .assertIsEnabled().performClick()
        waitUntil("new album dialog dismisses") {
            compose.onAllNodes(hasTestTag("atlas-create-album-confirm"))
                .fetchSemanticsNodes().isEmpty()
        }
        acceptWriteConsentUntil { fixtures.relativePathOf(ids[2]) == NEW_PATH }
        assertEquals(NEW_PATH, fixtures.relativePathOf(ids[2]))
        assertEquals(fixtures.nameOf(2), fixtures.displayNameOf(ids[2]))
        waitFor(hasTestTag("atlas-nav-photos"))

        // 4. Albums ⋮ → Hide albums: the fixture album leaves Photos, then returns.
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor(hasText(FIXTURE_ALBUM))
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor(hasTestTag("atlas-albums-menu-hide"))
        compose.onNodeWithTag("atlas-albums-menu-hide").performClick()
        waitFor(hasTestTag("atlas-hide-albums-grid"))
        waitFor(hasText(FIXTURE_ALBUM))
        compose.onNodeWithText(FIXTURE_ALBUM).assertIsOff().performClick()
        compose.onNodeWithText(FIXTURE_ALBUM).assertIsOn()
        compose.waitForIdle()
        saveUiEvidence("hide-568-albums.png")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasTestTag("atlas-album-grid"))
        compose.onNodeWithText(FIXTURE_ALBUM).assertDoesNotExist()
        compose.onNodeWithTag("atlas-nav-photos").performClick()
        // Copies in the target album stay; the fixture album's own items go.
        waitUntil("hidden album leaves Photos") {
            compose.onAllNodes(hasTestTag("atlas-timeline-media-${ids[3]}"))
                .fetchSemanticsNodes().isEmpty()
        }
        waitFor(hasTestTag("atlas-timeline-media-${ids[2]}")) // moved out before hiding
        compose.waitForIdle()
        saveUiEvidence("hide-568-photos.png")

        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor(hasTestTag("atlas-header-more"))
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor(hasTestTag("atlas-albums-menu-hide"))
        compose.onNodeWithTag("atlas-albums-menu-hide").performClick()
        waitFor(hasText(FIXTURE_ALBUM))
        compose.onNodeWithText(FIXTURE_ALBUM).performClick()
        compose.onNodeWithText(FIXTURE_ALBUM).assertIsOff()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("atlas-nav-photos").performClick()
        waitFor(hasTestTag("atlas-timeline-media-${ids[3]}"))
    }

    /**
     * Taps Android's own "Allow" if the write-consent sheet appears, until
     * [done]. On a timeout the error names every on-screen text seen while
     * waiting (consent sheet, ATLAS's result toast) and the row's state.
     */
    private fun acceptWriteConsentUntil(done: () -> Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val seen = LinkedHashSet<String>()
        var clicks = 0
        fun texts(node: AccessibilityNodeInfo?, out: MutableSet<String>, depth: Int = 0) {
            if (node == null || depth > 30 || out.size > 80) return
            listOfNotNull(node.text, node.contentDescription).map { it.toString().trim() }
                .filter { it.isNotEmpty() }.forEach { out += it.take(80) }
            for (i in 0 until node.childCount) texts(node.getChild(i), out, depth + 1)
        }
        val deadline = System.currentTimeMillis() + 60_000L
        while (!done()) {
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            roots.forEach { texts(it, seen) }
            if (System.currentTimeMillis() >= deadline) {
                val id = fixtures.ids[2]
                throw IllegalStateException("Move did not finish; Allow clicks=$clicks; row path=" +
                    "${fixtures.relativePathOf(id)} name=${fixtures.displayNameOf(id)}; " +
                    "new album=${fixtures.namesIn(NEW_PATH)}; windows=" +
                    automation.windows.joinToString { "${it.type}:${it.title}" } + "; texts seen=$seen" +
                    "; log=" + transferLog() + "; threads=" + threadState())
            }
            roots.firstNotNullOfOrNull { root ->
                root.findAccessibilityNodeInfosByText("Allow").firstOrNull { it.isClickable }
            }?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) clicks++ }
            // The v2 Compose rule runs effect coroutines on its test dispatcher:
            // without this the move's coroutine never resumes after its IO pass.
            compose.waitForIdle()
            Thread.sleep(300L)
        }
    }

    /**
     * The test runs in the app's process: report whether the main thread
     * answers within 5 s, its stack, and the top frames of busy pool threads.
     */
    private fun threadState(): String {
        val answered = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { answered.countDown() }
        val mainAlive = answered.await(5, java.util.concurrent.TimeUnit.SECONDS)
        fun frames(t: Thread, n: Int) = t.stackTrace.take(n).joinToString(" < ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        val main = android.os.Looper.getMainLooper().thread
        val pools = Thread.getAllStackTraces().keys
            .filter { it.name.startsWith("DefaultDispatcher") && it.state == Thread.State.RUNNABLE }
            .take(6).joinToString(" || ") { "${it.name}: ${frames(it, 6)}" }
        return "mainAnswered=$mainAlive main=[${frames(main, 18)}] pools=[$pools]"
    }

    /** The app's own Copy/Move stage lines plus MediaProvider warnings (synthetic rows only). */
    private fun transferLog(): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "logcat -d -t 2000 -s ATLAS_TRANSFER:V MediaProvider:W PermissionActivity:V")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
        return text.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.takeLast(30)
            .joinToString(" | ") { it.take(240) }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(60_000L, condition)
        } catch (e: Throwable) {
            throw AssertionError("Timed out: $what", e)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(60_000L) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val FIXTURE_ALBUM = "ATLAS-More-Fixture"
        const val TARGET_ALBUM = "ATLAS-More-Target"
        const val NEW_ALBUM = "ATLAS-More-New"
        const val FIXTURE_PATH = "Pictures/$FIXTURE_ALBUM/"
        const val TARGET_PATH = "Pictures/$TARGET_ALBUM/"
        const val NEW_PATH = "Pictures/$NEW_ALBUM/"
    }

    /**
     * Four invented squares in [FIXTURE_PATH] dated three days ahead (top of
     * Newest first) and one in [TARGET_PATH] an hour older. Afterwards every
     * row in the three test folders is deleted and hidden albums are cleared.
     */
    private class MoreFixtures : TestRule {
        val uris = mutableListOf<Uri>()
        val ids: List<Long> get() = uris.take(4).map { ContentUris.parseId(it) }
        private val names = mutableListOf<String>()
        val targetName: String get() = names[4]
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
                    clearHidden()
                    deleteTestFolders()
                    try {
                        insertAll()
                        base.evaluate()
                    } finally {
                        deleteTestFolders()
                        clearHidden()
                    }
                }
            }

        private fun clearHidden() {
            context.getSharedPreferences("atlas_album_order", Context.MODE_PRIVATE)
                .edit().remove("hidden").commit()
        }

        private fun deleteTestFolders() {
            listOf(FIXTURE_PATH, TARGET_PATH, NEW_PATH).forEach { path ->
                runCatching {
                    context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(path))
                }
            }
        }

        fun namesIn(path: String): List<String> = buildList {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                arrayOf(path), null)?.use { while (it.moveToNext()) add(it.getString(0)) }
        }

        private fun column(id: Long, column: String): String? =
            context.contentResolver.query(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

        fun relativePathOf(id: Long): String? = column(id, MediaStore.MediaColumns.RELATIVE_PATH)
        fun displayNameOf(id: Long): String? = column(id, MediaStore.MediaColumns.DISPLAY_NAME)

        private fun insertAll() {
            val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            val noon = java.time.LocalDate.now().plusDays(3).atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            repeat(5) { i ->
                val inTarget = i == 4
                val taken = noon - i * 60_000L - (if (inTarget) 3_600_000L else 0L)
                val name = "IMG_" + stamp.format(java.time.Instant.ofEpochMilli(taken)
                    .atZone(java.time.ZoneId.systemDefault())) + "_more$i.png"
                names += name
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, if (inTarget) TARGET_PATH else FIXTURE_PATH)
                    put(MediaStore.Images.Media.DATE_TAKEN, taken)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = requireNotNull(context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "Could not create fixture $i" }
                uris += uri
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(200 - 30 * i, 80 + 25 * i, 60 + 35 * i))
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
            }
            assertTrue(namesIn(FIXTURE_PATH).size == 4)
        }
    }
}
