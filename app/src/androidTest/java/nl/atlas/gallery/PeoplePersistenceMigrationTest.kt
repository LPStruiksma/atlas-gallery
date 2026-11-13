package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurepersistence.PeopleBackupCodec
import nl.atlas.gallery.featurepersistence.PeopleBackupRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Completely isolated v1 seed and v2 migration using synthetic fixture rows.
 * Uses a unique private cache database path, NOT atlas_native_people.db.
 * Never installs/uninstalls apps or touches the owner's People data.
 */
@RunWith(AndroidJUnit4::class)
class PeoplePersistenceMigrationTest {
    private val base: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val old = PeopleBackupRow(
        91001, 71001, "Fictional Person",
        "/storage/emulated/0/DCIM/atlas-only-fictional.jpg"
    )
    private val second = PeopleBackupRow(
        91002, 71002, "Another Fictional Person",
        "/storage/emulated/0/Pictures/atlas-fictional-2.png"
    )

    private fun fixture(): Pair<Context, File> {
        val directory = File(base.cacheDir, "lane-x-people-" + UUID.randomUUID())
        check(directory.mkdirs())
        val file = File(directory, "isolated_fixture.db")
        val isolated = object : ContextWrapper(base) {
            // Do not mutate the production V4 link preferences in tests.
            override fun getSharedPreferences(name: String, mode: Int):
                android.content.SharedPreferences =
                super.getSharedPreferences("atlas-lane-x-${directory.name}-$name", mode)
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler
            )
        }
        return isolated to file
    }

    private fun cleanupFixture(file: File) {
        val namespace = "atlas-lane-x-${file.parentFile?.name}-atlas_v4_live_sync"
        base.deleteSharedPreferences(namespace)
        file.parentFile?.deleteRecursively()
    }

    private fun seedV1(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE assignments(
                face_id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL,
                person_name TEXT NOT NULL, image_path TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX path_assignments ON assignments(image_path)")
            db.execSQL("CREATE INDEX person_assignments ON assignments(person_id)")
            db.execSQL(
                "INSERT INTO assignments VALUES(?,?,?,?)",
                arrayOf<Any>(old.faceId, old.personId, old.personName, old.imagePath)
            )
            db.version = 1
        }
    }

    @Test fun forwardV1MigrationPreservesSeedAndCreatesMetadata() {
        val (context, dbFile) = fixture()
        try {
            seedV1(dbFile)
            PeopleRepository(context).use { repo ->
                assertEquals(2, repo.readableDatabase.version)
                assertEquals(setOf(old.imagePath), repo.people().single().imagePaths)
                repo.readableDatabase.rawQuery(
                    "SELECT value FROM people_metadata WHERE key = 'schema_version'", null
                ).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("2", c.getString(0))
                }
                assertEquals(1, repo.previewPeopleBackup(repo.exportPeopleBackup()).rowCount)
            }
            PeopleRepository(context).use { repo ->
                assertEquals(setOf(old.imagePath), repo.people().single().imagePaths)
                assertEquals(2, repo.readableDatabase.version)
            }
        } finally {
            cleanupFixture(dbFile)
        }
    }

    @Test fun preparedExportReceiptMatchesExactSameSnapshotAndLegacyBytes() {
        val (context, dbFile) = fixture()
        try {
            seedV1(dbFile)
            PeopleRepository(context).use { repo ->
                val prepared = repo.preparePeopleBackupExport()
                assertEquals(1,prepared.preview.rowCount)
                assertEquals(1,prepared.preview.personCount)
                assertArrayEquals(repo.exportPeopleBackup(), prepared.bytes)
                assertEquals(repo.previewPeopleBackup(prepared.bytes),prepared.preview)
                assertEquals(listOf(old),PeopleBackupCodec.decode(prepared.bytes))
                // A full approved restore still checks preview SHA and does
                // not insert a duplicate after the source is re-read.
                assertEquals(0,repo.mergeNativeBackup(prepared.bytes,prepared.preview.sha256))
                try {
                    repo.mergeNativeBackup(prepared.bytes,"0".repeat(64))
                    fail("Changed SAF approval digest was accepted")
                } catch (_: IllegalArgumentException) { }
                assertEquals(1,repo.people().size)
            }
        } finally { cleanupFixture(dbFile) }
    }

    @Test fun compareAdditiveRestoreReportsOnlyMissingAssignmentsWithoutWriting() {
        val (context, dbFile) = fixture()
        try {
            seedV1(dbFile)
            PeopleRepository(context).use { repo ->
                val bytes = PeopleBackupCodec.encode(listOf(old,second))
                val plan = repo.compareNativeBackupWithCurrentPeople(bytes)
                assertEquals(2,plan.document.rowCount)
                assertEquals(2,plan.document.personCount)
                assertEquals(PeopleBackupCodec.digestHex(bytes),plan.document.sha256)
                assertEquals(1,plan.existingAssignmentCount)
                assertEquals(1,plan.identicalAssignmentCount)
                assertEquals(1,plan.newAssignmentCount)
                assertTrue(plan.canRequestApproval)
                assertEquals(1,repo.people().size) // read-only preview
                assertEquals(1,repo.mergeNativeBackup(bytes,plan.document.sha256))
                assertEquals(0,repo.compareNativeBackupWithCurrentPeople(bytes).newAssignmentCount)

                val conflicting = PeopleBackupCodec.encode(listOf(
                    old.copy(personId=71099,personName="Fictional Conflict")
                ))
                try {
                    repo.compareNativeBackupWithCurrentPeople(conflicting)
                    fail("Conflicting confirmed face identifier should refuse preview")
                } catch (_: IllegalArgumentException) { }
                assertEquals(2,repo.people().size)
                assertTrue(context.getSharedPreferences("atlas_v4_live_sync", Context.MODE_PRIVATE)
                    .edit().putString("uri","content://fictional/live-link").commit())
                val blocked = repo.compareNativeBackupWithCurrentPeople(bytes)
                assertTrue(blocked.v4LiveLinkMustBeDisconnected)
                assertFalse(blocked.canRequestApproval)
                assertEquals(2,repo.people().size)
            }
        } finally { cleanupFixture(dbFile) }
    }

    @Test fun restoreIsIdempotentAndCollisionsNeverDeletePreviousRows() {
        val (context, dbFile) = fixture()
        try {
            seedV1(dbFile)
            PeopleRepository(context).use { repo ->
                val payload = PeopleBackupCodec.encode(listOf(old, second))
                val preview = repo.previewPeopleBackup(payload)
                assertEquals(2, preview.rowCount)
                assertEquals(1, repo.mergeNativeBackup(payload, preview.sha256))
                assertEquals(0, repo.mergeNativeBackup(payload, preview.sha256))
                assertEquals(2, repo.people().size)
                val conflicting = PeopleBackupCodec.encode(listOf(
                    PeopleBackupRow(91003, 71003, "More Fictional", "/storage/emulated/0/DCIM/x.jpg"),
                    old.copy(personId = 71004, personName = "Wrong Person")
                ))
                val conflictDigest = repo.previewPeopleBackup(conflicting).sha256
                try {
                    repo.mergeNativeBackup(conflicting, conflictDigest)
                    fail("Conflicting People assignment was accepted")
                } catch (_: IllegalArgumentException) { }
                assertEquals(2, repo.people().size)
                assertEquals(setOf(old.imagePath), repo.people().single { it.id == old.personId }.imagePaths)
                try {
                    repo.mergeNativeBackup(payload, "0".repeat(64))
                    fail("Approval digest mismatch was accepted")
                } catch (_: IllegalArgumentException) { }
                assertEquals(2, repo.people().size)
            }
        } finally {
            cleanupFixture(dbFile)
        }
    }

    @Test fun malformedLegacyV4ReplacementRefusesBeforeDeletingConfirmedRows() {
        val (context, dbFile) = fixture()
        try {
            seedV1(dbFile)
            PeopleRepository(context).use { repo ->
                val header = "person_id,person_name,image_path,face_id,confidence\n"
                val badRows = listOf(
                    "71002,Fictional\uD800,/storage/emulated/0/DCIM/photo.jpg,91002,1.0",
                    "71002,Valid,/storage/emulated/0/DCIM/../escape.jpg,91002,1.0",
                    "-1,Valid,/storage/emulated/0/DCIM/photo.jpg,91002,1.0",
                    "71002,Valid,/storage/emulated/0/DCIM/photo.jpg,-1,1.0"
                )
                for (row in badRows) {
                    try {
                        repo.replaceFromV4Export(header + row + "\n")
                        fail("Unsafe V4 replacement was accepted")
                    } catch (_: IllegalArgumentException) { }
                    assertEquals(1, repo.people().size)
                    assertEquals(setOf(old.imagePath), repo.people().single().imagePaths)
                }
                // Valid legacy replacement remains available with the same
                // consented replacing semantics: it does NOT merge.
                val valid = header + "71002,Another Fictional Person," +
                    "/storage/emulated/0/DCIM/photo.jpg,91002,1.0\n"
                assertEquals(1, repo.replaceFromV4Export(valid))
                assertEquals(1, repo.people().size)
                assertEquals(71002L, repo.people().single().id)
            }
        } finally {
            cleanupFixture(dbFile)
        }
    }

    @Test fun linkedV4CannotOverwriteAdditiveRestoration() {
        val (context, dbFile) = fixture()
        try {
            seedV1(dbFile)
            val preferences = context.getSharedPreferences(
                "atlas_v4_live_sync", Context.MODE_PRIVATE
            )
            val payload = PeopleBackupCodec.encode(listOf(old, second))
            PeopleRepository(context).use { repo ->
                val preview = repo.previewPeopleBackup(payload)
                assertTrue(preferences.edit()
                    .putString("uri", "content://fictional-provider/live-v4")
                    .putString("sha256", "synthetic-digest").commit())
                try {
                    repo.mergeNativeBackup(payload, preview.sha256)
                    fail("Restoration succeeded with an active replacing V4 link")
                } catch (_: IllegalArgumentException) { }
                assertEquals(setOf(old.imagePath), repo.people().single().imagePaths)

                // In production Lane I must do this only after user approval
                // and under PeopleCsvSyncCoordinator.exclusive.
                assertTrue(preferences.edit().remove("uri").remove("sha256").commit())
                assertEquals(1, repo.mergeNativeBackup(payload, preview.sha256))
                assertEquals(2, repo.people().size)
            }
        } finally {
            cleanupFixture(dbFile)
        }
    }

    @Test fun newerSchemaDowngradeIsRefusedWithoutTableDeletion() {
        val (context, dbFile) = fixture()
        try {
            seedV1(dbFile)
            PeopleRepository(context).use { repo ->
                assertEquals(2, repo.readableDatabase.version)
                try {
                    repo.onDowngrade(repo.writableDatabase, 2, 1)
                    fail("Downgrade should refuse to open newer data")
                } catch (_: SQLiteException) { }
                assertEquals(2, repo.readableDatabase.version)
                assertEquals(setOf(old.imagePath), repo.people().single().imagePaths)
            }
        } finally {
            cleanupFixture(dbFile)
        }
    }
}
