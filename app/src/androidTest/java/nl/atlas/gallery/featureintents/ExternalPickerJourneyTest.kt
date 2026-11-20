package nl.atlas.gallery.featureintents

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import nl.atlas.gallery.MainActivity
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * BENCH-2 (#573) A2b: another app asks ATLAS for photos with GET_CONTENT or PICK
 * and gets back exactly what was chosen. Media are two generated PNGs in their
 * own folder; the result is read the way the calling app reads it.
 */
@RunWith(Parameterized::class)
class ExternalPickerJourneyTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    private val compose = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = dark)).around(compose)
    private val media = PickerMedia()
    private val theme get() = if (dark) "dark" else "light"

    @Before fun setUp() = media.create()
    @After fun tearDown() = media.delete()

    @Test fun singlePickReturnsTheOneChosenPhotoWithAReadGrant() {
        val scenario = media.launch(Intent.ACTION_GET_CONTENT, "image/*", multiple = false)
        compose.awaitPicker("Choose a photo")
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertIsNotEnabled()
        compose.tapTile(media.first)
        compose.tapTile(media.second) // single pick: the second tap replaces the first
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertIsEnabled()
        compose.capture("picker-573-single-$theme")
        compose.tap(hasTestTag(ExternalPickerTags.DONE))
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        val data = requireNotNull(result.resultData)
        assertSameItem(media.second, data.data)
        assertEquals(1, data.clipData!!.itemCount)
        assertSameItem(media.second, data.clipData!!.getItemAt(0).uri)
        assertTrue(data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun multiplePickReturnsEveryChosenItemInPickOrder() {
        val scenario = media.launch(Intent.ACTION_GET_CONTENT, "image/*", multiple = true)
        compose.awaitPicker("Choose photos")
        compose.tapTile(media.second)
        compose.tapTile(media.first)
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertTextEquals("Done (2)")
        compose.capture("picker-573-multiple-$theme")
        compose.tap(hasTestTag(ExternalPickerTags.DONE))
        val data = requireNotNull(scenario.result.resultData)
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        assertSameItem(media.second, data.data)
        val clip = data.clipData!!
        assertEquals(2, clip.itemCount)
        assertSameItem(media.second, clip.getItemAt(0).uri)
        assertSameItem(media.first, clip.getItemAt(1).uri)
    }

    @Test fun legacyPickOfTheImagesCollectionBrowsesAlbums() {
        val scenario = media.launch(Intent.ACTION_PICK, null, multiple = false,
            data = MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        compose.awaitPicker("Choose a photo")
        compose.tap(hasTestTag(ExternalPickerTags.TAB_ALBUMS))
        compose.awaitThat("the picker's album card") {
            compose.exists(hasContentDescription(PickerMedia.ALBUM, substring = true))
        }
        compose.capture("picker-573-albums-$theme")
        compose.tap(hasContentDescription(PickerMedia.ALBUM, substring = true))
        compose.awaitThat("the album grid") { compose.exists(hasTestTag(ExternalPickerTags.ALBUM_BACK)) }
        compose.tapTile(media.first)
        compose.tap(hasTestTag(ExternalPickerTags.DONE))
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        assertSameItem(media.first, scenario.result.resultData!!.data)
    }

    @Test fun cancelAndBackReturnNothing() {
        val cancelled = media.launch(Intent.ACTION_GET_CONTENT, "image/*", multiple = true)
        compose.awaitPicker("Choose photos")
        compose.tapTile(media.first)
        compose.tap(hasTestTag(ExternalPickerTags.CANCEL))
        assertEquals(Activity.RESULT_CANCELED, cancelled.result.resultCode)
        assertNull(cancelled.result.resultData)

        val backed = media.launch(Intent.ACTION_GET_CONTENT, "image/*", multiple = false)
        compose.awaitPicker("Choose a photo")
        compose.tapTile(media.first)
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        assertEquals(Activity.RESULT_CANCELED, backed.result.resultCode)
        assertNull(backed.result.resultData)
    }

    @Test fun aNewPickIntentStartsWithNothingChosen() {
        // The first caller chooses an item but never presses Done.
        val scenario = ActivityScenario.launch<MainActivity>(media.intent(Intent.ACTION_GET_CONTENT, "image/*", multiple = false))
        compose.awaitPicker("Choose a photo")
        var first: MainActivity? = null
        scenario.onActivity { first = it }
        compose.tapTile(media.first)
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertIsEnabled()
        compose.onNodeWithTag(media.tileTag(media.first)).assertIsSelected()
        // A second caller sends the identical request to the same Activity (single top).
        media.deliverNewIntent(Intent.ACTION_GET_CONTENT, "image/*", multiple = false)
        compose.awaitThat("the new request to reach the same Activity") {
            var same = false
            scenario.onActivity { same = it === first && it.intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0 }
            same
        }
        compose.awaitPicker("Choose a photo")
        compose.awaitThat("the old choice to be gone") {
            !compose.exists(hasTestTag(media.tileTag(media.first)) and isSelected())
        }
        // Nothing of the first caller's choice can be returned: Done is off until a new tap.
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertIsNotEnabled()
        compose.onNodeWithTag(media.tileTag(media.first)).assertIsNotSelected()
        compose.tapTile(media.second)
        compose.onNodeWithTag(media.tileTag(media.second)).assertIsSelected()
        compose.onNodeWithTag(media.tileTag(media.first)).assertIsNotSelected()
        // ActivityScenario does not track the onNewIntent pause/resume, so finish
        // through the lifecycle monitor (as ExternalIntentJourneyTest does).
        media.finishMainActivities()
        compose.awaitThat("every MainActivity to be destroyed") { !media.anyMainActivityAlive() }
    }

    @Test fun anItemTheProviderRefusesIsDroppedAndAnotherCanBeChosen() {
        // The caller asks for JPEG only; the folder holds two PNGs and one JPEG.
        val jpeg = media.jpeg()
        val scenario = media.launch(Intent.ACTION_GET_CONTENT, "image/jpeg", multiple = false)
        compose.awaitPicker("Choose a photo")
        compose.tapTile(media.second) // a PNG: the provider's own type is image/png
        compose.tap(hasTestTag(ExternalPickerTags.DONE))
        compose.awaitThat("the unusable-item message") { compose.exists(hasTestTag(ExternalPickerTags.ERROR)) }
        compose.onNodeWithTag(ExternalPickerTags.ERROR).assertTextEquals(PickCopy.SOME_UNAVAILABLE)
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertIsNotEnabled()
        compose.onNodeWithTag(media.tileTag(media.second)).assertIsNotSelected()
        compose.capture("picker-573-unusable-$theme")
        // Recovery: choosing the JPEG clears the message and returns it.
        compose.tapTile(jpeg)
        assertFalse(compose.exists(hasTestTag(ExternalPickerTags.ERROR)))
        compose.tap(hasTestTag(ExternalPickerTags.DONE))
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        val data = requireNotNull(scenario.result.resultData)
        assertSameItem(jpeg, data.data)
        assertEquals(1, data.clipData!!.itemCount)
    }

    @Test fun recreationKeepsTheChoice() {
        val scenario = media.launch(Intent.ACTION_GET_CONTENT, "image/*", multiple = true)
        compose.awaitPicker("Choose photos")
        compose.tapTile(media.second)
        compose.tapTile(media.first)
        compose.onNodeWithTag(ExternalPickerTags.DONE).assertTextEquals("Done (2)")
        // A configuration change (rotation, theme, font) recreates the Activity.
        scenario.recreate()
        compose.awaitPicker("Choose photos")
        compose.awaitThat("the choice to survive recreation") {
            compose.exists(hasTestTag(ExternalPickerTags.DONE) and hasText("Done (2)"))
        }
        compose.onNodeWithTag(media.tileTag(media.second)).assertIsSelected()
        compose.capture("picker-573-recreated-$theme")
        compose.tap(hasTestTag(ExternalPickerTags.DONE))
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        val clip = requireNotNull(scenario.result.resultData).clipData!!
        assertEquals(2, clip.itemCount)
        assertSameItem(media.second, clip.getItemAt(0).uri)
        assertSameItem(media.first, clip.getItemAt(1).uri)
    }

    @Test fun aPickForSomethingThatIsNotMediaIsCancelledAtOnce() {
        val scenario = media.launch(Intent.ACTION_GET_CONTENT, "text/plain", multiple = false)
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
    }
}

/**
 * The same MediaStore row: ATLAS returns the volume-specific item URI
 * (`external_primary`), while the test inserted through the `external` union.
 */
internal fun assertSameItem(expected: Uri, actual: Uri?) {
    requireNotNull(actual) { "no URI returned" }
    assertEquals("content", actual.scheme)
    assertEquals("media", actual.authority)
    assertEquals(expected.pathSegments.drop(1), actual.pathSegments.drop(1))
}

internal fun androidx.compose.ui.test.junit4.ComposeTestRule.awaitPicker(title: String) {
    awaitThat("the picker titled $title") { exists(hasTestTag(ExternalPickerTags.TITLE) and hasText(title)) }
}

internal fun androidx.compose.ui.test.junit4.ComposeTestRule.tapTile(uri: Uri) {
    val tag = "atlas-timeline-media-${ContentUris.parseId(uri)}"
    awaitThat("grid tile $tag", timeoutMs = 30_000L) { exists(hasTestTag(tag)) }
    tap(hasTestTag(tag))
}

/** Two generated PNGs in their own folder; the second is the newer. */
internal class PickerMedia {
    companion object {
        const val ALBUM = "ATLAS-573-Picker"
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val created = mutableListOf<Uri>()
    val first: Uri get() = created[0]
    val second: Uri get() = created[1]

    fun create() {
        for (permission in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        // Taken "now", so newest-first puts both at the top of any emulator library.
        val now = System.currentTimeMillis()
        png("ATLAS_573_PICK_1.png", AndroidColor.rgb(46, 104, 170), now - 60_000L)
        png("ATLAS_573_PICK_2.png", AndroidColor.rgb(176, 112, 48), now)
    }

    fun delete() {
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        created.clear()
    }

    fun tileTag(uri: Uri) = "atlas-timeline-media-${ContentUris.parseId(uri)}"

    fun intent(action: String, type: String?, multiple: Boolean, data: Uri? = null): Intent = Intent(action).apply {
        component = ComponentName(context, MainActivity::class.java)
        when {
            data != null && type != null -> setDataAndType(data, type)
            data != null -> this.data = data
            type != null -> this.type = type
        }
        if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    fun launch(action: String, type: String?, multiple: Boolean, data: Uri? = null): ActivityScenario<MainActivity> =
        ActivityScenario.launchActivityForResult(intent(action, type, multiple, data))

    /** Delivers a request to the running MainActivity through onNewIntent (single top). */
    fun deliverNewIntent(action: String, type: String?, multiple: Boolean) {
        context.startActivity(intent(action, type, multiple)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun anyMainActivityAlive(): Boolean {
        var alive = false
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            alive = Stage.values().filter { it != Stage.DESTROYED }
                .any { stage -> monitor.getActivitiesInStage(stage).any { it is MainActivity } }
        }
        return alive
    }

    fun finishMainActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }.forEach { stage ->
                monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
                    .forEach { if (!it.isFinishing) it.finish() }
            }
        }
    }

    /** A third item, a JPEG, between the two PNGs in time. */
    fun jpeg(): Uri = image("ATLAS_573_PICK_3.jpg", AndroidColor.rgb(64, 140, 92),
        System.currentTimeMillis() - 30_000L, Bitmap.CompressFormat.JPEG, "image/jpeg")

    private fun png(name: String, colour: Int, takenAt: Long) {
        image(name, colour, takenAt, Bitmap.CompressFormat.PNG, "image/png")
    }

    private fun image(name: String, colour: Int, takenAt: Long, format: Bitmap.CompressFormat, mime: String): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM/")
                put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })).also { created += it }
        resolver.openOutputStream(uri)!!.use { out ->
            val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(colour)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = AndroidColor.WHITE
                canvas.drawCircle(480f, 360f, 140f, paint)
                check(bitmap.compress(format, 95, out))
            } finally {
                bitmap.recycle()
            }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
