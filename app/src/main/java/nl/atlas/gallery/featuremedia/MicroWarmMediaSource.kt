package nl.atlas.gallery.featuremedia

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import nl.atlas.gallery.GalleryMediaGrant
import nl.atlas.gallery.MediaDateSource
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.mediaOrientationDegreesOrUnknown
import nl.atlas.gallery.mediaPixelDimensionOrUnknown
import nl.atlas.gallery.requireMediaCatalogueCursor
import nl.atlas.gallery.requireMediaStoreItemId
import nl.atlas.gallery.requireMountedMediaVolume
import nl.atlas.gallery.resolveMediaDate
import nl.atlas.gallery.sameMediaProviderScanSnapshot
import java.io.Closeable
import java.io.File
import java.time.ZoneId

/**
 * Charging-only, app-private external-memory MediaStore order.
 *
 * The gallery's live catalogue stays in the UI; this worker streams each
 * provider cursor directly into a temporary SQLite sort, not a second List.
 * SQLite indexes reproduce GalleryRepository's resolved capture-date order,
 * including filename dates and image-before-video stable ties. Each reader
 * materializes at most 128 MediaRecord objects at a time. No URI is logged,
 * exported, or placed in WorkManager Data. The stage is deleted on close.
 */
internal class MicroWarmMediaSource(
    context: Context,
    private val grant: GalleryMediaGrant,
    private val store: MicroThumbnailStore
) : Closeable {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val dbFile = File(app.cacheDir, "atlas-micro-warm-stage.db")
    private val db: SQLiteDatabase
    private var completed = false
    var totalItems: Int = 0
        private set

    init {
        // Unique WorkManager name means only one deferred worker owns the
        // stage. A killed earlier process may have left a stale temp file.
        if (dbFile.exists()) SQLiteDatabase.deleteDatabase(dbFile)
        db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("""CREATE TABLE media (
            row_id INTEGER PRIMARY KEY, item_id INTEGER NOT NULL,
            item_uri TEXT NOT NULL, name TEXT NOT NULL, folder TEXT NOT NULL,
            size INTEGER, taken INTEGER NOT NULL, video INTEGER, duration INTEGER,
            modified INTEGER, volume TEXT, version TEXT, generation INTEGER,
            width INTEGER, height INTEGER, orientation INTEGER,
            favorite INTEGER, fav_known INTEGER, source TEXT,
            cache_path TEXT
        )""")
    }

    /** False = a constraint or grant changed; never warm a partial scan. */
    fun scan(signal: CancellationSignal, gate: () -> Boolean): Boolean {
        check(!completed)
        if (!gate()) return false
        val versions = providerVersions()
        val generations = providerGenerations(versions.keys)
        val zone = ZoneId.systemDefault()
        db.beginTransaction()
        try {
            if (grant.images && !scanKind(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false,
                    versions, zone, signal, gate)) return false
            if (grant.videos && !scanKind(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true,
                    versions, zone, signal, gate)) return false
            if (!gate() || !sameMediaProviderScanSnapshot(
                    versions, providerVersions(),
                    generations, providerGenerations(versions.keys))) return false
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // ORDER BY taken, not DATE_ADDED. This correctly sorts filename-based
        // screenshots together with JPEG/video rows across both collections.
        db.execSQL("CREATE INDEX micro_warm_order ON media(taken DESC, row_id ASC)")
        db.execSQL("CREATE INDEX micro_warm_paths ON media(cache_path)")
        completed = true
        return gate()
    }

    private fun providerVersions(): Map<String, String?> =
        MediaStore.getExternalVolumeNames(app).associateWith { volume ->
            MediaStore.getVersion(app, volume).also {
                check(!it.isNullOrBlank()) { "Media provider version unavailable" }
            }
        }

    private fun providerGenerations(volumes: Set<String>): Map<String, Long?> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            volumes.associateWith { MediaStore.getGeneration(app, it) }
        else emptyMap()

    private fun scanKind(
        collection: android.net.Uri, video: Boolean,
        versions: Map<String, String?>,
        zone: ZoneId, signal: CancellationSignal, gate: () -> Boolean
    ): Boolean {
        if (!gate()) return false
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.ORIENTATION,
            "datetaken"
        )
        if (video) columns += MediaStore.Video.Media.DURATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            columns += MediaStore.MediaColumns.GENERATION_MODIFIED
            columns += MediaStore.MediaColumns.IS_FAVORITE
        }
        val specific = versions.keys.associateWith { volume ->
            if (video) MediaStore.Video.Media.getContentUri(volume)
            else MediaStore.Images.Media.getContentUri(volume)
        }
        requireMediaCatalogueCursor(resolver.query(
            collection, columns.toTypedArray(), null, null,
            MediaStore.MediaColumns.DATE_ADDED + " DESC", signal
        )).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val takenIdx = cursor.getColumnIndex("datetaken")
            val volumeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val orientationIdx = cursor.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
            val genIdx = cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED)
            val favIdx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
            val durationIdx = if (video) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
            while (cursor.moveToNext()) {
                if (!gate()) return false
                val id = requireMediaStoreItemId(cursor.getLong(idIdx))
                val name = cursor.textOr(nameIdx)
                val modified = cursor.numberOr(modIdx)
                val volume = requireMountedMediaVolume(
                    cursor.textOr(volumeIdx), versions.keys)
                val itemCollection = specific[volume]
                    ?: throw IllegalStateException("Media volume missing")
                val date = resolveMediaDate(cursor.numberOr(takenIdx), name,
                    modified, cursor.numberOr(addedIdx), zone)
                val hasOrientation = orientationIdx >= 0 && !cursor.isNull(orientationIdx)
                val media = MediaRecord(
                    id = id, uri = ContentUris.withAppendedId(itemCollection, id).toString(),
                    name = name, relativePath = cursor.textOr(pathIdx),
                    sizeBytes = cursor.numberOr(sizeIdx), takenAtMillis = date.millis,
                    isVideo = video, durationMillis = cursor.numberOr(durationIdx),
                    modifiedAtSeconds = modified, volumeName = volume,
                    providerVersion = versions[volume],
                    generationModified = cursor.numberOr(genIdx),
                    widthPixels = mediaPixelDimensionOrUnknown(cursor.numberOr(widthIdx)),
                    heightPixels = mediaPixelDimensionOrUnknown(cursor.numberOr(heightIdx)),
                    orientationDegrees = mediaOrientationDegreesOrUnknown(
                        cursor.numberOr(orientationIdx), hasOrientation),
                    isFavorite = cursor.numberOr(favIdx) != 0L,
                    favoriteKnown = favIdx >= 0 && !cursor.isNull(favIdx),
                    dateSource = date.source
                )
                val key = microThumbnailDiskKey(media)
                val row = ContentValues(20).apply {
                    put("row_id", totalItems + 1)
                    put("item_id", media.id)
                    put("item_uri", media.uri)
                    put("name", media.name)
                    put("folder", media.relativePath)
                    put("size", media.sizeBytes)
                    put("taken", media.takenAtMillis)
                    put("video", if (video) 1 else 0)
                    put("duration", media.durationMillis)
                    put("modified", media.modifiedAtSeconds)
                    put("volume", media.volumeName)
                    put("version", media.providerVersion)
                    put("generation", media.generationModified)
                    put("width", media.widthPixels)
                    put("height", media.heightPixels)
                    put("orientation", media.orientationDegrees)
                    put("favorite", if (media.isFavorite) 1 else 0)
                    put("fav_known", if (media.favoriteKnown) 1 else 0)
                    put("source", media.dateSource.name)
                    put("cache_path", key?.let { store.fileFor(it).path })
                }
                db.insertOrThrow("media", null, row)
                totalItems++
            }
        }
        return gate()
    }

    private fun Cursor.textOr(index: Int): String =
        if (index < 0 || isNull(index)) "" else getString(index)

    private fun Cursor.numberOr(index: Int): Long =
        if (index < 0 || isNull(index)) 0L else getLong(index)

    fun openNewest(): MicroWarmPageReader = open("taken DESC, row_id ASC")
    fun openOldest(): MicroWarmPageReader = open("taken ASC, row_id DESC")

    private fun open(order: String): MicroWarmPageReader {
        check(completed) { "Partial micro warm catalogue" }
        // Android CursorWindow pages the SQLite result; nextPage never holds
        // more than 128 reconstructed records even for a 250k-row library.
        val cursor = db.rawQuery("""SELECT item_id,item_uri,name,folder,size,taken,
            video,duration,modified,volume,version,generation,width,height,
            orientation,favorite,fav_known,source FROM media ORDER BY """ + order, null)
        return object : MicroWarmPageReader {
            override fun nextPage(): List<MediaRecord> {
                val result = ArrayList<MediaRecord>(MICRO_WARM_PAGE_SIZE)
                while (result.size < MICRO_WARM_PAGE_SIZE && cursor.moveToNext()) {
                    result += MediaRecord(
                        id = cursor.getLong(0), uri = cursor.getString(1),
                        name = cursor.getString(2), relativePath = cursor.getString(3),
                        sizeBytes = cursor.getLong(4), takenAtMillis = cursor.getLong(5),
                        isVideo = cursor.getInt(6) != 0,
                        durationMillis = cursor.getLong(7),
                        modifiedAtSeconds = cursor.getLong(8),
                        volumeName = cursor.getString(9),
                        providerVersion = cursor.getString(10),
                        generationModified = cursor.getLong(11),
                        widthPixels = cursor.getInt(12), heightPixels = cursor.getInt(13),
                        orientationDegrees = cursor.getInt(14),
                        isFavorite = cursor.getInt(15) != 0,
                        favoriteKnown = cursor.getInt(16) != 0,
                        dateSource = MediaDateSource.valueOf(cursor.getString(17))
                    )
                }
                return result
            }
            override fun close() = cursor.close()
        }
    }

    /**
     * Reclaim only files proven absent from the COMPLETE staged inventory.
     * A partial media-type grant must not erase valid files from the other
     * type; a constraint change immediately aborts this cleanup.
     */
    fun sweepOrphans(store: MicroThumbnailStore, gate: () -> Boolean): Boolean {
        if (!completed || !grant.images || !grant.videos || !gate()) return false
        val lookup = db.compileStatement("SELECT COUNT(*) FROM media WHERE cache_path=?")
        var deleted = false
        try {
            for (folder in store.root.listFiles().orEmpty()) {
                if (!gate()) return false
                for (file in folder.listFiles().orEmpty()) {
                    if (!gate()) return false
                    if (!file.isFile || !file.name.endsWith(".jpg")) continue
                    lookup.clearBindings()
                    lookup.bindString(1, file.path)
                    if (lookup.simpleQueryForLong() == 0L && file.delete()) deleted = true
                }
            }
        } finally {
            lookup.close()
            // The store's accounting is opaque; a scan is needed after
            // deleting by file path, and cannot be replaced by page retainOnly.
            if (deleted) store.scan()
        }
        return true
    }

    override fun close() {
        db.close()
        SQLiteDatabase.deleteDatabase(dbFile)
    }
}
