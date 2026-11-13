package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #553 rendered proof: swipe-up Details of a photo with three invented faces
 * (two named, one unnamed). A named circle opens that person; "+" opens the
 * name dialog and the stored name replaces it. Faces are drawn locally;
 * screenshots go to the emulator-only Pictures/ATLAS-UI-Evidence folder.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPeopleDetailsTest {
    private val rule = createComposeRule()

    // #645: evidence at the owner's S24+ size.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

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

    /** uiAutomation reads the screen, not Compose: let the last frame land first. */
    private fun settle() {
        rule.waitForIdle()
        SystemClock.sleep(500)
    }

    @Test fun detailsShowsPeopleTapOpensPersonAndPlusNamesTheGroup() {
        val photo = MediaRecord(
            id = 7, uri = "content://synthetic-atlas-test/images/7",
            name = "Fictional-party.jpg", relativePath = "DCIM/Camera/",
            sizeBytes = 3_200_000L, takenAtMillis = 1_600_000_000_000L, isVideo = false)
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
        rule.setContent {
            CompositionLocalProvider(LocalViewerDimensionsReader provides reader) {
                // #645: the app's dark theme and system bars, as in MainActivity.
                AtlasEvidenceHarness(edgeToEdge = true) {
                    GalleryViewer(listOf(photo), photo.uri, photoPeople = port, onBack = {})
                }
            }
        }
        rule.onNodeWithTag("atlas-viewer-details-open").performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodesWithTag("atlas-details-people").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-details-people").performScrollTo()
        rule.onNodeWithText("Ada").assertExists()
        rule.onNodeWithText("Bram").assertExists()
        rule.onAllNodesWithTag("atlas-details-person-add", useUnmergedTree = true)
            .assertCountEquals(1)
        assertEquals(listOf(photo.uri), synchronized(loads) { loads.toList() })
        settle()
        saveUiEvidence("553-details-people.png")

        rule.onNodeWithTag("atlas-details-person-1").performClick()
        assertEquals(listOf(ada), opened)

        rule.onNodeWithTag("atlas-details-person-3").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("atlas-details-name-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-details-name-field").performTextInput("  Cleo ")
        rule.onNodeWithText("  Cleo ").assertExists()
        settle()
        saveUiEvidence("553-details-add-name.png")
        rule.onNodeWithTag("atlas-details-name-done").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("atlas-details-name-dialog").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(listOf(unknown to "Cleo"), named)
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("atlas-details-person-add", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("Cleo").assertExists()
        settle()
        saveUiEvidence("553-details-people-named.png")
    }
}
