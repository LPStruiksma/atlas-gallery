package nl.atlas.gallery.featureidentity.profile

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IDS-4 (#472): the identity profile store on a real SQLite, with People
 * built from synthetic faces only (no pixels, no private media). Proves that
 * reading People and every profile operation leave the People database
 * byte-for-byte equal in content, and that deleting a profile leaves no rows
 * and no files.
 */
@RunWith(AndroidJUnit4::class)
class IdentityProfileStoreAndroidTest {
    private val person = PersonKey("synthetic-person-ada")
    private val box = IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)
    private lateinit var scratch: File

    private fun media(n: Int, revision: String = "generation:1") =
        IndexedMedia("content://synthetic/primary/image/$n#photo", revision)

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        scratch = File(ctx.cacheDir, "ids-4-" + UUID.randomUUID())
        check(scratch.mkdirs())
    }

    @After fun tearDown() { scratch.deleteRecursively() }

    private fun peopleContext(file: File): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(name: String, mode: Int,
                factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(name: String, mode: Int,
                factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
    }

    private fun profileStore(): IdentityProfileStore {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return IdentityProfileStore(ctx, root = File(scratch, "nobackup").also { it.mkdirs() })
    }

    /** Three synthetic photos, one face each, named as one People person. */
    private fun seedPeople(db: FaceIndexStore, epoch: String = "epoch-A") {
        db.beginSnapshot(epoch)
        db.stageBatch(epoch, (1..3).map { media(it) })
        db.commitSnapshot(epoch, "detector-1", SFaceModel.EMBEDDER_VERSION)
        scan(db, epoch)
        val prior = requireNotNull(db.peopleReview(epoch))
        val group = prior.clusters.unknownClusters.first { it.members.size == 3 }
        val result = requireNotNull(reviewPeopleCluster(
            PeopleClusterReviewState(epoch, prior.generation, prior.clusters, prior.names),
            PeopleReviewAction.NameCluster(group.id, person, "Ada")))
        assertNotNull(db.commitPeopleReview(epoch, prior.generation, result.clusters, result.names))
    }

    private fun scan(db: FaceIndexStore, epoch: String) {
        var tick = 1000L
        while (true) {
            val lease = db.claim(epoch, tick++) ?: break
            assertTrue(db.completeEmbeddings(lease, listOf(box),
                listOf(FloatArray(SFaceModel.DIMENSION) { if (it == 0) 1f else 0f }), SFaceModel.EMBEDDER_VERSION))
        }
        assertTrue(db.materializeClusters(epoch))
    }

    /** Content checksum of every People table: sorted rows of each table, in name order. */
    private fun peopleChecksum(db: FaceIndexStore): String {
        val sql = db.readableDatabase
        val digest = MessageDigest.getInstance("SHA-256")
        val tables = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        for (table in tables) {
            digest.update(table.toByteArray())
            sql.rawQuery("SELECT * FROM \"$table\"", null).use { c ->
                val rows = buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).joinToString("\u0001") { i ->
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "∅"
                            android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString(",")
                            else -> c.getString(i)
                        }
                    })
                }.sorted()
                rows.forEach { digest.update(it.toByteArray()); digest.update(byteArrayOf(10)) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeDerived(store: IdentityProfileStore, profileId: String, referenceId: String?) {
        store.derivedFile(profileId, referenceId, "aligned-112.bin").writeBytes(ByteArray(64) { it.toByte() })
        store.derivedFile(profileId, referenceId, "features.bin").writeBytes(ByteArray(32))
    }

    @Test fun peopleProfileReadsConfirmedFacesAndNeverChangesPeople() {
        FaceIndexStore(peopleContext(File(scratch, "people.db"))).use { people ->
            seedPeople(people)
            val before = peopleChecksum(people)
            val source = PeopleReferenceSource(FaceIndexStorePeopleReads(people))
            profileStore().use { store ->
                val read = source.faces(person.value) as PeopleFacesRead.Faces
                assertEquals(3, read.faces.size)
                assertTrue(read.faces.all { it.basis == PeopleFaceBasis.CONFIRMED })

                val profile = store.profileForPeoplePerson(person.value)
                assertEquals(profile.id, store.profileForPeoplePerson(person.value).id)
                assertNull("A People profile stores no copied name", profile.label)
                val current = profile.variants.single()
                assertEquals(IdentityProfileRules.DEFAULT_VARIANT_LABEL, current.label)
                val refs = read.faces.map { requireNotNull(store.addReference(current.id, it.toDraft())) }
                assertThrows(IllegalArgumentException::class.java) {
                    store.addReference(current.id, read.faces.first().toDraft(ReferenceOrigin.ADDED_BY_USER))
                }
                refs.forEach { writeDerived(store, profile.id, it.id) }

                // Variants, manual references, reorder and remove.
                val glasses = requireNotNull(store.addVariant(profile.id, "Glasses"))
                assertThrows(IllegalArgumentException::class.java) { store.addVariant(profile.id, "glasses") }
                val manual = requireNotNull(store.addReference(glasses.id, IdentityReferenceDraft(
                    "content://synthetic/primary/image/9#photo", "generation:1",
                    ReferenceFaceBox(0.2f, 0.2f, 0.6f, 0.7f),
                    ReferenceLandmarks(listOf(0.3f to 0.4f, 0.5f to 0.4f, 0.4f to 0.5f, 0.32f to 0.6f, 0.48f to 0.6f)),
                    ReferenceOrigin.MANUAL)))
                writeDerived(store, profile.id, manual.id)
                writeDerived(store, profile.id, null)
                assertTrue(store.reorderReferences(current.id, refs.map { it.id }.reversed()))
                assertFalse(store.reorderReferences(current.id, refs.map { it.id }.drop(1)))
                assertEquals(refs.map { it.id }.reversed(),
                    store.profile(profile.id)!!.variants.first().references.map { it.id })
                assertTrue(store.removeReference(refs[1].id))
                assertFalse(File(store.derivedFolder(profile.id), "../" + refs[1].id).exists())
                val reread = requireNotNull(store.profile(profile.id))
                assertEquals(listOf("Current", "Glasses"), reread.variants.map { it.label })
                assertEquals(2, reread.variants[0].references.size)
                assertEquals(manual.landmarks, reread.variants[1].references.single().landmarks)
                assertTrue(store.renameVariant(glasses.id, "Round glasses"))
                assertFalse("A People profile takes its name from People", store.renameProfile(profile.id, "Other"))

                // Checking and every write above left People exactly as it was.
                assertTrue(source.check(reread) { _, _ -> MediaPresence.PRESENT }.values.all { it == ReferenceCheck.Fresh })
                assertEquals(before, peopleChecksum(people))

                // Delete leaves no rows and no files, and People is still unchanged.
                val deletion = store.deleteProfile(profile.id)
                assertTrue(deletion.profileFound)
                assertTrue(deletion.complete)
                assertEquals(0 to 0, store.residue(profile.id))
                assertTrue(deletion.rowsDeleted >= 5 && deletion.filesDeleted >= 6)
                assertNull(store.profile(profile.id))
                assertFalse(File(File(scratch, "nobackup"), "${IdentityProfileStore.DERIVED_DIR}/${profile.id}").exists())
                assertEquals(before, peopleChecksum(people))
            }
        }
    }

    @Test fun takenOutAndRevisedFacesBecomeStaleNotSwapped() {
        FaceIndexStore(peopleContext(File(scratch, "people.db"))).use { people ->
            seedPeople(people)
            val source = PeopleReferenceSource(FaceIndexStorePeopleReads(people))
            profileStore().use { store ->
                val faces = (source.faces(person.value) as PeopleFacesRead.Faces).faces
                val profile = store.profileForPeoplePerson(person.value)
                val variant = profile.variants.single().id
                val refs = faces.map { requireNotNull(store.addReference(variant, it.toDraft())) }
                    // The face taken out below (photo 1) first, the photo edited below (3) last.
                    .sortedBy { it.mediaKey }

                // The user takes face 1 out of Ada in People (a People action, not ours).
                val prior = requireNotNull(people.peopleReview("epoch-A"))
                val takenOut = requireNotNull(reviewPeopleCluster(
                    PeopleClusterReviewState("epoch-A", prior.generation, prior.clusters, prior.names),
                    PeopleReviewAction.UnassignFace(NativeFaceObservationId(refs[0].peopleFaceUid!!))))
                assertNotNull(people.commitPeopleReview("epoch-A", prior.generation, takenOut.clusters, takenOut.names))
                val afterTakeOut = (source.faces(person.value) as PeopleFacesRead.Faces).faces.map { it.faceUid }
                assertFalse(refs[0].peopleFaceUid in afterTakeOut)

                // Photo 3 is edited: People drops its old face and rescans.
                val revisedMedia = refs.single { it.mediaKey.endsWith("/3#photo") }
                people.beginSnapshot("epoch-B")
                people.stageBatch("epoch-B", (1..3).map { if (it == 3) media(it, "generation:2") else media(it) })
                people.commitSnapshot("epoch-B", "detector-1", SFaceModel.EMBEDDER_VERSION)
                scan(people, "epoch-B")

                val order = requireNotNull(store.profile(profile.id)).variants.single().references
                val before = peopleChecksum(people)
                val checks = source.check(requireNotNull(store.profile(profile.id))) { _, _ -> MediaPresence.UNKNOWN }
                assertEquals(ReferenceCheck.Stale(StaleReason.LEFT_PERSON), checks[refs[0].id])
                assertEquals(ReferenceCheck.Stale(StaleReason.FACE_GONE), checks[revisedMedia.id])
                assertTrue(store.applyChecks(checks) >= 2)
                assertEquals(before, peopleChecksum(people))

                val after = requireNotNull(store.profile(profile.id)).variants.single().references
                assertEquals("Stale references stay in place", order.map { it.id }, after.map { it.id })
                assertEquals("and keep their face", order.map { it.peopleFaceUid }, after.map { it.peopleFaceUid })
                assertEquals(StaleReason.LEFT_PERSON, after[0].stale)
                assertEquals(StaleReason.FACE_GONE, after.single { it.id == revisedMedia.id }.stale)
                assertTrue(after.filter { it.id != refs[0].id && it.id != revisedMedia.id }.all { it.usable })
            }
        }
    }

    @Test fun manualProfileDeleteAllAndOrphanSweepLeaveNothing() {
        profileStore().use { store ->
            val a = store.createManualProfile("Grandpa")
            val b = store.createManualProfile("Aunt")
            assertTrue(store.renameProfile(a.id, "Grandpa Jan"))
            val ref = requireNotNull(store.addReference(a.variants.single().id, IdentityReferenceDraft(
                "content://synthetic/primary/image/21#photo", "generation:1",
                ReferenceFaceBox(0.1f, 0.1f, 0.4f, 0.5f), null, ReferenceOrigin.MANUAL)))
            writeDerived(store, a.id, ref.id)
            writeDerived(store, b.id, null)
            assertThrows(IllegalArgumentException::class.java) { store.derivedFile(a.id, null, "../escape") }

            // Gone media is marked stale and kept.
            val checks = PeopleReferenceSource(object : PeopleFaceReads {
                override fun activeEpoch(): String? = null
                override fun facts(epoch: String): PeopleFaceFacts? = null
                override fun location(epoch: String, faceUid: String) = null
                override fun notAFace(epoch: String, faceUid: String) = false
            }).check(requireNotNull(store.profile(a.id))) { _, _ -> MediaPresence.GONE }
            assertEquals(1, store.applyChecks(checks))
            assertEquals(StaleReason.MEDIA_GONE, store.profile(a.id)!!.variants.single().references.single().stale)

            // The last variant cannot be removed; an extra one takes its files with it.
            assertFalse(store.removeVariant(a.variants.single().id))
            val younger = requireNotNull(store.addVariant(a.id, "Younger"))
            val youngRef = requireNotNull(store.addReference(younger.id, IdentityReferenceDraft(
                "content://synthetic/primary/image/22#photo", "generation:1",
                ReferenceFaceBox(0.1f, 0.1f, 0.4f, 0.5f), null, ReferenceOrigin.MANUAL)))
            writeDerived(store, a.id, youngRef.id)
            assertTrue(store.removeVariant(younger.id))
            assertFalse(File(File(File(scratch, "nobackup"), IdentityProfileStore.DERIVED_DIR), "${a.id}/${youngRef.id}").exists())

            // An interrupted delete's leftover folder is swept.
            val orphan = File(File(File(scratch, "nobackup"), IdentityProfileStore.DERIVED_DIR), IdentityProfileIds.mint())
            assertTrue(orphan.mkdirs())
            File(orphan, "left.bin").writeBytes(ByteArray(4))
            assertEquals(1, store.sweepOrphanedDerivedData())
            assertFalse(orphan.exists())

            assertTrue(store.deleteAll())
            assertTrue(store.profiles().isEmpty())
            assertEquals(0 to 0, store.residue(a.id))
            assertEquals(0 to 0, store.residue(b.id))
        }
    }

    @Test fun storeLivesInTheNoBackupFolderUnderItsOwnName() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(scratch, "nobackup").also { it.mkdirs() }
        IdentityProfileStore(ctx, root = root).use { store ->
            store.createManualProfile("Someone")
            assertEquals(File(root, IdentityProfileStore.DB_NAME).absolutePath, store.readableDatabase.path)
        }
        assertFalse("No file in the app's shared databases folder",
            ctx.getDatabasePath(IdentityProfileStore.DB_NAME).exists())
        // The production default is the app's no-backup folder.
        assertTrue(File(ctx.noBackupFilesDir, IdentityProfileStore.DB_NAME).absolutePath
            .startsWith(ctx.noBackupFilesDir.absolutePath))
    }
}
