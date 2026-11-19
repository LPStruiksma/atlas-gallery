package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import nl.atlas.gallery.featurepersistence.NativePeopleNamesBackupCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** COVER-3: database-backed, synthetic faces only; independent of private media. */
@RunWith(AndroidJUnit4::class)
class PersonCoverOverrideAndroidTest {
    private val person = PersonKey("cover-person")
    private val box = IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)
    private fun media(n: Int) = IndexedMedia("content://synthetic/primary/image/$n#photo", "generation:1")
    private fun dbFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "cover-3-" + UUID.randomUUID())
        check(dir.mkdirs())
        return File(dir, "people.db")
    }
    private fun context(file: File): Context {
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
    private fun grant(db: FaceIndexStore, epoch: String, model: String = SFaceModel.EMBEDDER_VERSION) {
        db.beginSnapshot(epoch)
        db.stageBatch(epoch, (1..3).map(::media))
        db.commitSnapshot(epoch, "detector-1", model)
    }
    private fun scan(db: FaceIndexStore, epoch: String, model: String = SFaceModel.EMBEDDER_VERSION) {
        var tick = 1000L
        while (true) {
            val lease = db.claim(epoch, tick++) ?: break
            assertTrue(db.completeEmbeddings(lease, listOf(box),
                listOf(FloatArray(SFaceModel.DIMENSION) { if (it == 0) 1f else 0f }), model))
        }
        assertTrue(db.materializeClusters(epoch))
    }
    private fun name(db: FaceIndexStore, epoch: String): List<String> {
        val prior = requireNotNull(db.peopleReview(epoch))
        val group = prior.clusters.unknownClusters.first { it.members.size == 3 }
        val result = requireNotNull(reviewPeopleCluster(
            PeopleClusterReviewState(epoch, prior.generation, prior.clusters, prior.names),
            PeopleReviewAction.NameCluster(group.id, person, "Ada")))
        assertNotNull(db.commitPeopleReview(epoch, prior.generation, result.clusters, result.names))
        return requireNotNull(db.peopleReview(epoch)).clusters.reviews.confirmed.keys
            .map { it.localHex }.sorted()
    }
    @Test fun selectedFaceWinsRevertAndSplitFallBack() {
        val file = dbFile()
        try {
            FaceIndexStore(context(file)).use { db ->
                grant(db, "epoch-A"); scan(db, "epoch-A")
                val faces = name(db, "epoch-A")
                val automatic = requireNotNull(db.peopleHome("epoch-A")).people[person]!!.coverFaceUid
                val selected = faces.last()
                assertNotEquals(automatic, selected)
                assertTrue(db.setPersonCoverOverride("epoch-A", person, selected))
                assertEquals(selected, db.personCoverOverride("epoch-A", person))
                assertEquals(selected, db.peopleHome("epoch-A")!!.people[person]!!.coverFaceUid)
                assertTrue(db.setPersonCoverOverride("epoch-A", person, null))
                assertNull(db.personCoverOverride("epoch-A", person))
                assertEquals(automatic, db.peopleHome("epoch-A")!!.people[person]!!.coverFaceUid)
                assertTrue(db.setPersonCoverOverride("epoch-A", person, selected))
                val before = requireNotNull(db.peopleReview("epoch-A"))
                val next = requireNotNull(reviewPeopleCluster(
                    PeopleClusterReviewState("epoch-A", before.generation, before.clusters, before.names),
                    PeopleReviewAction.SplitPerson(person, setOf(
                        nl.atlas.gallery.featureface.NativeFaceObservationId(selected)))))
                assertNotNull(db.commitPeopleReview("epoch-A", before.generation, next.clusters, next.names))
                assertNull(db.personCoverOverride("epoch-A", person))
                assertEquals(automatic, db.peopleHome("epoch-A")!!.people[person]!!.coverFaceUid)
            }
        } finally { file.parentFile?.deleteRecursively() }
    }
    @Test fun detectorUpgradePreservesOnlyExactSourceFaceForSamePerson() {
        val file = dbFile()
        try {
            FaceIndexStore(context(file)).use { db ->
                grant(db, "epoch-A"); scan(db, "epoch-A")
                val selected = name(db, "epoch-A").last()
                assertTrue(db.setPersonCoverOverride("epoch-A", person, selected))

                // The detector version changes, but the same source/box/ordinal
                // is observed again and the person is still confirmed.
                db.beginSnapshot("epoch-B")
                db.stageBatch("epoch-B", (1..3).map(::media))
                db.commitSnapshot("epoch-B", "detector-2")
                assertNull(db.personCoverOverride("epoch-B", person))
                scan(db, "epoch-B")
                val remapped = db.personCoverOverride("epoch-B", person)
                assertNotNull("Exact face should survive detector upgrade", remapped)
                assertNotEquals(selected, remapped)
                assertEquals(remapped, db.peopleHome("epoch-B")!!.people[person]!!.coverFaceUid)

                // A genuinely changed source revision must not inherit a choice.
                // Face UIDs are random, so the selected face may sit on any of the
                // three photos (#425): revise exactly the photo that holds it.
                val source = requireNotNull(db.faceMedia("epoch-B", listOf(remapped!!))[remapped])
                db.beginSnapshot("epoch-C")
                db.stageBatch("epoch-C", (1..3).map {
                    if (media(it).key == source.key) IndexedMedia(source.key, "generation:2") else media(it)
                })
                db.commitSnapshot("epoch-C", "detector-2")
                assertNull(db.personCoverOverride("epoch-C", person))
            }
        } finally { file.parentFile?.deleteRecursively() }
    }
    @Test fun survivesRelaunchRescanModelChangeAndBackupRestore() {
        val file = dbFile()
        var chosen = ""
        try {
            FaceIndexStore(context(file)).use { db ->
                grant(db, "epoch-A"); scan(db, "epoch-A")
                chosen = name(db, "epoch-A").last()
                assertTrue(db.setPersonCoverOverride("epoch-A", person, chosen))
                val bytes = NativePeopleNamesBackupCodec.encode(
                    requireNotNull(db.exportNativeNamesSnapshot("epoch-A")))
                assertEquals(chosen, NativePeopleNamesBackupCodec.decode(bytes).coverOverrides[person.value])
                assertTrue(db.setPersonCoverOverride("epoch-A", person, null))
                db.importNativeNamesSnapshot("epoch-A", NativePeopleNamesBackupCodec.decode(bytes))
                assertEquals(chosen, db.personCoverOverride("epoch-A", person))
            }
            FaceIndexStore(context(file)).use { db ->
                assertNull(db.personCoverOverride("epoch-A", person))
                grant(db, "epoch-B")
                assertTrue(db.materializeClusters("epoch-B"))
                assertEquals(chosen, db.personCoverOverride("epoch-B", person))
                // Re-embed the same source with a new model version: observation UIDs change.
                grant(db, "epoch-C", "new-model-version")
                scan(db, "epoch-C", "new-model-version")
                val remapped = db.personCoverOverride("epoch-C", person)
                assertNotNull(remapped)
                assertNotEquals(chosen, remapped)
                assertEquals(remapped, db.peopleHome("epoch-C")!!.people[person]!!.coverFaceUid)
            }
        } finally { file.parentFile?.deleteRecursively() }
    }
}
