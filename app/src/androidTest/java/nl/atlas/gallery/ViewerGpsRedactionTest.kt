package nl.atlas.gallery

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * PLACES-0 (#579): does GPS reach ATLAS without ACCESS_MEDIA_LOCATION?
 *
 * Synthetic only. A generated JPEG carrying a fictional EXIF coordinate is published to
 * MediaStore by the shell (package com.android.shell), a package other than ATLAS, then read
 * through the production [PlatformViewerMetadataReader]. Android 10+ redacts location from
 * files owned by other apps for callers without ACCESS_MEDIA_LOCATION; this test asserts that
 * exact outcome and fails with the observed values otherwise. The control read of the same
 * bytes from memory proves the fixture really carries the coordinate and the reader decodes it.
 * The manifest is not touched: ATLAS holds READ_MEDIA_IMAGES but not ACCESS_MEDIA_LOCATION.
 */
@RunWith(AndroidJUnit4::class)
class ViewerGpsRedactionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val displayName = "atlas_places0_${UUID.randomUUID().toString().take(8)}.jpg"
    private val fixtureLat = 52.0907
    private val fixtureLon = 5.1214
    private var insertedUri: Uri? = null

    @After fun cleanUp() {
        // Shared fixture: remove it so MediaStore counts used by other tests stay unchanged.
        shell("content delete --uri content://media/external/images/media " +
            "--where \"_display_name='$displayName'\"")
    }

    @Test fun foreignOwnedGpsIsRedactedForAtlasWithoutAccessMediaLocation() {
        assumeTrue("Android 10+ (API 29) redaction rules", Build.VERSION.SDK_INT >= 29)
        // Attaching a permission grant here would defeat the question; assert the premise.
        assertNotEquals("ATLAS must not hold ACCESS_MEDIA_LOCATION in this unit",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            target.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION))
        val jpeg = syntheticGeotaggedJpeg()

        // Control: the reader decodes the fixture's coordinate when nothing redacts it.
        val control = jpeg.inputStream().use(::readViewerExif)
        assertEquals(fixtureLat, control.latitude!!, 0.001)
        assertEquals(fixtureLon, control.longitude!!, 0.001)

        val uri = publishAsShell(jpeg)
        // OWNER_PACKAGE_NAME is not returned to other apps (observed null on API 35), so
        // foreign ownership rests on the insert and write being done by the shell uid.
        val owner = "com.android.shell (inserted via shell)"

        val record = MediaRecord(
            ContentUris.parseId(uri), uri.toString(), displayName, "Pictures/AtlasPlaces0/",
            sizeBytes = jpeg.size.toLong(), takenAtMillis = 1_000_000_000_000L, isVideo = false,
            modifiedAtSeconds = 1L)
        val observed = runBlocking { PlatformViewerMetadataReader.read(target, record) }
        assertNotNull("the reader must still open the foreign-owned image (READ_MEDIA_IMAGES held)," +
            " owner=$owner api=${Build.VERSION.SDK_INT}", observed)
        // The outcome PLACES-0 is after: the fixture's coordinate never reaches ATLAS.
        // Observed on API 35: the platform zeroes the GPS values, so the reader reports
        // 0.0/0.0 ("Null Island") rather than null. Accept null or exactly zero, never the fixture.
        val detail = "lat=${observed!!.latitude} lon=${observed.longitude} owner=$owner " +
            "api=${Build.VERSION.SDK_INT} build=${Build.FINGERPRINT}"
        assertTrue("latitude must be redacted (null or 0.0); observed $detail",
            observed.latitude == null || observed.latitude == 0.0)
        assertTrue("longitude must be redacted (null or 0.0); observed $detail",
            observed.longitude == null || observed.longitude == 0.0)
        // Other EXIF stays readable, proving the redaction is selective, not a failed read.
        assertEquals("AtlasPlaces0", observed.cameraMake)

        // Requesting the original requires ACCESS_MEDIA_LOCATION; without it the open must fail.
        val original = MediaStore.setRequireOriginal(uri)
        val originalOpen = runCatching { target.contentResolver.openInputStream(original)?.close() }
        assertTrue("setRequireOriginal without ACCESS_MEDIA_LOCATION must be refused",
            originalOpen.isFailure)
    }

    private fun syntheticGeotaggedJpeg(): ByteArray {
        val file = File(target.cacheDir, "places0-$displayName")
        try {
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(40, 90, 160))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_MAKE, "AtlasPlaces0")
                // 52.0907 = 52 deg 5' 26.52" N ; 5.1214 = 5 deg 7' 16.92" E (platform ExifInterface has no setLatLong)
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,5/1,2652/100")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "5/1,7/1,1692/100")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** Insert row and write bytes as the shell uid, so the owner is not ATLAS. */
    private fun publishAsShell(bytes: ByteArray): Uri {
        shell("content insert --uri content://media/external/images/media " +
            "--bind _display_name:s:$displayName --bind mime_type:s:image/jpeg " +
            "--bind relative_path:s:Pictures/AtlasPlaces0 --bind is_pending:i:0")
        val id = queryId() ?: error("shell insert did not create $displayName")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val fds = instrumentation.uiAutomation.executeShellCommandRw(
            "content write --uri $uri")
        // executeShellCommandRw returns [stdout (stderr merged), stdin].
        ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes() }
        insertedUri = uri
        return uri
    }

    private fun queryId(): Long? = target.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME}=?", arrayOf(displayName), null
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }
}
