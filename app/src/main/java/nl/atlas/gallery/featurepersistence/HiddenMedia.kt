package nl.atlas.gallery.featurepersistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import nl.atlas.gallery.MediaRecord

/**
 * HIDE-1 (#585): the app-level Hidden set. A hidden item is only a stored media
 * key; no file is moved, renamed, encrypted or changed in MediaStore, and other
 * apps still see the file. This is a presentation filter, not protection.
 *
 * One shared predicate, applied once at the catalogue source in MainActivity,
 * so the timeline, albums, every search, People covers, Stories, Photo DNA and
 * Declutter all receive the same already-filtered list.
 */
object HiddenMedia {
    /** Stable across index rebuilds: MediaStore volume + row id, never a path or name. */
    fun keyOf(record: MediaRecord): String = "${record.volumeName}/${record.id}"

    fun isVisibleToAtlas(record: MediaRecord, hidden: Set<String>): Boolean =
        hidden.isEmpty() || keyOf(record) !in hidden

    /**
     * Items ATLAS may show. Returns [items] itself when nothing is hidden or no
     * item matches, because callers compare catalogue identity (`===`).
     */
    fun visible(items: List<MediaRecord>, hidden: Set<String>): List<MediaRecord> {
        if (hidden.isEmpty() || items.isEmpty()) return items
        if (items.none { keyOf(it) in hidden }) return items
        return items.filterNot { keyOf(it) in hidden }
    }

    /** The inverse set, for the Hidden collection only. */
    fun hiddenOnly(items: List<MediaRecord>, hidden: Set<String>): List<MediaRecord> =
        if (hidden.isEmpty()) emptyList() else items.filter { keyOf(it) in hidden }

    /** Idempotent membership transitions; the same key twice never duplicates. */
    fun withHidden(current: Set<String>, keys: Collection<String>): Set<String> = current + keys
    /**
     * The keys an operation would really change: hiding skips keys already
     * hidden, unhiding skips keys not hidden. Results and Undo cover only these.
     */
    fun delta(current: Set<String>, keys: Collection<String>, hide: Boolean): List<String> =
        keys.distinct().filter { (it in current) != hide }

    /**
     * Of [changed] (what an operation committed), the keys still in the state
     * that operation produced. A later transition of a key fences it out of Undo.
     */
    fun stillUndoable(current: Set<String>, changed: Collection<String>, hide: Boolean): List<String> =
        changed.filter { (it in current) == hide }

    fun withoutHidden(current: Set<String>, keys: Collection<String>): Set<String> = current - keys.toSet()
}

/**
 * Tiny versioned private DB (same style as [CurationKeepStore]). Writes are one
 * atomic transaction: all keys commit or none do. Reads that fail return null,
 * never an empty set, so a read error can not reveal hidden media.
 */
class HiddenMediaStore(context: Context) :
    SQLiteOpenHelper(context, "atlas_hidden_media.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE hidden_media(media_key TEXT PRIMARY KEY NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported Hidden schema; not deleting saved choices")
    }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported Hidden downgrade; data preserved")
    }

    /** Blocking; IO only. Null when the set could not be read. */
    fun readOrNull(): Set<String>? = try {
        val keys = LinkedHashSet<String>()
        readableDatabase.rawQuery("SELECT media_key FROM hidden_media", null).use { c ->
            while (c.moveToNext()) keys += c.getString(0)
        }
        keys
    } catch (_: SQLiteException) { null }

    /** Blocking; IO only. True only after the whole batch committed. */
    fun hide(keys: Collection<String>): Boolean = write { db ->
        keys.forEach { db.insertWithOnConflict("hidden_media", null,
            ContentValues().apply { put("media_key", it) }, SQLiteDatabase.CONFLICT_IGNORE) }
    }

    fun unhide(keys: Collection<String>): Boolean = write { db ->
        keys.forEach { db.delete("hidden_media", "media_key=?", arrayOf(it)) }
    }

    private fun write(block: (SQLiteDatabase) -> Unit): Boolean = try {
        val db = writableDatabase
        db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        true
    } catch (_: SQLiteException) { false }
}
