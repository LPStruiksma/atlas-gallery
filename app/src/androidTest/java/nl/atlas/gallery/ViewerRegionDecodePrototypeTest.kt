package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * TEST-ONLY platform decoder feasibility. No GalleryViewer production change,
 * no private images, network, MediaStore mutation or Telephoto promotion.
 *
 * Region decoder uses RAW encoded coordinates. The sample JPEG has EXIF
 * orientation 1 by construction. Rotated/mirrored HEIC and actual 200MP input
 * remain independent acceptance gates; NEVER map oriented planner rectangles
 * directly to a rotated raw-region decoder without a validated transform.
 */
@RunWith(AndroidJUnit4::class)
class ViewerRegionDecodePrototypeTest {
    private fun syntheticImage(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "atlas-region-test-only.jpg")
        // 3072 x 2048 would allocate only 24 MiB temporarily. A 50/200MP
        // test must not create a huge full-frame bitmap just to test zoom.
        val image = Bitmap.createBitmap(3072, 2048, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        canvas.drawColor(Color.rgb(18, 28, 38))
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
            Color.MAGENTA, Color.CYAN)
        val paint = android.graphics.Paint().apply { isAntiAlias = false }
        colors.forEachIndexed { i, color ->
            paint.color = color
            val x = (i % 3) * 1024f
            val y = (i / 3) * 1024f
            canvas.drawRect(x, y, x + 1024f, y + 1024f, paint)
        }
        file.outputStream().use { stream ->
            assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 96, stream))
        }
        image.recycle()
        return file
    }

    @Suppress("DEPRECATION")
    private fun createDecoder(fd: ParcelFileDescriptor): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= 31)
            BitmapRegionDecoder.newInstance(fd)
        else BitmapRegionDecoder.newInstance(fd.fileDescriptor, false)

    private inline fun withRegionDecoder(
        file: File, action: (BitmapRegionDecoder) -> Unit
    ) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val decoder = createDecoder(fd)
            try {
                action(decoder)
            } finally {
                decoder.recycle()
                assertTrue(decoder.isRecycled)
            }
        }
    }

    @Test fun boundedDecodedTilesCoverSyntheticJpegAndReleaseNativeDecoder() {
        val file = syntheticImage()
        try {
            withRegionDecoder(file) { decoder ->
                assertEquals(3072, decoder.width)
                assertEquals(2048, decoder.height)
                val orientation = requireNotNull(
                    viewerOrientationFromExif(decoder.width, decoder.height, 1))
                val plan = requireNotNull(planViewerOrientedTiles(
                    orientation, viewportWidth = 1080, viewportHeight = 2400,
                    zoom = 3.5f, translationX = -420f, translationY = 125f))
                val source = ViewerTileSource(
                    "content://atlas-synthetic/disposable-jpeg", grantEpoch = 7L,
                    contentRevision = 0L)
                val requests = viewerTileRequests(source, orientation, plan)
                assertEquals(plan.rawTiles.size, requests.missing.size)
                assertEquals(0, requests.cachedVisible)
                var decodedBytes = 0L
                for (request in requests.missing) {
                    val tile = request.key.sourceRect
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = request.key.sampleSize
                    }
                    val bitmap = requireNotNull(decoder.decodeRegion(
                        Rect(tile.left, tile.top, tile.right, tile.bottom), options))
                    try {
                        assertTrue(bitmap.width > 0 && bitmap.height > 0)
                        assertTrue(bitmap.width <= 512 && bitmap.height <= 512)
                        assertTrue(bitmap.allocationByteCount <= request.maximumArgbBytes)
                        assertTrue(viewerTileMayPublish(
                            request.key, source, requests.activeVisibleKeys))
                        // A revoke/regrant or edit invalidates a successfully
                        // decoded bitmap BEFORE publication, without relying
                        // on a filename or second-granularity mtime change.
                        assertFalse(viewerTileMayPublish(
                            request.key, source.copy(grantEpoch = 8L),
                            requests.activeVisibleKeys))
                        assertFalse(viewerTileMayPublish(
                            request.key, source.copy(contentRevision = 1L),
                            requests.activeVisibleKeys))
                        decodedBytes += bitmap.allocationByteCount.toLong()
                    } finally {
                        bitmap.recycle()
                    }
                }
                assertTrue("Actual tile buffers exceed planner budget",
                    decodedBytes <= plan.estimatedPeakTileBytes)
                assertTrue(decodedBytes <= 16L * 1024 * 1024)
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test fun revokedTileCacheActuallyRecyclesNativeBitmapMemory() {
        val file = syntheticImage()
        try {
            withRegionDecoder(file) { decoder ->
                val orientation = requireNotNull(
                    viewerOrientationFromExif(decoder.width, decoder.height, 1))
                val plan = requireNotNull(planViewerOrientedTiles(
                    orientation, 1080, 2400, 2.5f, 0f, 0f))
                val source = ViewerTileSource(
                    "content://atlas-synthetic/isolated-cache", 1L, 0L)
                val demand = viewerTileRequests(source, orientation, plan)
                val request = demand.missing.first()
                val rect = request.key.sourceRect
                val cache = ViewerTileResidentCache<Bitmap> { it.recycle() }
                try {
                    cache.show(source, demand.activeVisibleKeys)
                    fun decode(): Bitmap = requireNotNull(decoder.decodeRegion(
                        Rect(rect.left, rect.top, rect.right, rect.bottom),
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inSampleSize = request.key.sampleSize
                        }))
                    val image = decode()
                    assertTrue(cache.offer(request.key, image,
                        image.allocationByteCount.toLong()))
                    assertSame(image, cache.get(request.key))
                    cache.show(null, emptySet()) // permission revoked
                    assertTrue(image.isRecycled)
                    assertEquals(0L, cache.usedBytes)
                    assertTrue(cache.residentKeys().isEmpty())
                    val lateDecode = decode()
                    assertFalse(cache.offer(request.key, lateDecode,
                        lateDecode.allocationByteCount.toLong()))
                    assertTrue(lateDecode.isRecycled)
                    // Source URI may be identical after a regrant; epoch
                    // must change, and old tile keys cannot be republished.
                    cache.show(source.copy(grantEpoch = 2L), emptySet())
                    assertNull(cache.get(request.key))
                } finally {
                    cache.close()
                }
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test fun sourceRegionCoordinatesAreRawAndPixelsAreNotSwapped() {
        val file = syntheticImage()
        try {
            withRegionDecoder(file) { decoder ->
                val cases = listOf(
                    Rect(100, 100, 356, 356) to Color.RED,
                    Rect(1124, 100, 1380, 356) to Color.GREEN,
                    Rect(2148, 100, 2404, 356) to Color.BLUE,
                    Rect(100, 1124, 356, 1380) to Color.YELLOW,
                    Rect(1124, 1124, 1380, 1380) to Color.MAGENTA,
                    Rect(2148, 1124, 2404, 1380) to Color.CYAN
                )
                for ((rectangle, expected) in cases) {
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = 2
                    }
                    val bitmap = requireNotNull(decoder.decodeRegion(rectangle, options))
                    try {
                        assertTrue(bitmap.width in 126..130)
                        assertTrue(bitmap.height in 126..130)
                        val actual = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        // JPEG is lossy: compare channels with a small margin,
                        // never exact ARGB integers.
                        assertTrue("Pixel mismatch for $rectangle",
                            kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 18 &&
                            kotlin.math.abs(Color.green(actual) - Color.green(expected)) < 18 &&
                            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) < 18)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test fun allEightExifOrientationsRenderCorrectPixelQuadrants() {
        val file = syntheticImage()
        try {
            withRegionDecoder(file) { decoder ->
                // Raw rect crosses four 1024px color blocks:
                // raw (TL,TR,BL,BR) = (red, green, yellow, magenta).
                val rawSquare = ViewerSourceRect(880, 880, 1168, 1168)
                val uprightTopLeft = listOf(
                    Color.RED, Color.GREEN, Color.MAGENTA, Color.YELLOW,
                    Color.RED, Color.YELLOW, Color.MAGENTA, Color.GREEN
                )
                for (exifTag in 1..8) {
                    val o = requireNotNull(viewerOrientationFromExif(
                        decoder.width, decoder.height, exifTag))
                    val uprightRect = requireNotNull(
                        viewerUprightRegionForRawTile(rawSquare, o))
                    val rawRect = requireNotNull(
                        viewerRawRegionForUprightTile(uprightRect, o))
                    assertEquals(rawSquare, rawRect)
                    val raw = requireNotNull(decoder.decodeRegion(
                        Rect(rawRect.left, rawRect.top, rawRect.right, rawRect.bottom),
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inSampleSize = 1
                        }))
                    try {
                        val transform = Matrix().apply {
                            postRotate(o.clockwiseDegrees.toFloat())
                            if (o.mirrorHorizontally) postScale(-1f, 1f)
                        }
                        val upright = Bitmap.createBitmap(raw, 0, 0,
                            raw.width, raw.height, transform, false)
                        try {
                            assertEquals(uprightRect.right - uprightRect.left, upright.width)
                            assertEquals(uprightRect.bottom - uprightRect.top, upright.height)
                            val pixel = upright.getPixel(upright.width / 4, upright.height / 4)
                            val expected = uprightTopLeft[exifTag - 1]
                            assertTrue("EXIF $exifTag wrong upright top-left pixel",
                                kotlin.math.abs(Color.red(pixel) - Color.red(expected)) < 18 &&
                                kotlin.math.abs(Color.green(pixel) - Color.green(expected)) < 18 &&
                                kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) < 18)
                        } finally {
                            if (upright !== raw) upright.recycle()
                        }
                    } finally {
                        raw.recycle()
                    }
                }
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test fun corruptInputFailsClosedWithoutLeakingDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "atlas-region-corrupt-only.jpg")
        file.writeBytes(byteArrayOf(0x12, 0x34, 0x56))
        try {
            try {
                withRegionDecoder(file) { fail("Invalid image must not create a decoder") }
                fail("Corrupt JPEG-like bytes should be refused")
            } catch (_: IOException) {
                // Expected: vendor/format decoder refuses the fake input.
            }
        } finally {
            assertTrue(file.delete())
        }
    }
}
