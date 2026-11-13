package nl.atlas.gallery.featurepersistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/**
 * Tiny private receipt DB separate from People identity data, photo files and
 * system Trash. No MediaStore operation, delete job, file move or background work.
 *
 * Reads REQUIRE independently verified, currently readable source families.
 * A stale receipt is retained as inert evidence until explicitly overwritten or
 * forgotten; it is never interpreted as "okay to Trash other family members".
 */
class CurationKeepStore(context: Context) :
    SQLiteOpenHelper(context, "atlas_curation_keep.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE keep_receipts(
            family_key TEXT PRIMARY KEY NOT NULL,
            keeper_key TEXT NOT NULL, keeper_version TEXT NOT NULL,
            verified_sha256 TEXT NOT NULL,
            family_members_sha256 TEXT NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported Keep receipt schema; not deleting saved choices")
    }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported Keep receipt downgrade; data preserved")
    }

    /**
     * Commit only explicit owner choices that still agree with the provided
     * independently SHA-256-verified, accessible complete family snapshot.
     * Any invalid member prevents ALL choices from being persisted.
     */
    fun saveVerified(choices: List<Pair<VerifiedKeepFamily, String>>): Int {
        require(choices.size <= KeepReceiptContract.MAX_BATCH) { "Too many Keeper choices" }
        require(choices.fold(0L) { total, entry -> total + entry.first.members.size } <=
            KeepReceiptContract.MAX_MEMBERS) { "Unbounded total family membership" }
        val receipts = choices.map { (family, keeperKey) ->
            KeepReceiptContract.validate(family, keeperKey)
        }
        require(receipts.map { it.familyKey }.toSet().size == receipts.size) {
            "Conflicting keeper decisions for the same family"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            receipts.forEach { receipt ->
                val inserted = db.insertWithOnConflict("keep_receipts", null, ContentValues().apply {
                    put("family_key",receipt.familyKey)
                    put("keeper_key",receipt.keeperKey)
                    put("keeper_version",receipt.keeperVersion)
                    put("verified_sha256",receipt.verifiedSha256)
                    put("family_members_sha256",receipt.familyMembersSha256)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                // Android's insertWithOnConflict returns -1 when a BEFORE
                // INSERT trigger RAISE(IGNORE)s a row. Reporting all choices
                // saved would silently commit only a subset of the batch.
                check(inserted != -1L) { "Keep receipt write was not persisted" }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return receipts.size
    }

    /**
     * Read only individually verified current families. Unknown, revoked,
     * changed or partial evidence produces NO active Keep decision.
     * Call on IO dispatcher; never in Compose recomposition.
     */
    fun resolveCurrentlyValid(families: List<VerifiedKeepFamily>): List<KeepReceipt> {
        require(families.size <= KeepReceiptContract.MAX_BATCH)
        require(families.fold(0L) { total, family -> total + family.members.size } <=
            KeepReceiptContract.MAX_MEMBERS) { "Unbounded family membership in lookup" }
        val db = readableDatabase
        return families.mapNotNull { family ->
            db.rawQuery("""SELECT family_key,keeper_key,keeper_version,verified_sha256,family_members_sha256
                FROM keep_receipts WHERE family_key=?""", arrayOf(family.familyKey)).use { c ->
                if (!c.moveToFirst()) null else {
                    val receipt = KeepReceipt(c.getString(0), c.getString(1),
                        c.getString(2), c.getString(3), c.getString(4))
                    receipt.takeIf { KeepReceiptContract.remainsValid(it, family) }
                }
            }
        }
    }

    /** Explicit owner action only. Never automatically delete stale decisions. */
    fun forgetFamily(familyKey: String): Boolean {
        require(familyKey.matches(Regex("sha256:[0-9]+:[0-9a-f]{64}")))
        return writableDatabase.delete("keep_receipts", "family_key=?",
            arrayOf(familyKey)) > 0
    }
}
