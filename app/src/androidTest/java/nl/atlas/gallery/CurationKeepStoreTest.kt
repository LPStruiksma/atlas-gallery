package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurepersistence.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Fictional receipts only; isolated Android app-cache database fixture. */
@RunWith(AndroidJUnit4::class)
class CurationKeepStoreTest {
    private val digest = "b".repeat(64)
    private val family = VerifiedKeepFamily("sha256:7:$digest", listOf(
        VerifiedKeepMember("content://fixture/photo/1","rev-1",7,digest,true,true),
        VerifiedKeepMember("content://fixture/photo/2","rev-1",7,digest,true,true)
    ))
    private fun fixture(): Pair<CurationKeepStore, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir,"lane-x-curation-"+UUID.randomUUID())
        check(dir.mkdirs())
        val file = File(dir,"keeps.db")
        val isolated = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file,factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                file.path,factory,SQLiteDatabase.CREATE_IF_NECESSARY,errorHandler)
        }
        return CurationKeepStore(isolated) to file
    }

    @Test fun savedKeeperSurvivesReopenButDoesNotApplyToEditedOrRevokedMedia() {
        val (store,file) = fixture()
        try {
            store.use { db ->
                assertEquals(1,db.saveVerified(listOf(family to "content://fixture/photo/1")))
                assertEquals(1,db.resolveCurrentlyValid(listOf(family)).size)
            }
            // The test context points to same unique filename, not real app DB.
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM keep_receipts", null)
                    .use { c -> c.moveToFirst(); c.getInt(0) })
            }
            val (next, nextFile) = fixture()
            try {
                // This second isolated fixture is correctly empty, not a recovery.
                next.use { assertTrue(it.resolveCurrentlyValid(listOf(family)).isEmpty()) }
            } finally { nextFile.parentFile?.deleteRecursively() }
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun sqliteIgnoredReceiptInsertFailsWholeBatchInsteadOfReportingSuccess() {
        val (store,file) = fixture()
        try {
            store.use { db ->
                val otherDigest = "c".repeat(64)
                val other = VerifiedKeepFamily("sha256:7:$otherDigest", listOf(
                    VerifiedKeepMember("content://fixture/photo/3","rev-1",
                        7,otherDigest,true,true),
                    VerifiedKeepMember("content://fixture/photo/4","rev-1",
                        7,otherDigest,true,true)
                ))
                // SQLite deliberately ignores the SECOND insert without
                // throwing. The FIRST must roll back, not partially persist.
                db.writableDatabase.execSQL(
                    """CREATE TRIGGER ignore_second_keeper BEFORE INSERT ON keep_receipts
                       WHEN NEW.family_key='sha256:7:$otherDigest'
                       BEGIN SELECT RAISE(IGNORE); END"""
                )
                try {
                    db.saveVerified(listOf(
                        family to "content://fixture/photo/1",
                        other to "content://fixture/photo/3"))
                    fail("Ignored second receipt incorrectly reported saved")
                } catch (_: IllegalStateException) { }
                assertTrue(db.resolveCurrentlyValid(listOf(family,other)).isEmpty())
                db.writableDatabase.execSQL("DROP TRIGGER ignore_second_keeper")
                assertEquals(2,db.saveVerified(listOf(
                    family to "content://fixture/photo/1",
                    other to "content://fixture/photo/3")))
                assertEquals(2,db.resolveCurrentlyValid(listOf(family,other)).size)
            }
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun corruptedBatchAndUnverifiedSourceDoNotPartiallyWrite() {
        val (store,file) = fixture()
        try {
            store.use { db ->
                val corrupt = family.copy(members=family.members.map { it.copy(digestVerified=false) })
                try {
                    db.saveVerified(listOf(family to "content://fixture/photo/1",
                        corrupt to "content://fixture/photo/2"))
                    fail("Unverified batch was accepted")
                } catch (_: IllegalArgumentException) { }
                assertTrue(db.resolveCurrentlyValid(listOf(family)).isEmpty())
                assertEquals(1,db.saveVerified(listOf(family to "content://fixture/photo/2")))
                val valid = db.resolveCurrentlyValid(listOf(family)).single()
                assertEquals("content://fixture/photo/2",valid.keeperKey)
                val changed = family.copy(members=family.members.map { it.copy(contentVersion="rev-2") })
                assertTrue(db.resolveCurrentlyValid(listOf(changed)).isEmpty())
                val changedOnlyOther = family.copy(members=family.members.map { member ->
                    if (member.mediaKey == "content://fixture/photo/1")
                        member.copy(contentVersion="rev-2") else member
                })
                assertTrue(db.resolveCurrentlyValid(listOf(changedOnlyOther)).isEmpty())
                val replacedOther = family.copy(members=listOf(
                    family.members.first().copy(mediaKey="content://fixture/new"),
                    family.members.last()))
                assertTrue(db.resolveCurrentlyValid(listOf(replacedOther)).isEmpty())
                assertTrue(db.resolveCurrentlyValid(listOf(family.copy(
                    members=family.members.map { it.copy(readableNow=false) }))).isEmpty())
                assertTrue(db.resolveCurrentlyValid(listOf(family)).isNotEmpty())
                assertTrue(db.forgetFamily(family.familyKey))
                assertTrue(db.resolveCurrentlyValid(listOf(family)).isEmpty())
            }
        } finally { file.parentFile?.deleteRecursively() }
    }
}
