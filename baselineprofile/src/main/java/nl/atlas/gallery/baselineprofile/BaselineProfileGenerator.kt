package nl.atlas.gallery.baselineprofile

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Release-candidate-only profile journey over disposable, generated MediaStore pixels. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.context.contentResolver
    private val inserted = mutableListOf<android.net.Uri>()

    @Before
    fun insertSyntheticGallery() {
        assertTrue("Baseline profiles must use an emulator", isEmulator())
        repeat(FIXTURE_COUNT) { index ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "atlas-profile-$index.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, FIXTURE_FOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, 1_700_000_000_000L - index * 60_000L)
            }
            val uri = checkNotNull(
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            )
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb((index * 37) % 255, (index * 67) % 255, (index * 97) % 255))
            }
            resolver.openOutputStream(uri).use { output ->
                checkNotNull(output)
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output))
            }
            bitmap.recycle()
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            inserted += uri
        }
        primeCachedGrid()
    }

    @After
    fun removeSyntheticGallery() {
        inserted.forEach { resolver.delete(it, null, null) }
    }

    /**
     * Populates ATLAS's inventory cache outside profile capture (BaselineProfileRule has no
     * setup block; JUnit leaves the order of several @Before methods open, so the fixture
     * insert calls this last), so the captured journey is the cached-grid start the owner sees daily.
     */
    private fun primeCachedGrid() {
        val device = UiDevice.getInstance(instrumentation)
        if (Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.READ_MEDIA_IMAGES")
        }
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/.MainActivity")
        check(device.wait(Until.hasObject(By.textContains("photos")), UI_TIMEOUT_MS)) {
            "ATLAS did not show its photo header after the warm-up launch"
        }
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
    }

    @Test
    fun cachedGridColdStartAndFling() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.scrollable(true)), UI_TIMEOUT_MS))
        val grid = checkNotNull(device.findObject(By.scrollable(true)))
        // Leave the gesture margins clear so a fling is never read as a system back/home swipe.
        grid.setGestureMargin(device.displayWidth / 5)
        repeat(FLING_COUNT) { grid.fling(Direction.DOWN) }
        device.waitForIdle()
    }

    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)

    private companion object {
        const val TARGET_PACKAGE = "nl.atlas.gallery"
        const val FIXTURE_FOLDER = "Pictures/ATLAS-Baseline-Profile/"
        const val FIXTURE_COUNT = 120
        const val FLING_COUNT = 6
        const val UI_TIMEOUT_MS = 30_000L
    }
}
