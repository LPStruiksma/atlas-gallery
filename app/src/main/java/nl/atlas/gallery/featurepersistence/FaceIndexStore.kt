package nl.atlas.gallery.featurepersistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterDiagnostics
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.identity.ReviewTally
import nl.atlas.gallery.featurepeople.identity.ReviewTap
import nl.atlas.gallery.featurepeople.identity.TapCount
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.search.FaceSearchCorpus
import nl.atlas.gallery.featureface.search.PersonMatchInputs

/**
 * Private metadata-only face-discovery persistence; never People assignments.
 * This store deliberately has no compile dependency on P's not-yet-merged
 * featureface branch. I supplies the trivial FaceScanIndexPort adapter after
 * both branches are integrated. ALL calls belong on one background IO worker.
 */
data class IndexedMedia(val key: String, val revision: String)
data class IndexedFaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }
}
data class IndexedFaceLease(
    val media: IndexedMedia,
    val detectorVersion: String,
    val permissionEpoch: String,
    val token: String
)
/**
 * Native detector observation IDs are NOT V4 face IDs or person identifiers.
 * [frameMs] (VS-V1b) is the keyframe time of a face in a video, null for a photo.
 */
data class IndexedFaceObservation(
    val nativeFaceUid: String, val box: IndexedFaceBox, val frameMs: Long? = null
) {
    init {
        require(nativeFaceUid.matches(Regex("[0-9a-f]{32}")))
        require(frameMs == null || frameMs >= 0)
    }
}
/** One observation's source, box and video position (null: photo), for a review crop; never an identity. */
data class IndexedFaceLocation(
    val media: IndexedMedia, val box: IndexedFaceBox, val frameMs: Long? = null
) {
    init { require(frameMs == null || frameMs >= 0) }
}
private val FACE_UID = Regex("[0-9a-f]{32}")
/**
 * #553: one face of one photo for the viewer's People row. [clusterAnchor] is
 * the published unknown cluster (null while a publication is pending or the
 * face is confirmed); [person]/[name] are the human-confirmed identity.
 */
data class IndexedPhotoFace(
    val nativeFaceUid: String,
    val box: IndexedFaceBox,
    val clusterAnchor: String?,
    val person: String?,
    val name: String?
)
data class IndexedFaceProgress(
    val pending: Int, val running: Int, val processed: Int,
    val failed: Int, val observations: Int
)

/** Grant-scoped review counters; never confirmed People identities. */
data class IndexedFaceReviewCounts(
    val totalDetections: Int,
    val awaitingReview: Int,
    val later: Int,
    val notAFace: Int
)
/**
 * Keyset cursor for the unknown-face inbox. [ordinal] is only a page position:
 * UI actions MUST address [IndexedFaceObservation.nativeFaceUid], never ordinal.
 * The epoch tag prevents a UI page cursor from a prior authorization being
 * replayed into a newly granted permission snapshot.
 */
data class IndexedFacePageCursor(
    val permissionEpoch: String, val mediaKey: String, val ordinal: Int
)
data class IndexedPendingFaceReview(
    val cursor: IndexedFacePageCursor,
    /** Current source revision to permit P's exact key+revision URI resolution. */
    val media: IndexedMedia,
    val observation: IndexedFaceObservation,
    /** Model/source-version fence required by U's DetectedFaceReviewKey. */
    val detectorVersion: String
)

/** A model bump invalidates all processed face vectors, even for identical source bytes. */
object FaceEmbeddingVersionFence {
    fun changed(previous: String?, incoming: String): Boolean {
        require(incoming.isNotBlank() && incoming.length <= 256 && '\u0000' !in incoming)
        return previous != incoming
    }
}

/** A model- and source-fenced, app-private vector, never a confirmed identity. */
class IndexedFaceEmbedding(val nativeFaceUid: String, val mediaRevision: String,
                           val modelVersion: String, vector: FloatArray) {
    private val saved = vector.copyOf()
    val vector: FloatArray get() = saved.copyOf()
}

/** Little-endian portable vector codec, independent of Android's SQLite runtime. */
object FaceEmbeddingVectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        val unit = SFaceModel.l2Normalize(vector)
        return ByteBuffer.allocate(SFaceModel.DIMENSION * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { unit.forEach { putFloat(it) } }.array()
    }
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size == SFaceModel.DIMENSION * 4) { "Invalid SFace vector blob" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return SFaceModel.l2Normalize(FloatArray(SFaceModel.DIMENSION) { buffer.float })
    }
}

/**
 * Schema v1 remains separate from the V4 confirmed-identity People DB.
 * Only app-private vectors and unknown-cluster memberships are added, never images,
 * absolute paths, inferred person IDs or media permissions. A unique full MediaStore key + revision is required.
 *
 * Schema v3 (D2-5c, #434) adds the native identity review: human-confirmed
 * face to person facts, cannot-link pairs, rejected suggestions and person
 * names. They are human decisions, never inferred; every face-keyed row
 * cascades with its embedding, so a source change, rescan or model bump
 * drops the facts about the old observations exactly as it drops their
 * membership, and reads stay behind the same permission-epoch gate.
 *
 * Video columns (VS-V1b, #468): `faces.frame_ms` (nullable keyframe time of
 * a face in a video; NULL for every photo face) and `catalog.skip_reason`
 * (nullable typed skip of a corrupt/DRM video). They are ADDITIVE, NULLABLE
 * columns detected by name on every open, deliberately without a
 * [SCHEMA_VERSION] bump: a build without them (a plain revert) opens the
 * same file at version 3 without an onDowngrade refusal, ignores both
 * columns, and its next snapshot prunes the video rows it cannot stage,
 * cascading only video faces. Photo faces, embeddings, memberships and
 * People review facts are never rewritten by adding or ignoring them.
 */
class FaceIndexStore(
    context: Context, private val maxCatalogMedia: Int = MAX_CATALOG_MEDIA,
    /** Pending faces placed per publication transaction (#508). */
    private val publicationFaceLimit: Int = MAX_PUBLICATION_FACES
) : SQLiteOpenHelper(context, "atlas_face_discovery.db", null, SCHEMA_VERSION) {
    companion object {
        /**
         * #508: one publication places at most this many pending faces. Every
         * new face is compared with every cluster, so a publication of a
         * whole-library scan (a quarter of 300k faces at once) would be one
         * transaction of many minutes; a part stays inside one worker run.
         * Each extra part re-reads the published seed, so parts are not
         * smaller than needed (16k keeps a 300k scan's reads within 2x the
         * unsplit cadence's linear bound).
         */
        const val MAX_PUBLICATION_FACES = 16_384
        /** #579: like Samsung, People shows an unnamed group from this many faces. */
        const val PEOPLE_HOME_MIN_GROUP = 3
        /** #579: unnamed groups People home reads at most (largest first). */
        const val PEOPLE_HOME_MAX_GROUPS = 500
        /** `pending` of a split publication: due at once, whatever the cadence. */
        internal const val PUBLICATION_CONTINUES = 1_000_000_000
        // Limits runaway/corrupt clients without penalizing a 150k gallery.
        const val MAX_CATALOG_MEDIA = 500_000
        /** Unchanged by VS-V1b's video columns on purpose; see the class comment. */
        const val SCHEMA_VERSION = 3
        /** Bound variables per [faceMedia] statement (SQLite's old limit is 999). */
        private const val FACE_MEDIA_CHUNK = 500
        private const val FRAME_MS_COLUMN =
            "frame_ms INTEGER CHECK(frame_ms IS NULL OR frame_ms>=0)"
        private const val SKIP_REASON_COLUMN =
            "skip_reason TEXT CHECK(skip_reason IS NULL OR length(skip_reason) BETWEEN 1 AND 64)"
        private val SKIP_REASON = Regex("[A-Z][A-Z0-9_]{0,63}")
        /** #508: MediaStore capture time (ms) of a row; only orders the scan. */
        private const val TAKEN_MS_COLUMN = "taken_ms INTEGER"
        /**
         * #532: named people with present faces, mirroring buildClusterState's
         * processed/source/model eligibility (a confirmation alone does not
         * make a face present). CROSS JOIN pins the plan to start from the
         * names and walk indexes per confirmed face; without it SQLite drives
         * from `catalog` and scans every photo (~350 ms at 300k photos).
         * #580: the count adds the person's present faces grouping added on
         * its own (`people_matched`, one indexed lookup chain each); the
         * cover stays a confirmed face, so a person needs one to be listed.
         * Bound argument ?1: the active embedder model version.
         */
        private const val PEOPLE_HOME_SELECT = """SELECT n.person_key,n.name,
                COUNT(p.face_uid) + (SELECT COUNT(*) FROM people_matched pm
                    CROSS JOIN embeddings me ON me.face_uid=pm.face_uid AND me.model_version=?1
                    CROSS JOIN faces mf ON mf.face_uid=me.face_uid
                    CROSS JOIN catalog mc ON mc.media_key=mf.media_key AND mc.state='processed'
                        AND mc.revision=me.source_revision
                    WHERE pm.person_key=n.person_key),
                MIN(p.face_uid)
            FROM people_names n
            CROSS JOIN people_confirmed p ON p.person_key=n.person_key
            CROSS JOIN embeddings e ON e.face_uid=p.face_uid AND e.model_version=?1
            CROSS JOIN faces f ON f.face_uid=e.face_uid
            CROSS JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                AND c.revision=e.source_revision
            WHERE NOT EXISTS (SELECT 1 FROM people_hidden h WHERE h.person_key=n.person_key)
            GROUP BY n.person_key,n.name
            ORDER BY n.person_key"""

        /**
         * #571: unknown members, then confirmed faces. A confirmed face never
         * has a membership row; if a stale one existed, the confirmed branch
         * wins (the first branch skips it).
         */
        private const val PERSON_MATCH_ELIGIBLE = """
            CROSS JOIN faces f ON f.face_uid=e.face_uid
            CROSS JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                AND c.revision=e.source_revision
            WHERE NOT EXISTS (SELECT 1 FROM face_reviews r WHERE r.media_key=f.media_key
                AND r.ordinal=f.ordinal AND r.decision='NOT_A_FACE')"""
        private const val PERSON_MATCH_SELECT = """
            SELECT e.face_uid,e.vector,m.anchor_uid,NULL FROM cluster_members m
            CROSS JOIN embeddings e ON e.face_uid=m.face_uid AND e.model_version=?
            $PERSON_MATCH_ELIGIBLE
                AND NOT EXISTS (SELECT 1 FROM people_confirmed p WHERE p.face_uid=m.face_uid)
            UNION ALL
            SELECT e.face_uid,e.vector,NULL,p.person_key FROM people_confirmed p
            CROSS JOIN embeddings e ON e.face_uid=p.face_uid AND e.model_version=?
            $PERSON_MATCH_ELIGIBLE"""
    }
    init {
        require(maxCatalogMedia in 1..MAX_CATALOG_MEDIA)
        require(publicationFaceLimit in 1..MAX_PUBLICATION_FACES)
        // #47: write-ahead log, so a read outside a transaction takes its own
        // connection and sees the last commit instead of waiting for the
        // scanner's write transaction. Journal mode only: no schema version
        // change, and a reverted build reopens the file in its own mode.
        setWriteAheadLoggingEnabled(true)
    }

    /**
     * D2-5p work meter (#419): SQL statements issued, rows read through
     * cursors, and rows written as SQLite total_changes() counts them. Filled
     * by [completeEmbeddings] and cluster publication only; test evidence,
     * not a product API.
     */
    data class Work(val statements: Int, val rowsRead: Long, val rowsWritten: Long)
    @Volatile var lastCompletionWork: Work? = null
        private set
    @Volatile var lastPublicationWork: Work? = null
        private set
    private var meteredStatements = 0
    private var meteredRowsRead = 0L
    private fun totalChanges(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT total_changes()", null).use { c ->
            check(c.moveToFirst()); c.getLong(0)
        }
    private inline fun <T> metered(db: SQLiteDatabase, record: (Work) -> Unit, block: () -> T): T {
        val statements = meteredStatements
        val read = meteredRowsRead
        val changes = totalChanges(db)
        val result = block()
        record(Work(meteredStatements - statements, meteredRowsRead - read,
            totalChanges(db) - changes))
        return result
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    /**
     * A new SQLite helper connection is NOT evidence of a current Android
     * media permission grant. Never revive the last persisted scope after
     * process restart or helper reopen: only I's independently authorized,
     * new one-time permission epoch can publish catalog reads again.
     *
     * Keep catalog/faces/reviews privately for unchanged-source reconciliation.
     * Abort an interrupted staged inventory and revoke old in-flight leases.
     * This executes before the database can be returned to callers; a
     * read-only/corrupted DB must fail to open, not expose stale observations.
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        createClusterGeneration(db)
        createPeopleHidden(db)
        ensureVideoColumns(db)
        createPeopleHomeTables(db)
        createClusterPolicyTable(db)
        createSameUnknownTable(db)
        createReviewTallyTable(db)
        createPeopleMatchedTable(db)
        createModelCarryTable(db)
        ensureDormantEpochColumn(db)
        db.beginTransaction()
        try {
            regroupOnPolicyChange(db)
            refillPeopleMatchedIfStale(db)
            db.delete("stage", null, null)
            // #31: the last published epoch is kept DORMANT, never readable.
            // Only reviveDormantEpoch() can make it readable again, and the host
            // calls that only after it has re-checked the live Android grant.
            db.execSQL("""UPDATE control SET dormant_epoch=COALESCE(active_epoch,
                dormant_epoch), active_epoch=NULL, staging_epoch=NULL,
                staged_count=0 WHERE singleton=1""")
            db.execSQL("""UPDATE catalog SET state=CASE WHEN attempts>=3 THEN
                'failed' ELSE 'queued' END, lease=NULL, lease_epoch=NULL,
                lease_deadline=NULL WHERE state='running'""")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE control(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            active_epoch TEXT, staging_epoch TEXT, detector_version TEXT,
            embedder_version TEXT, staged_count INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("INSERT INTO control(singleton) VALUES(1)")
        // Epochs are single use, including across process/database reopen.
        db.execSQL("CREATE TABLE epoch_history(epoch TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("""CREATE TABLE catalog(
            media_key TEXT PRIMARY KEY NOT NULL,
            revision TEXT NOT NULL, detector_version TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('queued','running','processed','failed')),
            attempts INTEGER NOT NULL DEFAULT 0,
            lease TEXT, lease_epoch TEXT, lease_deadline INTEGER,
            $SKIP_REASON_COLUMN)""")
        db.execSQL("CREATE INDEX catalog_queue ON catalog(state, media_key)")
        db.execSQL("CREATE INDEX catalog_deadline ON catalog(state, lease_deadline, media_key)")
        db.execSQL("""CREATE TABLE faces(
            media_key TEXT NOT NULL REFERENCES catalog(media_key) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL,
            face_uid TEXT NOT NULL UNIQUE CHECK(length(face_uid)=32),
            l REAL NOT NULL, t REAL NOT NULL, r REAL NOT NULL, b REAL NOT NULL,
            $FRAME_MS_COLUMN,
            PRIMARY KEY(media_key, ordinal))""")
        db.execSQL("""CREATE TABLE face_reviews(
            media_key TEXT NOT NULL, ordinal INTEGER NOT NULL,
            decision TEXT NOT NULL CHECK(decision IN ('LATER','NOT_A_FACE')),
            PRIMARY KEY(media_key, ordinal),
            FOREIGN KEY(media_key, ordinal) REFERENCES faces(media_key, ordinal)
                ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE stage(media_key TEXT PRIMARY KEY NOT NULL,
            revision TEXT NOT NULL, $TAKEN_MS_COLUMN)""")
        createEmbeddingTables(db)
        createPeopleReviewTables(db)
        createModelCarryTable(db)
    }
    /**
     * VS-V1b: add the nullable video columns to a v1..v3 file that lacks
     * them. Idempotent (detected by name), additive only, and it never
     * touches a row: existing faces read frame_ms NULL (a photo).
     */
    /** #31: additive and nullable, like the other late columns. */
    private fun ensureDormantEpochColumn(db: SQLiteDatabase) {
        val has = db.rawQuery("PRAGMA table_info(control)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            var found = false
            while (c.moveToNext()) if (c.getString(name) == "dormant_epoch") found = true
            found
        }
        if (!has) db.execSQL("ALTER TABLE control ADD COLUMN dormant_epoch TEXT")
    }

    private fun ensureVideoColumns(db: SQLiteDatabase) {
        fun has(table: String, column: String): Boolean =
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val name = c.getColumnIndexOrThrow("name")
                var found = false
                while (c.moveToNext()) if (c.getString(name) == column) found = true
                found
            }
        if (!has("faces", "frame_ms")) db.execSQL("ALTER TABLE faces ADD COLUMN $FRAME_MS_COLUMN")
        if (!has("catalog", "skip_reason"))
            db.execSQL("ALTER TABLE catalog ADD COLUMN $SKIP_REASON_COLUMN")
        check(has("faces", "frame_ms") && has("catalog", "skip_reason")) {
            "Face index video columns are missing"
        }
        // #508 newest-first scan: the same additive, nullable pattern. A build
        // without it ignores the column and the index (SQLite keeps the index
        // current for it); NULL sorts last, after every dated row.
        if (!has("catalog", "taken_ms")) db.execSQL("ALTER TABLE catalog ADD COLUMN $TAKEN_MS_COLUMN")
        if (!has("stage", "taken_ms")) db.execSQL("ALTER TABLE stage ADD COLUMN $TAKEN_MS_COLUMN")
        db.execSQL("""CREATE INDEX IF NOT EXISTS catalog_newest
            ON catalog(state, taken_ms DESC, media_key)""")
        check(has("catalog", "taken_ms") && has("stage", "taken_ms")) {
            "Face index newest-first columns are missing"
        }
    }

    private fun createClusterGeneration(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS cluster_generation(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            dirty INTEGER NOT NULL CHECK(dirty IN (0,1)) DEFAULT 0,
            pending INTEGER NOT NULL DEFAULT 0,
            published INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("INSERT OR IGNORE INTO cluster_generation(singleton,dirty) VALUES(1,1)")
    }

    /**
     * #551 "Hide this person": the named people hidden from People. Like the
     * video columns it is ADDITIVE and created on every open without a
     * [SCHEMA_VERSION] bump, so a plain revert opens the same file and only
     * ignores it (every person shows again). Not face-keyed: a row lives as
     * long as the person's name; a commit keeps it a subset of the names.
     */
    private fun createPeopleHidden(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_hidden(
            person_key TEXT PRIMARY KEY NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256))""")
    }

    /**
     * #48: model-change journal is app-private metadata, not a vector or media
     * backup. It is intentionally independent of faces so the old observation
     * can be deleted atomically while its confirmation waits for re-embedding.
     * No inferred matches, only exact media revision/detector/ordinal/box.
     * Names remain in people_names even when no face can safely be restored.
     */
    private fun createModelCarryTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_model_carry(
            media_key TEXT NOT NULL, revision TEXT NOT NULL,
            detector_version TEXT NOT NULL, ordinal INTEGER NOT NULL,
            l REAL NOT NULL, t REAL NOT NULL, r REAL NOT NULL, b REAL NOT NULL,
            frame_ms INTEGER, person_key TEXT NOT NULL,
            PRIMARY KEY(media_key,ordinal))""")
    }

    private fun captureModelCarry(db: SQLiteDatabase) {
        db.execSQL("""INSERT OR REPLACE INTO people_model_carry
            (media_key,revision,detector_version,ordinal,l,t,r,b,frame_ms,person_key)
            SELECT f.media_key,c.revision,c.detector_version,f.ordinal,
                f.l,f.t,f.r,f.b,f.frame_ms,p.person_key
            FROM people_confirmed p
            JOIN faces f ON f.face_uid=p.face_uid
            JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
            JOIN embeddings e ON e.face_uid=f.face_uid AND e.source_revision=c.revision
            WHERE NOT EXISTS(SELECT 1 FROM people_model_carry pc
                WHERE pc.media_key=f.media_key AND pc.ordinal=f.ordinal)""")
    }

    /** Called only inside the current lease's atomic completion transaction. */
    private fun restoreModelCarry(db: SQLiteDatabase, lease: IndexedFaceLease,
                                  ordinal: Int, uid: String, box: IndexedFaceBox,
                                  frameMs: Long?) {
        // SQLite's rawQuery selectionArgs cannot safely represent a nullable
        // video timestamp: use two fixed SQL shapes, never interpolate content.
        val sql = """SELECT person_key FROM people_model_carry
            WHERE media_key=? AND revision=? AND detector_version=? AND ordinal=?
                AND ABS(l - ?) < 0.000001 AND ABS(t - ?) < 0.000001
                AND ABS(r - ?) < 0.000001 AND ABS(b - ?) < 0.000001""" +
            if (frameMs == null) " AND frame_ms IS NULL" else " AND frame_ms=?"
        val args = arrayOf(lease.media.key, lease.media.revision, lease.detectorVersion,
            ordinal.toString(), box.left.toString(), box.top.toString(),
            box.right.toString(), box.bottom.toString())
        val person = db.rawQuery(sql, if (frameMs == null) args else args + frameMs.toString())
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (person != null) {
            check(db.insertOrThrow("people_confirmed", null, ContentValues().apply {
                put("face_uid", uid); put("person_key", person)
            }) != -1L) { "Model carry confirmation was not saved" }
        }
    }

    /**
     * #579: the fingerprint of the [ClusterPolicy] the published membership
     * was made with. Additive and idempotent like `people_home`, so it is
     * created on open for files from before it, without a schema bump.
     */
    private fun createClusterPolicyTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS cluster_policy(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            fingerprint TEXT CHECK(fingerprint IS NULL OR length(fingerprint) BETWEEN 1 AND 256))""")
        db.execSQL("INSERT OR IGNORE INTO cluster_policy(singleton) VALUES(1)")
        // #579: People home counts unnamed groups by anchor; additive, rows unchanged.
        db.execSQL("""CREATE INDEX IF NOT EXISTS cluster_members_anchor
            ON cluster_members(anchor_uid)""")
    }

    private fun storedPolicyFingerprint(db: SQLiteDatabase): String? =
        db.rawQuery("SELECT fingerprint FROM cluster_policy WHERE singleton=1", null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    private fun hasMembership(db: SQLiteDatabase): Boolean =
        db.rawQuery("SELECT 1 FROM cluster_members LIMIT 1", null).use { it.moveToFirst() }

    /**
     * #579: membership published under other grouping rules must be
     * regrouped under [ClusterPolicy.DEFAULT]. Opening only marks the
     * generation dirty (cluster reads return null, as during a scan) and
     * writes no row; the next publication drops the old membership and
     * regroups every stored face from its stored vector, inside its own
     * transaction (see [regroupIfPolicyChanged]). No rescan.
     */
    private fun regroupOnPolicyChange(db: SQLiteDatabase) {
        // Every file published before #579 used SFACE_CONSERVATIVE, the only
        // policy that had shipped: record it, so the file is compared with
        // the rules it was really grouped under.
        if (storedPolicyFingerprint(db) == null) {
            db.execSQL("UPDATE cluster_policy SET fingerprint=? WHERE singleton=1",
                arrayOf(ClusterPolicy.SFACE_CONSERVATIVE.fingerprint))
        }
        if (storedPolicyFingerprint(db) == ClusterPolicy.DEFAULT.fingerprint) return
        if (hasMembership(db)) db.execSQL("UPDATE cluster_generation SET dirty=1 WHERE singleton=1")
    }

    /**
     * Inside a publication: if the stored membership was made under other
     * rules, delete it so every unconfirmed face is pending again (placed in
     * parts like a scan's), and record the current fingerprint. Review facts
     * (names, confirmations, "not the same person", rejections) are never
     * touched, and clustering never overrides them.
     */
    private fun regroupIfPolicyChanged(db: SQLiteDatabase) {
        val current = ClusterPolicy.DEFAULT.fingerprint
        if (storedPolicyFingerprint(db) == current) return
        meteredStatements += 2
        db.delete("cluster_members", null, null)
        db.execSQL("UPDATE cluster_policy SET fingerprint=? WHERE singleton=1", arrayOf(current))
    }

    private fun createEmbeddingTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS embeddings(
            face_uid TEXT PRIMARY KEY NOT NULL REFERENCES faces(face_uid) ON DELETE CASCADE,
            source_revision TEXT NOT NULL, model_version TEXT NOT NULL,
            vector BLOB NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS cluster_members(
            face_uid TEXT PRIMARY KEY NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
            anchor_uid TEXT NOT NULL)""")
        createClusterGeneration(db)
    }

    /**
     * v3 identity review. Face-keyed rows reference `embeddings`, so they
     * cascade with the observation (source change, rescan, model bump).
     * Person names are not face-keyed: a model bump retains them (#48). `people_review_generation` changes on every
     * review commit and cluster publication (see [StoredPeopleReview]).
     */
    private fun createPeopleReviewTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_names(
            person_key TEXT PRIMARY KEY NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256),
            name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 80))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_confirmed(
            face_uid TEXT PRIMARY KEY NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
            person_key TEXT NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS people_confirmed_person
            ON people_confirmed(person_key)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_cannot_link(
            face_a TEXT NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
            face_b TEXT NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
            CHECK(face_a < face_b), PRIMARY KEY(face_a, face_b))""")
        // Foreign-key cascades look children up by face_b too.
        db.execSQL("""CREATE INDEX IF NOT EXISTS people_cannot_link_b
            ON people_cannot_link(face_b)""")
        createSameUnknownTable(db)
        createReviewTallyTable(db)
        createPeopleMatchedTable(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_rejected(
            face_uid TEXT NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
            person_key TEXT NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256),
            PRIMARY KEY(face_uid, person_key))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_review_generation(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            revision INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("INSERT OR IGNORE INTO people_review_generation(singleton) VALUES(1)")
        createPeopleHomeTables(db)
    }

    /**
     * #580: a "yes" to "same person?" between two unnamed groups, kept as one
     * face of each. Additive and idempotent like `people_home`, so it is also
     * created on open for files from before it, without a schema bump; an
     * older build ignores the table.
     */
    private fun createSameUnknownTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_same_unknown(
            face_a TEXT NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
            face_b TEXT NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
            CHECK(face_a < face_b), PRIMARY KEY(face_a, face_b))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS people_same_unknown_b
            ON people_same_unknown(face_b)""")
    }

    /**
     * #580: the faces grouping shows under a named person on its own
     * ([FaceClusterState.matched]), so readers need no cluster state. Derived
     * like `cluster_members`: written in the same transaction as membership
     * by every publication and review commit, read back before commit, and
     * always equal to the state's matches. A matched face keeps its own
     * membership row anchored on itself, and the row references it, so
     * dropping membership (regroup, model bump, source change, rescan)
     * drops the match with it. `people_matched_generation` holds the review
     * revision the rows were written at; any other value (a file from before
     * the table, or one an older build wrote to) is refilled at the next
     * publication, see [refillPeopleMatchedIfStale]. Additive and created on
     * open like `people_same_unknown`; an older build ignores both tables.
     */
    private fun createPeopleMatchedTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_matched(
            face_uid TEXT PRIMARY KEY NOT NULL REFERENCES cluster_members(face_uid) ON DELETE CASCADE,
            person_key TEXT NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS people_matched_person
            ON people_matched(person_key)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_matched_generation(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1), revision INTEGER)""")
        db.execSQL("INSERT OR IGNORE INTO people_matched_generation(singleton) VALUES(1)")
    }

    /**
     * #580: rows of `people_matched` written at another review revision than
     * the current one (a file from before the table, or a revision an older
     * build committed) may not equal the published matches. Mark the
     * generation dirty with a publication part due, so the host publishes at
     * once from stored vectors (see [regroupPending]); that publication
     * places no face anew and rewrites the rows. Writes no membership row.
     */
    private fun refillPeopleMatchedIfStale(db: SQLiteDatabase) {
        val written = db.rawQuery("SELECT revision FROM people_matched_generation WHERE singleton=1",
            null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        if (written == reviewRevision(db) || !hasMembership(db)) return
        db.execSQL("""UPDATE cluster_generation SET dirty=1,
            pending=MAX(pending,$PUBLICATION_CONTINUES) WHERE singleton=1""")
    }

    private fun matchedRows(db: SQLiteDatabase): Map<String, String> {
        meteredStatements++
        return db.rawQuery("SELECT face_uid,person_key FROM people_matched", null).use { c ->
            val rows = HashMap<String, String>(maxOf(16, c.count * 2))
            while (c.moveToNext()) { meteredRowsRead++; rows[c.getString(0)] = c.getString(1) }
            rows
        }
    }

    /**
     * Make `people_matched` equal [expected], after membership is written in
     * the same transaction (a row needs its face's membership row). Only rows
     * that differ are written; the table is read back before commit.
     */
    private fun writeMatched(db: SQLiteDatabase, expected: Map<String, String>) {
        val delta = PeopleReviewRecords.mapDelta(matchedRows(db), expected)
        for ((face, _) in delta.delete) {
            meteredStatements++
            db.delete("people_matched", "face_uid=?", arrayOf(face))
        }
        for ((face, person) in delta.insert) {
            meteredStatements++
            db.insertOrThrow("people_matched", null, ContentValues().apply {
                put("face_uid", face); put("person_key", person)
            })
        }
        check(matchedRows(db) == expected) { "People matches were not persisted" }
    }

    /** Record that `people_matched` is current for the revision [db] now holds. */
    private fun markMatchedCurrent(db: SQLiteDatabase) {
        meteredStatements++
        db.execSQL("UPDATE people_matched_generation SET revision=? WHERE singleton=1",
            arrayOf<Any>(reviewRevision(db)))
    }

    /**
     * #580: how many review taps of each kind were committed and how many
     * faces they covered (see [ReviewTap]). Counts only, keyed by kind, so
     * nothing here refers to a face or a person. Additive and created on open
     * like `people_same_unknown`; an older build ignores the table.
     */
    private fun createReviewTallyTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_review_tally(
            kind TEXT PRIMARY KEY NOT NULL CHECK(length(kind) BETWEEN 1 AND 32),
            taps INTEGER NOT NULL DEFAULT 0 CHECK(taps >= 0),
            faces INTEGER NOT NULL DEFAULT 0 CHECK(faces >= 0))""")
    }

    /**
     * #532 People home summary: derived, rebuilt with every review-generation
     * bump; `revision` NULL until the first rebuild (the read then runs the
     * query live). Additive and idempotent, so also created on open for files
     * from before it; holds nothing `people_names` and the facts don't hold.
     */
    private fun createPeopleHomeTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_home(
            person_key TEXT PRIMARY KEY NOT NULL CHECK(length(person_key) BETWEEN 1 AND 256),
            name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 80),
            face_count INTEGER NOT NULL CHECK(face_count>0),
            cover_uid TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS people_home_generation(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1), revision INTEGER)""")
        db.execSQL("INSERT OR IGNORE INTO people_home_generation(singleton) VALUES(1)")
    }

    /** Additive only: each step adds tables or columns, never drops data. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion !in 1..2 || newVersion != SCHEMA_VERSION)
            throw SQLiteException("Unsupported face index upgrade; never wipe confirmed People")
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE control ADD COLUMN embedder_version TEXT")
            createEmbeddingTables(db)
        }
        createPeopleReviewTables(db)
        ensureVideoColumns(db)
    }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported face index downgrade; no table reset")
    }

    private inline fun <T> write(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun validEpoch(epoch: String) =
        require(epoch.isNotBlank() && epoch.length <= 256 && '\u0000' !in epoch)
    private fun validVersion(version: String) =
        require(version.isNotBlank() && version.length <= 256 && '\u0000' !in version)
    private fun validMedia(media: IndexedMedia) {
        require(media.key.isNotBlank() && media.key.length <= 1024 &&
            !media.key.startsWith("/") && '\u0000' !in media.key &&
            !media.key.contains("/storage/emulated/")) { "Media identity must not be a raw file path" }
        require(media.revision.isNotBlank() && media.revision.length <= 256 &&
            '\u0000' !in media.revision) { "Missing or invalid source revision" }
    }
    private fun currentEpoch(db: SQLiteDatabase): String? =
        db.also { meteredStatements++ }.rawQuery("SELECT active_epoch FROM control WHERE singleton=1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    private fun stagedEpoch(db: SQLiteDatabase): String? =
        db.rawQuery("SELECT staging_epoch FROM control WHERE singleton=1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /**
     * Call SYNCHRONOUSLY on permission uncertainty/revocation, even when no
     * new permission epoch can be minted yet. Stale workers lose the right to
     * complete, and every read becomes invisible in the same DB transaction.
     * The caller still must cancel its worker and recheck actual Android grants.
     */
    fun invalidatePermissionScope() {
        write { db ->
            db.delete("stage", null, null)
            db.execSQL("""UPDATE control SET active_epoch=NULL,
                staging_epoch=NULL, dormant_epoch=NULL, staged_count=0 WHERE singleton=1""")
        }
    }

    /**
     * #31: makes the epoch that was active when this database was last
     * opened readable again, and returns it; null when there is none, or a
     * snapshot is staging or active. The CALLER must first confirm that the
     * live Android grant equals the grant that epoch was published under.
     * Any invalidation or new snapshot drops the dormant epoch for good.
     */
    /** #31: the readable epoch now, if any. */
    fun activeEpoch(): String? = currentEpoch(readableDatabase)

    fun reviveDormantEpoch(): String? = write { db ->
        val (active, staging, dormant) = db.rawQuery(
            "SELECT active_epoch, staging_epoch, dormant_epoch FROM control WHERE singleton=1",
            null).use { c ->
            check(c.moveToFirst()); Triple(c.getString(0), c.getString(1), c.getString(2))
        }
        if (active != null || staging != null || dormant == null) return@write null
        db.execSQL("UPDATE control SET active_epoch=?, dormant_epoch=NULL WHERE singleton=1",
            arrayOf(dormant))
        dormant
    }

    /**
     * Cleanup for a FAILED or CANCELLED scan that owns [epoch], NOT a grant
     * revocation. An older publisher's finally block must never hide a newer
     * permission epoch or its staged/committed catalogue.
     *
     * Compare and clear under ONE SQLite writer transaction; returning false
     * means the caller has lost ownership and MUST NOT retry unconditionally.
     * On actual Android grant loss use invalidatePermissionScope() instead.
     */
    fun invalidateSnapshotIfCurrent(epoch: String): Boolean {
        validEpoch(epoch)
        return write { db ->
            val owned = db.rawQuery("""SELECT 1 FROM control WHERE singleton=1
                AND (active_epoch=? OR staging_epoch=?)""", arrayOf(epoch,epoch))
                .use { it.moveToFirst() }
            if (!owned) return@write false
            db.delete("stage", null, null)
            db.execSQL("""UPDATE control SET active_epoch=NULL,
                staging_epoch=NULL, dormant_epoch=NULL, staged_count=0 WHERE singleton=1""")
            true
        }
    }

    /**
     * MUST be called immediately when permission visibility becomes uncertain:
     * active observations become unreadable before any new catalog is staged.
     */
    fun beginSnapshot(epoch: String, keepActiveReadable: Boolean = false) {
        validEpoch(epoch)
        val accepted = write { db ->
            // A delayed worker may outlive a newer permission epoch. Replaying
            // its already-used token must NOT revoke a newer active/staged
            // snapshot. Only an ABA replay of the currently active/staging
            // token is fail-closed: hide its own stale visibility before throw.
            val used = db.rawQuery("SELECT 1 FROM epoch_history WHERE epoch=?",
                arrayOf(epoch)).use { it.moveToFirst() }
            if (used) {
                if (currentEpoch(db) == epoch || stagedEpoch(db) == epoch) {
                    db.delete("stage", null, null)
                    db.execSQL("""UPDATE control SET active_epoch=NULL,
                        staging_epoch=NULL, dormant_epoch=NULL, staged_count=0
                        WHERE singleton=1""")
                }
                return@write false
            }
            // A genuinely new token invalidates all earlier grant visibility
            // in the same SQLite transaction that starts the new snapshot.
            // #31: unless the caller has just re-checked that the Android
            // grant is unchanged, then the published catalogue stays readable
            // until commitSnapshot replaces it in one transaction. Staging
            // writes only the stage table, never the catalogue it serves.
            db.delete("stage", null, null)
            if (keepActiveReadable) {
                db.execSQL("""UPDATE control SET staging_epoch=NULL, dormant_epoch=NULL,
                    staged_count=0 WHERE singleton=1""")
            } else {
                db.execSQL("""UPDATE control SET active_epoch=NULL,
                    staging_epoch=NULL, dormant_epoch=NULL, staged_count=0 WHERE singleton=1""")
            }
            db.insertOrThrow("epoch_history", null,
                ContentValues().apply { put("epoch", epoch) })
            db.execSQL("UPDATE control SET staging_epoch=?, staged_count=0 WHERE singleton=1",
                arrayOf(epoch))
            true
        }
        require(accepted) { "Permission epoch already used; new epoch required" }
    }

    /**
     * An inconsistent batch aborts the WHOLE staged snapshot, never a partial publish.
     * [takenAtMillis] (#508), parallel to [rows], orders the scan newest first;
     * without it the rows sort after every dated row.
     */
    fun stageBatch(epoch: String, rows: List<IndexedMedia>, takenAtMillis: LongArray? = null) {
        validEpoch(epoch)
        val validInput = rows.size <= 1024 && runCatching {
            rows.forEach(::validMedia)
        }.isSuccess && (takenAtMillis == null || takenAtMillis.size == rows.size)
        val accepted = write { db ->
            require(stagedEpoch(db) == epoch) { "Stale or unstarted permission snapshot" }
            val currentCount = db.rawQuery(
                "SELECT staged_count FROM control WHERE singleton=1", null
            ).use { c -> check(c.moveToFirst()); c.getInt(0) }
            var totalCount = currentCount
            var consistent = validInput
            if (consistent) {
                for ((index, media) in rows.withIndex()) {
                    val values = ContentValues().apply {
                        put("media_key", media.key); put("revision", media.revision)
                        if (takenAtMillis != null) put("taken_ms", takenAtMillis[index])
                    }
                    if (db.insertWithOnConflict("stage", null, values,
                            SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                        val previous = db.rawQuery(
                            "SELECT revision FROM stage WHERE media_key=?", arrayOf(media.key)
                        ).use { c -> check(c.moveToFirst()); c.getString(0) }
                        if (previous != media.revision) { consistent = false; break }
                    } else {
                        totalCount++
                        if (totalCount > maxCatalogMedia) {
                            consistent = false
                            break
                        }
                    }
                }
            }
            if (!consistent) {
                db.delete("stage", null, null)
                db.execSQL("UPDATE control SET staging_epoch=NULL, staged_count=0 WHERE singleton=1")
            } else {
                db.execSQL("UPDATE control SET staged_count=? WHERE singleton=1",
                    arrayOf(totalCount))
            }
            consistent
        }
        require(accepted) { "Invalid or conflicting staged media; fresh permission epoch required" }
    }

    /** Prune inaccessible assets, invalidate changed sources, then publish atomically. */
    fun commitSnapshot(epoch: String, detectorVersion: String,
                       embedderVersion: String = SFaceModel.EMBEDDER_VERSION) {
        validEpoch(epoch); validVersion(detectorVersion); validVersion(embedderVersion)
        write { db ->
            require(stagedEpoch(db) == epoch) { "Staged permission snapshot is stale" }
            val priorModel = db.rawQuery(
                "SELECT embedder_version FROM control WHERE singleton=1", null
            ).use { c -> check(c.moveToFirst()); c.getString(0) }
            // #543: rows the pruning statements below change. Their cascades
            // (embeddings, memberships, facts) always come with a counted
            // parent row, so zero means membership is exactly as published.
            val beforePrune = totalChanges(db)
            val modelChanged = FaceEmbeddingVersionFence.changed(priorModel, embedderVersion)
            if (modelChanged) {
                // #48: journal only completed prior-model confirmations before
                // marking photos queued; captureModelCarry requires processed
                // catalog rows with a matching source embedding revision.
                // Both original face rows and confirmations still exist here.
                captureModelCarry(db)
                // Model/alignment bump or v1 migration: force re-embed before displaying clusters.
                db.execSQL("""UPDATE catalog SET state='queued', attempts=0, skip_reason=NULL,
                    lease=NULL, lease_epoch=NULL, lease_deadline=NULL""")
                db.delete("faces", null, null) // cascades embeddings, memberships, facts
                db.delete("people_home", null, null)
                db.execSQL("UPDATE people_home_generation SET revision=NULL WHERE singleton=1")
            }
            db.execSQL("""DELETE FROM catalog WHERE NOT EXISTS
                (SELECT 1 FROM stage WHERE stage.media_key=catalog.media_key)""")
            // Changed media or detector invalidates all prior boxes/review decisions.
            db.execSQL("""UPDATE catalog SET
                revision=(SELECT revision FROM stage WHERE stage.media_key=catalog.media_key),
                detector_version=?, state='queued', attempts=0, skip_reason=NULL,
                lease=NULL, lease_epoch=NULL, lease_deadline=NULL
                WHERE EXISTS (SELECT 1 FROM stage WHERE stage.media_key=catalog.media_key)
                AND (revision != (SELECT revision FROM stage
                      WHERE stage.media_key=catalog.media_key) OR detector_version != ?)""",
                arrayOf(detectorVersion, detectorVersion))
            db.execSQL("""DELETE FROM faces WHERE media_key IN
                (SELECT media_key FROM catalog WHERE state!='processed')""")
            // Do not re-attach to deleted, revised or re-detected photos.
            db.execSQL("""DELETE FROM people_model_carry WHERE NOT EXISTS
                (SELECT 1 FROM catalog c WHERE c.media_key=people_model_carry.media_key
                    AND c.revision=people_model_carry.revision
                    AND c.detector_version=people_model_carry.detector_version)""")
            val pruned = modelChanged || totalChanges(db) != beforePrune
            // Even identical media may have an in-flight old-epoch lease.
            db.execSQL("""UPDATE catalog SET state='queued',
                attempts=MAX(0, attempts-1),
                lease=NULL, lease_epoch=NULL, lease_deadline=NULL WHERE state='running'""")
            db.execSQL("""INSERT INTO catalog(media_key,revision,detector_version,state,taken_ms)
                SELECT s.media_key,s.revision,?,'queued',s.taken_ms FROM stage s
                WHERE NOT EXISTS(SELECT 1 FROM catalog c WHERE c.media_key=s.media_key)""",
                arrayOf(detectorVersion))
            // #508: keep the newest-first order current; only changed rows are written.
            db.execSQL("""UPDATE catalog SET taken_ms=(SELECT s.taken_ms FROM stage s
                    WHERE s.media_key=catalog.media_key)
                WHERE taken_ms IS NOT (SELECT s.taken_ms FROM stage s
                    WHERE s.media_key=catalog.media_key)""")
            // Snapshot reconciliation can cascade-delete embedding rows. Never
            // publish an old membership generation after a source/model change:
            // any prune (removed photo, changed revision or detector, model
            // bump, dropped faces) invalidates the generation. A reconciliation
            // that changed none of those keeps it as it was (#543): new photos
            // are only queued, and a restart's fresh epoch then shows People
            // at once instead of waiting for a full cluster rebuild.
            // #39: the last publication stays readable while dirty, minus the
            // pruned faces, under a new review revision (see [servable]).
            if (pruned) {
                db.execSQL("UPDATE cluster_generation SET dirty=1 WHERE singleton=1")
                retirePublishedRevision(db)
            }
            // Bulk SQLite changes can be silently ignored by triggers.
            // Never authorize partial keys, stale source/model metadata or
            // unreleased leases under a newly published permission epoch.
            val expectedCount = db.rawQuery(
                "SELECT staged_count FROM control WHERE singleton=1",null
            ).use { c -> check(c.moveToFirst()); c.getInt(0) }
            val actualCount = db.rawQuery(
                "SELECT COUNT(*) FROM catalog",null
            ).use { c -> check(c.moveToFirst()); c.getInt(0) }
            check(actualCount == expectedCount) {
                "Face catalog publication missed staged rows or retained inaccessible media"
            }
            // Equal row counts are NOT proof of equal membership: a
            // suppressed DELETE of an inaccessible key plus a suppressed
            // INSERT of a newly authorized key can cancel each other out.
            // Both directions are required before granting visibility.
            val missingOrStale = db.rawQuery(
                """SELECT 1 FROM stage s LEFT JOIN catalog c ON c.media_key=s.media_key
                    WHERE c.media_key IS NULL OR c.revision!=s.revision
                       OR c.detector_version!=? OR c.state='running'
                       OR c.lease IS NOT NULL OR c.lease_epoch IS NOT NULL
                       OR c.lease_deadline IS NOT NULL LIMIT 1""",
                arrayOf(detectorVersion)
            ).use { it.moveToFirst() }
            check(!missingOrStale) {
                "Face catalog publication missed or changed a staged media identity"
            }
            val inaccessibleRetained = db.rawQuery(
                """SELECT 1 FROM catalog c LEFT JOIN stage s ON s.media_key=c.media_key
                    WHERE s.media_key IS NULL LIMIT 1""",null
            ).use { it.moveToFirst() }
            check(!inaccessibleRetained) {
                "Face catalog publication retained inaccessible media"
            }
            db.delete("stage", null, null)
            db.execSQL("""UPDATE control SET active_epoch=?, staging_epoch=NULL,
                detector_version=?, embedder_version=?, staged_count=0 WHERE singleton=1""",
                arrayOf(epoch,detectorVersion,embedderVersion))
        }
    }

    /** Call once after taking exclusive worker ownership on process restart. */
    fun recoverInterrupted() {
        write { db ->
            db.execSQL("""UPDATE catalog SET
                state=CASE WHEN attempts>=3 THEN 'failed' ELSE 'queued' END,
                lease=NULL, lease_epoch=NULL, lease_deadline=NULL WHERE state='running'""")
        }
    }

    /** Monotonic elapsed-time deadline; stale/rebooted leases recover on startup. */
    fun claim(epoch: String, nowMillis: Long = SystemClock.elapsedRealtime(),
              leaseMillis: Long = 30_000): IndexedFaceLease? {
        validEpoch(epoch)
        require(nowMillis in 0..(Long.MAX_VALUE - 300_000) && leaseMillis in 1..300_000) { "Invalid lease deadline" }
        return write { db ->
            if (currentEpoch(db) != epoch) return@write null
            db.execSQL("""UPDATE catalog SET state='failed', lease=NULL,
                lease_epoch=NULL, lease_deadline=NULL WHERE state='running'
                AND attempts>=3 AND lease_deadline<?""", arrayOf(nowMillis))
            // Separate index-friendly probes; don't sort the entire 400k-item
            // catalog by an expression on every new photo claim. #508: newest
            // photo first (catalog_newest), so People is useful early.
            fun first(sql: String, args: Array<String>? = null): Triple<String,String,String>? =
                db.rawQuery(sql, args).use { c ->
                    if (c.moveToFirst())
                        Triple(c.getString(0), c.getString(1), c.getString(2))
                    else null
                }
            val row = first("""SELECT media_key,revision,detector_version FROM catalog
                WHERE state='queued' ORDER BY taken_ms DESC, media_key LIMIT 1""")
                ?: first("""SELECT media_key,revision,detector_version FROM catalog
                    WHERE state='running' AND attempts<3 AND lease_deadline<?
                    ORDER BY lease_deadline,media_key LIMIT 1""",
                    arrayOf(nowMillis.toString()))
                ?: return@write null
            val token = UUID.randomUUID().toString()
            db.execSQL("""UPDATE catalog SET state='running', attempts=attempts+1,
                lease=?,lease_epoch=?,lease_deadline=? WHERE media_key=?""",
                // Explicit element type: Kotlin 2.4 rejects reifying the
                // inferred Comparable<*> & Serializable intersection.
                arrayOf<Any>(token,epoch,nowMillis+leaseMillis,row.first))
            val claimed = IndexedFaceLease(
                IndexedMedia(row.first,row.second),row.third,epoch,token)
            // A silently ignored UPDATE must not hand P a fake reservation
            // for which no durable matching token actually exists.
            check(matches(db,claimed)) { "Face scan lease was not persisted" }
            claimed
        }
    }

    private fun matches(db: SQLiteDatabase, lease: IndexedFaceLease): Boolean =
        db.also { meteredStatements++ }.rawQuery("""SELECT 1 FROM catalog WHERE media_key=? AND revision=?
            AND detector_version=? AND state='running'
            AND lease=? AND lease_epoch=?""",
            arrayOf(lease.media.key,lease.media.revision,lease.detectorVersion,
                lease.token,lease.permissionEpoch)).use { it.moveToFirst() }

    /**
     * All-or-nothing face observation replacement, only for exact current
     * permission epoch, source revision, model version and opaque lease.
     */
    fun complete(lease: IndexedFaceLease, boxes: List<IndexedFaceBox>,
                 frameMs: List<Long?> = emptyList()): Boolean {
        require(boxes.size <= 32) { "Unbounded face detector output" }
        requireFrames(boxes.size, frameMs)
        validEpoch(lease.permissionEpoch); validVersion(lease.detectorVersion)
        validMedia(lease.media)
        return write { db ->
            if (currentEpoch(db) != lease.permissionEpoch || !matches(db, lease))
                return@write false
            // #39: a removed face retires the review snapshots that held it.
            if (db.delete("faces","media_key=?",arrayOf(lease.media.key)) > 0)
                retirePublishedRevision(db)
            // Box-only completion may cascade an earlier embedding or cluster.
            db.execSQL("UPDATE cluster_generation SET dirty=1 WHERE singleton=1")
            boxes.forEachIndexed { index, b ->
                db.insertOrThrow("faces",null,ContentValues().apply {
                    put("media_key",lease.media.key);put("ordinal",index)
                    // Generated independently; cannot collide with V4 numeric face_id.
                    put("face_uid", UUID.randomUUID().toString().replace("-", ""))
                    put("l",b.left.toDouble());put("t",b.top.toDouble())
                    put("r",b.right.toDouble());put("b",b.bottom.toDouble())
                    putFrame(this, frameMs, index)
                })
            }
            db.execSQL("""UPDATE catalog SET state='processed',
                lease=NULL,lease_epoch=NULL,lease_deadline=NULL WHERE media_key=?""",
                arrayOf(lease.media.key))
            val recorded = db.rawQuery("""SELECT 1 FROM catalog
                WHERE media_key=? AND revision=? AND detector_version=?
                AND state='processed' AND lease IS NULL
                AND lease_epoch IS NULL AND lease_deadline IS NULL""",
                arrayOf(lease.media.key,lease.media.revision,lease.detectorVersion)
            ).use { it.moveToFirst() }
            // If the state update was ignored, roll back the new rectangles
            // as well. An uncommitted detector result is never "processed".
            check(recorded) { "Face completion state was not persisted" }
            true
        }
    }


    /**
     * One lease commits boxes, source/model-tagged vectors and cluster memberships
     * atomically. Neither a stale permission nor an old embedding model can finish.
     */
    fun completeEmbeddings(
        lease: IndexedFaceLease, boxes: List<IndexedFaceBox>,
        vectors: List<FloatArray>, embedderVersion: String,
        frameMs: List<Long?> = emptyList()
    ): Boolean {
        require(boxes.size <= 32 && boxes.size == vectors.size) { "Embedding/box count mismatch" }
        requireFrames(boxes.size, frameMs)
        validEpoch(lease.permissionEpoch); validVersion(lease.detectorVersion)
        validVersion(embedderVersion); validMedia(lease.media)
        val encoded = vectors.map(FaceEmbeddingVectorCodec::encode)
        return write { db -> metered(db, { lastCompletionWork = it }) {
            if (currentEpoch(db) != lease.permissionEpoch || !matches(db, lease) ||
                storedEmbedderVersion(db) != embedderVersion) return@metered false
            meteredStatements++
            // #39: a removed face retires the review snapshots that held it.
            if (db.delete("faces", "media_key=?", arrayOf(lease.media.key)) > 0)
                retirePublishedRevision(db)
            boxes.forEachIndexed { ordinal, box ->
                val uid = UUID.randomUUID().toString().replace("-", "")
                meteredStatements += 2
                db.insertOrThrow("faces", null, ContentValues().apply {
                    put("media_key", lease.media.key); put("ordinal", ordinal)
                    put("face_uid", uid)
                    put("l", box.left.toDouble()); put("t", box.top.toDouble())
                    put("r", box.right.toDouble()); put("b", box.bottom.toDouble())
                    putFrame(this, frameMs, ordinal)
                })
                db.insertOrThrow("embeddings", null, ContentValues().apply {
                    put("face_uid", uid); put("source_revision", lease.media.revision)
                    put("model_version", embedderVersion); put("vector", encoded[ordinal])
                })
                restoreModelCarry(db, lease, ordinal, uid, box, frameMs.getOrNull(ordinal))
            }
            // Rescanned media cannot claim an old decision again.
            db.delete("people_model_carry", "media_key=?", arrayOf(lease.media.key))
            // D2-5p: never read or rewrite other photos' clusters here; the
            // work above and below is bounded by this photo's (<=32) faces.
            // Until a batch publishes, clusterState is unavailable.
            meteredStatements += 2
            db.execSQL("""UPDATE cluster_generation
                SET dirty=1, pending=pending+1 WHERE singleton=1""")
            val pendingRecorded = db.rawQuery("""SELECT pending,dirty
                FROM cluster_generation WHERE singleton=1""",null).use { c ->
                check(c.moveToFirst()); c.getInt(0)>0 && c.getInt(1)==1
            }
            check(pendingRecorded) { "Cluster dirtiness was not persisted" }
            meteredStatements += 2
            db.execSQL("""UPDATE catalog SET state='processed', lease=NULL,
                lease_epoch=NULL, lease_deadline=NULL WHERE media_key=?""",
                arrayOf(lease.media.key))
            val recorded = db.rawQuery("""SELECT 1 FROM catalog
                WHERE media_key=? AND revision=? AND detector_version=?
                AND state='processed' AND lease IS NULL AND lease_epoch IS NULL
                AND lease_deadline IS NULL""",
                arrayOf(lease.media.key, lease.media.revision, lease.detectorVersion)
            ).use { it.moveToFirst() }
            check(recorded) { "Embedded scan completion was not persisted" }
            true
        } }
    }

    /** Empty: every face is a photo face. Else one non-negative time (or null) per box. */
    private fun requireFrames(boxes: Int, frameMs: List<Long?>) =
        require((frameMs.isEmpty() || frameMs.size == boxes) &&
            frameMs.all { it == null || it >= 0 }) { "Invalid video frame positions" }
    private fun putFrame(values: ContentValues, frameMs: List<Long?>, ordinal: Int) {
        val at = frameMs.getOrNull(ordinal)
        if (at == null) values.putNull("frame_ms") else values.put("frame_ms", at)
    }

    private fun storedEmbedderVersion(db: SQLiteDatabase): String? =
        db.also { meteredStatements++ }.rawQuery("SELECT embedder_version FROM control WHERE singleton=1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /**
     * Rebuild incremental clustering from persisted vectors and memberships.
     * With [onlyMaterialized], only faces that already have a published
     * membership row: the seed of the next publication.
     */
    private fun buildClusterState(db: SQLiteDatabase, version: String,
                                  onlyMaterialized: Boolean = false): FaceClusterState {
        val embeddings = ArrayList<FaceEmbedding>()
        val groups = LinkedHashMap<String, MutableList<NativeFaceObservationId>>()
        val facts = reviewFacts(db)
        meteredStatements++
        // A confirmed face has no membership row but is part of the seed:
        // it feeds its person's centroid and is never re-placed as unknown.
        db.rawQuery("""SELECT e.face_uid,e.vector,m.anchor_uid,f.media_key,f.frame_ms FROM embeddings e
            JOIN faces f ON f.face_uid=e.face_uid
            JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                AND c.revision=e.source_revision
            LEFT JOIN cluster_members m ON m.face_uid=e.face_uid
            WHERE e.model_version=? ${if (onlyMaterialized) """AND (m.face_uid IS NOT NULL
                OR EXISTS (SELECT 1 FROM people_confirmed p WHERE p.face_uid=e.face_uid))"""
                else ""} ORDER BY e.face_uid""", arrayOf(version)).use { c ->
            while (c.moveToNext()) {
                meteredRowsRead++
                val id = NativeFaceObservationId(c.getString(0))
                embeddings += ClusterPolicy.DEFAULT.embedding(id, version,
                    FaceEmbeddingVectorCodec.decode(c.getBlob(1)),
                    ClusterFrameKey.of(c.getString(3), if (c.isNull(4)) null else c.getLong(4)))
                if (!c.isNull(2)) groups.getOrPut(c.getString(2)) { ArrayList() }.add(id)
            }
        }
        if (embeddings.isEmpty()) return FaceClusterState.empty(
            version, SFaceModel.DIMENSION, ClusterPolicy.DEFAULT)
        return FaceClusterer.restore(version, ClusterPolicy.DEFAULT,
            embeddings, groups.values, facts)
    }

    private fun reviewRows(db: SQLiteDatabase): PeopleReviewRecords.Rows {
        fun pairs(sql: String): Set<Pair<String, String>> {
            meteredStatements++
            return db.rawQuery(sql, null).use { c ->
                val out = HashSet<Pair<String, String>>(maxOf(16, c.count * 2))
                while (c.moveToNext()) { meteredRowsRead++; out += c.getString(0) to c.getString(1) }
                out
            }
        }
        val confirmed = pairs("SELECT face_uid,person_key FROM people_confirmed").toMap()
        return PeopleReviewRecords.Rows(confirmed,
            pairs("SELECT face_a,face_b FROM people_cannot_link"),
            pairs("SELECT face_uid,person_key FROM people_rejected"),
            pairs("SELECT face_a,face_b FROM people_same_unknown"))
    }
    private fun reviewFacts(db: SQLiteDatabase): ClusterReviewFacts {
        val rows = reviewRows(db)
        return if (rows.confirmed.isEmpty() && rows.cannotLink.isEmpty() &&
            rows.rejected.isEmpty() && rows.sameUnknown.isEmpty())
            ClusterReviewFacts.NONE else PeopleReviewRecords.facts(rows)
    }
    private fun personNames(db: SQLiteDatabase): Map<PersonKey, String> {
        meteredStatements++
        return db.rawQuery("SELECT person_key,name FROM people_names", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    meteredRowsRead++
                    put(PersonKey(c.getString(0)), c.getString(1))
                }
            }
        }
    }
    private fun hiddenPeople(db: SQLiteDatabase): Set<String> {
        meteredStatements++
        return db.rawQuery("SELECT person_key FROM people_hidden", null).use { c ->
            buildSet { while (c.moveToNext()) { meteredRowsRead++; add(c.getString(0)) } }
        }
    }
    private fun reviewRevision(db: SQLiteDatabase): Long {
        meteredStatements++
        return db.rawQuery("SELECT revision FROM people_review_generation WHERE singleton=1",
            null).use { c -> check(c.moveToFirst()); c.getLong(0) }
    }
    /** Any change to published clusters or facts invalidates older review snapshots. */
    private fun bumpReviewRevision(db: SQLiteDatabase, from: Long) {
        meteredStatements++
        db.execSQL("UPDATE people_review_generation SET revision=? WHERE singleton=1",
            arrayOf<Any>(from + 1))
        check(reviewRevision(db) == from + 1) {
            "People review generation was not advanced"
        }
    }

    /**
     * True when `cluster_members` holds exactly [expected], streamed so a
     * whole-library check holds no second copy of the rows (#508).
     */
    private fun membershipMatches(db: SQLiteDatabase, expected: Map<String, String>): Boolean {
        meteredStatements++
        var rows = 0
        db.rawQuery("SELECT face_uid,anchor_uid FROM cluster_members", null).use { c ->
            while (c.moveToNext()) {
                meteredRowsRead++
                if (expected[c.getString(0)] != c.getString(1)) return false
                rows++
            }
        }
        return rows == expected.size
    }

    /**
     * True while a publication was split (#508) and more pending faces wait
     * for the next part. The worker then keeps running after a drained queue.
     */
    fun clusterPublicationContinues(epoch: String): Boolean {
        validEpoch(epoch)
        val db = readableDatabase
        if (currentEpoch(db) != epoch) return false
        val gen = generation(db)
        return gen.dirty && gen.pending >= PUBLICATION_CONTINUES
    }

    /**
     * #580: true while membership published under other grouping rules waits
     * to be regrouped (see [regroupOnPolicyChange]). That publication needs
     * no photo and no model, only the stored vectors, so a host may run it at
     * once with [materializeClusters] instead of waiting for the next scan.
     */
    fun regroupPending(epoch: String): Boolean {
        validEpoch(epoch)
        val db = readableDatabase
        if (currentEpoch(db) != epoch) return false
        val gen = generation(db)
        if (!gen.dirty) return false
        // A part of a split publication is waiting (a regroup the process did
        // not live to finish has already recorded the new fingerprint): the
        // rest is placed from stored vectors all the same.
        if (gen.pending >= PUBLICATION_CONTINUES) return true
        return hasMembership(db) &&
            storedPolicyFingerprint(db) != ClusterPolicy.DEFAULT.fingerprint
    }

    /** Every `cluster_members` row, valid or stale. */
    private fun membershipRows(db: SQLiteDatabase): Map<String, String> {
        meteredStatements++
        return db.rawQuery("SELECT face_uid,anchor_uid FROM cluster_members", null).use { c ->
            val rows = HashMap<String, String>(maxOf(16, c.count * 2))
            while (c.moveToNext()) { meteredRowsRead++; rows[c.getString(0)] = c.getString(1) }
            rows
        }
    }

    private class Generation(val dirty: Boolean, val pending: Int, val published: Int)
    private fun generation(db: SQLiteDatabase): Generation {
        meteredStatements++
        return db.rawQuery("""SELECT dirty,pending,published FROM cluster_generation
            WHERE singleton=1""", null).use { c ->
            check(c.moveToFirst()); Generation(c.getInt(0) != 0, c.getInt(1), c.getInt(2))
        }
    }

    /**
     * Keep the epoch check, active embedder model and vector cursor inside one
     * SQLite transaction. A concurrent permission revocation or newer snapshot
     * must wait for this read to finish; if revocation wins first, no data is read.
     * A check in a separate statement without the transaction would be TOCTOU.
     */
    private inline fun <T> authorizedEmbeddingRead(
        epoch: String, denied: T, read: (SQLiteDatabase, String) -> T
    ): T {
        validEpoch(epoch)
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val version = db.rawQuery("""SELECT embedder_version FROM control
                WHERE singleton=1 AND active_epoch=?""", arrayOf(epoch)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return denied
            return read(db, version)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Publish cluster membership now, if the generation is dirty. Full-library
     * reads happen only here, never in [completeEmbeddings]; callers pace it
     * with [publishClustersIfDue]. A durable dirty flag denies every cluster
     * read until this transaction commits, also after process death.
     *
     * Seed = the last published membership (path dependence of FaceClusterer
     * is kept), plus every pending face in ONE update; see [ClusterPublication]
     * for the accepted batching deviation. Only changed rows are written.
     */
    fun materializeClusters(epoch: String): Boolean {
        validEpoch(epoch)
        return write { db -> publish(db, epoch, force = true, scanFinished = true) }
            .also { logFinishedPublication() }
    }

    /** The state of the last publication that placed every pending face. */
    @Volatile private var finishedPublication: FaceClusterState? = null

    /**
     * #579: one aggregates-only `ATLAS_FACE` line (see [FaceClusterDiagnostics])
     * after a publication that left nothing pending, outside the writer
     * transaction. Bounded CPU; never throws into the scan.
     */
    private fun logFinishedPublication() {
        val state = finishedPublication ?: return
        finishedPublication = null
        try {
            Log.i("ATLAS_FACE", FaceClusterDiagnostics.of(state).logLine())
        } catch (e: RuntimeException) {
            Log.w("ATLAS_FACE", "people_clusters diagnostic failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Per-photo hook for the scanner: publishes only when
     * [ClusterPublicationCadence] says so (or [scanFinished]); otherwise one
     * constant-cost read. Returns true when membership was published now.
     */
    fun publishClustersIfDue(epoch: String, scanFinished: Boolean): Boolean {
        validEpoch(epoch)
        return write { db -> publish(db, epoch, force = false, scanFinished = scanFinished) }
            .also { logFinishedPublication() }
    }

    private fun publish(db: SQLiteDatabase, epoch: String, force: Boolean,
                        scanFinished: Boolean): Boolean =
        metered(db, { lastPublicationWork = it }) {
            if (currentEpoch(db) != epoch) return@metered false
            val version = storedEmbedderVersion(db) ?: return@metered false
            val gen = generation(db)
            if (!gen.dirty) return@metered force
            if (!force && !ClusterPublicationCadence.due(gen.dirty, gen.pending,
                    gen.published, scanFinished)) return@metered false
            regroupIfPolicyChanged(db)
            val old = membershipRows(db)
            val seed = buildClusterState(db, version, onlyMaterialized = true)
            val pending = ArrayList<FaceEmbedding>()
            meteredStatements++
            // #508: at most publicationFaceLimit pending faces per transaction
            // (lowest face uid first), so one publication of a whole-library
            // scan stays a bounded step. More pending faces keep the
            // generation dirty and due: the next call publishes the next part.
            db.rawQuery("""SELECT e.face_uid,e.vector,f.media_key,f.frame_ms FROM embeddings e
                JOIN faces f ON f.face_uid=e.face_uid
                JOIN catalog c ON c.media_key=f.media_key
                    AND c.state='processed' AND c.revision=e.source_revision
                WHERE e.model_version=? AND NOT EXISTS
                    (SELECT 1 FROM cluster_members m WHERE m.face_uid=e.face_uid)
                AND NOT EXISTS
                    (SELECT 1 FROM people_confirmed p WHERE p.face_uid=e.face_uid)
                ORDER BY e.face_uid LIMIT ${publicationFaceLimit + 1}""",
                arrayOf(version)).use { c ->
                while (c.moveToNext()) {
                    meteredRowsRead++
                    pending += ClusterPolicy.DEFAULT.embedding(
                        NativeFaceObservationId(c.getString(0)), version,
                        FaceEmbeddingVectorCodec.decode(c.getBlob(1)),
                        ClusterFrameKey.of(c.getString(2), if (c.isNull(3)) null else c.getLong(3)))
                }
            }
            val more = pending.size > publicationFaceLimit
            if (more) pending.removeAt(pending.size - 1)
            val state = ClusterPublication.publish(seed, pending)
            val expected = ClusterPublication.membership(state)
            val confirmedFaces = state.reviews.confirmed.keys.count { it in state }
            check(expected.size + confirmedFaces == state.faceCount) {
                "Cluster publication lost members"
            }
            // One transaction: a reader never sees a partial generation and a
            // failing statement rolls everything back, dirty flag included.
            val delta = ClusterPublication.delta(old, expected)
            for (face in delta.delete) {
                meteredStatements++
                db.delete("cluster_members", "face_uid=?", arrayOf(face))
            }
            for ((face, anchor) in delta.upsert) {
                meteredStatements++
                db.insertWithOnConflict("cluster_members", null, ContentValues().apply {
                    put("face_uid", face); put("anchor_uid", anchor)
                }, SQLiteDatabase.CONFLICT_REPLACE).also {
                    check(it != -1L) { "Cluster publication write failed" }
                }
            }
            check(membershipMatches(db, expected)) {
                "Cluster publication changed membership or anchor"
            }
            writeMatched(db, PeopleReviewRecords.matchedRows(state))
            meteredStatements += 2
            val (dirty, due) = if (more) 1 to PUBLICATION_CONTINUES else 0 to 0
            db.execSQL("""UPDATE cluster_generation SET dirty=?,pending=?,published=?
                WHERE singleton=1""", arrayOf<Any>(dirty, due, expected.size))
            val clean = db.rawQuery("""SELECT dirty,pending,published FROM cluster_generation
                WHERE singleton=1""",null).use { c ->
                check(c.moveToFirst())
                c.getInt(0)==dirty && c.getInt(1)==due && c.getInt(2)==expected.size
            }
            check(clean) { "Cluster publication fence was not cleared" }
            bumpReviewRevision(db, reviewRevision(db))
            refreshPeopleHome(db, version)
            markMatchedCurrent(db)
            if (!more) finishedPublication = state
            true
        }

    /**
     * Never show incomplete cluster membership, including after a restart:
     * only a whole publication, never a half-written one. #39: while new faces
     * wait, the last publication without them.
     */
    fun clusterState(epoch: String): FaceClusterState? = peopleReview(epoch)?.clusters

    private fun clusterGenerationClean(db: SQLiteDatabase): Boolean =
        db.rawQuery("SELECT dirty FROM cluster_generation WHERE singleton=1", null).use { c ->
            check(c.moveToFirst()); c.getInt(0) == 0
        }

    /**
     * #39: true when `people_matched` was written at the current review
     * revision, i.e. a publication or review commit ran and nothing since
     * left its rows stale (see [refillPeopleMatchedIfStale]).
     */
    private fun matchedCurrent(db: SQLiteDatabase): Boolean {
        meteredStatements++
        val written = db.rawQuery("SELECT revision FROM people_matched_generation WHERE singleton=1",
            null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        return written == reviewRevision(db)
    }

    /**
     * #39: whether the last published People data may be read and reviewed.
     * A dirty generation only means new faces wait for the next publication
     * (or a regroup is due): published membership, names and facts stay as
     * the last publication or commit left them, minus faces that were since
     * removed, and every removal moves the review revision on
     * ([retirePublishedRevision]), so an older snapshot cannot commit. Not
     * servable before the first publication, while `people_matched` waits
     * for its refill (those rows may name the wrong person), or between the
     * parts of a split publication (#508; a regroup rebuilds membership in
     * parts, so a part alone is not a whole publication).
     */
    private fun servable(db: SQLiteDatabase): Boolean {
        val gen = generation(db)
        return !gen.dirty || (gen.pending < PUBLICATION_CONTINUES && matchedCurrent(db))
    }

    /**
     * #39: faces were removed from the published People data (their rows
     * cascaded away). Move the review revision on, so a snapshot that still
     * holds them is refused at commit, and keep `people_matched` marked
     * current: it lost only the removed faces' rows. `people_home` keeps the
     * old revision, so [peopleHome] counts live until the next refresh.
     * Nothing to do while the published data is not servable anyway. When no
     * published face is left (every photo edited, a model change), nothing
     * is marked current: People waits for the rescan's publication rather
     * than showing an empty publication as "No people".
     */
    private fun retirePublishedRevision(db: SQLiteDatabase) {
        if (!matchedCurrent(db)) return
        bumpReviewRevision(db, reviewRevision(db))
        meteredStatements++
        val anyPublished = db.rawQuery("""SELECT EXISTS(SELECT 1 FROM cluster_members)
            OR EXISTS(SELECT 1 FROM people_confirmed)""", null).use { c ->
            check(c.moveToFirst()); c.getInt(0) != 0
        }
        if (anyPublished) markMatchedCurrent(db)
    }

    /**
     * Published clusters with the durable review facts and names, read in one
     * epoch-gated transaction. Null while revoked, superseded or unpublished.
     * #39: while new faces wait for publication, the last published clusters
     * (pending faces left out, as a publication seeds from them).
     */
    fun peopleReview(epoch: String): StoredPeopleReview? =
        authorizedEmbeddingRead(epoch, null) { db, version ->
            val clean = clusterGenerationClean(db)
            if (!servable(db)) null
            else {
                val names = personNames(db)
                StoredPeopleReview(PeopleReviewRecords.generation(reviewRevision(db)),
                    buildClusterState(db, version, onlyMaterialized = !clean), names,
                    // A name dropped outside a commit never leaves a dangling hide.
                    hiddenPeople(db).mapTo(HashSet()) { PersonKey(it) }.filterTo(HashSet()) {
                        it in names
                    })
            }
        }

    /**
     * #62: one epoch-checked, consistent snapshot of only human-authored
     * People metadata. Never select a media key, URI, pathname or vector.
     */
    fun exportNativeNamesSnapshot(epoch: String): NativePeopleNamesSnapshot? =
        authorizedEmbeddingRead(epoch, null) { db, _ ->
            if (!clusterGenerationClean(db)) return@authorizedEmbeddingRead null
            fun pairs(sql: String): Set<Pair<String, String>> =
                db.rawQuery(sql, null).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0) to cursor.getString(1))
                        }
                    }
                }
            val names = personNames(db).entries.associate { it.key.value to it.value }
            val faceDecisions = db.rawQuery(
                """SELECT f.face_uid,r.decision FROM face_reviews r
                    JOIN faces f ON f.media_key=r.media_key AND f.ordinal=r.ordinal
                    JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'""", null
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
            NativePeopleNamesSnapshot(names, hiddenPeople(db),
                pairs("SELECT face_uid,person_key FROM people_confirmed").toMap(),
                pairs("SELECT face_a,face_b FROM people_cannot_link"),
                pairs("SELECT face_uid,person_key FROM people_rejected"),
                pairs("SELECT face_a,face_b FROM people_same_unknown"),
                faceDecisions)
        }

    /**
     * Non-destructive import: only keys already in the active native index
     * qualify. Missing identities/faces, or conflicting confirmations, are
     * counted and skipped. All writes share one SQLite transaction and epoch
     * check. A new identity fact forces a cluster publication from local
     * vectors; no media is ever read as part of import.
     */
    fun importNativeNamesSnapshot(
        epoch: String, incoming: NativePeopleNamesSnapshot
    ): NativePeopleNamesImportResult {
        validEpoch(epoch)
        // Revalidate before touching SQLite, including type/size/key bounds.
        NativePeopleNamesBackupCodec.encode(incoming)
        return write { db ->
            val version = storedEmbedderVersion(db) ?: error("People model unavailable")
            require(currentEpoch(db) == epoch &&
                clusterGenerationClean(db)) { "People index changed; reopen the backup" }
            fun strings(sql: String, args: Array<String>? = null): Set<String> =
                db.rawQuery(sql, args).use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
            val people = strings("SELECT person_key FROM people_names") +
                strings("SELECT DISTINCT person_key FROM people_confirmed")
            val faces = strings("""SELECT e.face_uid FROM embeddings e
                JOIN faces f ON f.face_uid=e.face_uid
                JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                    AND c.revision=e.source_revision
                WHERE e.model_version=?""", arrayOf(version))
            val observations = strings("""SELECT f.face_uid FROM faces f
                JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'""")
            var applied = 0
            var skipped = 0
            var reGroup = false
            for ((key, name) in incoming.names) {
                if (key !in people) { skipped++; continue }
                check(db.insertWithOnConflict("people_names", null,
                    ContentValues().apply { put("person_key", key); put("name", name) },
                    SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                applied++
            }
            for (key in incoming.hidden) {
                if (key !in people || key !in incoming.names) { skipped++; continue }
                check(db.insertWithOnConflict("people_hidden", null,
                    ContentValues().apply { put("person_key", key) },
                    SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                applied++
            }
            val oldConfirmed = reviewRows(db).confirmed
            val mergedConfirmed = oldConfirmed.toMutableMap()
            for ((face, person) in incoming.confirmed) {
                val current = mergedConfirmed[face]
                if (face !in faces || person !in people ||
                    (current != null && current != person)) {
                    skipped++; continue
                }
                if (current == null) {
                    check(db.insertOrThrow("people_confirmed", null,
                        ContentValues().apply {
                            put("face_uid", face); put("person_key", person)
                        }) != -1L)
                    db.delete("cluster_members", "face_uid=?", arrayOf(face))
                    mergedConfirmed[face] = person
                    reGroup = true
                }
                applied++
            }
            fun addPairs(table: String, a: String, b: String,
                         incomingPairs: Set<Pair<String, String>>) {
                for ((first, second) in incomingPairs) {
                    val valid = first in faces && second in faces &&
                        // The two contradictory pair kinds must never coexist.
                        !(table == "people_same_unknown" &&
                            db.rawQuery("""SELECT 1 FROM people_cannot_link
                                WHERE face_a=? AND face_b=?""",
                                arrayOf(first, second)).use { it.moveToFirst() }) &&
                        !(table == "people_cannot_link" &&
                            db.rawQuery("""SELECT 1 FROM people_same_unknown
                                WHERE face_a=? AND face_b=?""",
                                arrayOf(first, second)).use { it.moveToFirst() }) &&
                        !(table == "people_cannot_link" &&
                            mergedConfirmed[first] != null &&
                            mergedConfirmed[first] == mergedConfirmed[second])
                    if (!valid) { skipped++; continue }
                    val count = db.insertWithOnConflict(table, null,
                        ContentValues().apply { put(a, first); put(b, second) },
                        SQLiteDatabase.CONFLICT_REPLACE)
                    check(count != -1L)
                    applied++
                    reGroup = true
                }
            }
            addPairs("people_cannot_link", "face_a", "face_b", incoming.cannotLink)
            for ((face, person) in incoming.rejected) {
                if (face !in faces || person !in people || mergedConfirmed[face] == person) {
                    skipped++; continue
                }
                check(db.insertWithOnConflict("people_rejected", null,
                    ContentValues().apply {
                        put("face_uid", face); put("person_key", person)
                    }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                applied++
                reGroup = true
            }
            addPairs("people_same_unknown", "face_a", "face_b", incoming.sameUnknown)
            for ((face, decision) in incoming.faceDecisions) {
                if (face !in observations || face in mergedConfirmed) {
                    skipped++; continue
                }
                val target = db.rawQuery("""SELECT media_key,ordinal FROM faces
                    WHERE face_uid=?""", arrayOf(face)).use { cursor ->
                    if (cursor.moveToFirst())
                        cursor.getString(0) to cursor.getInt(1) else null
                }
                if (target == null) { skipped++; continue }
                check(db.insertWithOnConflict("face_reviews", null,
                    ContentValues().apply {
                        put("media_key", target.first); put("ordinal", target.second)
                        put("decision", decision)
                    }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                applied++
            }
            if (applied > 0) {
                bumpReviewRevision(db, reviewRevision(db))
                if (reGroup) {
                    db.execSQL("""UPDATE cluster_generation SET dirty=1,
                        pending=MAX(pending,$PUBLICATION_CONTINUES)
                        WHERE singleton=1""")
                } else {
                    refreshPeopleHome(db, version)
                    markMatchedCurrent(db)
                }
            }
            NativePeopleNamesImportResult(applied, skipped)
        }
    }

    /**
     * People home (#532): named identities that still own a present,
     * processed, current-model face, with their face count and a stable cover
     * face. No vector is decoded and no cluster state is built.
     *
     * Served from `people_home`, which [refreshPeopleHome] rewrites in the same
     * transaction as every review-generation bump (publication and review
     * commit). Removing a face moves the revision on without a refresh
     * ([retirePublishedRevision]), and a new face counts nowhere here until
     * a publication, so a summary that carries the current revision is exact:
     * the read is one row per named person. A summary from another revision
     * (after a removal, or a file from before #532, until its next refresh)
     * falls back to the same query, run live. Generation and rows share this
     * authorized transaction. #39: served while new faces wait for
     * publication (see [servable]), so People never empties during a scan.
     */
    fun peopleHome(epoch: String): StoredPeopleHome? =
        authorizedEmbeddingRead(epoch, null) { db, version ->
            if (!servable(db)) return@authorizedEmbeddingRead null
            val revision = reviewRevision(db)
            meteredStatements++
            val built = db.rawQuery("SELECT revision FROM people_home_generation WHERE singleton=1",
                null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            val people = LinkedHashMap<PersonKey, StoredPersonSummary>()
            meteredStatements++
            val cursor = if (built == revision) db.rawQuery("""SELECT person_key,name,
                    face_count,cover_uid FROM people_home ORDER BY person_key""", null)
                else db.rawQuery(PEOPLE_HOME_SELECT, arrayOf(version))
            cursor.use { c ->
                while (c.moveToNext()) {
                    people[PersonKey(c.getString(0))] =
                        StoredPersonSummary(c.getString(1), c.getInt(2), c.getString(3))
                }
            }
            // #579: unnamed groups. Published membership never holds a
            // confirmed face, so each row here is an unknown face.
            val unnamed = ArrayList<StoredUnnamedGroup>()
            meteredStatements++
            db.rawQuery("""SELECT anchor_uid,COUNT(*) AS n FROM cluster_members
                GROUP BY anchor_uid HAVING n>=$PEOPLE_HOME_MIN_GROUP
                ORDER BY n DESC, anchor_uid LIMIT $PEOPLE_HOME_MAX_GROUPS""", null).use { c ->
                while (c.moveToNext()) unnamed += StoredUnnamedGroup(c.getString(0), c.getInt(1))
            }
            StoredPeopleHome(PeopleReviewRecords.generation(revision), people, unnamed)
        }

    /**
     * Rewrite `people_home` for the revision [db] now holds. Called inside the
     * writer transaction right after each [bumpReviewRevision], so a reader
     * never sees a summary that disagrees with its generation. Cost is one
     * indexed lookup chain per confirmed face, paid in the background writer.
     */
    private fun refreshPeopleHome(db: SQLiteDatabase, version: String) {
        meteredStatements += 3
        db.delete("people_home", null, null)
        db.execSQL("""INSERT INTO people_home(person_key,name,face_count,cover_uid)
            $PEOPLE_HOME_SELECT""", arrayOf(version))
        db.execSQL("UPDATE people_home_generation SET revision=? WHERE singleton=1",
            arrayOf<Any>(reviewRevision(db)))
    }

    /**
     * Persist one review command's result atomically: the facts in
     * [clusters].reviews, [names] and the unknown membership of [clusters],
     * which the reducer computed from the snapshot named [expectedGeneration].
     * Returns the new generation, or null (nothing written) when the epoch is
     * no longer active, the model changed, published data is not servable
     * (see [servable]), or any publication, face removal or other review
     * committed since that snapshot. Only rows
     * that differ are written; every table is read back before commit.
     * [tally] (#580) is counted in the same transaction, so a refused or
     * rolled-back commit counts nothing; an undo also takes the undone tap
     * off its kind.
     */
    fun commitPeopleReview(
        epoch: String, expectedGeneration: String,
        clusters: FaceClusterState, names: Map<PersonKey, String>,
        hidden: Set<PersonKey> = emptySet(),
        tally: ReviewTally? = null
    ): String? {
        validEpoch(epoch)
        PeopleReviewRecords.requireConsistent(clusters, names, hidden)
        val hiddenRows = hidden.mapTo(HashSet()) { it.value }
        val newRows = PeopleReviewRecords.rows(clusters.reviews)
        val membership = ClusterPublication.membership(clusters)
        val nameRows = names.entries.associate { it.key.value to it.value }
        return write { db ->
            // #39: allowed while new faces wait for publication; the
            // snapshot leaves them out and the commit writes no row for them.
            if (currentEpoch(db) != epoch ||
                storedEmbedderVersion(db) != clusters.modelVersion ||
                !servable(db)) return@write null
            val revision = reviewRevision(db)
            if (PeopleReviewRecords.generation(revision) != expectedGeneration) return@write null
            val old = reviewRows(db)
            fun pairDelta(table: String, a: String, b: String,
                          delta: PeopleReviewRecords.SetDelta<Pair<String, String>>) {
                for ((x, y) in delta.delete)
                    db.delete(table, "$a=? AND $b=?", arrayOf(x, y))
                for ((x, y) in delta.insert)
                    db.insertOrThrow(table, null, ContentValues().apply { put(a, x); put(b, y) })
            }
            pairDelta("people_confirmed", "face_uid", "person_key",
                PeopleReviewRecords.mapDelta(old.confirmed, newRows.confirmed))
            pairDelta("people_cannot_link", "face_a", "face_b",
                PeopleReviewRecords.setDelta(old.cannotLink, newRows.cannotLink))
            pairDelta("people_rejected", "face_uid", "person_key",
                PeopleReviewRecords.setDelta(old.rejected, newRows.rejected))
            pairDelta("people_same_unknown", "face_a", "face_b",
                PeopleReviewRecords.setDelta(old.sameUnknown, newRows.sameUnknown))
            pairDelta("people_names", "person_key", "name", PeopleReviewRecords.mapDelta(
                personNames(db).entries.associate { it.key.value to it.value }, nameRows))
            val hiddenDelta = PeopleReviewRecords.setDelta(hiddenPeople(db), hiddenRows)
            for (person in hiddenDelta.delete)
                db.delete("people_hidden", "person_key=?", arrayOf(person))
            for (person in hiddenDelta.insert)
                db.insertOrThrow("people_hidden", null,
                    ContentValues().apply { put("person_key", person) })
            val memberDelta = ClusterPublication.delta(membershipRows(db), membership)
            for (face in memberDelta.delete)
                db.delete("cluster_members", "face_uid=?", arrayOf(face))
            for ((face, anchor) in memberDelta.upsert)
                check(db.insertWithOnConflict("cluster_members", null, ContentValues().apply {
                    put("face_uid", face); put("anchor_uid", anchor)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Review membership write failed" }
            // A suppressed write must roll the whole review back, not half-apply it.
            check(reviewRows(db) == newRows) { "Review facts were not persisted" }
            check(personNames(db).entries.associate { it.key.value to it.value } == nameRows) {
                "Person names were not persisted"
            }
            check(membershipRows(db) == membership) { "Review membership was not persisted" }
            writeMatched(db, PeopleReviewRecords.matchedRows(clusters))
            check(hiddenPeople(db) == hiddenRows) { "Hidden people were not persisted" }
            db.execSQL("UPDATE cluster_generation SET published=? WHERE singleton=1",
                arrayOf<Any>(membership.size))
            bumpReviewRevision(db, revision)
            refreshPeopleHome(db, clusters.modelVersion)
            markMatchedCurrent(db)
            if (tally != null) {
                db.execSQL("INSERT OR IGNORE INTO people_review_tally(kind) VALUES(?)",
                    arrayOf<Any>(tally.tap.key))
                db.execSQL("""UPDATE people_review_tally SET taps=taps+1, faces=faces+?
                    WHERE kind=?""", arrayOf<Any>(tally.faces, tally.tap.key))
                // An undo takes the undone tap off its kind, never below zero.
                tally.undoes?.let { undone ->
                    db.execSQL("""UPDATE people_review_tally SET taps=MAX(taps-1,0),
                        faces=MAX(faces-?,0) WHERE kind=?""",
                        arrayOf<Any>(undone.faces, undone.tap.key))
                }
            }
            PeopleReviewRecords.generation(revision + 1)
        }
    }

    /**
     * #580: the committed review taps per kind, under the active epoch. Null
     * while revoked or superseded. A kind this build does not know is left out.
     */
    fun peopleReviewTally(epoch: String): Map<ReviewTap, TapCount>? =
        authorizedEmbeddingRead(epoch, null) { db, _ ->
            val counts = LinkedHashMap<ReviewTap, TapCount>()
            db.rawQuery("SELECT kind,taps,faces FROM people_review_tally ORDER BY kind", null)
                .use { c ->
                    while (c.moveToNext()) {
                        val tap = ReviewTap.ofKey(c.getString(0)) ?: continue
                        counts[tap] = TapCount(c.getLong(1), c.getLong(2))
                    }
                }
            counts
        }

    /**
     * The current review generation alone: one cheap read, so a host can tell
     * whether its cached [peopleReview] snapshot is still current without
     * rebuilding clusters. Null while revoked, superseded or unpublished.
     */
    fun peopleReviewGeneration(epoch: String): String? =
        authorizedEmbeddingRead(epoch, null) { db, _ ->
            if (!servable(db)) null
            else PeopleReviewRecords.generation(reviewRevision(db))
        }

    /**
     * Source keys of the processed photos that hold a face of [person] under
     * the active epoch and model: a confirmed face, or (#580) one grouping
     * showed under the person on its own (`people_matched`, as of the last
     * publication). Ordered by key, at most [limit]. Keys only: the host
     * resolves each one against its current grant before anything is shown.
     * Empty while revoked or superseded.
     */
    fun personMedia(epoch: String, person: PersonKey,
                    limit: Int = 50_000): List<IndexedMedia> {
        require(limit in 1..200_000) { "Person album limit must be 1..200000" }
        return authorizedEmbeddingRead(epoch, emptyList()) { db, version ->
            meteredStatements++
            db.rawQuery("""SELECT media_key,revision FROM (
                    SELECT c.media_key,c.revision FROM people_confirmed p
                    JOIN embeddings e ON e.face_uid=p.face_uid AND e.model_version=?1
                    JOIN faces f ON f.face_uid=p.face_uid
                    JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                        AND c.revision=e.source_revision
                    WHERE p.person_key=?2
                    UNION
                    SELECT c.media_key,c.revision FROM people_matched pm
                    JOIN embeddings e ON e.face_uid=pm.face_uid AND e.model_version=?1
                    JOIN faces f ON f.face_uid=pm.face_uid
                    JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                        AND c.revision=e.source_revision
                    WHERE pm.person_key=?2)
                ORDER BY media_key LIMIT ?3""",
                arrayOf(version, person.value, limit.toString())).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        meteredRowsRead++
                        add(IndexedMedia(c.getString(0), c.getString(1)))
                    }
                }
            }
        }
    }

    /**
     * #551 person page: the processed photo or video that holds each of
     * [faces] (native face UIDs) under the active epoch and model, keyed by
     * face UID. A face whose media is not processed at the embedding's source
     * revision is left out. Keys only: the host resolves each one against its
     * current grant before anything is shown. Empty while revoked or superseded.
     */
    fun faceMedia(epoch: String, faces: Collection<String>): Map<String, IndexedMedia> {
        require(faces.size <= 400_000) { "Too many faces for one person page" }
        if (faces.isEmpty()) return emptyMap()
        return authorizedEmbeddingRead(epoch, emptyMap()) { db, version ->
            val out = HashMap<String, IndexedMedia>(faces.size * 2)
            for (chunk in faces.distinct().chunked(FACE_MEDIA_CHUNK)) {
                meteredStatements++
                val marks = chunk.joinToString(",") { "?" }
                db.rawQuery("""SELECT f.face_uid,c.media_key,c.revision FROM faces f
                    JOIN embeddings e ON e.face_uid=f.face_uid AND e.model_version=?
                    JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                        AND c.revision=e.source_revision
                    WHERE f.face_uid IN ($marks)""",
                    (listOf(version) + chunk).toTypedArray()).use { c ->
                    while (c.moveToNext()) {
                        meteredRowsRead++
                        out[c.getString(0)] = IndexedMedia(c.getString(1), c.getString(2))
                    }
                }
            }
            out
        }
    }

    /** Return only vectors matching a processed media revision and active model. */
    fun embeddingRecords(epoch: String, key: String): List<IndexedFaceEmbedding> =
        authorizedEmbeddingRead(epoch, emptyList()) { db, version ->
            db.rawQuery("""SELECT e.face_uid,e.source_revision,e.model_version,e.vector
                FROM embeddings e
                JOIN faces f ON f.face_uid=e.face_uid
                JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                    AND c.revision=e.source_revision
                JOIN control p ON p.singleton=1 AND p.active_epoch=?
                    AND p.embedder_version=e.model_version
                WHERE f.media_key=? AND e.model_version=? ORDER BY f.ordinal""",
                arrayOf(epoch, key, version)
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(IndexedFaceEmbedding(
                        c.getString(0), c.getString(1), c.getString(2),
                        FaceEmbeddingVectorCodec.decode(c.getBlob(3))))
                }
            }
        }

    /**
     * VS-F1 (#468), read-only: the in-memory search corpus of the active
     * [epoch] and embedder model, read in ONE epoch-gated transaction (see
     * [authorizedEmbeddingRead]): every embedded face of a processed source at
     * its current revision, minus faces reviewed NOT_A_FACE, with its box,
     * published cluster anchor and the human review facts. Cluster anchors are
     * left out while a publication is pending, so search never expands through
     * partial membership. Null while revoked, superseded or never committed.
     * Writes nothing; the caller holds the result only while the grant lasts.
     */
    fun faceSearchCorpus(epoch: String): FaceSearchCorpus? =
        authorizedEmbeddingRead(epoch, null) { db, version ->
            val published = clusterGenerationClean(db)
            val facts = reviewFacts(db)
            meteredStatements++
            db.rawQuery("""SELECT e.face_uid,e.vector,c.media_key,c.revision,
                    f.l,f.t,f.r,f.b,m.anchor_uid,f.frame_ms
                FROM embeddings e
                JOIN faces f ON f.face_uid=e.face_uid
                JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                    AND c.revision=e.source_revision
                LEFT JOIN cluster_members m ON m.face_uid=e.face_uid
                LEFT JOIN face_reviews r ON r.media_key=f.media_key AND r.ordinal=f.ordinal
                WHERE e.model_version=? AND (r.decision IS NULL OR r.decision<>'NOT_A_FACE')
                ORDER BY e.face_uid""", arrayOf(version)).use { c ->
                // No c.count: on Android that walks the whole result an extra time.
                val builder = FaceSearchCorpus.Builder(version)
                while (c.moveToNext()) {
                    meteredRowsRead++
                    builder.add(NativeFaceObservationId(c.getString(0)),
                        IndexedMedia(c.getString(2), c.getString(3)),
                        IndexedFaceBox(c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getFloat(7)),
                        if (!published || c.isNull(8)) null else NativeFaceObservationId(c.getString(8)),
                        FaceEmbeddingVectorCodec.decode(c.getBlob(1)),
                        if (c.isNull(9)) null else c.getLong(9))
                }
                builder.build(facts, published)
            }
        }

    /**
     * #571 "Same person?" inputs for the current review generation: every
     * published unknown-cluster member and every confirmed face that is
     * present, processed, of the current model and not reviewed NOT_A_FACE,
     * with its vector, plus the review facts. Null while revoked, superseded
     * or not servable, like [peopleHome].
     *
     * Cheap on purpose: it drives from `cluster_members` and
     * `people_confirmed` (pinned with CROSS JOIN, see [PEOPLE_HOME_SELECT]),
     * never scans faces that are in neither, and never restores or rebuilds
     * cluster state; `PersonMatchSuggester` does the arithmetic off this read.
     */
    fun personMatchInputs(epoch: String): PersonMatchInputs? =
        authorizedEmbeddingRead(epoch, null) { db, version ->
            if (!servable(db)) return@authorizedEmbeddingRead null
            val builder = PersonMatchInputs.Builder(
                PeopleReviewRecords.generation(reviewRevision(db)), version, reviewFacts(db))
            meteredStatements++
            db.rawQuery(PERSON_MATCH_SELECT, arrayOf(version, version)).use { c ->
                while (c.moveToNext()) {
                    meteredRowsRead++
                    builder.add(NativeFaceObservationId(c.getString(0)),
                        FaceEmbeddingVectorCodec.decode(c.getBlob(1)),
                        if (c.isNull(2)) null else NativeFaceObservationId(c.getString(2)),
                        if (c.isNull(3)) null else PersonKey(c.getString(3)))
                }
            }
            builder.build()
        }

    /** Revoked/stale worker may release only its OWN token without spending retry. */
    fun release(lease: IndexedFaceLease): Boolean = write { db ->
        if (!matches(db,lease)) return@write false
        db.execSQL("""UPDATE catalog SET state='queued', attempts=MAX(0,attempts-1),
            lease=NULL,lease_epoch=NULL,lease_deadline=NULL WHERE media_key=?""",
            arrayOf(lease.media.key))
        true
    }

    /** A bad decoder/detector consumes up to three retries, never marks processed. */
    fun fail(lease: IndexedFaceLease): Boolean = write { db ->
        if (currentEpoch(db)!=lease.permissionEpoch || !matches(db,lease))
            return@write false
        db.execSQL("""UPDATE catalog SET state=
            CASE WHEN attempts>=3 THEN 'failed' ELSE 'queued' END,
            lease=NULL,lease_epoch=NULL,lease_deadline=NULL WHERE media_key=?""",
            arrayOf(lease.media.key))
        true
    }

    /**
     * VS-V1b: a typed, terminal skip (corrupt or DRM video, no decodable
     * keyframe). Unlike [fail] it spends every retry at once and records why;
     * only a new source revision or model (commitSnapshot) queues it again.
     * Same exact epoch + lease rule as [complete]; never marks it processed.
     */
    fun skip(lease: IndexedFaceLease, reason: String): Boolean {
        require(reason.matches(SKIP_REASON)) { "Invalid skip reason" }
        return write { db ->
            if (currentEpoch(db) != lease.permissionEpoch || !matches(db, lease))
                return@write false
            db.execSQL("""UPDATE catalog SET state='failed', attempts=MAX(attempts,3),
                skip_reason=?, lease=NULL, lease_epoch=NULL, lease_deadline=NULL
                WHERE media_key=?""", arrayOf(reason, lease.media.key))
            val recorded = db.rawQuery("""SELECT 1 FROM catalog WHERE media_key=?
                AND revision=? AND state='failed' AND skip_reason=? AND lease IS NULL""",
                arrayOf(lease.media.key, lease.media.revision, reason)).use { it.moveToFirst() }
            check(recorded) { "Face source skip was not persisted" }
            true
        }
    }

    /** Typed skip reason of [key] under the active [epoch]; null for none or a stale epoch. */
    fun skipReason(epoch: String, key: String): String? {
        validEpoch(epoch)
        return readableDatabase.rawQuery("""SELECT m.skip_reason FROM catalog m
            JOIN control c ON c.singleton=1 AND c.active_epoch=?
            WHERE m.media_key=?""", arrayOf(epoch, key)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
    }

    /** Scope gate and aggregate counts execute as ONE SQLite read statement. */
    fun progress(epoch: String): IndexedFaceProgress? {
        validEpoch(epoch)
        return readableDatabase.rawQuery("""SELECT
            (SELECT COUNT(*) FROM catalog WHERE state='queued'),
            (SELECT COUNT(*) FROM catalog WHERE state='running'),
            (SELECT COUNT(*) FROM catalog WHERE state='processed'),
            (SELECT COUNT(*) FROM catalog WHERE state='failed'),
            (SELECT COUNT(*) FROM faces)
            FROM control WHERE singleton=1 AND active_epoch=?""", arrayOf(epoch)).use { c ->
                if (!c.moveToFirst()) null else IndexedFaceProgress(
                    c.getInt(0),c.getInt(1),c.getInt(2),c.getInt(3),c.getInt(4))
        }
    }

    /**
     * Accurate current Unknown inbox count without conflating observations
     * with already-dismissed reviews. All counters derive from the SAME
     * SQLite read statement and the SAME active permission epoch. An old
     * epoch returns null, never a cached or stale person's count.
     *
     * Run on I's IO worker, not the UI thread, especially for 150k media.
     * The existing IndexedFaceProgress.observations remains the raw total.
     */
    fun reviewCounts(epoch: String): IndexedFaceReviewCounts? {
        validEpoch(epoch)
        return readableDatabase.rawQuery(
            """SELECT COUNT(f.face_uid),
                COUNT(CASE WHEN r.decision IS NULL THEN f.face_uid END),
                COUNT(CASE WHEN r.decision='LATER' THEN f.face_uid END),
                COUNT(CASE WHEN r.decision='NOT_A_FACE' THEN f.face_uid END)
                FROM control c
                LEFT JOIN catalog m ON m.state='processed'
                LEFT JOIN faces f ON f.media_key=m.media_key
                LEFT JOIN face_reviews r ON r.media_key=f.media_key AND r.ordinal=f.ordinal
                WHERE c.singleton=1 AND c.active_epoch=?
                GROUP BY c.singleton""",
            arrayOf(epoch)
        ).use { c ->
            if (!c.moveToFirst()) null else IndexedFaceReviewCounts(
                c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
        }
    }

    /**
     * Read a bounded, deterministic page of *unreviewed* native detections.
     * Source authorization is checked by JOINing active_epoch in the very
     * same SELECT; the UI must additionally discard old pages immediately on
     * real Android grant loss and call invalidatePermissionScope().
     *
     * Ordinal is solely a stable keyset page position. Save LATER/NOT_A_FACE
     * against the opaque nativeFaceUid with reviewObservation(), never against
     * a possibly reused ordinal or a confirmed V4 identity.
     *
     * A new snapshot owns a fresh one-time epoch. A cursor from a prior epoch
     * cannot silently skip records in the newly authorized review inbox.
     */
    /**
     * The read model exposes BOTH parts of P's FaceMediaKey. The hashed key
     * alone is insufficient to locate the exact authorized source revision.
     * P's FaceAuthorizedMediaDirectory must recheck the current Android grant,
     * provider snapshot, and full key+revision before yielding any URI.
     */
    fun pendingFaceReviewPage(
        epoch: String,
        after: IndexedFacePageCursor? = null,
        limit: Int = 64
    ): List<IndexedPendingFaceReview> {
        validEpoch(epoch)
        require(limit in 1..128) { "Unknown-face page limit must be 1..128" }
        require(after == null || (after.permissionEpoch == epoch &&
            after.mediaKey.isNotBlank() && after.mediaKey.length <= 1024 &&
            '\u0000' !in after.mediaKey && after.ordinal >= 0)) {
            "Stale or invalid native-face page cursor"
        }
        val startKey = after?.mediaKey ?: ""
        val startOrdinal = after?.ordinal ?: -1
        return readableDatabase.rawQuery(
            """SELECT f.media_key,m.revision,m.detector_version,f.ordinal,f.face_uid,f.l,f.t,f.r,f.b,
                    f.frame_ms
                FROM faces f
                JOIN catalog m ON m.media_key=f.media_key AND m.state='processed'
                JOIN control c ON c.singleton=1 AND c.active_epoch=?
                LEFT JOIN face_reviews r ON r.media_key=f.media_key AND r.ordinal=f.ordinal
                WHERE r.decision IS NULL AND
                    (f.media_key,f.ordinal)>(?,?)
                ORDER BY f.media_key,f.ordinal LIMIT ?""",
            arrayOf(epoch, startKey, startOrdinal.toString(), limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val key = c.getString(0)
                    val media = IndexedMedia(key,c.getString(1))
                    val detectorVersion = c.getString(2)
                    val cursor = IndexedFacePageCursor(epoch,key,c.getInt(3))
                    val observation = IndexedFaceObservation(c.getString(4),
                        IndexedFaceBox(c.getFloat(5),c.getFloat(6),c.getFloat(7),c.getFloat(8)),
                        if (c.isNull(9)) null else c.getLong(9))
                    add(IndexedPendingFaceReview(cursor,media,observation,detectorVersion))
                }
            }
        }
    }

    /** Never return boxes from an old permission epoch or unprocessed source. */
    fun observationRecords(epoch: String, key: String): List<IndexedFaceObservation> {
        validEpoch(epoch)
        return readableDatabase.rawQuery("""SELECT f.face_uid,f.l,f.t,f.r,f.b,f.frame_ms FROM faces f
            JOIN catalog m ON m.media_key=f.media_key AND m.state='processed'
            JOIN control c ON c.singleton=1 AND c.active_epoch=?
            WHERE f.media_key=? ORDER BY f.ordinal""", arrayOf(epoch,key)).use { c ->
                buildList { while (c.moveToNext())
                    add(IndexedFaceObservation(c.getString(0), IndexedFaceBox(
                        c.getFloat(1),c.getFloat(2),c.getFloat(3),c.getFloat(4)),
                        if (c.isNull(5)) null else c.getLong(5))) }
        }
    }

    /** Compatibility projection for the earlier P box-only index contract. */
    fun observations(epoch: String, key: String): List<IndexedFaceBox> =
        observationRecords(epoch, key).map { it.box }

    /**
     * Read-only: where one observation sits, for a review face crop (#438).
     * Only a face of a processed source under the active [epoch]; the caller
     * must still resolve [IndexedFaceLocation.media] through the live grant
     * (key AND revision) before reading any pixels. Null for an unknown UID,
     * a stale epoch or a source that is not processed at its current revision.
     */
    fun faceLocation(epoch: String, nativeFaceUid: String): IndexedFaceLocation? {
        validEpoch(epoch)
        if (!nativeFaceUid.matches(FACE_UID)) return null
        return readableDatabase.rawQuery("""SELECT f.media_key,m.revision,f.l,f.t,f.r,f.b,f.frame_ms
            FROM faces f
            JOIN catalog m ON m.media_key=f.media_key AND m.state='processed'
            JOIN control c ON c.singleton=1 AND c.active_epoch=?
            WHERE f.face_uid=?""", arrayOf(epoch, nativeFaceUid)).use { c ->
                if (!c.moveToFirst()) null
                else IndexedFaceLocation(IndexedMedia(c.getString(0), c.getString(1)),
                    IndexedFaceBox(c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5)),
                    if (c.isNull(6)) null else c.getLong(6))
        }
    }

    /** Undo must prove that a previously persisted decision is really gone. */
    private fun clearReview(db: SQLiteDatabase, key: String, ordinal: Int): Boolean {
        val args = arrayOf(key, ordinal.toString())
        db.delete("face_reviews", "media_key=? AND ordinal=?", args)
        // RAISE(IGNORE) on a BEFORE DELETE trigger returns 0 rather than
        // throwing. A formerly saved LATER/NOT_A_FACE must not appear undone
        // while the row actually persists. Missing already-clear rows are
        // naturally idempotent.
        return db.rawQuery("""SELECT 1 FROM face_reviews
            WHERE media_key=? AND ordinal=? LIMIT 1""", args
        ).use { !it.moveToFirst() }
    }

    /**
     * UI-facing review must name the actual native observation UUID, never
     * trust a bare ordinal reused after a detector/source revision change.
     */
    fun reviewObservation(epoch: String, nativeFaceUid: String, decision: String): Boolean {
        validEpoch(epoch)
        require(nativeFaceUid.matches(Regex("[0-9a-f]{32}")))
        require(decision in listOf("UNREVIEWED", "LATER", "NOT_A_FACE"))
        return write { db ->
            val target = db.rawQuery("""SELECT f.media_key,f.ordinal FROM faces f
                JOIN catalog m ON m.media_key=f.media_key AND m.state='processed'
                JOIN control c ON c.singleton=1 AND c.active_epoch=?
                WHERE f.face_uid=?""", arrayOf(epoch, nativeFaceUid)).use { c ->
                    if (c.moveToFirst()) c.getString(0) to c.getInt(1) else null
                } ?: return@write false
            if (decision == "UNREVIEWED") {
                if (!clearReview(db,target.first,target.second)) return@write false
            } else {
                val inserted = db.insertWithOnConflict("face_reviews", null, ContentValues().apply {
                    put("media_key", target.first)
                    put("ordinal", target.second)
                    put("decision", decision)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                if (inserted == -1L) return@write false
            }
            true
        }
    }

    /** Permission gate and observation-ID match occur in the SAME SQLite query. */
    fun reviewObservationState(epoch: String, nativeFaceUid: String): String? {
        validEpoch(epoch)
        require(nativeFaceUid.matches(Regex("[0-9a-f]{32}")))
        return readableDatabase.rawQuery("""SELECT COALESCE(r.decision,'UNREVIEWED')
            FROM faces f
            JOIN catalog m ON m.media_key=f.media_key AND m.state='processed'
            JOIN control c ON c.singleton=1 AND c.active_epoch=?
            LEFT JOIN face_reviews r ON r.media_key=f.media_key AND r.ordinal=f.ordinal
            WHERE f.face_uid=?""", arrayOf(epoch, nativeFaceUid)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }

    /** Only unknown-face review; NEVER cross-image identity inference. */
    fun review(epoch: String, key: String, ordinal: Int, decision: String): Boolean {
        validEpoch(epoch)
        require(decision in listOf("UNREVIEWED","LATER","NOT_A_FACE"))
        return write { db ->
            if (currentEpoch(db)!=epoch) return@write false
            val isPresent = db.rawQuery("""SELECT 1 FROM faces f
                JOIN catalog m ON m.media_key=f.media_key AND m.state='processed'
                WHERE f.media_key=? AND f.ordinal=?""",
                arrayOf(key,ordinal.toString())).use { it.moveToFirst() }
            if (!isPresent) return@write false
            if (decision=="UNREVIEWED") {
                if (!clearReview(db,key,ordinal)) return@write false
            } else {
                val inserted = db.insertWithOnConflict("face_reviews",null,ContentValues().apply {
                    put("media_key",key);put("ordinal",ordinal);put("decision",decision)
                },SQLiteDatabase.CONFLICT_REPLACE)
                if (inserted == -1L) return@write false
            }
            true
        }
    }

    fun reviewState(epoch: String, key: String, ordinal: Int): String? {
        validEpoch(epoch)
        return readableDatabase.rawQuery("""SELECT COALESCE(r.decision,'UNREVIEWED')
            FROM control p
            JOIN faces f ON p.singleton=1 AND p.active_epoch=?
            JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
            LEFT JOIN face_reviews r ON r.media_key=f.media_key AND r.ordinal=f.ordinal
            WHERE f.media_key=? AND f.ordinal=?""",
            arrayOf(epoch,key,ordinal.toString())).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }

    /**
     * #553, read-only and cheap: the faces of ONE photo ([key] at [revision])
     * with their confirmed or matched (#580) person and name, or published
     * unknown cluster, in
     * one epoch-gated transaction. It walks the faces primary key of that
     * photo only (a handful of rows, plus a primary-key lookup each); it
     * never triggers a scan or builds cluster state. Photos only (no video
     * keyframes); faces reviewed NOT_A_FACE are left out. Empty while revoked,
     * superseded, at another revision or not yet processed.
     */
    fun photoFaces(epoch: String, key: String, revision: String): List<IndexedPhotoFace> =
        authorizedEmbeddingRead(epoch, emptyList()) { db, version ->
            val published = clusterGenerationClean(db)
            db.rawQuery("""SELECT f.face_uid,f.l,f.t,f.r,f.b,m.anchor_uid,p.person_key,n.name,
                    pm.person_key,mn.name
                FROM faces f
                JOIN catalog c ON c.media_key=f.media_key AND c.state='processed'
                    AND c.revision=?
                JOIN embeddings e ON e.face_uid=f.face_uid AND e.model_version=?
                    AND e.source_revision=c.revision
                LEFT JOIN cluster_members m ON m.face_uid=f.face_uid
                LEFT JOIN people_confirmed p ON p.face_uid=f.face_uid
                LEFT JOIN people_names n ON n.person_key=p.person_key
                LEFT JOIN people_matched pm ON pm.face_uid=f.face_uid
                LEFT JOIN people_names mn ON mn.person_key=pm.person_key
                LEFT JOIN face_reviews r ON r.media_key=f.media_key AND r.ordinal=f.ordinal
                WHERE f.media_key=? AND f.frame_ms IS NULL
                    AND (r.decision IS NULL OR r.decision<>'NOT_A_FACE')
                ORDER BY f.ordinal""", arrayOf(revision, version, key)).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        // #580: a face grouping showed under a named person on
                        // its own reads as that person, from the last
                        // publication (kept with its membership row, so it
                        // never outlives it); a confirmed person wins.
                        val matched = c.isNull(6) && !c.isNull(8)
                        add(IndexedPhotoFace(
                            c.getString(0),
                            IndexedFaceBox(c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getFloat(4)),
                            if (!published || matched || c.isNull(5)) null else c.getString(5),
                            if (matched) c.getString(8) else if (c.isNull(6)) null else c.getString(6),
                            if (matched) { if (c.isNull(9)) null else c.getString(9) }
                            else if (c.isNull(7)) null else c.getString(7)))
                    }
                }
            }
        }
}
