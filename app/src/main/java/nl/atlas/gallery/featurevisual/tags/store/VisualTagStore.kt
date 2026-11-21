package nl.atlas.gallery.featurevisual.tags.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/**
 * TAGS-1 (#732): app-private tag rows, in their own database next to
 * `atlas_visual_index.db` (never backed up: derived data, rebuilt by the
 * sweep). One `items` row per scored photo or video moment, keyed like the
 * visual index (media key, frame; -1 for a photo) with the revision it was
 * scored at; its tags in `hits` by item rowid and a small `tags` dictionary
 * id (so a hit costs a few bytes, not a repeated media key), cascade-deleted
 * with the item. `meta` holds the
 * fence (`TagMatrix.fence`, which includes the vector version) and the sweep
 * position. A new fence deletes everything.
 *
 * All public calls are serialised, like VisualIndexStore's.
 */
class VisualTagStore(context: Context, name: String = DB_NAME) :
    SQLiteOpenHelper(context, name, null, SCHEMA_VERSION), VisualTagStorePort {

    companion object {
        const val DB_NAME = "atlas_visual_tags.db"
        const val SCHEMA_VERSION = 1
        /** Scope keys per mediaForTag query (SQLite's bound-parameter limit is 999 before 3.32). */
        const val SCOPE_CHUNK = 500
        private const val AFTER = "(media_key>? OR (media_key=? AND frame_ms>?))"
        private const val UPTO = "(media_key<? OR (media_key=? AND frame_ms<=?))"
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE meta(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            fence TEXT, sweep_key TEXT, sweep_frame INTEGER, sweep_done_at INTEGER)""")
        db.execSQL("INSERT INTO meta(singleton) VALUES(1)")
        db.execSQL("""CREATE TABLE items(
            id INTEGER PRIMARY KEY,
            media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL, revision TEXT NOT NULL,
            UNIQUE(media_key,frame_ms))""")
        db.execSQL("CREATE TABLE tags(id INTEGER PRIMARY KEY, tag_id TEXT NOT NULL UNIQUE)")
        db.execSQL("""CREATE TABLE hits(
            item INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
            tag INTEGER NOT NULL REFERENCES tags(id),
            margin REAL NOT NULL,
            PRIMARY KEY(item,tag)) WITHOUT ROWID""")
        db.execSQL("CREATE INDEX hits_by_tag ON hits(tag,margin)")
    }

    /** Derived data with no upgrade path yet: refuse instead of guessing. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported visual tag upgrade: $oldVersion -> $newVersion")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported visual tag downgrade")
    }

    private inline fun <T> write(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun afterArgs(k: TagRowKey) = arrayOf(k.mediaKey, k.mediaKey, k.frameMs.toString())

    /** WHERE clause and args for keys in (after, last]; either bound may be open. */
    private fun range(after: TagRowKey?, last: TagRowKey?): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        after?.let { parts += AFTER; args += afterArgs(it) }
        last?.let { parts += UPTO; args += afterArgs(it) }
        return (if (parts.isEmpty()) "1" else parts.joinToString(" AND ")) to args.toTypedArray()
    }

    private fun wipe(db: SQLiteDatabase) {
        db.delete("hits", null, null)
        db.delete("items", null, null)
        db.execSQL("UPDATE meta SET sweep_key=NULL, sweep_frame=NULL, sweep_done_at=NULL WHERE singleton=1")
    }

    @Synchronized override fun useFence(fence: String): Boolean {
        require(fence.isNotBlank())
        val current = readableDatabase.rawQuery("SELECT fence FROM meta WHERE singleton=1", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (current == fence) return false
        write { db ->
            wipe(db)
            db.execSQL("UPDATE meta SET fence=? WHERE singleton=1", arrayOf(fence))
        }
        return current != null
    }

    @Synchronized override fun sweepState(): TagSweepState =
        readableDatabase.rawQuery("SELECT sweep_key, sweep_frame, sweep_done_at FROM meta WHERE singleton=1", null)
            .use { c ->
                check(c.moveToFirst())
                val after = if (c.isNull(0)) null else TagRowKey(c.getString(0), c.getLong(1))
                TagSweepState(after, if (c.isNull(2)) null else c.getLong(2))
            }

    @Synchronized override fun revisionsIn(after: TagRowKey?, last: TagRowKey): Map<TagRowKey, String> {
        val (where, args) = range(after, last)
        return readableDatabase.rawQuery("SELECT media_key, frame_ms, revision FROM items WHERE $where", args)
            .use { c ->
                val out = HashMap<TagRowKey, String>()
                while (c.moveToNext()) out[TagRowKey(c.getString(0), c.getLong(1))] = c.getString(2)
                out
            }
    }

    private val tagIds = HashMap<String, Long>()

    private fun tagId(db: SQLiteDatabase, id: String): Long = tagIds.getOrPut(id) {
        db.rawQuery("SELECT id FROM tags WHERE tag_id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: db.insertOrThrow("tags", null, ContentValues().apply { put("tag_id", id) })
    }

    private fun upsert(db: SQLiteDatabase, rows: List<TaggedRow>) {
        for (row in rows) {
            val keyArgs = arrayOf(row.key.mediaKey, row.key.frameMs.toString())
            // Replacing the item cascades its old hits away.
            db.delete("items", "media_key=? AND frame_ms=?", keyArgs)
            val item = db.insertOrThrow("items", null, ContentValues().apply {
                put("media_key", row.key.mediaKey); put("frame_ms", row.key.frameMs); put("revision", row.revision)
            })
            for (hit in row.hits) {
                db.insertOrThrow("hits", null, ContentValues().apply {
                    put("item", item); put("tag", tagId(db, hit.id)); put("margin", hit.margin)
                })
            }
        }
    }

    @Synchronized override fun applySweepPage(
        after: TagRowKey?, last: TagRowKey, present: Set<TagRowKey>, rows: List<TaggedRow>,
    ) = write { db ->
        val stale = run {
            val (where, args) = range(after, last)
            db.rawQuery("SELECT media_key, frame_ms FROM items WHERE $where", args).use { c ->
                val out = ArrayList<TagRowKey>()
                while (c.moveToNext()) TagRowKey(c.getString(0), c.getLong(1)).let { if (it !in present) out += it }
                out
            }
        }
        for (k in stale) db.delete("items", "media_key=? AND frame_ms=?", arrayOf(k.mediaKey, k.frameMs.toString()))
        upsert(db, rows)
        db.execSQL("UPDATE meta SET sweep_key=?, sweep_frame=? WHERE singleton=1",
            arrayOf<Any>(last.mediaKey, last.frameMs))
    }

    @Synchronized override fun rowsAfter(after: TagRowKey?): Int {
        val (where, args) = range(after, null)
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE $where", args)
            .use { check(it.moveToFirst()); it.getInt(0) }
    }

    @Synchronized override fun finishSweep(after: TagRowKey?, nowMs: Long) = write { db ->
        val (where, args) = range(after, null)
        db.delete("items", where, args)
        db.execSQL("UPDATE meta SET sweep_key=NULL, sweep_frame=NULL, sweep_done_at=? WHERE singleton=1",
            arrayOf<Any>(nowMs))
    }

    @Synchronized override fun restartSweep() {
        writableDatabase.execSQL(
            "UPDATE meta SET sweep_key=NULL, sweep_frame=NULL, sweep_done_at=NULL WHERE singleton=1")
    }

    @Synchronized override fun putRows(rows: List<TaggedRow>, replaceMedia: Set<String>) = write { db ->
        for (key in replaceMedia) db.delete("items", "media_key=?", arrayOf(key))
        upsert(db, rows)
    }

    @Synchronized override fun tagsFor(mediaKey: String, revision: String?): List<StoredTag> {
        val sql = """SELECT t.tag_id, MAX(h.margin) m FROM items i JOIN hits h ON h.item=i.id
            JOIN tags t ON t.id=h.tag
            WHERE i.media_key=?${if (revision != null) " AND i.revision=?" else ""}
            GROUP BY t.tag_id ORDER BY m DESC, t.tag_id"""
        val args = if (revision != null) arrayOf(mediaKey, revision) else arrayOf(mediaKey)
        return readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<StoredTag>()
            while (c.moveToNext()) out += StoredTag(c.getString(0), c.getFloat(1))
            out
        }
    }

    /**
     * The scope is applied in SQL before any limit: a [TagScope.Media] scope
     * is looked up in chunks of [SCOPE_CHUNK] keys (each chunk's own top
     * [limit]), then merged, so a scoped match is found however many better
     * matches lie outside the scope. [TagScope.Library] honours [limit] as is.
     */
    @Synchronized override fun mediaForTag(tagId: String, scope: TagScope, limit: Int): List<String> {
        require(limit >= 1)
        val keys = (scope as? TagScope.Media)?.keys
        if (keys != null && keys.isEmpty()) return emptyList()
        val db = readableDatabase
        val tag = db.rawQuery("SELECT id FROM tags WHERE tag_id=?", arrayOf(tagId))
            .use { if (it.moveToFirst()) it.getLong(0) else null } ?: return emptyList()
        fun query(filter: List<String>?): List<Pair<String, Float>> {
            val inClause = filter?.let { " AND i.media_key IN (" + it.joinToString(",") { "?" } + ")" } ?: ""
            val args = buildList { add(tag.toString()); filter?.let { addAll(it) }; add(limit.toString()) }
            return db.rawQuery("""SELECT i.media_key, MAX(h.margin) m FROM hits h
                JOIN items i ON i.id=h.item
                WHERE h.tag=?$inClause
                GROUP BY i.media_key ORDER BY m DESC, i.media_key LIMIT ?""", args.toTypedArray()).use { c ->
                val out = ArrayList<Pair<String, Float>>()
                while (c.moveToNext()) out += c.getString(0) to c.getFloat(1)
                out
            }
        }
        if (keys == null) return query(null).map { it.first }
        return keys.sorted().chunked(SCOPE_CHUNK).flatMap { query(it) }
            .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
            .take(limit).map { it.first }
    }

    @Synchronized override fun counts(): TagStoreCounts {
        val db = readableDatabase
        fun count(sql: String) = db.rawQuery(sql, null).use { check(it.moveToFirst()); it.getInt(0) }
        return TagStoreCounts(
            rows = count("SELECT COUNT(*) FROM items"),
            hits = count("SELECT COUNT(*) FROM hits"),
            taggedMedia = count("SELECT COUNT(DISTINCT i.media_key) FROM hits h JOIN items i ON i.id=h.item"),
        )
    }

    @Synchronized override fun deleteAll() = write { db ->
        wipe(db)
        db.execSQL("UPDATE meta SET fence=NULL WHERE singleton=1")
    }
}
