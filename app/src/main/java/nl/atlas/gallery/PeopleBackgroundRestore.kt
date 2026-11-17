package nl.atlas.gallery

import android.content.Context
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.toFaceVisibleMedia
import java.io.File

/**
 * #43: lets a People scan worker carry on after process death, without the
 * Activity. The scope comes only from stored state that the host re-checks
 * ([PeopleDiscoveryHost.restoreForBackgroundWork]); the inventory is the
 * gallery's own cache, and only when it was written under the grant Android
 * reports now. Returns true when a run is armed.
 */
internal fun restorePeopleScanInBackground(
    context: Context,
    host: PeopleDiscoveryHost = PeopleDiscoveryHost.forApp(context),
    inventory: () -> List<FaceVisibleMedia>? = { cachedFaceInventory(context) }
): Boolean = try {
    host.restoreForBackgroundWork(inventory)
} catch (e: RuntimeException) {
    android.util.Log.w("ATLAS_FACE", "background_restore failed type=${e.javaClass.simpleName}")
    false
}

/** The gallery's on-disk catalogue, projected for faces; null unless its grant is today's. */
internal fun cachedFaceInventory(context: Context): List<FaceVisibleMedia>? {
    val app = context.applicationContext
    val file = File(app.noBackupFilesDir, GALLERY_INVENTORY_CACHE_FILE)
    val snapshot = GalleryInventoryCacheFile(file).read() ?: run {
        // #284: fixed reason codes only, never paths or media.
        android.util.Log.i("ATLAS_FACE", "background_inventory refused=" +
            if (file.isFile) "unreadable" else "missing")
        return null
    }
    if (snapshot.grant != readGalleryMediaGrant(app)) {
        android.util.Log.i("ATLAS_FACE", "background_inventory refused=grant_changed")
        return null
    }
    return snapshot.items.map { it.toFaceVisibleMedia(editorRevision = 0L) }
}

/** The file [GalleryViewModel] keeps its catalogue in (#505). */
internal const val GALLERY_INVENTORY_CACHE_FILE = "gallery-inventory.bin"
