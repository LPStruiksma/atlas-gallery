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
