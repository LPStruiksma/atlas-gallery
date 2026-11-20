package nl.atlas.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featureaiedit.save.AiEditLineage
import nl.atlas.gallery.featureaiedit.save.AiEditLineageStore
import nl.atlas.gallery.featurecuration.hashCurationBytes
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * AIE-10 / #196: real Compose Photo DNA -> viewer details, using only three
 * locally drawn MediaStore PNGs and two completed app-private lineage rows.
 * Neither duplicate/similarity guesses nor deleted media can supply provenance.
 * Capture the populated family and both distinct viewer states at S24+ size.
 */
@RunWith(AndroidJUnit4::class)
class AiEditProvenanceJourneyTest {
    private val fixtures = AiEditProvenanceMediaFixtures()
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun originalAndTwoSavedEditsRenderSeparatelyAndOpenTheirOwnDetails() {
        val records = fixtures.records
        val grant = GalleryMediaGrant(images = true, videos = false, selected = false)
        val tokens = requireNotNull(photoDnaTokensOrNull(grant, 196L, true, records))
        val result = planPhotoDna(records, tokens, hash = { record ->
            hashCurationBytes(record.sizeBytes,
                openAuthorized = {
                    fixtures.bytes[record.uri]?.let(::ByteArrayInputStream)
                },
                stillAuthorized = { true })
        })
        var opened by mutableStateOf<String?>(null)
        compose.setContent {
            AtlasEvidenceHarness(edgeToEdge = true) {
                if (opened == null) {
                    PhotoDnaRoute(
                        result = result, verifying = false, onBack = {},
                        onOpenPhoto = { opened = it }, onReviewRequested = {},
                        accessible = true
                    )
                } else {
                    key(opened) {
                        GalleryViewer(records, requireNotNull(opened), onBack = { opened = null })
                    }
                }
            }
        }

        val family = hasTestTag("photo-dna-provenance-group") and
            hasAnyDescendant(hasText("Original + AI versions (2)"))
        waitFor("lineage loaded", hasText("AI edits (1)"))
        compose.onNodeWithTag("photo-dna-list").performScrollToNode(family)
        compose.onNode(family).assertIsDisplayed()
        compose.onNodeWithText("Saved edit history · not a visual similarity match")
            .assertExists()
        // The provenance card is not a duplicate/Trash selection surface.
        compose.onAllNodes(family and hasAnyDescendant(hasTestTag("photo-dna-stage")))
            .assertCountEquals(0)
        compose.onNodeWithTag("photo-dna-provenance-tiles").performScrollToIndex(0)
        compose.onNodeWithTag("photo-dna-provenance-original").assertIsDisplayed()
        compose.waitForIdle()
        saveUiEvidence("aie-10-photo-dna-family-start.png")

        // Tapping the original from Photo DNA opens the actual viewer.
        compose.onNodeWithTag("photo-dna-provenance-original").performClick()
        waitFor("original viewer", hasTestTag("atlas-viewer-details-open"))
        compose.onNodeWithTag("atlas-viewer-details-open").performClick()
        waitFor("original details", hasText("AI versions (2)"))
        compose.onNodeWithText("AI versions (2)").assertIsDisplayed()
        compose.onNodeWithText("Saved AI edits").assertIsDisplayed()
        compose.onNodeWithText(AiEditProvenanceMediaFixtures.ORIGINAL).assertExists()
        compose.onNodeWithText("Edited from…").assertDoesNotExist()
        compose.onNodeWithTag("atlas-details-ai-edited").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("aie-10-viewer-original-details.png")

        // Return to the same route and scroll the horizontal family to edit #2.
        compose.runOnIdle { opened = null }
        waitFor("return to Photo DNA", hasText("AI edits (1)"))
        compose.onNodeWithTag("photo-dna-list").performScrollToNode(family)
        compose.onNodeWithTag("photo-dna-provenance-tiles").performScrollToIndex(2)
        val secondEditTag = "photo-dna-provenance-edit-${records[2].id}"
        compose.onNodeWithTag(secondEditTag).assertIsDisplayed()
        compose.waitForIdle()
        saveUiEvidence("aie-10-photo-dna-family-end.png")

        compose.onNodeWithTag(secondEditTag).performClick()
        waitFor("edited viewer", hasTestTag("atlas-viewer-details-open"))
        compose.onNodeWithTag("atlas-viewer-details-open").performClick()
        waitFor("edited details", hasText("Edited from…"))
        compose.onNodeWithText("Edited from…").assertIsDisplayed()
        compose.onNodeWithText("Original photo (AI edit)").assertIsDisplayed()
        compose.onNodeWithText(AiEditProvenanceMediaFixtures.EDIT_TWO).assertExists()
        compose.onNodeWithText("AI versions (2)").assertDoesNotExist()
        compose.onNodeWithText(AI_EDITED_LABEL).assertIsDisplayed()
        compose.waitForIdle()
        saveUiEvidence("aie-10-viewer-edited-details.png")
    }

    private fun waitFor(stage: String, matcher: SemanticsMatcher) {
        compose.waitUntil(30_000L) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        check(compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) { stage }
    }
}

/**
 * Own test fixtures only; remove every MediaStore image and lineage by URI.
 * #458 adds a fourth plain photo without lineage (the non-AI state) and a way
 * to delete the original mid-test (the missing-original state).
 */
internal class AiEditProvenanceMediaFixtures : TestRule {
    companion object {
        const val ORIGINAL = "AIE10_SYNTHETIC_ORIGINAL.png"
        const val EDIT_ONE = "AIE10_SYNTHETIC_EDIT_1.png"
        const val EDIT_TWO = "AIE10_SYNTHETIC_EDIT_2.png"
        const val PLAIN = "AIE26_SYNTHETIC_PLAIN.png"
    }
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = mutableListOf<Uri>()
    var records: List<MediaRecord> = emptyList()
        private set
    val bytes = mutableMapOf<String, ByteArray>()

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                for (permission in listOf(Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO)) {
                    automation.grantRuntimePermission(context.packageName, permission)
                }
                try {
                    records = listOf(
                        insert(ORIGINAL, Color.rgb(24, 98, 196)),
                        insert(EDIT_ONE, Color.rgb(182, 60, 92)),
                        insert(EDIT_TWO, Color.rgb(61, 174, 107)),
                        insert(PLAIN, Color.rgb(201, 158, 38))
                    )
                    val store = AiEditLineageStore(context)
                    for (edit in records.subList(1, 3)) {
                        store.put(AiEditLineage(
                            editedUri = edit.uri, originalUri = records[0].uri,
                            originalRevision = "fixture-r1", provider = "synthetic",
                            model = "test", modelVersion = "1", prompt = "",
                            settingsJson = "{}", createdAtMillis = 196L,
                            candidateParentUri = null
                        ))
                    }
                    check(store.all().count { it.originalUri == records[0].uri } == 2)
                    base.evaluate()
                } finally {
                    // The shared app-private table must not retain stale test
                    // relationships across retries or other emulator journeys.
                    val dbFile = File(context.noBackupFilesDir,
                        "atlas-ai-edit-lineage-v1.sqlite")
                    if (dbFile.exists()) {
                        SQLiteDatabase.openDatabase(dbFile.absolutePath, null,
                            SQLiteDatabase.OPEN_READWRITE).use { db ->
                            for (uri in inserted) {
                                db.delete("edit_lineage", "edited_uri = ?",
                                    arrayOf(uri.toString()))
                            }
                        }
                    }
                    inserted.forEach {
                        runCatching { context.contentResolver.delete(it, null, null) }
                    }
                    bytes.clear()
                }
            }
        }

    /** The missing-original state: the saved edits and their lineage stay. */
    fun deleteOriginal() {
        check(context.contentResolver.delete(Uri.parse(records[0].uri), null, null) == 1)
    }

    private fun insert(name: String, color: Int): MediaRecord {
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val data = try {
            ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-AIE10-Fixture/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        inserted += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(data) }
        check(context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1)
        bytes[uri.toString()] = data
        return MediaRecord(
            id = ContentUris.parseId(uri), uri = uri.toString(), name = name,
            relativePath = "Pictures/ATLAS-AIE10-Fixture/",
            sizeBytes = data.size.toLong(), takenAtMillis = 1_700_000_000_000L,
            isVideo = false, modifiedAtSeconds = 1_700_000_000L
        )
    }
}
