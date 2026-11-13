package nl.atlas.gallery.featurevisual.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import nl.atlas.gallery.featurevisual.VisualScanIndexPort

/**
 * A MediaStore-scoped identity, never a filesystem path. A null frame is the
 * source item (a photo, or a whole video as one unit of work); a keyframe of a
 * video has its position in [frameMs] (>= 0).
 */
data class VisualMedia(val key: String, val revision: String, val frameMs: Long? = null)

/** The token is opaque, single-use and bound to a grant, revision and model. */
data class VisualIndexLease(
    val media: VisualMedia,
    val modelVersion: String,
    val dimension: Int,
    val permissionEpoch: String,
    val token: String
)
data class VisualVectorWrite(val lease: VisualIndexLease, val vector: FloatArray)

/** VS-V2: one embedded keyframe of a video, at [frameMs] (>= 0). */
class VisualMoment(val frameMs: Long, val vector: FloatArray)

/**
 * VS-V2: all moments of the video leased by [lease] (a source row, null
 * frame). They replace every earlier moment of that video atomically.
 */
class VisualMomentsWrite(val lease: VisualIndexLease, val moments: List<VisualMoment>)

/**
 * Index progress of the ACTIVE epoch, in source items (a video counts once,
 * however many moments it has). [skipped] items could not be read (#480):
 * they hold no vector and are not retried until their revision or the model changes.
 */
data class VisualIndexProgress(
    val queued: Int, val running: Int, val indexed: Int, val skipped: Int
) {
    val total: Int get() = queued + running + indexed + skipped
    val done: Int get() = indexed + skipped
}
data class VisualVectorCursor(
    val permissionEpoch: String, val modelVersion: String,
    val mediaKey: String, val frameMs: Long?
)
data class VisualIndexedVector(
    val media: VisualMedia, val modelVersion: String,
    val vector: FloatArray, val cursor: VisualVectorCursor
)

/**
 * App-private semantic index. Caller owns Android grant checking and provides
 * fresh, single-use permission epochs; a database reopen NEVER reinstates a
 * saved grant. All public APIs are serialised against synchronous revocation.
 *
 * One catalogue row per image or keyframe, with source revision and an int8
 * embedding stored separately. A model/dimension bump clears all vectors;
 * an unchanged source/model survives a new authorised snapshot. Leases fence
 * late worker writes, and every completion batch commits atomically.
 *
 * VS-V2: a video is ONE catalogue row (null frame) that is leased like a
 * photo; its embedded keyframes live in `moments`, keyed by frame_ms and
 * cascade-deleted with that row, so a changed revision drops all of them in
 * the commit that re-queues the video, and a completion replaces all of them
 * in one transaction. [loadActiveVectors] serves photos and moments together.
 * `failures` counts unexpected failures per item (#482 follow-up).
 */
class VisualIndexStore(context: Context) :
    SQLiteOpenHelper(context, "atlas_visual_index.db", null, SCHEMA_VERSION),
    VisualScanIndexPort {
    companion object {
        /**
         * v2 (VS-S1c): `skips` records unreadable items per revision and model (#480).
         * v3 (VS-V2): `moments` holds video keyframe vectors; `failures` counts
         * unexpected failures per item and model (#482 follow-up).
         */
        const val SCHEMA_VERSION = 3
        const val MAX_MEDIA = 500_000
        const val MAX_BATCH = 1024
        const val DEFAULT_DIMENSION = 768
        /** VideoKeyframePlan.MAX_FRAMES_PER_VIDEO. */
        const val MAX_MOMENTS_PER_VIDEO = 60
        private const val IMAGE_FRAME = -1L
        private val ORDER = compareBy<VisualVectorCursor> { it.mediaKey }
            .thenBy { it.frameMs ?: IMAGE_FRAME }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE control(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            active_epoch TEXT, staging_epoch TEXT,
            model_version TEXT, dimension INTEGER, staged_count INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("INSERT INTO control(singleton) VALUES(1)")
        db.execSQL("CREATE TABLE epoch_history(epoch TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("""CREATE TABLE stage(
            media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL,
            revision TEXT NOT NULL, PRIMARY KEY(media_key,frame_ms))""")
        db.execSQL("""CREATE TABLE catalog(
            media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL, revision TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('queued','running','processed')),
            lease_token TEXT, lease_epoch TEXT,
            PRIMARY KEY(media_key,frame_ms))""")
        db.execSQL("CREATE INDEX catalog_queue ON catalog(state,media_key,frame_ms)")
        db.execSQL("""CREATE TABLE vectors(
            media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL,
            revision TEXT NOT NULL, model_version TEXT NOT NULL,
            dimension INTEGER NOT NULL, vector BLOB NOT NULL, scale REAL NOT NULL,
            PRIMARY KEY(media_key,frame_ms),
            FOREIGN KEY(media_key,frame_ms) REFERENCES catalog(media_key,frame_ms)
                ON DELETE CASCADE)""")
        createSkips(db)
        createMomentsAndFailures(db)
    }

    /** No vector, no retry: keyed to the item's revision and the model that tried it. */
    private fun createSkips(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE skips(
            media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL,
            revision TEXT NOT NULL, model_version TEXT NOT NULL,
            PRIMARY KEY(media_key,frame_ms),
            FOREIGN KEY(media_key,frame_ms) REFERENCES catalog(media_key,frame_ms)
                ON DELETE CASCADE)""")
    }

    /**
     * VS-V2: a video's keyframe vectors, owned by its source row (frame -1),
     * and the per-item failure count. Both cascade with the catalogue row.
     */
    private fun createMomentsAndFailures(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE moments(
            media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL CHECK(frame_ms>=0),
            source_frame INTEGER NOT NULL DEFAULT -1 CHECK(source_frame=-1),
            revision TEXT NOT NULL, model_version TEXT NOT NULL,
            dimension INTEGER NOT NULL, vector BLOB NOT NULL, scale REAL NOT NULL,
            PRIMARY KEY(media_key,frame_ms),
            FOREIGN KEY(media_key,source_frame) REFERENCES catalog(media_key,frame_ms)
                ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE failures(
            media_key TEXT NOT NULL, frame_ms INTEGER NOT NULL,
            revision TEXT NOT NULL, model_version TEXT NOT NULL,
            count INTEGER NOT NULL CHECK(count>0),
            PRIMARY KEY(media_key,frame_ms),
            FOREIGN KEY(media_key,frame_ms) REFERENCES catalog(media_key,frame_ms)
                ON DELETE CASCADE)""")
    }

    /** Refuse unrecognised migrations instead of deleting user-derived data. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion == 3 && oldVersion in 1..2) {
            if (oldVersion == 1) createSkips(db)
            createMomentsAndFailures(db)
            return
        }
        throw SQLiteException("Unsupported visual index upgrade: $oldVersion -> $newVersion")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported visual index downgrade")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.beginTransaction()
        try {
            // Neither an in-flight job nor a previous process proves a grant.
            db.delete("stage", null, null)
            db.execSQL("""UPDATE control SET active_epoch=NULL, staging_epoch=NULL,
                staged_count=0 WHERE singleton=1""")
            db.execSQL("""UPDATE catalog SET state='queued', lease_token=NULL,
                lease_epoch=NULL WHERE state='running'""")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    private fun validToken(value: String) {
        require(value.isNotBlank() && value.length <= 256 && '\u0000' !in value)
    }

    private fun validMedia(media: VisualMedia) {
        require(media.key.isNotBlank() && media.key.length <= 1024 &&
            !media.key.startsWith("/") && !media.key.contains("/storage/emulated/") &&
            '\u0000' !in media.key) { "Visual media must use an opaque media key" }
        validToken(media.revision)
        require(media.frameMs == null || media.frameMs >= 0L)
    }

    private fun frame(media: VisualMedia) = media.frameMs ?: IMAGE_FRAME

    private fun stageEpoch(db: SQLiteDatabase): String? =
        db.rawQuery("SELECT staging_epoch FROM control WHERE singleton=1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun activeModel(db: SQLiteDatabase, epoch: String): Pair<String, Int>? =
        db.rawQuery("""SELECT model_version, dimension FROM control
            WHERE singleton=1 AND active_epoch=?""", arrayOf(epoch)).use {
            if (it.moveToFirst()) Pair(it.getString(0), it.getInt(1)) else null
        }

    private fun clearScope(db: SQLiteDatabase) {
        db.delete("stage", null, null)
        db.execSQL("""UPDATE control SET active_epoch=NULL, staging_epoch=NULL,
            staged_count=0 WHERE singleton=1""")
        db.execSQL("""UPDATE catalog SET state='queued', lease_token=NULL,
            lease_epoch=NULL WHERE state='running'""")
    }

    /** Must be called on loss/uncertainty of Android permission, not deferred. */
    @Synchronized fun invalidatePermissionScope() {
        write(::clearScope)
    }

    /** A stale worker's finally block must not revoke a newer epoch. */
    @Synchronized fun invalidateSnapshotIfCurrent(epoch: String): Boolean {
        validToken(epoch)
        return write { db ->
            val current = db.rawQuery("""SELECT 1 FROM control WHERE singleton=1
                AND (active_epoch=? OR staging_epoch=?)""", arrayOf(epoch, epoch))
                .use { it.moveToFirst() }
            if (current) clearScope(db)
            current
        }
    }

    /**
     * Start a grant-scoped complete inventory. Tokens are NEVER reused, even
     * after process restart or cancellation. A replay of an older token must
     * not hide a newer granted catalogue.
     */
    @Synchronized fun beginSnapshot(epoch: String) {
        validToken(epoch)
        val accepted = write { db ->
            val used = db.rawQuery("SELECT 1 FROM epoch_history WHERE epoch=?",
                arrayOf(epoch)).use { it.moveToFirst() }
            if (used) {
                val owns = db.rawQuery("""SELECT 1 FROM control WHERE singleton=1 AND
                    (active_epoch=? OR staging_epoch=?)""", arrayOf(epoch,epoch))
                    .use { it.moveToFirst() }
                if (owns) clearScope(db)
                false
            } else {
                clearScope(db)
                db.insertOrThrow("epoch_history", null,
                    ContentValues().apply { put("epoch", epoch) })
                db.execSQL("UPDATE control SET staging_epoch=? WHERE singleton=1",
                    arrayOf(epoch))
                true
            }
        }
        require(accepted) { "Permission epoch was already consumed" }
    }

    /** Stage at most 1024 candidates per transaction; abort inconsistent inventory. */
    @Synchronized fun stageBatch(epoch: String, rows: List<VisualMedia>) {
        validToken(epoch)
        require(rows.size <= MAX_BATCH)
        val valid = runCatching { rows.forEach(::validMedia) }.isSuccess
        val accepted = write { db ->
            require(stageEpoch(db) == epoch) { "Stale staging epoch" }
            var consistent = valid
            if (consistent) {
                for (media in rows) {
                    val values = ContentValues().apply {
                        put("media_key", media.key)
                        put("frame_ms", frame(media))
                        put("revision", media.revision)
                    }
                    db.insertWithOnConflict("stage", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    val revision = db.rawQuery("""SELECT revision FROM stage
                        WHERE media_key=? AND frame_ms=?""",
                        arrayOf(media.key, frame(media).toString())).use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    if (revision != media.revision) { consistent = false; break }
                }
            }
            val count = if (consistent) db.rawQuery("SELECT COUNT(*) FROM stage", null).use {
                check(it.moveToFirst()); it.getInt(0)
            } else 0
            if (!consistent || count > MAX_MEDIA) {
                clearScope(db)
                false
            } else {
                db.execSQL("UPDATE control SET staged_count=? WHERE singleton=1",
                    arrayOf(count))
                true
            }
        }
        require(accepted) { "Invalid or inconsistent visual inventory; new epoch required" }
    }

    /**
     * Reconcile the entire authorised inventory atomically. Unauthorised keys
     * and changed revisions cascade-delete their vectors; identical sources
     * remain indexed. A changed embedding space invalidates EVERY vector.
     * Verify both directions of the stage/catalog join before publishing.
     */
    @Synchronized fun commitSnapshot(epoch: String, modelVersion: String,
                                     dimension: Int = DEFAULT_DIMENSION) {
        validToken(epoch); validToken(modelVersion)
        require(dimension in 1..4096)
        write { db ->
            require(stageEpoch(db) == epoch) { "Stale or missing snapshot" }
            db.execSQL("""DELETE FROM catalog WHERE NOT EXISTS(
                SELECT 1 FROM stage s WHERE s.media_key=catalog.media_key
                    AND s.frame_ms=catalog.frame_ms AND s.revision=catalog.revision)""")
            db.execSQL("""INSERT OR IGNORE INTO catalog(media_key,frame_ms,revision,state)
                SELECT media_key,frame_ms,revision,'queued' FROM stage""")
            val (previousVersion, previousDimension) =
                db.rawQuery("SELECT model_version,dimension FROM control WHERE singleton=1", null)
                    .use { c ->
                        check(c.moveToFirst())
                        Pair(c.getString(0), if (c.isNull(1)) null else c.getInt(1))
                    }
            if (previousVersion != modelVersion || previousDimension != dimension) {
                db.delete("vectors", null, null)
                db.delete("moments", null, null)
                db.delete("skips", null, null)
                db.delete("failures", null, null)
                db.execSQL("""UPDATE catalog SET state='queued', lease_token=NULL,
                    lease_epoch=NULL""")
            } else {
                db.execSQL("""UPDATE catalog SET state='queued', lease_token=NULL,
                    lease_epoch=NULL WHERE state='running'""")
                db.execSQL("""UPDATE catalog SET state='queued' WHERE state='processed'
                    AND NOT EXISTS (SELECT 1 FROM vectors v WHERE
                        v.media_key=catalog.media_key AND v.frame_ms=catalog.frame_ms
                        AND v.revision=catalog.revision AND v.model_version=?
                        AND v.dimension=?)
                    AND NOT EXISTS (SELECT 1 FROM skips k WHERE
                        k.media_key=catalog.media_key AND k.frame_ms=catalog.frame_ms
                        AND k.revision=catalog.revision AND k.model_version=?)
                    AND NOT EXISTS (SELECT 1 FROM moments m WHERE
                        m.media_key=catalog.media_key AND catalog.frame_ms=-1
                        AND m.revision=catalog.revision AND m.model_version=?
                        AND m.dimension=?)""",
                    arrayOf<Any>(modelVersion, dimension, modelVersion, modelVersion, dimension))
            }
            val staged = db.rawQuery("SELECT staged_count FROM control WHERE singleton=1", null)
                .use { c -> check(c.moveToFirst()); c.getInt(0) }
            val count = db.rawQuery("SELECT COUNT(*) FROM catalog", null)
                .use { c -> check(c.moveToFirst()); c.getInt(0) }
            val mismatched = db.rawQuery("""SELECT 1 FROM stage s
                LEFT JOIN catalog c ON c.media_key=s.media_key AND c.frame_ms=s.frame_ms
                WHERE c.media_key IS NULL OR c.revision<>s.revision LIMIT 1""", null)
                .use { it.moveToFirst() }
            val extra = db.rawQuery("""SELECT 1 FROM catalog c
                LEFT JOIN stage s ON s.media_key=c.media_key AND s.frame_ms=c.frame_ms
                WHERE s.media_key IS NULL LIMIT 1""", null).use { it.moveToFirst() }
            check(count == staged && !mismatched && !extra) {
                "Incomplete visual permission inventory: cannot publish"
            }
            db.delete("stage", null, null)
            db.execSQL("""UPDATE control SET active_epoch=?, staging_epoch=NULL,
                model_version=?, dimension=?, staged_count=0 WHERE singleton=1""",
                arrayOf<Any>(epoch, modelVersion, dimension))
        }
    }

    /**
     * Only the supplied, independently grant-authorised candidates can be
     * claimed. Each claim is CAS-updated inside one transaction, so repeated
     * candidates or concurrent old leases cannot duplicate work.
     */
    @Synchronized override fun claimAuthorized(
        epoch: String, modelVersion: String, authorized: List<VisualMedia>, maxItems: Int
    ): List<VisualIndexLease> {
        validToken(epoch); validToken(modelVersion)
        require(maxItems in 1..MAX_BATCH && authorized.size <= MAX_BATCH)
        authorized.forEach(::validMedia)
        return write { db ->
            val active = activeModel(db, epoch)
            if (active == null || active.first != modelVersion) return@write emptyList()
            val results = ArrayList<VisualIndexLease>(maxItems)
            for (media in authorized) {
                if (results.size == maxItems) break
                val token = UUID.randomUUID().toString()
                val values = ContentValues().apply {
                    put("state", "running"); put("lease_token", token)
                    put("lease_epoch", epoch)
                }
                val updated = db.update("catalog", values,
                    """media_key=? AND frame_ms=? AND revision=? AND state='queued'
                        AND lease_token IS NULL""",
                    arrayOf(media.key, frame(media).toString(), media.revision))
                if (updated == 1) {
                    results += VisualIndexLease(media, modelVersion, active.second, epoch, token)
                } else {
                    check(updated == 0) { "Ambiguous visual lease update" }
                }
            }
            results
        }
    }

    /**
     * All-or-nothing, even when only the last lease in a batch is stale.
     * [skipped] leases (unreadable items, #480) are recorded in `skips` and
     * marked processed without a vector in the same transaction.
     */
    @Synchronized override fun completeBatch(
        writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>
    ): Boolean = completeBatch(writes, skipped, emptyList())

    /**
     * VS-V2: [videos] are video source leases (null frame) with their moments
     * (1..60, increasing frame_ms). Each replaces ALL earlier moments of its
     * video in this same transaction, so a reader never sees a mix of two
     * revisions or two passes of one video.
     */
    @Synchronized override fun completeBatch(
        writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>,
        videos: List<VisualMomentsWrite>
    ): Boolean {
        require(writes.size + skipped.size + videos.size <= MAX_BATCH)
        val leases = writes.map { it.lease } + skipped + videos.map { it.lease }
        if (leases.isEmpty()) return false
        val lease0 = leases.first()
        validToken(lease0.permissionEpoch); validToken(lease0.modelVersion)
        require(lease0.dimension in 1..4096)
        val seen = HashSet<Pair<String, Long>>()
        for (lease in leases) {
            validMedia(lease.media)
            require(lease.permissionEpoch == lease0.permissionEpoch &&
                lease.modelVersion == lease0.modelVersion &&
                lease.dimension == lease0.dimension) { "Mixed leases in batch" }
            require(seen.add(lease.media.key to frame(lease.media))) {
                "Duplicate visual lease in one batch"
            }
        }
        val packed = writes.map { item ->
            require(item.vector.size == lease0.dimension)
            VisualVectorCodec.encode(item.vector)
        }
        val packedMoments = videos.map { video ->
            require(video.lease.media.frameMs == null) { "Moments belong to a video source row" }
            require(video.moments.size in 1..MAX_MOMENTS_PER_VIDEO)
            require(video.moments.first().frameMs >= 0L &&
                video.moments.zipWithNext().all { (a, b) -> a.frameMs < b.frameMs })
            video.moments.map { moment ->
                require(moment.vector.size == lease0.dimension)
                moment.frameMs to VisualVectorCodec.encode(moment.vector)
            }
        }
        return write { db ->
            if (activeModel(db, lease0.permissionEpoch) !=
                (lease0.modelVersion to lease0.dimension)) return@write false
            for (lease in leases) {
                val matches = db.rawQuery("""SELECT 1 FROM catalog WHERE media_key=?
                    AND frame_ms=? AND revision=? AND state='running'
                    AND lease_epoch=? AND lease_token=?""",
                    arrayOf(lease.media.key, frame(lease.media).toString(),
                        lease.media.revision, lease.permissionEpoch, lease.token))
                    .use { it.moveToFirst() }
                if (!matches) return@write false
            }
            for (index in writes.indices) {
                val lease = writes[index].lease
                val encoded = packed[index]
                val vectorValues = ContentValues().apply {
                    put("media_key", lease.media.key); put("frame_ms", frame(lease.media))
                    put("revision", lease.media.revision); put("model_version", lease.modelVersion)
                    put("dimension", lease.dimension); put("vector", encoded.bytes)
                    put("scale", encoded.scale)
                }
                val row = db.insertWithOnConflict("vectors", null, vectorValues,
                    SQLiteDatabase.CONFLICT_REPLACE)
                check(row != -1L) { "Visual vector insert ignored" }
                db.delete("skips", "media_key=? AND frame_ms=?",
                    arrayOf(lease.media.key, frame(lease.media).toString()))
            }
            for (index in videos.indices) {
                val lease = videos[index].lease
                db.delete("moments", "media_key=?", arrayOf(lease.media.key))
                db.delete("skips", "media_key=? AND frame_ms=?",
                    arrayOf(lease.media.key, frame(lease.media).toString()))
                for ((frameMs, encoded) in packedMoments[index]) {
                    val values = ContentValues().apply {
                        put("media_key", lease.media.key); put("frame_ms", frameMs)
                        put("revision", lease.media.revision)
                        put("model_version", lease.modelVersion)
                        put("dimension", lease.dimension); put("vector", encoded.bytes)
                        put("scale", encoded.scale)
                    }
                    check(db.insert("moments", null, values) != -1L) { "Visual moment insert ignored" }
                }
            }
            for (lease in skipped) {
                db.delete("vectors", "media_key=? AND frame_ms=?",
                    arrayOf(lease.media.key, frame(lease.media).toString()))
                if (lease.media.frameMs == null) {
                    db.delete("moments", "media_key=?", arrayOf(lease.media.key))
                }
                val skipValues = ContentValues().apply {
                    put("media_key", lease.media.key); put("frame_ms", frame(lease.media))
                    put("revision", lease.media.revision); put("model_version", lease.modelVersion)
                }
                val row = db.insertWithOnConflict("skips", null, skipValues,
                    SQLiteDatabase.CONFLICT_REPLACE)
                check(row != -1L) { "Visual skip insert ignored" }
            }
            for (lease in leases) {
                val values = ContentValues().apply {
                    put("state", "processed")
                    putNull("lease_token"); putNull("lease_epoch")
                }
                val updated = db.update("catalog", values,
                    """media_key=? AND frame_ms=? AND revision=? AND state='running'
                       AND lease_token=? AND lease_epoch=?""",
                    arrayOf(lease.media.key, frame(lease.media).toString(),
                        lease.media.revision, lease.token, lease.permissionEpoch))
                check(updated == 1) { "Visual completion update ignored" }
                db.delete("failures", "media_key=? AND frame_ms=?",
                    arrayOf(lease.media.key, frame(lease.media).toString()))
            }
            true
        }
    }

    /**
     * #482 follow-up: one unexpected failure of a still-leased item, in its own
     * transaction (the failed step publishes nothing else). The count belongs
     * to the item's revision and model: a different revision or model starts
     * again at 1. Returns the new count, or 0 when the lease is stale.
     */
    @Synchronized override fun recordFailure(lease: VisualIndexLease): Int {
        validMedia(lease.media)
        validToken(lease.permissionEpoch); validToken(lease.modelVersion)
        return write { db ->
            if (activeModel(db, lease.permissionEpoch) !=
                (lease.modelVersion to lease.dimension)) return@write 0
            val key = lease.media.key
            val frame = frame(lease.media).toString()
            val leased = db.rawQuery("""SELECT 1 FROM catalog WHERE media_key=?
                AND frame_ms=? AND revision=? AND state='running'
                AND lease_epoch=? AND lease_token=?""",
                arrayOf(key, frame, lease.media.revision, lease.permissionEpoch, lease.token))
                .use { it.moveToFirst() }
            if (!leased) return@write 0
            val previous = db.rawQuery("""SELECT count FROM failures WHERE media_key=?
                AND frame_ms=? AND revision=? AND model_version=?""",
                arrayOf(key, frame, lease.media.revision, lease.modelVersion))
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val count = previous + 1
            val values = ContentValues().apply {
                put("media_key", key); put("frame_ms", frame(lease.media))
                put("revision", lease.media.revision); put("model_version", lease.modelVersion)
                put("count", count)
            }
            check(db.insertWithOnConflict("failures", null, values,
                SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Visual failure insert ignored" }
            count
        }
    }

    /** Safe cleanup only: an old grant can never mutate a newer grant. */
    @Synchronized override fun releaseBatch(leases: List<VisualIndexLease>): Int {
        require(leases.size <= MAX_BATCH)
        if (leases.isEmpty()) return 0
        return write { db ->
            var released = 0
            for (lease in leases) {
                if (activeModel(db, lease.permissionEpoch) !=
                    (lease.modelVersion to lease.dimension)) continue
                val values = ContentValues().apply {
                    put("state", "queued"); putNull("lease_token"); putNull("lease_epoch")
                }
                released += db.update("catalog", values,
                    """media_key=? AND frame_ms=? AND revision=? AND state='running'
                       AND lease_epoch=? AND lease_token=?""",
                    arrayOf(lease.media.key, frame(lease.media).toString(),
                        lease.media.revision, lease.permissionEpoch, lease.token))
            }
            released
        }
    }

    /**
     * Page through active version-matched vectors rather than allocating
     * 150k x 768 floats at once. Keyset pagination is stable for a single
     * worker session; callers must recheck Android permission between pages.
     */
    @Synchronized fun loadActiveVectors(
        epoch: String, modelVersion: String,
        after: VisualVectorCursor? = null, limit: Int = 256
    ): List<VisualIndexedVector> {
        validToken(epoch); validToken(modelVersion)
        require(limit in 1..512)
        require(after == null || (after.permissionEpoch == epoch &&
            after.modelVersion == modelVersion)) { "Stale vector page cursor" }
        val db = readableDatabase
        val active = activeModel(db, epoch)
        if (active == null || active.first != modelVersion) return emptyList()
        val params = mutableListOf(modelVersion, active.second.toString())
        val continuation = if (after == null) "" else {
            params += after.mediaKey
            params += after.mediaKey
            params += (after.frameMs ?: IMAGE_FRAME).toString()
            " AND (c.media_key>? OR (c.media_key=? AND c.frame_ms>?))"
        }
        params += limit.toString()
        fun read(sql: String): List<VisualIndexedVector> =
            db.rawQuery(sql, params.toTypedArray()).use { cursor ->
                val result = ArrayList<VisualIndexedVector>()
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val rev = cursor.getString(1)
                    val frame = cursor.getLong(2).takeUnless { it == IMAGE_FRAME }
                    val vector = VisualVectorCodec.decode(cursor.getBlob(3),
                        cursor.getFloat(4), active.second)
                    result += VisualIndexedVector(VisualMedia(key, rev, frame), modelVersion,
                        vector, VisualVectorCursor(epoch, modelVersion, key, frame))
                }
                result
            }
        val photos = read("""SELECT c.media_key,c.revision,c.frame_ms,v.vector,v.scale
            FROM catalog c JOIN vectors v ON c.media_key=v.media_key
                AND c.frame_ms=v.frame_ms
            WHERE c.state='processed' AND c.revision=v.revision
                AND v.model_version=? AND v.dimension=? $continuation
            ORDER BY c.media_key,c.frame_ms LIMIT ?""")
        // VS-V2: a processed video's moments, in the same (key, frame) order.
        val moments = read("""SELECT m.media_key,m.revision,m.frame_ms,m.vector,m.scale
            FROM moments m JOIN catalog c ON c.media_key=m.media_key AND c.frame_ms=-1
            WHERE c.state='processed' AND c.revision=m.revision
                AND m.model_version=? AND m.dimension=? ${continuation.replace("c.", "m.")}
            ORDER BY m.media_key,m.frame_ms LIMIT ?""")
        if (moments.isEmpty()) return photos
        if (photos.isEmpty()) return moments
        // Two keyset pages in the same order: merge and keep the first [limit].
        val merged = ArrayList<VisualIndexedVector>(minOf(limit, photos.size + moments.size))
        var i = 0
        var j = 0
        while (merged.size < limit && (i < photos.size || j < moments.size)) {
            merged += when {
                j == moments.size -> photos[i++]
                i == photos.size -> moments[j++]
                ORDER.compare(photos[i].cursor, moments[j].cursor) <= 0 -> photos[i++]
                else -> moments[j++]
            }
        }
        return merged
    }

    /**
     * Up to [limit] queued items of the active [epoch] in key order, for the
     * host to resolve through its CURRENT grant before claiming. Null when
     * [epoch] is not active. Reading is not authority: claims recheck all.
     */
    @Synchronized fun queuedCandidates(
        epoch: String, limit: Int = 64, after: VisualMedia? = null
    ): List<VisualMedia>? {
        validToken(epoch)
        require(limit in 1..MAX_BATCH)
        after?.let(::validMedia)
        val db = readableDatabase
        if (activeModel(db, epoch) == null) return null
        // #482 follow-up: a window strictly after [after] in (key, frame) order.
        val (continuation, args) = if (after == null) "" to arrayOf(limit.toString())
            else " AND (media_key>? OR (media_key=? AND frame_ms>?))" to
                arrayOf(after.key, after.key, frame(after).toString(), limit.toString())
        return db.rawQuery("""SELECT media_key,revision,frame_ms FROM catalog
            WHERE state='queued'$continuation ORDER BY media_key,frame_ms LIMIT ?""",
            args).use { c ->
            val out = ArrayList<VisualMedia>()
            while (c.moveToNext()) {
                out += VisualMedia(c.getString(0), c.getString(1),
                    c.getLong(2).takeUnless { it == IMAGE_FRAME })
            }
            out
        }
    }

    /** Counts for the opt-in's progress line; null unless [epoch] is active. */
    @Synchronized fun progress(epoch: String): VisualIndexProgress? {
        validToken(epoch)
        val db = readableDatabase
        val (model, dimension) = activeModel(db, epoch) ?: return null
        fun count(sql: String, args: Array<String> = emptyArray()) =
            db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getInt(0) }
        val queued = count("SELECT COUNT(*) FROM catalog WHERE state='queued'")
        val running = count("SELECT COUNT(*) FROM catalog WHERE state='running'")
        val indexed = count("""SELECT COUNT(*) FROM catalog c JOIN vectors v
            ON c.media_key=v.media_key AND c.frame_ms=v.frame_ms
            WHERE c.state='processed' AND v.revision=c.revision
            AND v.model_version=? AND v.dimension=?""", arrayOf(model, dimension.toString())) +
            count("""SELECT COUNT(*) FROM catalog c WHERE c.state='processed'
            AND c.frame_ms=-1 AND EXISTS (SELECT 1 FROM moments m
                WHERE m.media_key=c.media_key AND m.revision=c.revision
                AND m.model_version=? AND m.dimension=?)""",
                arrayOf(model, dimension.toString()))
        val skipped = count("""SELECT COUNT(*) FROM catalog c JOIN skips k
            ON c.media_key=k.media_key AND c.frame_ms=k.frame_ms
            WHERE c.state='processed' AND k.revision=c.revision
            AND k.model_version=?""", arrayOf(model))
        return VisualIndexProgress(queued, running, indexed, skipped)
    }

    /**
     * The user switched the visual index off and chose to delete it: every
     * catalogue row, vector and skip goes, and no epoch stays active. Used
     * epochs stay consumed, so an old worker can never revive a scope.
     */
    @Synchronized fun deleteIndex() {
        write { db ->
            clearScope(db)
            db.delete("skips", null, null)
            db.delete("failures", null, null)
            db.delete("moments", null, null)
            db.delete("vectors", null, null)
            db.delete("catalog", null, null)
            db.execSQL("UPDATE control SET model_version=NULL, dimension=NULL WHERE singleton=1")
        }
    }
}
