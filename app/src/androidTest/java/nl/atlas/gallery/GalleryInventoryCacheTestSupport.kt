package nl.atlas.gallery

import android.content.Context
import java.io.File

/**
 * #505: journey tests insert their own fixtures before launch. Drop the
 * cold-start catalogue a previous test left behind, so no test sees another
 * test's (already deleted) fixtures before its own first scan lands.
 */
internal fun clearGalleryInventoryCacheForTest(context: Context) {
    File(context.noBackupFilesDir, "gallery-inventory.bin").delete()
    File(context.noBackupFilesDir, "gallery-inventory.bin.tmp").delete()
}

/**
 * #703: the images a gallery scan can see, by display name. A test that
 * calls [assertLibraryIsExactly] after inserting its fixtures proves that no
 * earlier test (or UI-evidence screenshot) leaked into its library.
 */
internal fun mediaStoreImageNamesForTest(context: Context): List<String> =
    context.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
        ?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0).orEmpty()) } }
        .orEmpty()
        .sorted()

internal fun assertLibraryIsExactly(context: Context, fixtures: Collection<String>) {
    val names = mediaStoreImageNamesForTest(context)
    check(names == fixtures.sorted()) {
        "Library is not this test's fixtures. Expected ${fixtures.sorted()}, " +
            "MediaStore has $names (extra ${names - fixtures.toSet()}, " +
            "missing ${fixtures - names.toSet()})"
    }
}
