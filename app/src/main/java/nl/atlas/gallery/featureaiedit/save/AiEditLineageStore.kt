package nl.atlas.gallery.featureaiedit.save

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Small app-private, no-backup SQLite table for Photo DNA and a write-ahead
 * journal for MediaStore inserts. All calls should run on Dispatchers.IO.
 * A journal intent is committed BEFORE any pending MediaStore row is created.
 */
internal class AiEditLineageStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "atlas-ai-edit-lineage-v1.sqlite")
    private val lock = Any()

    internal data class PendingSave(
        val token: String, val volume: String, val relativePath: String,
        val displayName: String, val insertedUri: String?
    )

    private fun <T> db(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS edit_lineage(
                    edited_uri TEXT PRIMARY KEY NOT NULL,
                    original_uri TEXT NOT NULL, original_revision TEXT NOT NULL,
                    provider TEXT NOT NULL, model TEXT NOT NULL, model_version TEXT NOT NULL,
                    prompt TEXT NOT NULL, settings_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL, candidate_parent_uri TEXT)"""
            )
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS pending_save(
                    token TEXT PRIMARY KEY NOT NULL, volume TEXT NOT NULL,
                    relative_path TEXT NOT NULL, display_name TEXT NOT NULL,
                    inserted_uri TEXT)"""
            )
            block(database)
        }
    }

    fun registerIntent(token: String, volume: String, path: String, name: String) = db { database ->
        val row = ContentValues().apply {
            put("token", token)
            put("volume", volume)
            put("relative_path", path)
            put("display_name", name)
        }
        check(database.insertOrThrow("pending_save", null, row) != -1L)
    }

    fun recordInserted(token: String, uri: String) = db { database ->
        check(database.update("pending_save",
            ContentValues().apply { put("inserted_uri", uri) },
            "token = ?", arrayOf(token)) == 1) { "Missing AI edit pending intent" }
    }

    fun put(lineage: AiEditLineage) = db { database ->
        val row = ContentValues().apply {
            put("edited_uri", lineage.editedUri)
            put("original_uri", lineage.originalUri)
            put("original_revision", lineage.originalRevision)
            put("provider", lineage.provider)
            put("model", lineage.model)
            put("model_version", lineage.modelVersion)
            put("prompt", lineage.prompt)
            put("settings_json", lineage.settingsJson)
            put("created_at", lineage.createdAtMillis)
            if (lineage.candidateParentUri == null) putNull("candidate_parent_uri")
            else put("candidate_parent_uri", lineage.candidateParentUri)
        }
        database.insertOrThrow("edit_lineage", null, row)
        Unit
    }

    fun get(uri: String): AiEditLineage? = db { database ->
        database.query("edit_lineage", arrayOf(
            "edited_uri", "original_uri", "original_revision", "provider",
            "model", "model_version", "prompt", "settings_json", "created_at",
            "candidate_parent_uri"
        ), "edited_uri = ?", arrayOf(uri), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else AiEditLineage(
                editedUri = cursor.getString(0),
                originalUri = cursor.getString(1),
                originalRevision = cursor.getString(2),
                provider = cursor.getString(3),
                model = cursor.getString(4),
                modelVersion = cursor.getString(5),
                prompt = cursor.getString(6),
                settingsJson = cursor.getString(7),
                createdAtMillis = cursor.getLong(8),
                candidateParentUri = if (cursor.isNull(9)) null else cursor.getString(9)
            )
        }
    }

    fun pending(): List<PendingSave> = db { database ->
        database.query("pending_save", arrayOf(
            "token", "volume", "relative_path", "display_name", "inserted_uri"
        ), null, null, null, null, null).use { cursor ->
            val rows = ArrayList<PendingSave>()
            while (cursor.moveToNext()) rows += PendingSave(
                token = cursor.getString(0), volume = cursor.getString(1),
                relativePath = cursor.getString(2), displayName = cursor.getString(3),
                insertedUri = if (cursor.isNull(4)) null else cursor.getString(4)
            )
            rows
        }
    }

    /** On failed/pending saves also remove lineage; on published saves retain it. */
    fun finishIntent(token: String, discardLineage: Boolean) = db { database ->
        database.beginTransaction()
        try {
            if (discardLineage) {
                database.rawQuery(
                    "SELECT inserted_uri FROM pending_save WHERE token = ?", arrayOf(token)
                ).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0))
                        database.delete("edit_lineage", "edited_uri = ?",
                            arrayOf(cursor.getString(0)))
                }
            }
            database.delete("pending_save", "token = ?", arrayOf(token))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}
