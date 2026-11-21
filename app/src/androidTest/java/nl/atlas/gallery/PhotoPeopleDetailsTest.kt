package nl.atlas.gallery

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.sampleOnFrames
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.typeText
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * #553 rendered proof: swipe-up Details of a photo with three invented faces
 * (two named, one unnamed). A named circle opens that person; "+" opens the
 * name dialog and the stored name replaces it. Faces are drawn locally;
 * screenshots go to the emulator-only UI-evidence folder (UI_EVIDENCE_DEVICE_DIR).
 */
@RunWith(AndroidJUnit4::class)
class PhotoPeopleDetailsTest {
    private val rule = createComposeRule()

    // #645: evidence at the owner's S24+ size. No keyboard slides up over the
    // name dialog's render (docs/testing/EMULATOR_TESTS.md).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.None))
        .around(S24DisplayRule()).around(rule)

    private fun uid(n: Int) = n.toString(16).padStart(32, '0')

    private fun syntheticFace(skin: Int, hair: Int) = Bitmap.createBitmap(
        160, 160, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFF5C6B7A.toInt())
        paint.color = hair; canvas.drawCircle(80f, 70f, 58f, paint)
        paint.color = skin; canvas.drawOval(34f, 40f, 126f, 150f, paint)
        paint.color = 0xFF222222.toInt()
        canvas.drawCircle(62f, 88f, 6f, paint); canvas.drawCircle(98f, 88f, 6f, paint)
        paint.color = 0xFFB0605A.toInt(); canvas.drawOval(66f, 118f, 94f, 128f, paint)
    }.asImageBitmap()

    /**
     * uiAutomation reads the screen, not Compose: let the last frame land
     * first. The crops are in-memory bitmaps, so nothing decodes later.
     */
    private fun evidence(filename: String) {
        rule.waitForIdle()
        sampleOnFrames(frames = 3) { }
        saveUiEvidence(filename)
    }

    /** Keep the full viewer decodeable across theme changes; never read personal media. */
    private val generatedImages = mutableListOf<File>()

    @After fun deleteSyntheticImages() {
        generatedImages.forEach { check(!it.exists() || it.delete()) }
    }

    private fun syntheticPhoto(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("atlas-details-people-", ".png", context.cacheDir)
        generatedImages += file
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF253C52.toInt())
            val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD4A67B.toInt() }
            canvas.drawOval(70f, 28f, 250f, 210f, brush)
            brush.color = 0xFFF4E2BC.toInt()
            canvas.drawCircle(162f, 105f, 44f, brush)
            FileOutputStream(file).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    @Test fun detailsShowsPeopleTapOpensPersonAndPlusNamesTheGroup() {
        val source = syntheticPhoto()
        val photo = MediaRecord(
            id = 7, uri = Uri.fromFile(source).toString(),
            name = "Fictional-party.jpg", relativePath = "DCIM/Camera/",
            sizeBytes = source.length(), takenAtMillis = 1_600_000_000_000L, isVideo = false)
        val ada = PhotoPerson.Named("synthetic-epoch", uid(1), PersonKey("native-ada"), "Ada")
        val bram = PhotoPerson.Named("synthetic-epoch", uid(2), PersonKey("native-bram"), "Bram")
        val unknown = PhotoPerson.Unnamed("synthetic-epoch", uid(3), uid(3))
        val crops = mapOf(
            uid(1) to syntheticFace(0xFFE8C4A0.toInt(), 0xFF3B2A20.toInt()),
            uid(2) to syntheticFace(0xFFC69C72.toInt(), 0xFF111111.toInt()),
            uid(3) to syntheticFace(0xFFF1D3B5.toInt(), 0xFFB8862F.toInt()))
        val changes = MutableStateFlow(0L)
        var people: List<PhotoPerson> = listOf(ada, bram, unknown)
        val loads = mutableListOf<String>()
        val opened = mutableListOf<PhotoPerson.Named>()
        val named = mutableListOf<Pair<PhotoPerson.Unnamed, String>>()
        val port = ViewerPhotoPeople(
            changes = changes,
            load = { record -> synchronized(loads) { loads += record.uri }; people },
            crop = { crops[it.face] },
            onOpen = { opened += it },
            onName = { target, name ->
                named += target to name
                people = listOf(PhotoPerson.Named("synthetic-epoch", target.face,
                    PersonKey("native-" + target.clusterAnchor), name), ada, bram)
                    .sortedBy { it.name }
                changes.value = changes.value + 1
                true
            })
        val reader = ViewerDimensionsReader { _, _ -> ViewerDimensions(4000, 3000) }
        var dark by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalViewerDimensionsReader provides reader) {
                // #645: the app's dark theme and system bars, as in MainActivity.
                AtlasEvidenceHarness(edgeToEdge = true, dark = dark) {
                    GalleryViewer(listOf(photo), photo.uri, photoPeople = port, onBack = {})
                }
            }
        }
        rule.tap(hasTestTag("atlas-viewer-details-open"))
        rule.awaitThat("the Details people row", 8_000) {
            rule.exists(hasTestTag("atlas-details-people"))
        }
        rule.onNodeWithTag("atlas-details-people").performScrollTo()
        rule.onNodeWithText("Ada").assertExists()
        rule.onNodeWithText("Bram").assertExists()
        rule.onAllNodesWithTag("atlas-details-person-add", useUnmergedTree = true)
            .assertCountEquals(1)
        assertEquals(listOf(photo.uri), synchronized(loads) { loads.toList() })
        evidence("553-details-people.png")
        // #655: same synthetic Details/People mapping, both Activity themes.
        evidence("parity-24-viewer-details-people-dark.png")
        dark = false
        rule.awaitThat("light Details people survive recompose", 8_000) {
            rule.exists(hasTestTag("atlas-details-people")) &&
                !rule.exists(hasTestTag("atlas-viewer-decode-error"))
        }
        evidence("parity-24-viewer-details-people-light.png")
        dark = true
        rule.awaitThat("dark Details people survive recompose", 8_000) {
            rule.exists(hasTestTag("atlas-details-people")) &&
                !rule.exists(hasTestTag("atlas-viewer-decode-error"))
        }

        rule.tap(hasTestTag("atlas-details-person-1")) { opened.isNotEmpty() }
        assertEquals(listOf(ada), opened)

        rule.tap(hasTestTag("atlas-details-person-3"))
        // The name dialog is its own window (rule 5).
        rule.awaitThat("the name dialog", 5_000) {
            rule.exists(hasTestTag("atlas-details-name-dialog"))
        }
        rule.typeText(hasTestTag("atlas-details-name-field"), "  Cleo ")
        rule.onNodeWithText("  Cleo ").assertExists()
        evidence("553-details-add-name.png")
        rule.tap(hasTestTag("atlas-details-name-done"))
        rule.awaitThat("the name dialog to close", 5_000) {
            !rule.exists(hasTestTag("atlas-details-name-dialog"))
        }
        assertEquals(listOf(unknown to "Cleo"), named)
        rule.awaitThat("the + circle to go", 5_000) {
            !rule.exists(hasTestTag("atlas-details-person-add"), useUnmergedTree = true)
        }
        rule.onNodeWithText("Cleo").assertExists()
        evidence("553-details-people-named.png")
    }
}
