package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.UUID
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.NativePersonAlbum
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurestories.AtlasStoriesScreen
import nl.atlas.gallery.featurestories.StoryCard
import nl.atlas.gallery.featurestories.StoryDeck
import nl.atlas.gallery.featurestories.StoryKind
import nl.atlas.gallery.featurestories.StoryMedia
import nl.atlas.gallery.featurestories.StoryPlaybackPosition
import nl.atlas.gallery.featurestories.StoryPlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * YEARS-1 (#586): synthetic S24+ evidence for the person-page row, the person
 * menu, the birth date editor, Ignored people and the Through the years player,
 * plus the durable Ignore / birth date rows. Photos are plain drawn bitmaps
 * written to MediaStore by this test (removed afterwards); no faces, no real
 * photos, no People data. Fixed UI states are rendered directly; the full
 * face-model journey is covered by PersonPageJourneyTest.
 */
@RunWith(AndroidJUnit4::class)
class Years1EvidenceTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val inserted = mutableListOf<Uri>()
    private lateinit var uris: List<String>

    @Before fun insertSyntheticPhotos() {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            .forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
        val colors = intArrayOf(0xFF3F51B5.toInt(), 0xFF00897B.toInt(), 0xFFEF6C00.toInt(),
            0xFF6A1B9A.toInt(), 0xFF2E7D32.toInt(), 0xFFC62828.toInt())
        uris = colors.mapIndexed { i, color ->
            val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(color)
                val p = Paint().apply { this.color = AColor.WHITE; textSize = 160f; isAntiAlias = true }
                drawText("Fictional ${i + 1}", 80f, 640f, p)
            }
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_YEARS1_FIXTURE_$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.DATE_TAKEN, 1_500_000_000_000L + i * 86_400_000L)
                }))
            target.contentResolver.openOutputStream(uri)!!.use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            inserted += uri
            uri.toString()
        }
    }

    @After fun removeSyntheticPhotos() {
        inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
    }

    private val ada = NativePersonAlbum(PersonKey("native-synthetic-ada"), "Ada",
        emptyList(), emptyList())
    private val bo = NativePersonAlbum(PersonKey("native-synthetic-bo"), "Bo",
        emptyList(), emptyList())

    @Composable
    private fun Themed(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        AtlasGalleryTheme(dark = dark) {
            CompositionLocalProvider(LocalDensity provides Density(
                LocalDensity.current.density, fontScale)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
        }
    }

    private fun records() = uris.mapIndexed { i, u ->
        MediaRecord(i + 1L, u, "IMG_YEARS1_FIXTURE_$i.png", "DCIM/Fictional/", 1_000L,
            1_500_000_000L + i, false)
    }

    @Composable
    private fun PersonPage(dark: Boolean, fontScale: Float, withBirth: Boolean = false,
                           dialog: PersonPageDialog? = null) {
        var shown by androidx.compose.runtime.remember { mutableStateOf(dialog) }
        Themed(dark, fontScale) {
            PersonPageScreen(
                ui = PersonPageUi("Ada", records(), hidden = false, mayHide = true,
                    hasBirthDate = withBirth, mayIgnore = true,
                    yearsPreview = listOf(uris[0], uris[2], uris[5])),
                gridState = rememberLazyGridState(), catalogueEpoch = 1L,
                portrait = {}, onBack = {}, onOpen = {},
                dialog = shown, onDialog = { shown = it },
                nameDialog = {}, mergePicker = {}, confirmMerge = {},
                onHide = {}, selectedUris = null, onSelectionChange = {},
                onRemoveSelected = null, onYears = {},
                birthDialog = {
                    PersonBirthDateDialog(
                        initial = if (withBirth) LocalDate.of(2010, 8, 20) else null,
                        today = LocalDate.of(2026, 11, 21),
                        onDismiss = { shown = null }, onSave = { shown = null },
                        onClear = { shown = null })
                }
            )
        }
    }

    @Test fun personPageRowAndMenu() {
        var dark by mutableStateOf(true)
        rule.setContent { PersonPage(dark, 1f) }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-person-years").assertExists()
        saveUiEvidence("years1-person-page-row-dark.png")
        rule.runOnIdle { dark = false }
        rule.waitForIdle()
        saveUiEvidence("years1-person-page-row-light.png")
        rule.runOnIdle { dark = true }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-header-more").performClick()
        rule.onNodeWithTag("atlas-person-ignore").assertExists()
        rule.onNodeWithTag("atlas-person-birth-date").assertExists()
        // Every pre-existing named-person action is still in the menu.
        listOf("atlas-person-edit-name", "atlas-person-merge", "atlas-person-remove",
            "atlas-person-hide").forEach { rule.onNodeWithTag(it).assertExists() }
        saveUiEvidence("years1-person-menu-dark.png")
    }

    @Test fun personMenuAtLargeText() {
        rule.setContent { PersonPage(true, 2f) }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-header-more").performClick()
        rule.onNodeWithTag("atlas-person-ignore").assertExists()
        saveUiEvidence("years1-person-menu-large-text-dark.png")
    }

    @Test fun birthDateEditor() {
        var dark by mutableStateOf(true)
        rule.setContent { PersonPage(dark, 1f, withBirth = true, dialog = PersonPageDialog.BirthDate) }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-person-birth-clear").assertExists()
        saveUiEvidence("years1-birth-date-dark.png")
        rule.runOnIdle { dark = false }
        rule.waitForIdle()
        saveUiEvidence("years1-birth-date-light.png")
    }

    @Test fun birthDateEditorAtLargeText() {
        rule.setContent { PersonPage(true, 2f, withBirth = true, dialog = PersonPageDialog.BirthDate) }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-person-birth-save").assertExists()
        saveUiEvidence("years1-birth-date-large-text-dark.png")
    }

    @Test fun ignoredPeopleListAndEmptyState() {
        var dark by mutableStateOf(true)
        var people by mutableStateOf(listOf(ada, bo))
        rule.setContent {
            Themed(dark) {
                HiddenPeopleScreen(people = people, portrait = {}, onBack = {}, onOpen = {},
                    title = "Ignored people", emptyText = "No ignored people.",
                    tagPrefix = "atlas-people-ignored")
            }
        }
        rule.waitForIdle()
        saveUiEvidence("years1-ignored-people-dark.png")
        rule.runOnIdle { dark = false }
        rule.waitForIdle()
        saveUiEvidence("years1-ignored-people-light.png")
        rule.runOnIdle { dark = true; people = emptyList() }
        rule.waitForIdle()
        saveUiEvidence("years1-ignored-people-empty-dark.png")
    }

    @Test fun throughTheYearsPlayerShowsYearThenAge() {
        val frames = uris.map { StoryMedia(it, 1_500_000_000_000L) }
        fun deck(captions: List<String>) = StoryDeck("g", "r", listOf(StoryCard(
            key = "years:native-synthetic-ada", title = "Ada through the years",
            caption = "2017 to 2024", kind = StoryKind.PERSON_YEARS, items = frames,
            frameCaptions = captions)))
        val years = listOf("2017", "2017", "2019", "2021", "2023", "2024")
        val ages = listOf("Under 1", "Age 2", "Age 4", "Age 6", "Age 8", "Age 9")
        var captions by mutableStateOf(years)
        val playback = StoryPlaybackState()
        playback.position = StoryPlaybackPosition("g", "r", "years:native-synthetic-ada", 2, false)
        rule.setContent {
            Themed(true) {
                AtlasStoriesScreen(deck(captions), "g", "r", onBack = {}, onOpenMedia = {},
                    playback = playback)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-story-frame-caption").assertExists()
        Thread.sleep(1500) // let the synthetic photo decode before the capture
        saveUiEvidence("years1-story-year-dark.png")
        rule.runOnIdle { captions = ages }
        rule.waitForIdle()
        Thread.sleep(500)
        saveUiEvidence("years1-story-age-dark.png")
    }

    private fun contextFor(file: File): Context = object : ContextWrapper(target) {
        override fun getDatabasePath(name: String): File = file
        override fun openOrCreateDatabase(name: String, mode: Int,
            factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(file, factory)
        override fun openOrCreateDatabase(name: String, mode: Int,
            factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(file.path, factory, errorHandler)
    }

    /** Ignore and the birth date are plain rows keyed by name, kept across a reopen. */
    @Test fun ignoreAndBirthDateSurviveReopen() {
        val dir = File(target.cacheDir, "years1-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val file = File(dir, "face_fixture.db")
        val ada = PersonKey("native-synthetic-ada")
        val bo = PersonKey("native-synthetic-bo")
        val born = LocalDate.of(2010, 8, 20)
        FaceIndexStore(contextFor(file)).use { s ->
            assertTrue(s.ignoredPeople().isEmpty())
            s.setPersonIgnored(ada, true)
            s.setPersonIgnored(ada, true) // idempotent
            s.setPersonBirthDate(ada, LocalDate.of(2009, 1, 1))
            s.setPersonBirthDate(ada, born) // replace
            s.setPersonBirthDate(bo, born)
            s.setPersonBirthDate(bo, null) // clear
        }
        FaceIndexStore(contextFor(file)).use { reopened ->
            assertEquals(setOf(ada), reopened.ignoredPeople())
            assertEquals(mapOf(ada to born), reopened.personBirthDates())
            reopened.setPersonIgnored(ada, false)
            assertTrue(reopened.ignoredPeople().isEmpty())
        }
    }
}
