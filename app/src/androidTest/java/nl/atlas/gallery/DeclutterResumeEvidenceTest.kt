package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.testkit.advanceVirtualTime
import org.junit.Assert.assertEquals
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
            var exited by mutableStateOf(false)
            rule.setContent {
                AtlasGalleryTheme(dark = dark) {
                    DeclutterScreen(folder, deck, onExit = { exited = true },
                        onTrashRequest = {}, scopeKey = key, keepStore = store)
                }
            }
            rule.onNodeWithTag("atlas-declutter-resume-dialog").assertExists()
            rule.onNodeWithText("Reviewed 2 of 3", substring = true).assertExists()
            capture("declutter-577-resume-light")
            rule.runOnIdle { dark = true }
            capture("declutter-577-resume-dark")

            // Start again asks first; cancelling keeps every saved Keep.
            rule.onNodeWithTag("atlas-declutter-start-again").performClick()
            rule.onNodeWithTag("atlas-declutter-reset-confirm").assertExists()
            rule.onNodeWithText("Cancel").performClick()
            assertEquals(2, store.load(key).size)

            rule.onNodeWithTag("atlas-declutter-resume").performClick()
            rule.onNodeWithTag("atlas-declutter-progress").assertTextEquals("Reviewed 2 of 3")
            rule.onNodeWithText("ATLAS_577_C.png", substring = true).assertExists()
            capture("declutter-577-deck-dark")
            rule.runOnIdle { dark = false }
            capture("declutter-577-deck-light")

            // Keep is saved at once; a stage never is.
            rule.onNodeWithTag("atlas-declutter-keep").performClick()
            assertEquals(3, store.load(key).size)
            rule.onNodeWithText("Folder review complete").assertExists()
            rule.onNodeWithTag("atlas-declutter-undo").assertDoesNotExist()
        } finally {
            uris.forEach { resolver.delete(it, null, null) }
        }
    }
}
