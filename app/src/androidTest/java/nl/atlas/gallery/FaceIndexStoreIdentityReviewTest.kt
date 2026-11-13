package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeople.identity.PeopleNumbers
import nl.atlas.gallery.featurepeople.identity.ReviewTally
import nl.atlas.gallery.featurepeople.identity.ReviewTap
import nl.atlas.gallery.featurepeople.identity.TapCount
import nl.atlas.gallery.featurepeopleui.reviewTally
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import nl.atlas.gallery.featurepersistence.PeopleReviewRecords
import nl.atlas.gallery.featurepersistence.StoredPeopleReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D2-5c part 1 (#434): schema v3 durable identity review on a real SQLite
 * database. Invented media keys and axis-aligned synthetic vectors only; no
 * photos or real biometric data. Each test uses its own app-cache DB file.
 */
@RunWith(AndroidJUnit4::class)
class FaceIndexStoreIdentityReviewTest {
    private val model = SFaceModel.EMBEDDER_VERSION
    private val box = IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)
    private fun media(n: Int, rev: Int = 1) =
        IndexedMedia("content://synthetic/primary/image/$n#photo", "generation:$rev")
    private fun vector(axis: Int) = FloatArray(SFaceModel.DIMENSION) { if (it == axis) 1f else 0f }
    private val ana = PersonKey("person-ana")

    private fun dbFile(): File {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "d25c-identity-" + UUID.randomUUID())
        check(dir.mkdirs())
        return File(dir, "face_fixture.db")
    }
    private fun contextFor(file: File): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
    }
    private inline fun withFile(test: (File) -> Unit) {
        val file = dbFile()
        try { test(file) } finally { file.parentFile?.deleteRecursively() }
    }
    private inline fun withStore(test: (FaceIndexStore) -> Unit) = withFile { file ->
        FaceIndexStore(contextFor(file)).use(test)
    }

    private fun grant(store: FaceIndexStore, epoch: String, rows: List<IndexedMedia>,
                      embedder: String = model) {
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, rows)
        store.commitSnapshot(epoch, "detector-1", embedder)
    }
    /** Scan every queued photo; photo n gets one face on axis [axisOf] (n). */
    private fun scanAll(store: FaceIndexStore, epoch: String, embedder: String = model,
                        axisOf: (String) -> Int) {
        var now = 1000L
        while (true) {
            val lease = store.claim(epoch, now++) ?: break
            assertTrue(store.completeEmbeddings(lease, listOf(box),
                listOf(vector(axisOf(lease.media.key))), embedder))
        }
        assertTrue(store.materializeClusters(epoch))
    }
    private fun count(store: FaceIndexStore, table: String): Int {
        require(table in listOf("people_names", "people_confirmed", "people_cannot_link",
            "people_rejected", "people_same_unknown", "cluster_members", "embeddings",
            "people_matched"))
        return store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            check(it.moveToFirst()); it.getInt(0)
        }
    }
    /** #580: `people_matched` as stored, face uid to person key. */
    private fun storedMatches(store: FaceIndexStore): Map<String, String> =
        store.readableDatabase.rawQuery("SELECT face_uid,person_key FROM people_matched", null)
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
    private fun assertMatchesStored(store: FaceIndexStore, stored: StoredPeopleReview) =
        assertEquals("people_matched equals the published matches",
            PeopleReviewRecords.matchedRows(stored.clusters), storedMatches(store))

    private fun uiState(epoch: String, stored: StoredPeopleReview) =
        PeopleClusterReviewState(epoch, stored.generation, stored.clusters, stored.names)

    /** Photos 1..3 share axis 0 (one person), photo 4 is a stranger on axis 5. */
    private fun scannedLibrary(store: FaceIndexStore, epoch: String = "epoch-A"): StoredPeopleReview {
        grant(store, epoch, (1..4).map { media(it) })
        scanAll(store, epoch) { if (it.endsWith("/4#photo")) 5 else 0 }
        return requireNotNull(store.peopleReview(epoch))
    }
    private fun nameBiggest(epoch: String, stored: StoredPeopleReview,
                            person: PersonKey = ana, name: String = "Ana"): PeopleClusterReviewState {
        val cluster = stored.clusters.unknownClusters.first { it.members.size == 3 }
        return requireNotNull(reviewPeopleCluster(uiState(epoch, stored),
            PeopleReviewAction.NameCluster(cluster.id, person, name)))
    }
    private fun commit(store: FaceIndexStore, epoch: String, expected: String,
                       next: PeopleClusterReviewState): String? =
        store.commitPeopleReview(epoch, expected, next.clusters, next.names)

    @Test fun namedPersonSurvivesProcessRestartAndFreshGrant() = withFile { file ->
        var confirmedFaces = emptySet<NativeFaceObservationId>()
        var unknownAfterReview = emptyList<List<NativeFaceObservationId>>()
        FaceIndexStore(contextFor(file)).use { store ->
            val stored = scannedLibrary(store)
            val named = nameBiggest("epoch-A", stored)
            val generation = requireNotNull(commit(store, "epoch-A", stored.generation, named))
            assertNotEquals(stored.generation, generation)
            val reread = requireNotNull(store.peopleReview("epoch-A"))
            assertEquals(generation, reread.generation)
            assertEquals(mapOf(ana to "Ana"), reread.names)
            assertEquals(named.clusters.reviews, reread.clusters.reviews)
            assertEquals(named.clusters.unknownClusters, reread.clusters.unknownClusters)
            confirmedFaces = reread.clusters.reviews.confirmed.keys
            unknownAfterReview = reread.clusters.unknownClusters.map { it.members }
            assertEquals(3, confirmedFaces.size)
            assertEquals(3, count(store, "people_confirmed"))
            assertEquals(1, count(store, "cluster_members")) // only the stranger
        }
        // Process death: a reopened helper is no grant; a fresh epoch restores.
        FaceIndexStore(contextFor(file)).use { store ->
            assertNull(store.peopleReview("epoch-A"))
            grant(store, "epoch-B", (1..4).map { media(it) })
            assertTrue(store.materializeClusters("epoch-B"))
            val restored = requireNotNull(store.peopleReview("epoch-B"))
            assertEquals(mapOf(ana to "Ana"), restored.names)
            assertEquals(confirmedFaces, restored.clusters.reviews.confirmed.keys)
            assertTrue(restored.clusters.reviews.confirmed.values.all { it == ana })
            assertEquals(unknownAfterReview, restored.clusters.unknownClusters.map { it.members })
        }
    }

    /**
     * #580: review taps are counted in the commit's own transaction, per kind,
     * survive a restart and a fresh grant, and a refused commit counts nothing.
     * A file from before the table gets it on open.
     */
    @Test fun reviewTapsAreCountedWithTheCommitAndSurviveRestart() = withFile { file ->
        FaceIndexStore(contextFor(file)).use { store ->
            val stored = scannedLibrary(store)
            assertEquals(emptyMap<ReviewTap, TapCount>(), store.peopleReviewTally("epoch-A"))
            val before = uiState("epoch-A", stored)
            val group = stored.clusters.unknownClusters.first { it.members.size == 3 }
            val name = PeopleReviewAction.NameCluster(group.id, ana, "Ana")
            val named = requireNotNull(reviewPeopleCluster(before, name))
            val tally = requireNotNull(reviewTally(before, name))
            assertEquals(ReviewTally(ReviewTap.NAMED, 3), tally)
            val generation = requireNotNull(store.commitPeopleReview("epoch-A", stored.generation,
                named.clusters, named.names, tally = tally))
            // The same snapshot again is stale: refused, and not counted.
            assertNull(store.commitPeopleReview("epoch-A", stored.generation,
                named.clusters, named.names, tally = tally))
            val after = uiState("epoch-A", requireNotNull(store.peopleReview("epoch-A")))
            val out = PeopleReviewAction.SplitPerson(ana, setOf(group.members.last()))
            val split = requireNotNull(reviewPeopleCluster(after, out))
            assertNotNull(store.commitPeopleReview("epoch-A", generation, split.clusters,
                split.names, tally = reviewTally(after, out)))
            assertEquals(mapOf(ReviewTap.NAMED to TapCount(1, 3),
                ReviewTap.TAKEN_OUT to TapCount(1, 1)), store.peopleReviewTally("epoch-A"))
            // Only counts are stored: a kind and two numbers per row.
            store.readableDatabase.rawQuery("SELECT kind,taps,faces FROM people_review_tally", null)
                .use { c -> assertEquals(2, c.count); assertEquals(3, c.columnCount) }
            // Undo takes the undone tap off its kind again.
            val undo = requireNotNull(reviewTally(split, PeopleReviewAction.Undo))
            assertEquals(ReviewTally(ReviewTap.TAKEN_OUT, 1), undo.undoes)
            val undone = requireNotNull(reviewPeopleCluster(split, PeopleReviewAction.Undo))
            assertNotNull(store.commitPeopleReview("epoch-A",
                requireNotNull(store.peopleReviewGeneration("epoch-A")),
                undone.clusters, undone.names, tally = undo))
            assertEquals(mapOf(ReviewTap.NAMED to TapCount(1, 3),
                ReviewTap.TAKEN_OUT to TapCount(0, 0), ReviewTap.UNDONE to TapCount(1, 0)),
                store.peopleReviewTally("epoch-A"))
            store.writableDatabase.execSQL("DROP TABLE people_review_tally")
        }
        FaceIndexStore(contextFor(file)).use { store ->
            assertNull(store.peopleReviewTally("epoch-A"))
            grant(store, "epoch-B", (1..4).map { media(it) })
            assertTrue(store.materializeClusters("epoch-B"))
            assertEquals(emptyMap<ReviewTap, TapCount>(), store.peopleReviewTally("epoch-B"))
            val stored = requireNotNull(store.peopleReview("epoch-B"))
            val state = uiState("epoch-B", stored)
            val numbers = PeopleNumbers.of(stored.clusters, mapOf(ReviewTap.TAKEN_OUT to TapCount(1, 1)))
            assertEquals(1, numbers.namedPeople)
            assertEquals(3, numbers.namedFaces)
            assertEquals(1.0 / 4, numbers.wrongShare!!, 1e-9)
            assertFalse(numbers.line().contains("Ana"))
            assertEquals(mapOf(ana to "Ana"), state.names)
        }
    }

    /**
     * #580: a "yes" to "same person?" joins two unnamed groups, and the answer
     * outlives a restart and a regroup from the stored vectors; a "no" is
     * stored as "not the same person".
     */
    @Test fun samePersonAnswersAreStoredAndSurviveRestartAndRegroup() = withFile { file ->
        var joinedMembers = emptyList<NativeFaceObservationId>()
        FaceIndexStore(contextFor(file)).use { store ->
            val stored = scannedLibrary(store)
            val group = stored.clusters.unknownClusters.first { it.members.size == 3 }
            val stranger = stored.clusters.unknownClusters.single { it.members.size == 1 }
            val joined = requireNotNull(reviewPeopleCluster(uiState("epoch-A", stored),
                PeopleReviewAction.JoinUnknownClusters(group.id, stranger.id)))
            assertNotNull(commit(store, "epoch-A", stored.generation, joined))
            val reread = requireNotNull(store.peopleReview("epoch-A"))
            assertEquals(joined.clusters.reviews, reread.clusters.reviews)
            joinedMembers = reread.clusters.unknownClusters.single().members
            assertEquals(4, joinedMembers.size)
            assertEquals(1, count(store, "people_same_unknown"))
            assertEquals(0, count(store, "people_cannot_link"))
        }
        // Restart, then a regroup as after a rules change: every face placed again.
        FaceIndexStore(contextFor(file)).use { store ->
            grant(store, "epoch-B", (1..4).map { media(it) })
            assertEquals(joinedMembers,
                requireNotNull(store.peopleReview("epoch-B")).clusters.unknownClusters.single().members)
            store.writableDatabase.execSQL("UPDATE cluster_members SET anchor_uid=face_uid")
            store.writableDatabase.execSQL(
                "UPDATE cluster_policy SET fingerprint='older-rules' WHERE singleton=1")
        }
        FaceIndexStore(contextFor(file)).use { store ->
            grant(store, "epoch-C", (1..4).map { media(it) })
            assertTrue(store.regroupPending("epoch-C"))
            assertTrue(store.materializeClusters("epoch-C"))
            val regrouped = requireNotNull(store.peopleReview("epoch-C"))
            assertEquals(joinedMembers, regrouped.clusters.unknownClusters.single().members)
            // Taking a face out again works as for any group, and beats the earlier yes.
            val split = requireNotNull(reviewPeopleCluster(uiState("epoch-C", regrouped),
                PeopleReviewAction.SplitUnknownCluster(regrouped.clusters.unknownClusters.single().id,
                    setOf(joinedMembers.last()))))
            assertNotNull(commit(store, "epoch-C", regrouped.generation, split))
            val apart = requireNotNull(store.peopleReview("epoch-C"))
            assertTrue(apart.clusters.unknownClusters.none { it.members.size == 4 })
            assertEquals(3, count(store, "people_cannot_link"))
        }
    }

    /** #434 part 2: the host's cheap generation read and the person album keys. */
    @Test fun personAlbumKeysAndGenerationFollowTheLiveEpoch() = withFile { file ->
        FaceIndexStore(contextFor(file)).use { store ->
            val stored = scannedLibrary(store)
            assertEquals(stored.generation, store.peopleReviewGeneration("epoch-A"))
            assertTrue(store.personMedia("epoch-A", ana).isEmpty())
            // #553: before naming, photo 1's one face is in a published unknown group.
            val before = store.photoFaces("epoch-A", media(1).key, media(1).revision).single()
            val group = stored.clusters.unknownClusters.first { it.members.size == 3 }
            assertEquals(group.id.anchor.localHex, before.clusterAnchor)
            assertNull(before.person)
            assertEquals(box, before.box)
            val generation = requireNotNull(
                commit(store, "epoch-A", stored.generation, nameBiggest("epoch-A", stored)))
            assertEquals(generation, store.peopleReviewGeneration("epoch-A"))
            assertEquals((1..3).map { media(it) }, store.personMedia("epoch-A", ana))
            assertEquals(listOf(media(1)), store.personMedia("epoch-A", ana, limit = 1))
            assertTrue(store.personMedia("epoch-A", PersonKey("person-nobody")).isEmpty())
            // #553: the named face reads its person and name, the stranger stays unnamed.
            val named = store.photoFaces("epoch-A", media(2).key, media(2).revision).single()
            assertEquals(Triple("person-ana", "Ana", null as String?),
                Triple(named.person, named.name, named.clusterAnchor))
            val stranger = store.photoFaces("epoch-A", media(4).key, media(4).revision).single()
            assertNull(stranger.person)
            assertEquals(stranger.nativeFaceUid, stranger.clusterAnchor)
            assertTrue(store.photoFaces("epoch-A", media(2).key, "generation:9").isEmpty())
            assertTrue(store.photoFaces("epoch-old", media(2).key, media(2).revision).isEmpty())
            // A stale or revoked epoch reads nothing.
            assertNull(store.peopleReviewGeneration("epoch-old"))
            assertTrue(store.personMedia("epoch-old", ana).isEmpty())
            store.invalidatePermissionScope()
            assertNull(store.peopleReviewGeneration("epoch-A"))
            assertTrue(store.personMedia("epoch-A", ana).isEmpty())
        }
        FaceIndexStore(contextFor(file)).use { store ->
            // Photo 2 was edited (new revision): its face is no longer processed,
            // so the album keeps photos 1 and 3 until the photo is scanned again.
            grant(store, "epoch-B", listOf(media(1), media(2, rev = 2), media(3), media(4)))
            assertEquals(listOf(media(1), media(3)), store.personMedia("epoch-B", ana))
            assertTrue(store.photoFaces("epoch-B", media(2).key, "generation:2").isEmpty())
        }
    }

    /**
     * #580: a later face the rule is sure about shows under the named person
     * without becoming a confirmed example; the store keeps it as a row of its
     * own, so a fresh grant reads the same.
     */
    @Test fun laterScanAddsASureFaceOnItsOwnAndConfirmedFacesAreNotPending() = withStore { store ->
        val stored = scannedLibrary(store)
        val named = nameBiggest("epoch-A", stored)
        assertNotNull(commit(store, "epoch-A", stored.generation, named))
        grant(store, "epoch-B", (1..5).map { media(it) })
        // #543: a new grant that only adds a photo prunes nothing, so the
        // generation stays clean and People is readable at once.
        assertNotNull(store.peopleReview("epoch-B"))
        assertTrue(store.materializeClusters("epoch-B"))
        // Re-publishing a clean grant reads confirmed faces as seed, not pending.
        assertEquals(1, count(store, "cluster_members"))
        assertEquals(3, count(store, "people_confirmed"))
        scanAll(store, "epoch-B") { 0 } // photo 5: same direction as Ana
        val after = requireNotNull(store.peopleReview("epoch-B"))
        assertEquals(3, after.clusters.reviews.confirmed.size)
        val newcomer = after.clusters.matched.keys.single()
        assertTrue(newcomer !in stored.clusters)
        assertEquals(ana, after.clusters.matched.getValue(newcomer).person)
        assertTrue(after.clusters.unknownClusters.none { newcomer in it.members })
        assertEquals(1, after.clusters.unknownClusters.size) // the stranger
        assertEquals(2, count(store, "cluster_members"))
        assertEquals(3, count(store, "people_confirmed"))
        // #580: the match is stored, so no reader needs cluster state.
        assertEquals(mapOf(newcomer.localHex to ana.value), storedMatches(store))
        assertMatchesStored(store, after)
        // People home counts it; the cover stays a confirmed face.
        val home = store.peopleHome("epoch-B")!!.people.getValue(ana)
        assertEquals(4, home.faceCount)
        assertTrue(home.coverFaceUid != newcomer.localHex)
        // The viewer's People row names it, and the person album holds its photo.
        val shown = store.photoFaces("epoch-B", media(5).key, media(5).revision).single()
        assertEquals(newcomer.localHex, shown.nativeFaceUid)
        assertEquals(ana.value, shown.person)
        assertEquals("Ana", shown.name)
        assertNull(shown.clusterAnchor)
        assertEquals((1..3).map { media(it) } + media(5), store.personMedia("epoch-B", ana))
        // Taking it out is a stored "not this person": it stays an unnamed face.
        val before = uiState("epoch-B", after)
        val out = PeopleReviewAction.SplitPerson(ana, setOf(newcomer))
        assertEquals(ReviewTally(ReviewTap.AUTO_TAKEN_OUT, 1), reviewTally(before, out))
        val split = requireNotNull(reviewPeopleCluster(before, out))
        assertNotNull(store.commitPeopleReview("epoch-B", after.generation, split.clusters,
            split.names, tally = reviewTally(before, out)))
        assertEquals(1, count(store, "people_rejected"))
        assertEquals(3, count(store, "people_confirmed"))
        assertEquals(TapCount(1, 1), store.peopleReviewTally("epoch-B")!![ReviewTap.AUTO_TAKEN_OUT])
        assertEquals(0, count(store, "people_matched"))
        assertMatchesStored(store, requireNotNull(store.peopleReview("epoch-B")))
        val takenOut = store.photoFaces("epoch-B", media(5).key, media(5).revision).single()
        assertNull(takenOut.person)
        assertEquals((1..3).map { media(it) }, store.personMedia("epoch-B", ana))
        assertEquals(3, store.peopleHome("epoch-B")!!.people.getValue(ana).faceCount)
        // Undo puts the match back, and the stored rows follow.
        val undone = requireNotNull(reviewPeopleCluster(split, PeopleReviewAction.Undo))
        assertNotNull(store.commitPeopleReview("epoch-B",
            requireNotNull(store.peopleReviewGeneration("epoch-B")), undone.clusters, undone.names,
            tally = reviewTally(split, PeopleReviewAction.Undo)))
        val back = requireNotNull(store.peopleReview("epoch-B"))
        assertEquals(setOf(newcomer), back.clusters.matched.keys)
        assertMatchesStored(store, back)
        assertEquals(ana.value, store.photoFaces("epoch-B", media(5).key, media(5).revision)
            .single().person)
        assertEquals(4, store.peopleHome("epoch-B")!!.people.getValue(ana).faceCount)
        // Take it out again for the regroup below.
        val again = requireNotNull(reviewPeopleCluster(uiState("epoch-B", back), out))
        assertNotNull(store.commitPeopleReview("epoch-B", back.generation, again.clusters,
            again.names))
        assertMatchesStored(store, requireNotNull(store.peopleReview("epoch-B")))
        grant(store, "epoch-C", (1..5).map { media(it) })
        assertTrue(store.materializeClusters("epoch-C"))
        val later = requireNotNull(store.peopleReview("epoch-C"))
        assertTrue(later.clusters.matched.isEmpty())
        assertEquals(2, later.clusters.unknownClusters.size)
        assertMatchesStored(store, later)
    }

    /**
     * #580: a file from before `people_matched` (or one an older build wrote
     * to after) opens, waits for one publication from stored vectors, and
     * then holds exactly the published matches; a current file needs none.
     */
    @Test fun olderFileFillsStoredMatchesAtTheNextPublication() = withFile { file ->
        var newcomer = ""
        FaceIndexStore(contextFor(file)).use { store ->
            val stored = scannedLibrary(store)
            assertNotNull(commit(store, "epoch-A", stored.generation, nameBiggest("epoch-A", stored)))
            grant(store, "epoch-B", (1..5).map { media(it) })
            scanAll(store, "epoch-B") { if (it.endsWith("/4#photo")) 5 else 0 }
            newcomer = requireNotNull(store.peopleReview("epoch-B")).clusters.matched.keys
                .single().localHex
            // As a build from before the table left the file.
            store.writableDatabase.execSQL("DROP TABLE people_matched")
            store.writableDatabase.execSQL("DROP TABLE people_matched_generation")
        }
        FaceIndexStore(contextFor(file)).use { store ->
            assertEquals(0, count(store, "people_matched"))
            grant(store, "epoch-C", (1..5).map { media(it) })
            assertNull(store.peopleReview("epoch-C")) // waits for the fill
            // Before the fill: no wrong name, confirmed photos only, no stale count.
            assertNull(store.photoFaces("epoch-C", media(5).key, media(5).revision).single().person)
            assertEquals((1..3).map { media(it) }, store.personMedia("epoch-C", ana))
            assertNull(store.peopleHome("epoch-C"))
            assertTrue(store.regroupPending("epoch-C"))
            val members = count(store, "cluster_members")
            assertTrue(store.materializeClusters("epoch-C"))
            val filled = requireNotNull(store.peopleReview("epoch-C"))
            assertEquals(mapOf(newcomer to ana.value), storedMatches(store))
            assertMatchesStored(store, filled)
            assertEquals(members, count(store, "cluster_members")) // no face placed anew
            assertEquals(4, store.peopleHome("epoch-C")!!.people.getValue(ana).faceCount)
            // As an older build left it after a later commit: a wrong row, another revision.
            val stranger = store.photoFaces("epoch-C", media(4).key, media(4).revision)
                .single().nativeFaceUid
            store.writableDatabase.execSQL(
                "INSERT INTO people_matched(face_uid,person_key) VALUES(?,?)",
                arrayOf<Any>(stranger, ana.value))
            store.writableDatabase.execSQL(
                "UPDATE people_matched_generation SET revision=-1 WHERE singleton=1")
        }
        FaceIndexStore(contextFor(file)).use { store ->
            grant(store, "epoch-D", (1..5).map { media(it) })
            assertTrue(store.regroupPending("epoch-D"))
            assertTrue(store.materializeClusters("epoch-D"))
            assertEquals(mapOf(newcomer to ana.value), storedMatches(store))
            assertMatchesStored(store, requireNotNull(store.peopleReview("epoch-D")))
        }
        // Current rows: the next open publishes nothing and People reads at once.
        FaceIndexStore(contextFor(file)).use { store ->
            grant(store, "epoch-E", (1..5).map { media(it) })
            assertFalse(store.regroupPending("epoch-E"))
            assertNotNull(store.peopleReview("epoch-E"))
        }
    }

    @Test fun staleGenerationRevokedEpochAndDirtyClustersRefuseCommit() = withStore { store ->
        val stored = scannedLibrary(store)
        val named = nameBiggest("epoch-A", stored)
        assertNull(commit(store, "epoch-A", "people-review:999", named))
        assertNull(commit(store, "epoch-B", stored.generation, named))
        val committed = requireNotNull(commit(store, "epoch-A", stored.generation, named))
        // The same command replayed against its old snapshot is refused.
        assertNull(commit(store, "epoch-A", stored.generation, named))
        // A second review computed from the committed snapshot is accepted.
        val reread = requireNotNull(store.peopleReview("epoch-A"))
        val stranger = reread.clusters.unknownClusters.single()
        val second = requireNotNull(reviewPeopleCluster(uiState("epoch-A", reread),
            PeopleReviewAction.NameCluster(stranger.id, PersonKey("person-bo"), "Bo")))
        // A new grant that edits a photo makes clusters dirty: no commit until
        // they are published, even with the still-current generation.
        grant(store, "epoch-B", (1..5).map { if (it == 4) media(4, rev = 2) else media(it) })
        assertNull(store.peopleReview("epoch-B"))
        assertNull(commit(store, "epoch-B", committed, second))
        assertNull(commit(store, "epoch-A", committed, second)) // superseded epoch
        scanAll(store, "epoch-B") { 9 }
        val fresh = requireNotNull(store.peopleReview("epoch-B"))
        assertNotEquals(committed, fresh.generation) // publication advanced it
        assertNull(commit(store, "epoch-B", committed, second))
        assertTrue(fresh.names.keys == setOf(ana))
        // Revocation hides names and facts and refuses writes.
        store.invalidatePermissionScope()
        assertNull(store.peopleReview("epoch-B"))
        assertNull(store.clusterState("epoch-B"))
        assertNull(commit(store, "epoch-B", fresh.generation,
            uiState("epoch-B", fresh)))
        assertEquals(1, count(store, "people_names"))
    }

    @Test fun sourceChangeDropsFactsForOldObservationsAndModelBumpClearsNames() = withStore { store ->
        val stored = scannedLibrary(store)
        val named = nameBiggest("epoch-A", stored)
        val cluster = stored.clusters.unknownClusters.first { it.members.size == 3 }
        // Split-off barrier and a rejection, so every v3 table holds rows.
        val withBarrier = requireNotNull(reviewPeopleCluster(named,
            PeopleReviewAction.SplitPerson(ana, setOf(cluster.members.first()))))
        assertNotNull(commit(store, "epoch-A", stored.generation, withBarrier))
        assertEquals(2, count(store, "people_confirmed"))
        assertEquals(2, count(store, "people_cannot_link"))
        assertEquals(1, count(store, "people_rejected"))

        val oldUid = store.embeddingRecords("epoch-A", media(1).key).single().nativeFaceUid
        val oldId = NativeFaceObservationId(oldUid)
        assertTrue(oldId in withBarrier.clusters.reviews.confirmed ||
            withBarrier.clusters.reviews.rejectedPeople.any { it.observation == oldId })
        // Photo 1 edited: its observation is rescanned under a new ID.
        val changed = listOf(media(1, rev = 2)) + (2..4).map { media(it) }
        grant(store, "epoch-B", changed)
        scanAll(store, "epoch-B") { if (it.endsWith("/4#photo")) 5 else 0 }
        val after = requireNotNull(store.peopleReview("epoch-B"))
        val facts = after.clusters.reviews
        assertFalse(oldId in after.clusters)
        assertFalse(oldId in facts.confirmed)
        assertTrue(facts.rejectedPeople.none { it.observation == oldId })
        assertTrue(facts.cannotLink.none { it.first == oldId || it.second == oldId })
        assertTrue(after.clusters.reviews.confirmed.keys.all { it in after.clusters })
        assertTrue(after.clusters.reviews.cannotLink.all {
            it.first in after.clusters && it.second in after.clusters })
        assertEquals(mapOf(ana to "Ana"), after.names) // other photos still name Ana

        // Embedder bump: every observation is new, so identity starts empty.
        grant(store, "epoch-C", changed, embedder = "$model-vNext")
        assertEquals(0, count(store, "embeddings"))
        assertEquals(0, count(store, "people_confirmed"))
        assertEquals(0, count(store, "people_cannot_link"))
        assertEquals(0, count(store, "people_rejected"))
        assertEquals(0, count(store, "people_names"))
        scanAll(store, "epoch-C", embedder = "$model-vNext") { 0 }
        val bumped = requireNotNull(store.peopleReview("epoch-C"))
        assertTrue(bumped.names.isEmpty())
        assertTrue(bumped.clusters.reviews.confirmed.isEmpty())
    }

    @Test fun suppressedFactWriteRollsBackWholeReview() = withStore { store ->
        val stored = scannedLibrary(store)
        val named = nameBiggest("epoch-A", stored)
        store.writableDatabase.execSQL("""CREATE TRIGGER suppress_people_name
            BEFORE INSERT ON people_names BEGIN SELECT RAISE(IGNORE); END""")
        try {
            commit(store, "epoch-A", stored.generation, named)
            fail("A suppressed name write was reported as saved")
        } catch (_: IllegalStateException) { }
        store.writableDatabase.execSQL("DROP TRIGGER suppress_people_name")
        val unchanged = requireNotNull(store.peopleReview("epoch-A"))
        assertEquals(stored.generation, unchanged.generation)
        assertTrue(unchanged.clusters.reviews.confirmed.isEmpty())
        assertEquals(0, count(store, "people_confirmed"))
        assertEquals(4, count(store, "cluster_members"))
        assertNotNull(commit(store, "epoch-A", stored.generation, named))
    }

    @Test fun inconsistentCommandsAreRejectedBeforeAnyWrite() = withStore { store ->
        val stored = scannedLibrary(store)
        val named = nameBiggest("epoch-A", stored)
        for (badNames in listOf(emptyMap(), mapOf(ana to " Ana"), mapOf(ana to "A\u0000"))) {
            try {
                store.commitPeopleReview("epoch-A", stored.generation, named.clusters, badNames)
                fail("Inconsistent names were accepted: $badNames")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(stored.generation, store.peopleReview("epoch-A")?.generation)
        assertEquals(0, count(store, "people_names"))
    }

    @Test fun realSqliteV2UpgradeKeepsVectorsAndMembershipAndAddsEmptyReview() = withFile { file ->
        val uid = "000000000000000000000000000000cd"
        val a = media(1)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            // The v2 schema as shipped before #434 (tables and columns only).
            old.execSQL("""CREATE TABLE control(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                active_epoch TEXT, staging_epoch TEXT, detector_version TEXT,
                embedder_version TEXT, staged_count INTEGER NOT NULL DEFAULT 0)""")
            old.execSQL("""INSERT INTO control(singleton,active_epoch,detector_version,embedder_version)
                VALUES(1,'epoch-v2','detector-1',?)""", arrayOf(model))
            old.execSQL("CREATE TABLE epoch_history(epoch TEXT PRIMARY KEY NOT NULL)")
            old.execSQL("INSERT INTO epoch_history(epoch) VALUES('epoch-v2')")
            old.execSQL("""CREATE TABLE catalog(
                media_key TEXT PRIMARY KEY NOT NULL,
                revision TEXT NOT NULL, detector_version TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('queued','running','processed','failed')),
                attempts INTEGER NOT NULL DEFAULT 0,
                lease TEXT, lease_epoch TEXT, lease_deadline INTEGER)""")
            old.execSQL("""CREATE TABLE faces(
                media_key TEXT NOT NULL REFERENCES catalog(media_key) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL, face_uid TEXT NOT NULL UNIQUE CHECK(length(face_uid)=32),
                l REAL NOT NULL, t REAL NOT NULL, r REAL NOT NULL, b REAL NOT NULL,
                PRIMARY KEY(media_key,ordinal))""")
            old.execSQL("""CREATE TABLE face_reviews(
                media_key TEXT NOT NULL, ordinal INTEGER NOT NULL,
                decision TEXT NOT NULL CHECK(decision IN ('LATER','NOT_A_FACE')),
                PRIMARY KEY(media_key,ordinal),
                FOREIGN KEY(media_key,ordinal) REFERENCES faces(media_key,ordinal)
                    ON DELETE CASCADE)""")
            old.execSQL("CREATE TABLE stage(media_key TEXT PRIMARY KEY NOT NULL, revision TEXT NOT NULL)")
            old.execSQL("""CREATE TABLE embeddings(
                face_uid TEXT PRIMARY KEY NOT NULL REFERENCES faces(face_uid) ON DELETE CASCADE,
                source_revision TEXT NOT NULL, model_version TEXT NOT NULL, vector BLOB NOT NULL)""")
            old.execSQL("""CREATE TABLE cluster_members(
                face_uid TEXT PRIMARY KEY NOT NULL REFERENCES embeddings(face_uid) ON DELETE CASCADE,
                anchor_uid TEXT NOT NULL)""")
            old.execSQL("""CREATE TABLE cluster_generation(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                dirty INTEGER NOT NULL CHECK(dirty IN (0,1)) DEFAULT 0,
                pending INTEGER NOT NULL DEFAULT 0,
                published INTEGER NOT NULL DEFAULT 0)""")
            old.execSQL("INSERT INTO cluster_generation(singleton,dirty,published) VALUES(1,0,1)")
            old.execSQL("""INSERT INTO catalog(media_key,revision,detector_version,state)
                VALUES(?,?,'detector-1','processed')""", arrayOf(a.key, a.revision))
            old.execSQL("""INSERT INTO faces(media_key,ordinal,face_uid,l,t,r,b)
                VALUES(?,0,?,0.1,0.2,0.5,0.8)""", arrayOf(a.key, uid))
            old.execSQL("""INSERT INTO face_reviews(media_key,ordinal,decision)
                VALUES(?,0,'LATER')""", arrayOf(a.key))
            old.execSQL("""INSERT INTO embeddings(face_uid,source_revision,model_version,vector)
                VALUES(?,?,?,?)""", arrayOf<Any>(uid, a.revision, model,
                nl.atlas.gallery.featurepersistence.FaceEmbeddingVectorCodec.encode(vector(0))))
            old.execSQL("INSERT INTO cluster_members(face_uid,anchor_uid) VALUES(?,?)",
                arrayOf(uid, uid))
            old.version = 2
        }
        FaceIndexStore(contextFor(file)).use { store ->
            assertEquals(FaceIndexStore.SCHEMA_VERSION, store.readableDatabase.version)
            assertNull(store.peopleReview("epoch-v2")) // reopen is never a grant
            grant(store, "epoch-v3", listOf(a))
            assertEquals(1, count(store, "embeddings"))
            assertTrue(store.materializeClusters("epoch-v3"))
            val upgraded = requireNotNull(store.peopleReview("epoch-v3"))
            assertEquals(listOf(NativeFaceObservationId(uid)),
                upgraded.clusters.unknownClusters.single().members)
            assertTrue(upgraded.names.isEmpty())
            assertTrue(upgraded.clusters.reviews.confirmed.isEmpty())
            assertEquals("LATER", store.reviewObservationState("epoch-v3", uid))
            // The upgraded store accepts a durable review straight away.
            val named = requireNotNull(reviewPeopleCluster(uiState("epoch-v3", upgraded),
                PeopleReviewAction.NameCluster(upgraded.clusters.unknownClusters.single().id,
                    ana, "Ana")))
            assertNotNull(commit(store, "epoch-v3", upgraded.generation, named))
            assertEquals(mapOf(ana to "Ana"), store.peopleReview("epoch-v3")?.names)
        }
    }
}
