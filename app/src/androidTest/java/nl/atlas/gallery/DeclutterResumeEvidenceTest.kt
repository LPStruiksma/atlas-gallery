package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.testkit.advanceVirtualTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** #577 TIDY-1A: resume journey on generated PNGs; never personal media. */
@RunWith(AndroidJUnit4::class)
class DeclutterResumeEvidenceTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private val folder = "Pictures/ATLAS-Declutter-Resume/"

    private fun insert(name: String, color: Int): Uri {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            resolver.openOutputStream(uri)!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        return uri
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.advanceVirtualTime(700)
        rule.waitForIdle()
        saveUiEvidence(name)
    }

    @Test fun resumeOffersFirstUnkeptPhotoPersistsKeepAndResetsOnlyAfterConfirm() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uris = listOf(
            insert("ATLAS_577_A.png", AndroidColor.rgb(33, 145, 192)),
            insert("ATLAS_577_B.png", AndroidColor.rgb(192, 120, 33)),
            insert("ATLAS_577_C.png", AndroidColor.rgb(90, 160, 70))
        )
        try {
            val deck = uris.mapIndexed { i, uri ->
                MediaRecord(577L + i, uri.toString(), "ATLAS_577_${'A' + i}.png", folder,
                    4096L, 577L + i, false, modifiedAtSeconds = 1L)
            }
            val store = InMemoryDeclutterKeepStore()
            val key = declutterScopeKey("album", folder, sort = "size")
            store.add(key, declutterKeepIdentity(deck[0]))
            store.add(key, declutterKeepIdentity(deck[1]))
            var dark by mutableStateOf(false)
            var fontScale by mutableStateOf(1f)
            var exited by mutableStateOf(false)
            rule.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    AtlasGalleryTheme(dark = dark) {
                        DeclutterScreen(folder, deck, onExit = { exited = true },
                            onTrashRequest = {}, scopeKey = key, keepStore = store)
                    }
                }
            }
            rule.onNodeWithTag("atlas-declutter-resume-dialog").assertExists()
            rule.onNodeWithText("Reviewed 2 of 3", substring = true).assertExists()
            for (tag in listOf("atlas-declutter-resume", "atlas-declutter-start-again",
                "atlas-declutter-not-now")) {
                rule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
            }
            capture("declutter-577-resume-light.png")
            rule.runOnIdle { dark = true }
            capture("declutter-577-resume-dark.png")
            rule.runOnIdle { fontScale = 2f }
            rule.onNodeWithTag("atlas-declutter-resume").assertExists()
            capture("declutter-577-resume-large.png")
            rule.runOnIdle { fontScale = 1f }

            // Start again asks first (own copy, 48 dp targets); cancelling keeps every Keep.
            rule.onNodeWithTag("atlas-declutter-start-again").performClick()
            rule.onNodeWithTag("atlas-declutter-reset-confirm").assertHeightIsAtLeast(48.dp)
            rule.onNodeWithTag("atlas-declutter-reset-cancel").assertHeightIsAtLeast(48.dp)
            capture("declutter-577-reset-dark.png")
            rule.runOnIdle { dark = false }
            capture("declutter-577-reset-light.png")
            rule.onNodeWithTag("atlas-declutter-reset-cancel").performClick()
            assertEquals(2, store.load(key).size)

            rule.onNodeWithTag("atlas-declutter-resume").performClick()
            rule.onNodeWithTag("atlas-declutter-progress").assertTextEquals("Reviewed 2 of 3")
            rule.onNodeWithText("ATLAS_577_C.png", substring = true).assertExists()
            capture("declutter-577-deck-dark.png")
            rule.runOnIdle { dark = false }
            capture("declutter-577-deck-light.png")

            // Keep is saved at once; a stage never is.
            rule.onNodeWithTag("atlas-declutter-keep").performClick()
            assertEquals(3, store.load(key).size)
            rule.onNodeWithText("Folder review complete").assertExists()
            rule.onNodeWithTag("atlas-declutter-undo").assertDoesNotExist()
        } finally {
            uris.forEach { resolver.delete(it, null, null) }
        }
    }
    private fun fakeDeck(count: Int) = (1..count).map { i ->
        MediaRecord(900L + i, "content://media/external/images/media/${900 + i}",
            "ATLAS_577_FAKE_$i.png", folder, 2048L, 900L + i, false, modifiedAtSeconds = 1L)
    }

    @Test fun everyItemKeptShowsAllReviewedAndNeverOffersAMissingResume() {
        val deck = fakeDeck(3)
        val store = InMemoryDeclutterKeepStore()
        val key = declutterScopeKey("album", folder, sort = "size")
        deck.forEach { store.add(key, declutterKeepIdentity(it)) }
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var exited by mutableStateOf(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    DeclutterScreen(folder, deck, onExit = { exited = true },
                        onTrashRequest = {}, scopeKey = key, keepStore = store)
                }
            }
        }
        rule.onNodeWithTag("atlas-declutter-complete").assertExists()
        rule.onNodeWithText("All 3 items reviewed.").assertExists()
        rule.onNodeWithTag("atlas-declutter-resume-dialog").assertDoesNotExist()
        rule.onNodeWithTag("atlas-declutter-complete-back").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("atlas-declutter-complete-restart").assertHeightIsAtLeast(48.dp)
        capture("declutter-577-complete-light.png")
        rule.runOnIdle { dark = true }
        capture("declutter-577-complete-dark.png")
        rule.runOnIdle { fontScale = 2f }
        rule.onNodeWithTag("atlas-declutter-complete-back").assertIsDisplayed()
        rule.onNodeWithTag("atlas-declutter-complete-restart").assertIsDisplayed()
        capture("declutter-577-complete-large.png")
        rule.runOnIdle { fontScale = 1f }

        // No automatic reset: restart needs its confirmation, and cancelling keeps the Keeps.
        rule.onNodeWithTag("atlas-declutter-complete-restart").performClick()
        rule.onNodeWithTag("atlas-declutter-reset-dialog").assertExists()
        rule.onNodeWithTag("atlas-declutter-reset-cancel").performClick()
        assertEquals(3, store.load(key).size)
        rule.onNodeWithTag("atlas-declutter-complete").assertExists()

        rule.onNodeWithTag("atlas-declutter-complete-back").performClick()
        rule.runOnIdle { assertTrue(exited) }
        assertEquals(3, store.load(key).size)
    }

    @Test fun keepsPersistInPrivatePreferencesAcrossRemountsPerAlbumAndStageNeverDoes() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_declutter_keeps_test_577", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val keyA = declutterScopeKey("album", "Pictures/AlbumA/", sort = "size")
            val keyB = declutterScopeKey("album", "Pictures/AlbumB/", sort = "size")
            val deckA = fakeDeck(3)
            val deckB = fakeDeck(2).map {
                MediaRecord(it.id + 100, it.uri + "b", it.name + "B", "Pictures/AlbumB/",
                    it.sizeBytes, it.takenAtMillis, false, modifiedAtSeconds = 1L)
            }
            // Album B has one saved Keep from an earlier session.
            SharedPreferencesDeclutterKeepStore(prefs).add(keyB, declutterKeepIdentity(deckB[0]))

            var generation by mutableStateOf(0)
            var showB by mutableStateOf(false)
            rule.setContent {
                // A new store object per mount: nothing survives in memory, only in preferences.
                key(generation, showB) {
                    val store = SharedPreferencesDeclutterKeepStore(prefs)
                    AtlasGalleryTheme(dark = false) {
                        if (showB) DeclutterScreen("Pictures/AlbumB/", deckB, onExit = {},
                            onTrashRequest = {}, scopeKey = keyB, keepStore = store)
                        else DeclutterScreen("Pictures/AlbumA/", deckA, onExit = {},
                            onTrashRequest = {}, scopeKey = keyA, keepStore = store)
                    }
                }
            }
            // First open of A has no saved Keeps: straight to review. Keep one, stage one.
            rule.onNodeWithTag("atlas-declutter-resume-dialog").assertDoesNotExist()
            rule.onNodeWithTag("atlas-declutter-keep").performClick()
            rule.onNodeWithTag("atlas-declutter-discard").performClick()
            val fresh = SharedPreferencesDeclutterKeepStore(prefs)
            assertEquals(setOf(declutterKeepIdentity(deckA[0])), fresh.load(keyA))
            assertEquals(setOf(declutterKeepIdentity(deckB[0])), fresh.load(keyB))

            // Destroy and remount Declutter: the Keep is offered back, the stage is not.
            rule.runOnIdle { generation++ }
            rule.onNodeWithText("Reviewed 1 of 3", substring = true).assertExists()
            rule.onNodeWithTag("atlas-declutter-resume").performClick()
            rule.onNodeWithTag("atlas-declutter-progress").assertTextEquals("Reviewed 1 of 3")
            rule.onNodeWithText("ATLAS_577_FAKE_2.png", substring = true).assertExists()

            // Album B is its own source: its saved Keep is offered there, then A's
            // confirmed reset must leave it untouched.
            rule.runOnIdle { showB = true }
            rule.onNodeWithText("Reviewed 1 of 2", substring = true).assertExists()
            rule.runOnIdle { showB = false }
            rule.onNodeWithText("Reviewed 1 of 3", substring = true).assertExists()
            rule.onNodeWithTag("atlas-declutter-start-again").performClick()
            rule.onNodeWithTag("atlas-declutter-reset-confirm").performClick()
            val after = SharedPreferencesDeclutterKeepStore(prefs)
            assertEquals(emptySet<String>(), after.load(keyA))
            assertEquals(setOf(declutterKeepIdentity(deckB[0])), after.load(keyB))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
