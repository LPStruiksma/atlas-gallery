package nl.atlas.gallery.featureidentity.profile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * IDS-4 (#472): the Identity Studio's own profile store. An app-private,
 * no-backup SQLite file (`atlas_identity_profiles.db` in `noBackupFilesDir`,
 * the same small-helper pattern as `CurationKeepStore`), separate from every
 * People database. It holds links, never copies: a People profile stores the
 * person key only, and a People face is referenced by its native face uid.
 * Nothing here opens, reads or writes `atlas_face_discovery.db` or
 * `atlas_native_people.db`.
 *
 * Derived data (aligned crops, local identity features) lives only under
 * `noBackupFilesDir/identity_profiles/<profile id>/`, and [deleteProfile]
 * removes the rows and that folder, then reports anything left.
 *
 * All calls block; run them on an IO worker, never in composition.
 */
class IdentityProfileStore(
    context: Context,
    /** App-private, no-backup root. Tests pass a scratch folder. */
    private val root: File = context.noBackupFilesDir,
    private val clock: () -> Long = System::currentTimeMillis
) : SQLiteOpenHelper(context, File(root, DB_NAME).absolutePath, null, SCHEMA_VERSION) {

    companion object {
        const val DB_NAME = "atlas_identity_profiles.db"
        const val DERIVED_DIR = "identity_profiles"
        const val SCHEMA_VERSION = 1
        private const val PROFILE_FOLDER = "_profile"
    }

    private val lock = Any()
    private val derivedRoot: File get() = File(root, DERIVED_DIR)

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE profiles(
            id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=32),
            people_person_key TEXT UNIQUE CHECK(people_person_key IS NULL OR length(people_person_key) BETWEEN 1 AND 256),
            label TEXT CHECK(label IS NULL OR length(label) BETWEEN 1 AND 60),
            created_at INTEGER NOT NULL,
            CHECK(people_person_key IS NOT NULL OR label IS NOT NULL))""")
        db.execSQL("""CREATE TABLE variants(
            id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=32),
            profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
            label TEXT NOT NULL CHECK(length(label) BETWEEN 1 AND 60),
            label_key TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            UNIQUE(profile_id, label_key), UNIQUE(profile_id, ordinal))""")
        db.execSQL("""CREATE TABLE profile_references(
            id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=32),
            variant_id TEXT NOT NULL REFERENCES variants(id) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL,
            media_key TEXT NOT NULL, revision TEXT NOT NULL,
            l REAL NOT NULL, t REAL NOT NULL, r REAL NOT NULL, b REAL NOT NULL,
            landmarks TEXT,
            origin TEXT NOT NULL CHECK(origin IN ('people_suggested','user_added','manual')),
            people_face_uid TEXT CHECK(people_face_uid IS NULL OR length(people_face_uid)=32),
            added_at INTEGER NOT NULL,
            stale_reason TEXT,
            UNIQUE(variant_id, ordinal))""")
        db.execSQL("CREATE INDEX profile_references_variant ON profile_references(variant_id, ordinal)")
        db.execSQL("CREATE INDEX variants_profile ON variants(profile_id, ordinal)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported identity profile schema; profiles preserved")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Identity profile store is newer than ATLAS; downgrade refused, data preserved")
    }

    // ---- Profiles ----------------------------------------------------------

    /** A hand-made profile with its own [label] and a "Current" variant. */
    fun createManualProfile(label: String): IdentityProfile {
        require(IdentityProfileRules.validLabel(label)) { "Invalid profile label" }
        return insertProfile(null, label)
    }

    /**
     * The profile linked to People person [personKey], created with a
     * "Current" variant if none exists. Only the key is stored; the person's
     * name is read from People whenever it is shown.
     */
    fun profileForPeoplePerson(personKey: String): IdentityProfile {
        require(IdentityProfileRules.validPersonKey(personKey)) { "Invalid person key" }
        synchronized(lock) {
            findByPerson(personKey)?.let { return it }
            return insertProfile(personKey, null)
        }
    }

    fun findByPerson(personKey: String): IdentityProfile? {
        require(IdentityProfileRules.validPersonKey(personKey)) { "Invalid person key" }
        val id = readableDatabase.rawQuery("SELECT id FROM profiles WHERE people_person_key=?",
            arrayOf(personKey)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return id?.let(::profile)
    }

    private fun insertProfile(personKey: String?, label: String?): IdentityProfile = synchronized(lock) {
        val id = IdentityProfileIds.mint()
        val variant = IdentityProfileIds.mint()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("profiles", null, ContentValues().apply {
                put("id", id)
                if (personKey == null) putNull("people_person_key") else put("people_person_key", personKey)
                if (label == null) putNull("label") else put("label", label)
                put("created_at", clock())
            })
            insertVariant(db, id, variant, IdentityProfileRules.DEFAULT_VARIANT_LABEL, 0)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        checkNotNull(profile(id))
    }

    /** Rename a manual profile. A People-linked profile takes its name from People and has no label. */
    fun renameProfile(profileId: String, label: String): Boolean {
        IdentityProfileIds.require(profileId)
        require(IdentityProfileRules.validLabel(label)) { "Invalid profile label" }
        return synchronized(lock) {
            writableDatabase.update("profiles", ContentValues().apply { put("label", label) },
                "id=? AND people_person_key IS NULL", arrayOf(profileId)) == 1
        }
    }

    fun profiles(): List<IdentityProfile> {
        val ids = readableDatabase.rawQuery("SELECT id FROM profiles ORDER BY created_at, id", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return ids.mapNotNull(::profile)
    }

    /** One consistent read of the profile, its variants and their ordered references. */
    fun profile(profileId: String): IdentityProfile? {
        if (!IdentityProfileIds.valid(profileId)) return null
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val head = db.rawQuery("SELECT people_person_key,label,created_at FROM profiles WHERE id=?",
                arrayOf(profileId)).use { c ->
                if (!c.moveToFirst()) null
                else Triple(c.stringOrNull(0), c.stringOrNull(1), c.getLong(2))
            } ?: return null
            val variants = db.rawQuery("SELECT id,label FROM variants WHERE profile_id=? ORDER BY ordinal",
                arrayOf(profileId)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }.map { (variantId, label) ->
                AppearanceVariant(variantId, profileId, label, readReferences(db, variantId))
            }
            db.setTransactionSuccessful()
            return IdentityProfile(profileId, head.first, head.second, head.third, variants)
        } finally { db.endTransaction() }
    }

    private fun readReferences(db: SQLiteDatabase, variantId: String): List<IdentityReference> =
        db.rawQuery("""SELECT id,media_key,revision,l,t,r,b,landmarks,origin,people_face_uid,added_at,stale_reason
            FROM profile_references WHERE variant_id=? ORDER BY ordinal""", arrayOf(variantId)).use { c ->
            buildList {
                while (c.moveToNext()) add(IdentityReference(
                    id = c.getString(0), variantId = variantId,
                    mediaKey = c.getString(1), revision = c.getString(2),
                    box = ReferenceFaceBox(c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getFloat(6)),
                    landmarks = c.stringOrNull(7)?.let(ReferenceLandmarks::decode),
                    origin = ReferenceOrigin.fromCode(c.getString(8)),
                    peopleFaceUid = c.stringOrNull(9),
                    addedAtMillis = c.getLong(10),
                    stale = c.stringOrNull(11)?.let(StaleReason::fromCode)))
            }
        }

    // ---- Variants ----------------------------------------------------------

    /** Adds an appearance variant ("Younger", "Glasses"); null if the profile is gone. */
    fun addVariant(profileId: String, label: String): AppearanceVariant? {
        IdentityProfileIds.require(profileId)
        require(IdentityProfileRules.validLabel(label)) { "Invalid variant label" }
        return synchronized(lock) {
            val db = writableDatabase
            db.beginTransaction()
            val id = IdentityProfileIds.mint()
            try {
                if (!exists(db, "profiles", profileId)) return null
                val count = count(db, "SELECT COUNT(*) FROM variants WHERE profile_id=?", profileId)
                require(count < IdentityProfileRules.MAX_VARIANTS) { "Too many variants" }
                require(count(db, "SELECT COUNT(*) FROM variants WHERE profile_id=? AND label_key=?",
                    profileId, labelKey(label)) == 0) { "A variant with this name already exists" }
                insertVariant(db, profileId, id, label, nextOrdinal(db, "variants", "profile_id", profileId))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            AppearanceVariant(id, profileId, label, emptyList())
        }
    }

    fun renameVariant(variantId: String, label: String): Boolean {
        IdentityProfileIds.require(variantId)
        require(IdentityProfileRules.validLabel(label)) { "Invalid variant label" }
        return synchronized(lock) {
            try {
                writableDatabase.update("variants", ContentValues().apply {
                    put("label", label); put("label_key", labelKey(label))
                }, "id=?", arrayOf(variantId)) == 1
            } catch (_: android.database.sqlite.SQLiteConstraintException) { false }
        }
    }

    /**
     * Removes a variant, its references and their derived files. The last
     * variant of a profile stays (delete the profile instead).
     */
    fun removeVariant(variantId: String): Boolean {
        IdentityProfileIds.require(variantId)
        return synchronized(lock) {
            val db = writableDatabase
            val profileId = variantProfile(db, variantId) ?: return false
            if (count(db, "SELECT COUNT(*) FROM variants WHERE profile_id=?", profileId) <= 1) return false
            val refs = db.rawQuery("SELECT id FROM profile_references WHERE variant_id=?", arrayOf(variantId))
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            val removed = db.delete("variants", "id=?", arrayOf(variantId)) == 1
            if (removed) refs.forEach { referenceFolder(profileId, it).deleteRecursively() }
            removed
        }
    }

    // ---- References --------------------------------------------------------

    /** Appends a reference to a variant; null if the variant is gone. The same face twice is refused. */
    fun addReference(variantId: String, draft: IdentityReferenceDraft): IdentityReference? {
        IdentityProfileIds.require(variantId)
        return synchronized(lock) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                if (!exists(db, "variants", variantId)) return null
                val current = readReferences(db, variantId)
                require(current.size < IdentityProfileRules.MAX_REFERENCES_PER_VARIANT) { "Too many references" }
                require(current.none { draft.sameFaceAs(it) }) { "This face is already a reference" }
                val reference = IdentityReference(
                    id = IdentityProfileIds.mint(), variantId = variantId,
                    mediaKey = draft.mediaKey, revision = draft.revision, box = draft.box,
                    landmarks = draft.landmarks, origin = draft.origin,
                    peopleFaceUid = draft.peopleFaceUid, addedAtMillis = clock(), stale = null)
                db.insertOrThrow("profile_references", null, ContentValues().apply {
                    put("id", reference.id)
                    put("variant_id", variantId)
                    put("ordinal", nextOrdinal(db, "profile_references", "variant_id", variantId))
                    put("media_key", reference.mediaKey)
                    put("revision", reference.revision)
                    put("l", reference.box.left); put("t", reference.box.top)
                    put("r", reference.box.right); put("b", reference.box.bottom)
                    val marks = reference.landmarks
                    if (marks == null) putNull("landmarks") else put("landmarks", marks.encode())
                    put("origin", reference.origin.code)
                    val uid = reference.peopleFaceUid
                    if (uid == null) putNull("people_face_uid") else put("people_face_uid", uid)
                    put("added_at", reference.addedAtMillis)
                    putNull("stale_reason")
                })
                db.setTransactionSuccessful()
                reference
            } finally { db.endTransaction() }
        }
    }

    /** Removes one reference and its derived files. */
    fun removeReference(referenceId: String): Boolean {
        IdentityProfileIds.require(referenceId)
        return synchronized(lock) {
            val db = writableDatabase
            val profileId = referenceProfile(db, referenceId) ?: return false
            val removed = db.delete("profile_references", "id=?", arrayOf(referenceId)) == 1
            if (removed) referenceFolder(profileId, referenceId).deleteRecursively()
            removed
        }
    }

    /** Sets a variant's reference order; [orderedIds] must be exactly its current references. */
    fun reorderReferences(variantId: String, orderedIds: List<String>): Boolean {
        IdentityProfileIds.require(variantId)
        return synchronized(lock) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val current = readReferences(db, variantId).map { it.id }
                if (!ReferenceOrdering.validReorder(current, orderedIds)) return false
                // Two passes keep UNIQUE(variant_id, ordinal) satisfied mid-update.
                orderedIds.forEachIndexed { i, id -> setOrdinal(db, id, -1 - i) }
                orderedIds.forEachIndexed { i, id -> setOrdinal(db, id, i) }
                db.setTransactionSuccessful()
                true
            } finally { db.endTransaction() }
        }
    }

    /**
     * Records that a reference no longer matches its source. It stays in its
     * place (never swapped for another face) until the user removes it.
     */
    fun markStale(referenceId: String, reason: StaleReason): Boolean {
        IdentityProfileIds.require(referenceId)
        return synchronized(lock) {
            writableDatabase.update("profile_references", ContentValues().apply { put("stale_reason", reason.code) },
                "id=?", arrayOf(referenceId)) == 1
        }
    }

    /** Applies [checks] (from [PeopleReferenceSource.check]): only Stale results are written. */
    fun applyChecks(checks: Map<String, ReferenceCheck>): Int =
        checks.count { (id, check) -> check is ReferenceCheck.Stale && markStale(id, check.reason) }

    // ---- Derived data ------------------------------------------------------

    /**
     * App-private folder for data derived from one reference (aligned crop,
     * local identity features), or for the whole profile when [referenceId]
     * is null. It is removed with its reference, variant or profile.
     */
    fun derivedFolder(profileId: String, referenceId: String? = null): File {
        IdentityProfileIds.require(profileId)
        referenceId?.let(IdentityProfileIds::require)
        val folder = if (referenceId == null) File(profileFolder(profileId), PROFILE_FOLDER)
        else referenceFolder(profileId, referenceId)
        check(folder.isDirectory || folder.mkdirs()) { "Could not create derived-data folder" }
        return folder
    }

    /** A file inside [derivedFolder]; [name] cannot leave the folder. */
    fun derivedFile(profileId: String, referenceId: String?, name: String): File {
        require(DerivedFileNames.valid(name)) { "Invalid derived file name" }
        return File(derivedFolder(profileId, referenceId), name)
    }

    private fun profileFolder(profileId: String) = File(derivedRoot, profileId)
    private fun referenceFolder(profileId: String, referenceId: String) = File(profileFolder(profileId), referenceId)

    // ---- Delete ------------------------------------------------------------

    /**
     * Deletes the profile, every variant and reference (one transaction),
     * then every derived file, and re-checks that nothing is left.
     */
    fun deleteProfile(profileId: String): IdentityProfileDeletion {
        IdentityProfileIds.require(profileId)
        return synchronized(lock) {
            val db = writableDatabase
            val before = rowsFor(db, profileId)
            val found = exists(db, "profiles", profileId)
            db.beginTransaction()
            try {
                db.delete("profiles", "id=?", arrayOf(profileId))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val folder = profileFolder(profileId)
            val files = countFiles(folder)
            folder.deleteRecursively()
            val rowsLeft = rowsFor(db, profileId)
            val filesLeft = countFiles(folder)
            IdentityProfileDeletion(found, before - rowsLeft, files - filesLeft, rowsLeft, filesLeft)
        }
    }

    /** Deletes every profile and the whole derived-data folder. */
    fun deleteAll(): Boolean = synchronized(lock) {
        val db = writableDatabase
        db.delete("profiles", null, null)
        derivedRoot.deleteRecursively()
        count(db, "SELECT (SELECT COUNT(*) FROM profiles)+(SELECT COUNT(*) FROM variants)+" +
            "(SELECT COUNT(*) FROM profile_references)") == 0 && !derivedRoot.exists()
    }

    /**
     * Removes derived folders whose profile no longer exists (a delete that
     * was interrupted between the rows and the files). Returns folders removed.
     */
    fun sweepOrphanedDerivedData(): Int = synchronized(lock) {
        val db = readableDatabase
        val folders = derivedRoot.listFiles()?.filter { it.isDirectory } ?: return 0
        folders.count { folder ->
            val orphan = !IdentityProfileIds.valid(folder.name) || !exists(db, "profiles", folder.name)
            orphan && folder.deleteRecursively()
        }
    }

    /** Rows and files still held for [profileId]; both zero after [deleteProfile]. */
    fun residue(profileId: String): Pair<Int, Int> {
        IdentityProfileIds.require(profileId)
        return rowsFor(readableDatabase, profileId) to countFiles(profileFolder(profileId))
    }

    // ---- Helpers -----------------------------------------------------------

    private fun rowsFor(db: SQLiteDatabase, profileId: String): Int =
        count(db, """SELECT (SELECT COUNT(*) FROM profiles WHERE id=?1)
            + (SELECT COUNT(*) FROM variants WHERE profile_id=?1)
            + (SELECT COUNT(*) FROM profile_references r JOIN variants v ON v.id=r.variant_id
                WHERE v.profile_id=?1)""", profileId)

    private fun countFiles(folder: File): Int =
        if (!folder.exists()) 0 else folder.walkTopDown().count()

    private fun insertVariant(db: SQLiteDatabase, profileId: String, id: String, label: String, ordinal: Int) {
        db.insertOrThrow("variants", null, ContentValues().apply {
            put("id", id); put("profile_id", profileId)
            put("label", label); put("label_key", labelKey(label)); put("ordinal", ordinal)
        })
    }

    private fun labelKey(label: String) = label.lowercase(java.util.Locale.ROOT)

    private fun setOrdinal(db: SQLiteDatabase, referenceId: String, ordinal: Int) {
        check(db.update("profile_references", ContentValues().apply { put("ordinal", ordinal) },
            "id=?", arrayOf(referenceId)) == 1)
    }

    private fun nextOrdinal(db: SQLiteDatabase, table: String, column: String, owner: String): Int =
        count(db, "SELECT COALESCE(MAX(ordinal)+1,0) FROM $table WHERE $column=?", owner)

    private fun variantProfile(db: SQLiteDatabase, variantId: String): String? =
        db.rawQuery("SELECT profile_id FROM variants WHERE id=?", arrayOf(variantId))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun referenceProfile(db: SQLiteDatabase, referenceId: String): String? =
        db.rawQuery("""SELECT v.profile_id FROM profile_references r JOIN variants v ON v.id=r.variant_id
            WHERE r.id=?""", arrayOf(referenceId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun exists(db: SQLiteDatabase, table: String, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    private fun count(db: SQLiteDatabase, sql: String, vararg args: String): Int =
        db.rawQuery(sql, args).use { c -> check(c.moveToFirst()); c.getInt(0) }

    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
}
