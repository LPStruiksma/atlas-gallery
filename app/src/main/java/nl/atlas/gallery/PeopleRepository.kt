package nl.atlas.gallery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import nl.atlas.gallery.featurepersistence.PeopleBackupCodec
import nl.atlas.gallery.featurepersistence.PeopleBackupRow
import nl.atlas.gallery.featurepersistence.PeopleMergePlanner

data class PeopleBackupPreview(val rowCount: Int, val personCount: Int, val sha256: String)

/**
 * Advisory, read-only comparison against the current confirmed People database.
 * Every approval revalidates the source SHA and destination inside the actual
 * insertion transaction. Nothing here grants authority to overwrite V4 labels.
 */
data class PeopleRestoreComparison(
    val document: PeopleBackupPreview,
    val existingAssignmentCount: Int,
    val identicalAssignmentCount: Int,
    val newAssignmentCount: Int,
    val v4LiveLinkMustBeDisconnected: Boolean
) {
    val canRequestApproval: Boolean get() = !v4LiveLinkMustBeDisconnected
}

/** Export bytes plus metadata from the SAME SQLite snapshot. Treat bytes as owned by caller. */
data class PeoplePreparedBackup(val bytes: ByteArray, val preview: PeopleBackupPreview)

data class PersonEntry(val id: Long, val name: String, val imagePaths: Set<String>) {
    val count: Int get() = imagePaths.size
}

/** Private read/write copy of V4's EXPORTED CONFIRMED labels; V4 database remains untouched. */
class PeopleRepository(private val storageContext: Context) :
    SQLiteOpenHelper(storageContext, "atlas_native_people.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE assignments(
          face_id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL,
          person_name TEXT NOT NULL, image_path TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX path_assignments ON assignments(image_path)")
        db.execSQL("CREATE INDEX person_assignments ON assignments(person_id)")
        createV2Metadata(db)
    }

    private fun createV2Metadata(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE people_metadata(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO people_metadata(key, value) VALUES('schema_version', '2')")
    }

    /** SQLiteOpenHelper invokes onUpgrade in a transaction; v1 assignments remain intact. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion == 1 && newVersion == 2) {
            "Unsupported People database migration; existing data not intentionally modified"
        }
        createV2Metadata(db)
    }

    /** Fail closed without silently dropping data; integrator owns safe-mode UI. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("People database is newer than ATLAS; downgrade refused, data preserved")
    }

    /** Fully transactional replacement of a previous import, including failed-import rollback. */
    fun replaceFromV4Export(csv: String): Int {
        val rows = parseCsv(csv.removePrefix("\uFEFF"))
        val expected = listOf("person_id", "person_name", "image_path", "face_id", "confidence")
        require(rows.firstOrNull() == expected) { "Expected a V4 export with columns: ${expected.joinToString()}" }
        // Validate the entire input BEFORE removing the previous copy.
        val parsed = rows.drop(1).map { r ->
            require(r.size == 5) { "Malformed V4 row with ${r.size} columns" }
            val pid = r[0].toLongOrNull() ?: error("Invalid person ID")
            val fid = r[3].toLongOrNull() ?: error("Invalid face ID")
            PeopleBackupRow(fid, pid, r[1], r[2])
        }
        // A replacing V4 CSV is still destructive after explicit UI consent,
        // but malformed IDs, noncanonical paths, lossy UTF-16 and oversized
        // documents must fail BEFORE the existing confirmed rows are deleted.
        // Reuse exact native backup admission without allocating a document.
        PeopleBackupCodec.checkedDocumentSize(parsed)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assignments", null, null)
            for (r in parsed) {
                val values = ContentValues().apply {
                    put("person_id", r.personId)
                    put("face_id", r.faceId)
                    put("person_name", r.personName)
                    put("image_path", r.imagePath)
                }
                db.insertOrThrow("assignments", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return parsed.size
    }


    /**
     * Snapshot rows while SQLite holds a consistent read transaction. Returns a
     * bounded versioned document, including names and absolute paths, NOT photos,
     * V4 face geometry, preferences or SAF permissions. Caller owns SAF consent.
     */
    /** Preserve the existing byte-only API without a redundant receipt pass. */
    fun exportPeopleBackup(): ByteArray = PeopleBackupCodec.encode(snapshotConfirmedRows())

    /**
     * Export receipt metadata must come from the same stable SQLite rows as
     * the encoded payload. Avoid decoding the entire 128 MiB document again
     * merely to count its rows/people after encoding it.
     */
    fun preparePeopleBackupExport(): PeoplePreparedBackup {
        val rows = snapshotConfirmedRows()
        val bytes = PeopleBackupCodec.encode(rows)
        val preview = PeopleBackupPreview(
            rows.size, rows.mapTo(HashSet<Long>()) { it.personId }.size,
            PeopleBackupCodec.digestHex(bytes)
        )
        return PeoplePreparedBackup(bytes, preview)
    }

    /** Materialize one consistent, bounded export snapshot while holding SQLite read lock. */
    private fun snapshotConfirmedRows(): List<PeopleBackupRow> {
        val rows = ArrayList<PeopleBackupRow>()
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.rawQuery(
                "SELECT face_id, person_id, person_name, image_path FROM assignments ORDER BY face_id",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(PeopleBackupRow(
                        cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3)
                    ))
                    require(rows.size <= PeopleBackupCodec.MAX_ROWS) { "People export row limit exceeded" }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return rows
    }

    /** Pure preview; intentionally has no import, write or phone data side effects. */
    fun previewPeopleBackup(bytes: ByteArray): PeopleBackupPreview {
        val rows = PeopleBackupCodec.decode(bytes)
        return PeopleBackupPreview(
            rows.size, rows.mapTo(HashSet<Long>()) { it.personId }.size,
            PeopleBackupCodec.digestHex(bytes)
        )
    }

    /**
     * Preview the additive import without changing assignments or V4 state.
     * Conflict or invalid payload throws before ANY database write. This
     * comparison is advisory: concurrent V4 sync and a changed SAF document
     * are independently rechecked by mergeNativeBackup after approval.
     *
     * Keep the read transaction over snapshot+planning; never trust this
     * preview as permission to bypass the final transaction/consent guard.
     */
    fun compareNativeBackupWithCurrentPeople(bytes: ByteArray): PeopleRestoreComparison {
        val incoming = PeopleBackupCodec.decode(bytes)
        val preview = PeopleBackupPreview(
            incoming.size,
            incoming.mapTo(HashSet<Long>()) { it.personId }.size,
            PeopleBackupCodec.digestHex(bytes)
        )
        val existing = ArrayList<PeopleBackupRow>()
        val db = writableDatabase
        val additions: Int
        db.beginTransactionNonExclusive()
        try {
            db.rawQuery(
                "SELECT face_id,person_id,person_name,image_path FROM assignments",null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    existing.add(PeopleBackupRow(
                        cursor.getLong(0),cursor.getLong(1),
                        cursor.getString(2),cursor.getString(3)
                    ))
                    require(existing.size <= PeopleBackupCodec.MAX_ROWS) {
                        "Existing People snapshot exceeds backup limit"
                    }
                }
            }
            additions = PeopleMergePlanner.missingAssignments(existing,incoming).size
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return PeopleRestoreComparison(
            document = preview,
            existingAssignmentCount = existing.size,
            identicalAssignmentCount = incoming.size - additions,
            newAssignmentCount = additions,
            v4LiveLinkMustBeDisconnected = storageContext.getSharedPreferences(
                "atlas_v4_live_sync", Context.MODE_PRIVATE).contains("uri")
        )
    }

    /**
     * Re-read the SAF document when the owner approves. A digest mismatch between
     * preview and approval fails closed. Existing assignments are never deleted.
     * Identical rows are skipped, different bindings or names are conflicts.
     */
    fun mergeNativeBackup(bytes: ByteArray, approvedPreviewSha256: String): Int {
        require(approvedPreviewSha256.matches(Regex("[0-9a-f]{64}")) &&
            approvedPreviewSha256 == PeopleBackupCodec.digestHex(bytes)) {
            "People backup changed since approval; nothing imported"
        }
        return mergeVerifiedRows(PeopleBackupCodec.decode(bytes))
    }

    /** Optional non-replacing V4 path for Lane U/I; never called automatically. */
    fun mergeFromV4Export(csv: String): Int {
        val rows = parseCsv(csv.removePrefix("\uFEFF"))
        require(rows.firstOrNull() ==
            listOf("person_id", "person_name", "image_path", "face_id", "confidence")) {
            "Expected original five-column V4 export"
        }
        val assignments = rows.drop(1).map { r ->
            require(r.size == 5) { "Malformed V4 row" }
            PeopleBackupRow(
                r[3].toLongOrNull() ?: error("Invalid face ID"),
                r[0].toLongOrNull() ?: error("Invalid person ID"),
                r[1], r[2]
            )
        }
        return mergeVerifiedRows(assignments)
    }

    /**
     * The old replaceFromV4Export remains deliberately separate and destructive;
     * MainActivity must retain its explicit replacement consent. This merge path
     * is transactionally non-destructive and aborts on ANY conflicting row.
     */
    private fun mergeVerifiedRows(rows: List<PeopleBackupRow>): Int {
        // Pure admission: validate row IDs, names, paths, duplicates and the
        // original 128 MiB on-wire size without allocating a second document.
        // Restoring a large approved backup must not re-encode it in memory.
        PeopleBackupCodec.checkedDocumentSize(rows)
        // A linked V4 refresh uses the legacy REPLACING importer on app resume.
        // Never accept additive native/V4 recovery into a destination that can be
        // silently overwritten by that refresh. Lane I must first disconnect V4
        // using a successful synchronous SharedPreferences.commit(), inside the
        // existing PeopleCsvSyncCoordinator lock, after explicit user approval.
        require(!storageContext.getSharedPreferences("atlas_v4_live_sync", Context.MODE_PRIVATE)
            .contains("uri")) {
            "Disconnect the active V4 live link before restoring People; nothing imported"
        }
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            val existing = ArrayList<PeopleBackupRow>()
            db.rawQuery(
                "SELECT face_id, person_id, person_name, image_path FROM assignments", null
            ).use { c ->
                while (c.moveToNext()) {
                    existing.add(PeopleBackupRow(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3)
                    ))
                    require(existing.size <= PeopleBackupCodec.MAX_ROWS) {
                        "Existing People row limit exceeded; no import performed"
                    }
                }
            }
            // Pure planner must see the ENTIRE existing snapshot and incoming set.
            // Nothing is inserted until every potential identity conflict is checked.
            val insertions = PeopleMergePlanner.missingAssignments(existing, rows)
            insertions.forEach { row ->
                val values = ContentValues().apply {
                    put("face_id", row.faceId)
                    put("person_id", row.personId)
                    put("person_name", row.personName)
                    put("image_path", row.imagePath)
                }
                db.insertOrThrow("assignments", null, values)
                inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun people(): List<PersonEntry> {
        val map = linkedMapOf<Long, Pair<String, MutableSet<String>>>()
        readableDatabase.rawQuery("""SELECT person_id, person_name, image_path
            FROM assignments ORDER BY person_name, person_id""", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1)
                val path = c.getString(2)
                val entry = map.getOrPut(id) { name to linkedSetOf() }
                entry.second.add(path)
            }
        }
        return map.map { (id, v) -> PersonEntry(id, v.first, v.second.toSet()) }
    }
}
