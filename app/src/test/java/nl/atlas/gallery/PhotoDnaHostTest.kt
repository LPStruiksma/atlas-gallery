package nl.atlas.gallery

import nl.atlas.gallery.featurecuration.CurationPairKind
import nl.atlas.gallery.featurecuration.CurationReviewPlan
import nl.atlas.gallery.featurecuration.CurationReviewRequest
import nl.atlas.gallery.featurecuration.CurationVisualHash
import nl.atlas.gallery.featurecuration.hashCurationBytes
import nl.atlas.gallery.featurepersistence.KeepReceipt
import nl.atlas.gallery.featurepersistence.KeepReceiptContract
import nl.atlas.gallery.featurepersistence.VerifiedKeepFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** E2b (#442): Photo DNA host logic on invented records and bytes only. */
class PhotoDnaHostTest {
    private val grant = GalleryMediaGrant(images = true, videos = false, selected = false)

    private fun record(
        id: Long, size: Long, name: String = "IMG_$id.jpg", path: String = "DCIM/Camera/",
        modified: Long = 1_700_000_000L
    ) = MediaRecord(
        id = id, uri = "content://media/external/images/media/$id", name = name,
        relativePath = path, sizeBytes = size, takenAtMillis = id * 1000L,
        isVideo = false, modifiedAtSeconds = modified
    )

    /** Fake provider: bytes per URI, readable through the real full-byte hasher. */
    private class Bytes(val content: MutableMap<String, ByteArray>) {
        var reads = 0
        fun hash(record: MediaRecord) = hashCurationBytes(record.sizeBytes,
            openAuthorized = { reads++; content[record.uri]?.let(::ByteArrayInputStream) },
            stillAuthorized = { true })
    }

    private fun bytes(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

    @Test fun tokensRequireAuthorizationAndChangeWithTheSourceList() {
        val source = listOf(record(1, 10))
        assertNull(photoDnaTokensOrNull(grant, 3, authorized = false, source = source))
        assertNull(photoDnaTokensOrNull(GalleryMediaGrant(false, false, false), 3, true, source))
        assertNull(photoDnaTokensOrNull(grant, -1, true, source))
        val a = photoDnaTokensOrNull(grant, 3, true, source)
        assertNotNull(a)
        assertEquals(a, photoDnaTokensOrNull(grant, 3, true, source))
        assertTrue(a != photoDnaTokensOrNull(grant, 4, true, source))
        assertTrue(a != photoDnaTokensOrNull(grant, 3, true, ArrayList(source)))
        assertTrue(a != photoDnaTokensOrNull(grant.copy(videos = true), 3, true, source))
    }

    @Test fun onlyEligibleSameSizeFilesAreCandidates() {
        val source = listOf(
            record(1, 100), record(2, 100), // a real pair
            record(3, 200), // unique size: never hashed
            record(4, 300, name = ".trashed-1700000000-IMG_4.jpg"), record(5, 300),
            record(6, 400, name = ".pending-1-IMG_6.jpg"), record(7, 400),
            record(8, 500, path = "Pictures/.hidden/"), record(9, 500),
            record(10, 600, modified = 0L), record(11, 600),
            record(12, 0), record(13, 0)
        )
        val candidates = photoDnaCandidates(source)
        assertEquals(listOf(1L, 2L), candidates.records.map { it.id })
        assertEquals(0, candidates.deferredFiles)
    }

    @Test fun budgetsKeepWholeClassesLargestSavingFirst() {
        val source = listOf(record(1, 1_000), record(2, 1_000), record(3, 1_000),
            record(4, 5_000), record(5, 5_000), record(6, 10))
            .plus(record(7, 10))
        val budget = PhotoDnaBudget(maxFiles = 4, maxTotalBytes = 20_000, maxFileBytes = 5_000)
        val candidates = photoDnaCandidates(source, budget)
        // 5k x2 saves 5k, 1k x3 saves 2k: the 3-file class no longer fits 4 files.
        assertEquals(listOf(4L, 5L, 6L, 7L), candidates.records.map { it.id })
        assertEquals(3, candidates.deferredFiles)
        val tooBig = PhotoDnaBudget(maxFileBytes = 999)
        assertEquals(listOf(6L, 7L), photoDnaCandidates(source, tooBig).records.map { it.id })
    }

    @Test fun identicalBytesGroupAndSameSizeDifferentBytesDoNot() {
        val source = listOf(record(1, 64), record(2, 64), record(3, 64), record(4, 64))
        val store = Bytes(mutableMapOf(
            source[0].uri to bytes(64, 7), source[1].uri to bytes(64, 7),
            source[2].uri to bytes(64, 8), source[3].uri to bytes(64, 9)))
        val tokens = photoDnaTokensOrNull(grant, 1, true, source)!!
        val result = planPhotoDna(source, tokens, store::hash)
        assertEquals(4, result.checkedFiles)
        assertEquals(1, result.plan.exactGroups.size)
        assertEquals(listOf(source[0].uri, source[1].uri),
            result.plan.exactGroups.single().members.map { it.uri })
        assertTrue(result.plan.visualPairs.isEmpty())
        assertEquals(result, currentPhotoDna(result, tokens))
        assertNull(currentPhotoDna(result, null))
        assertNull(currentPhotoDna(result, photoDnaTokensOrNull(grant, 2, true, source)))
    }

    @Test fun unreadableOrShortFilesAreUnknownNotMatches() {
        val source = listOf(record(1, 64), record(2, 64), record(3, 64))
        val store = Bytes(mutableMapOf(
            source[0].uri to bytes(64, 1), source[1].uri to bytes(63, 1)))
        val tokens = photoDnaTokensOrNull(grant, 1, true, source)!!
        val result = planPhotoDna(source, tokens, store::hash)
        assertTrue(result.plan.exactGroups.isEmpty())
        val asset = photoDnaAsset(source[0], store.hash(source[0])!!.copy(bytes = 65))
        assertNull(asset.sha256)
        assertNull(asset.sha256AtRevision)
    }

    @Test fun favouriteCountsOnlyWhenTheProviderReportedIt() {
        assertTrue(!photoDnaAsset(record(1, 5).copy(isFavorite = true), null).favorite)
        assertTrue(photoDnaAsset(record(1, 5).copy(isFavorite = true, favoriteKnown = true),
            null).favorite)
    }

    private fun pairFixture(): Triple<List<MediaRecord>, Bytes, PhotoDnaResult> {
        val source = listOf(record(1, 32), record(2, 32), record(3, 32))
        val store = Bytes(mutableMapOf(source[0].uri to bytes(32, 3),
            source[1].uri to bytes(32, 3), source[2].uri to bytes(32, 3)))
        val tokens = photoDnaTokensOrNull(grant, 1, true, source)!!
        return Triple(source, store, planPhotoDna(source, tokens, store::hash))
    }

    private fun request(result: PhotoDnaResult, keeper: String, vararg selected: String) =
        CurationReviewRequest(result.plan.scopeEpoch, result.plan.exactGroups.single().key,
            keeper, selected.toSet())

    @Test fun recheckReadsEveryCopyAgainAndReturnsLiveRows() {
        val (source, store, result) = pairFixture()
        store.reads = 0
        val check = recheckPhotoDnaTrash(result, request(result, source[0].uri, source[2].uri),
            result.tokens, source, store::hash)
        assertTrue(check is PhotoDnaTrashCheck.Ready)
        check as PhotoDnaTrashCheck.Ready
        assertEquals(source[0], check.keeper)
        assertEquals(listOf(source[2]), check.selected)
        assertEquals(3, store.reads) // keeper and the unselected copy too
    }

    @Test fun recheckRefusesWhenBytesChangedEvenOnAnUnselectedCopy() {
        val (source, store, result) = pairFixture()
        store.content[source[1].uri] = bytes(32, 4)
        val check = recheckPhotoDnaTrash(result, request(result, source[0].uri, source[2].uri),
            result.tokens, source, store::hash)
        assertTrue(check is PhotoDnaTrashCheck.Refused)
    }

    @Test fun recheckRefusesWhenTheCatalogueOrGrantMoved() {
        val (source, store, result) = pairFixture()
        val req = request(result, source[0].uri, source[2].uri)
        assertTrue(recheckPhotoDnaTrash(result, req, null, source, store::hash)
            is PhotoDnaTrashCheck.Refused)
        val newer = photoDnaTokensOrNull(grant, 2, true, source)
        assertTrue(recheckPhotoDnaTrash(result, req, newer, source, store::hash)
            is PhotoDnaTrashCheck.Refused)
        // Same tokens but a member vanished or changed revision.
        assertTrue(recheckPhotoDnaTrash(result, req, result.tokens, source.dropLast(1),
            store::hash) is PhotoDnaTrashCheck.Refused)
        val edited = source.map { if (it.id == 2L) it.copy(modifiedAtSeconds = 1_800_000_000L) else it }
        assertTrue(recheckPhotoDnaTrash(result, req, result.tokens, edited, store::hash)
            is PhotoDnaTrashCheck.Refused)
    }

    @Test fun recheckRefusesKeeperOrProtectedSelection() {
        val (source, store, result) = pairFixture()
        assertTrue(recheckPhotoDnaTrash(result, request(result, source[0].uri, source[0].uri),
            result.tokens, source, store::hash) is PhotoDnaTrashCheck.Refused)
        assertTrue(recheckPhotoDnaTrash(result, request(result, source[0].uri),
            result.tokens, source, store::hash) is PhotoDnaTrashCheck.Refused)
        val stale = CurationReviewRequest("other", result.plan.exactGroups.single().key,
            source[0].uri, setOf(source[1].uri))
        assertTrue(recheckPhotoDnaTrash(result, stale, result.tokens, source, store::hash)
            is PhotoDnaTrashCheck.Refused)
    }

    /** In-memory stand-in for CurationKeepStore, with the store's own contract. */
    private class Receipts {
        val saved = HashMap<String, KeepReceipt>()
        var lookups = 0
        fun save(family: VerifiedKeepFamily, keeper: String) {
            val receipt = KeepReceiptContract.validate(family, keeper)
            saved[receipt.familyKey] = receipt
        }
        fun resolve(families: List<VerifiedKeepFamily>): List<KeepReceipt> {
            lookups++
            require(families.size <= KeepReceiptContract.MAX_BATCH)
            return families.mapNotNull { family ->
                saved[family.familyKey]?.takeIf { KeepReceiptContract.remainsValid(it, family) }
            }
        }
    }

    private fun keepFixture(): Triple<List<MediaRecord>, Bytes, PhotoDnaTokens> {
        val source = listOf(record(1, 48), record(2, 48), record(3, 48), record(4, 48))
        val store = Bytes(mutableMapOf(source[0].uri to bytes(48, 5),
            source[1].uri to bytes(48, 5), source[2].uri to bytes(48, 5),
            source[3].uri to bytes(48, 6)))
        return Triple(source, store, photoDnaTokensOrNull(grant, 1, true, source)!!)
    }

    @Test fun keepTapSavesAReceiptAfterAFreshReadAndReopeningShowsItDecided() {
        val (source, store, tokens) = keepFixture()
        val receipts = Receipts()
        val first = planPhotoDna(source, tokens, store::hash, keepReceipts = receipts::resolve)
        val group = first.plan.exactGroups.single()
        assertEquals(source[0].uri, group.suggestedKeeperUri)
        assertTrue(group.members.none { it.userKept })

        store.reads = 0
        val outcome = savePhotoDnaKeeper(first, group.key, source[1].uri, store::hash,
            unchanged = { true }, save = receipts::save)
        assertEquals(PhotoDnaKeepOutcome.Saved(group.key, source[1].uri), outcome)
        assertEquals(3, store.reads) // every member read again, keeper included
        assertEquals(1, receipts.saved.size)

        // Same session: the held plan marks the chosen keeper, and only it.
        assertTrue(first.markKept(group.key, source[1].uri))
        val marked = first.plan.exactGroups.single()
        assertEquals(source[1].uri, marked.suggestedKeeperUri)
        assertEquals("Keeping the copy you chose", marked.keeperReason)
        assertEquals(listOf(source[1].uri), marked.members.filter { it.userKept }.map { it.uri })

        // Reopened later (a fresh plan): the receipt still matches, so it is decided.
        val again = planPhotoDna(source, tokens, store::hash, keepReceipts = receipts::resolve)
        val decided = again.plan.exactGroups.single()
        assertEquals(source[1].uri, decided.suggestedKeeperUri)
        assertEquals("Keeping the copy you chose", decided.keeperReason)
        assertTrue(decided.members.single { it.userKept }.protected)
    }

    @Test fun aChangedFamilyIgnoresTheOldReceipt() {
        val (source, store, tokens) = keepFixture()
        val receipts = Receipts()
        val first = planPhotoDna(source, tokens, store::hash, keepReceipts = receipts::resolve)
        val key = first.plan.exactGroups.single().key
        savePhotoDnaKeeper(first, key, source[1].uri, store::hash, { true }, receipts::save)

        // One member's bytes change (new revision): the family is now two copies.
        store.content[source[2].uri] = bytes(48, 9)
        val edited = source.map { if (it.id == 3L) it.copy(modifiedAtSeconds = 1_800_000_000L) else it }
        val later = planPhotoDna(edited, photoDnaTokensOrNull(grant, 2, true, edited)!!,
            store::hash, keepReceipts = receipts::resolve)
        val group = later.plan.exactGroups.single()
        assertEquals(listOf(source[0].uri, source[1].uri), group.members.map { it.uri })
        assertTrue(group.members.none { it.userKept })
        assertEquals(source[0].uri, group.suggestedKeeperUri)

        // A revision change alone (same bytes) also ignores it.
        store.content[source[2].uri] = bytes(48, 5)
        val touched = source.map { if (it.id == 1L) it.copy(generationModified = 77L) else it }
        val touchedPlan = planPhotoDna(touched, photoDnaTokensOrNull(grant, 3, true, touched)!!,
            store::hash, keepReceipts = receipts::resolve)
        assertTrue(touchedPlan.plan.exactGroups.single().members.none { it.userKept })
    }

    @Test fun keepIsRefusedWhenBytesMetadataOrStoreDisagree() {
        val (source, store, tokens) = keepFixture()
        val receipts = Receipts()
        val result = planPhotoDna(source, tokens, store::hash)
        val key = result.plan.exactGroups.single().key
        assertTrue(savePhotoDnaKeeper(result, key, source[3].uri, store::hash, { true },
            receipts::save) is PhotoDnaKeepOutcome.Refused) // not in the family
        assertTrue(savePhotoDnaKeeper(result, "0".repeat(64), source[0].uri, store::hash,
            { true }, receipts::save) is PhotoDnaKeepOutcome.Refused)
        assertTrue(savePhotoDnaKeeper(result, key, source[0].uri, store::hash, { false },
            receipts::save) is PhotoDnaKeepOutcome.Refused) // MediaStore rows changed
        assertTrue(savePhotoDnaKeeper(result, key, source[0].uri, store::hash, { true },
            { _, _ -> throw IllegalArgumentException("Family source is unverified") })
            is PhotoDnaKeepOutcome.Refused)
        store.content[source[2].uri] = bytes(48, 1) // an unselected copy changed
        assertTrue(savePhotoDnaKeeper(result, key, source[0].uri, store::hash, { true },
            receipts::save) is PhotoDnaKeepOutcome.Refused)
        store.content.remove(source[2].uri) // unreadable now
        assertTrue(savePhotoDnaKeeper(result, key, source[0].uri, store::hash, { true },
            receipts::save) is PhotoDnaKeepOutcome.Refused)
        assertTrue(receipts.saved.isEmpty())
    }

    @Test fun aKeepReceiptNeverStagesOrAuthorizesTrash() {
        val (source, store, tokens) = keepFixture()
        val receipts = Receipts()
        val first = planPhotoDna(source, tokens, store::hash, keepReceipts = receipts::resolve)
        val key = first.plan.exactGroups.single().key
        savePhotoDnaKeeper(first, key, source[1].uri, store::hash, { true }, receipts::save)
        val result = planPhotoDna(source, tokens, store::hash, keepReceipts = receipts::resolve)
        val kept = result.plan.exactGroups.single().members.single { it.userKept }
        assertTrue(kept.protected)
        // The kept copy can never be selected, whatever keeper the request names.
        assertTrue(recheckPhotoDnaTrash(result, CurationReviewRequest(result.plan.scopeEpoch, key,
            source[0].uri, setOf(source[1].uri)), result.tokens, source, store::hash)
            is PhotoDnaTrashCheck.Refused)
        // An explicit selection of another copy still needs the full fresh recheck.
        val ok = recheckPhotoDnaTrash(result, CurationReviewRequest(result.plan.scopeEpoch, key,
            source[1].uri, setOf(source[2].uri)), result.tokens, source, store::hash)
        assertTrue(ok is PhotoDnaTrashCheck.Ready)
        store.content[source[0].uri] = bytes(48, 2)
        assertTrue(recheckPhotoDnaTrash(result, CurationReviewRequest(result.plan.scopeEpoch, key,
            source[1].uri, setOf(source[2].uri)), result.tokens, source, store::hash)
            is PhotoDnaTrashCheck.Refused)
    }

    @Test fun receiptLookupIsBatchedAndAFailingStoreMeansNoReceipts() {
        val source = (1L..600L).map { record(it, 1000L + (it + 1) / 2) } // 300 pairs
        val store = Bytes(source.associate { it.uri to bytes(it.sizeBytes.toInt(), 1) }
            .toMutableMap())
        val tokens = photoDnaTokensOrNull(grant, 1, true, source)!!
        val receipts = Receipts()
        val result = planPhotoDna(source, tokens, store::hash, keepReceipts = receipts::resolve)
        assertEquals(300, result.plan.exactGroups.size)
        assertEquals(2, receipts.lookups) // 256 + 44
        val failing = planPhotoDna(source, tokens, store::hash,
            keepReceipts = { throw android.database.sqlite.SQLiteException("fictional") })
        assertEquals(300, failing.plan.exactGroups.size)
        assertTrue(failing.plan.exactGroups.all { group -> group.members.none { it.userKept } })
    }

    @Test fun markKeptIgnoresAGroupOrKeeperThatIsNotInThePlan() {
        val (source, store, tokens) = keepFixture()
        val result = planPhotoDna(source, tokens, store::hash)
        val before = result.plan
        assertTrue(!result.markKept("f".repeat(64), source[0].uri))
        assertTrue(!result.markKept(before.exactGroups.single().key, source[3].uri))
        assertTrue(before === result.plan)
    }

    // ---- E2e (#450): visual pairs from an in-memory perceptual hash ----

    private class Visual(val hashes: MutableMap<String, Long?>) {
        val decoded = ArrayList<String>()
        fun decode(record: MediaRecord): CurationVisualHash? {
            decoded += record.uri
            return hashes[record.uri]?.let { CurationVisualHash(it, informative = true) }
        }
    }

    private fun visualFixture(): Triple<List<MediaRecord>, Visual, PhotoDnaTokens> {
        // 1+2: byte-identical (an exact group); 3: a near visual match of 1, other size;
        // 4: unrelated; 5: a video, never hashed.
        val source = listOf(record(1, 64), record(2, 64), record(3, 90), record(4, 120),
            record(5, 200).copy(isVideo = true))
        val near = 0x0f0f_1234_5678_9abcL
        val visual = Visual(mutableMapOf(source[0].uri to near, source[1].uri to near,
            source[2].uri to (near xor 0b101L), source[3].uri to near.inv(),
            source[4].uri to near))
        return Triple(source, visual, photoDnaTokensOrNull(grant, 1, true, source)!!)
    }

    private fun bytePlan(source: List<MediaRecord>, tokens: PhotoDnaTokens): PhotoDnaResult {
        val store = Bytes(mutableMapOf(source[0].uri to bytes(64, 5), source[1].uri to bytes(64, 5)))
        return planPhotoDna(source, tokens, store::hash)
    }

    @Test fun nearImagesBecomeStampedVisualPairsAndExactCopiesAreNotRepeated() {
        val (source, visual, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        assertEquals(1, result.plan.exactGroups.size)
        val cache = PhotoDnaVisualCache()
        val pass = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode,
            stillAuthorized = { true })
        assertTrue(source[4].uri !in visual.decoded)
        assertEquals(4, pass.hashedFiles)
        // 1-3 and 2-3 are near; 1-2 is the exact group, so not a pair; 4 is far.
        assertEquals(setOf(source[0].uri to source[2].uri, source[1].uri to source[2].uri),
            pass.evidence.map { listOf(it.leftUri, it.rightUri).sorted().let { p -> p[0] to p[1] } }.toSet())
        pass.evidence.forEach {
            assertEquals(CurationPairKind.GEOMETRIC_CANDIDATE, it.kind)
            assertEquals(tokens.scopeEpoch, it.observedScopeEpoch)
            assertEquals(photoDnaRevision(source.single { r -> r.uri == it.leftUri }), it.leftRevision)
            assertEquals(photoDnaRevision(source.single { r -> r.uri == it.rightUri }), it.rightRevision)
        }
        result.addVisualPairs(pass)
        assertTrue(result.visualPlanned)
        assertEquals(2, result.plan.visualPairs.size)
        assertEquals(1, result.plan.exactGroups.size)
        assertNotNull(result.records[source[2].uri])
        // A saved Keep keeps the pairs.
        assertTrue(result.markKept(result.plan.exactGroups.single().key, source[1].uri))
        assertEquals(2, result.plan.visualPairs.size)
        assertTrue(result.plan.exactGroups.single().members.single { it.uri == source[1].uri }.userKept)
    }

    @Test fun aRevisionChangeDropsTheCachedHashAndTheOldEvidence() {
        val (source, visual, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        val cache = PhotoDnaVisualCache()
        val first = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, { true })
        assertEquals(4, visual.decoded.size)
        // Same revisions: served from memory, nothing decoded again.
        planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, { true })
        assertEquals(4, visual.decoded.size)

        // Photo 3 is edited (new modification time) and no longer looks alike.
        val edited = source[2].copy(modifiedAtSeconds = source[2].modifiedAtSeconds + 60)
        val live = source.toMutableList().also { it[2] = edited }
        visual.hashes[edited.uri] = 0x0f0f_1234_5678_9abcL.inv() xor 0xffL
        val liveTokens = photoDnaTokensOrNull(grant, 2, true, live)!!
        val liveResult = bytePlan(live, liveTokens)
        val second = planPhotoDnaVisual(live, liveTokens, liveResult.plan, cache, visual::decode, { true })
        assertEquals(edited.uri, visual.decoded.last())
        assertTrue(second.evidence.none { edited.uri == it.leftUri || edited.uri == it.rightUri })
        // Old evidence is refused against the new revision, by the plan contract.
        val liveAssets = live.map { photoDnaAsset(it, null) }
        try {
            CurationReviewPlan.build(tokens.scopeEpoch, liveAssets, first.evidence)
            throw AssertionError("stale evidence accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Stale"))
        }
        // And a result from the old tokens is never current.
        assertNull(currentPhotoDna(result, liveTokens))
    }

    @Test fun aGrantChangeDuringOrBeforeAPassYieldsNoPairs() {
        val (source, visual, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        val cache = PhotoDnaVisualCache()
        cache.bindGrant(grant)
        val revoked = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode,
            stillAuthorized = { false })
        assertTrue(revoked.evidence.isEmpty())
        assertTrue(cache.size > 0)
        cache.bindGrant(grant.copy(selected = true))
        assertEquals(0, cache.size)
        cache.put(source[0], CurationVisualHash(1L, true))
        cache.clear()
        assertEquals(0, cache.size)
        // Tokens from another publication: nothing is computed at all.
        val other = photoDnaTokensOrNull(grant, 9, true, source)!!
        assertTrue(planPhotoDnaVisual(source, other, result.plan, cache, visual::decode, { true })
            .evidence.isEmpty())
    }

    @Test fun decodesAreBoundedPerPassNewestFirstAndUnreadableIsNotCached() {
        val (source, visual, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        val cache = PhotoDnaVisualCache()
        val budget = PhotoDnaVisualBudget(maxDecodes = 2)
        val first = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, { true }, budget)
        assertEquals(2, first.decodedFiles)
        assertEquals(2, first.deferredFiles)
        // takenAtMillis = id * 1000: newest are 4 then 3.
        assertEquals(listOf(source[3].uri, source[2].uri), visual.decoded)
        val second = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, { true }, budget)
        assertEquals(0, second.deferredFiles)
        assertEquals(2, second.evidence.size)

        visual.hashes[source[0].uri] = null
        val fresh = PhotoDnaVisualCache()
        planPhotoDnaVisual(source, tokens, result.plan, fresh, visual::decode, { true })
        assertEquals(3, fresh.size)
        assertEquals(1, fresh.failedSize)
    }

    // ---- E2g (#456): failed decodes are remembered, deferred images keep coming ----

    @Test fun aFailedDecodeIsSkippedUntilItsRevisionChangesAndClearsOnAGrantChange() {
        val (source, visual, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        val cache = PhotoDnaVisualCache()
        cache.bindGrant(grant)
        visual.hashes[source[3].uri] = null
        val first = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, { true })
        assertEquals(4, first.decodedFiles)
        assertTrue(cache.failed(source[3]))
        // Later passes neither decode it again nor count it as waiting.
        visual.decoded.clear()
        val second = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, { true },
            PhotoDnaVisualBudget(maxDecodes = 1))
        assertEquals(emptyList<String>(), visual.decoded)
        assertEquals(0, second.decodedFiles)
        assertEquals(0, second.deferredFiles)
        assertEquals(2, second.evidence.size)

        // An edit (new revision) gets one new try, and now it decodes.
        val edited = source[3].copy(modifiedAtSeconds = source[3].modifiedAtSeconds + 60)
        val live = source.toMutableList().also { it[3] = edited }
        visual.hashes[edited.uri] = 0x0f0f_1234_5678_9abcL.inv()
        assertTrue(!cache.failed(edited))
        planPhotoDnaVisual(live, tokens, result.plan, cache, visual::decode, { true })
        assertEquals(listOf(edited.uri), visual.decoded)
        assertEquals(0, cache.failedSize)
        assertEquals(4, cache.size)

        // A failure is forgotten when the grant changes, and on revoke.
        visual.hashes[source[2].uri] = null
        val other = source[2].copy(modifiedAtSeconds = source[2].modifiedAtSeconds + 1)
        cache.putFailure(other)
        assertTrue(cache.failed(other))
        cache.bindGrant(grant)
        assertTrue(cache.failed(other))
        cache.bindGrant(grant.copy(selected = true))
        assertEquals(0, cache.failedSize)
        assertTrue(!cache.failed(other))
        cache.putFailure(other)
        cache.clear()
        assertEquals(0, cache.failedSize)
    }

    @Test fun aRevokeMidPassIsNotRememberedAsAFailedDecode() {
        val (source, visual, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        val cache = PhotoDnaVisualCache()
        source.forEach { visual.hashes[it.uri] = null }
        planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, stillAuthorized = { false })
        assertEquals(0, cache.failedSize)
        planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, stillAuthorized = { true })
        assertEquals(4, cache.failedSize)
    }

    @Test fun deferredImagesAreHashedOnTheNextOpeningWithoutANewPublication() {
        val (source, visual, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        val cache = PhotoDnaVisualCache()
        val budget = PhotoDnaVisualBudget(maxDecodes = 1)
        // One image never decodes: it must not keep the chain alive.
        visual.hashes[source[3].uri] = null
        assertTrue(result.needsVisualPass)

        // Opening 1, capped at two passes: 1 decode each, newest first (4, 3).
        var previous: Int? = null
        var passes = 0
        val seen = ArrayList<Int>()
        while (true) {
            val pass = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, { true }, budget)
            passes++
            result.addVisualPairs(pass)
            seen += pass.deferredFiles
            if (!photoDnaVisualChainContinues(previous, pass, passes, maxPassesPerOpening = 2)) break
            previous = pass.deferredFiles
        }
        assertEquals(listOf(3, 2), seen)
        assertEquals(listOf(source[3].uri, source[2].uri), visual.decoded)
        assertTrue(result.visualPlanned)
        assertTrue(result.needsVisualPass)

        // Opening 2: same tokens (no new catalogue publication), the next batch is hashed.
        assertTrue(currentPhotoDna(result, tokens) === result)
        previous = null
        passes = 0
        seen.clear()
        while (result.needsVisualPass) {
            val pass = planPhotoDnaVisual(source, tokens, result.plan, cache, visual::decode, { true }, budget)
            passes++
            result.addVisualPairs(pass)
            seen += pass.deferredFiles
            if (!photoDnaVisualChainContinues(previous, pass, passes, maxPassesPerOpening = 2)) break
            previous = pass.deferredFiles
        }
        assertEquals(listOf(1, 0), seen)
        assertEquals(listOf(source[3].uri, source[2].uri, source[1].uri, source[0].uri), visual.decoded)
        // Stops at 0 deferred; the next opening has nothing to do and decodes nothing.
        assertTrue(!result.needsVisualPass)
        assertEquals(0, result.visualDeferred)
        assertEquals(2, result.plan.visualPairs.size)
    }

    @Test fun theChainStopsWithoutProgressAtTheCapOrAtZeroDeferred() {
        fun pass(deferred: Int) = PhotoDnaVisualPass(emptyList(), emptyList(), emptyMap(), 0, 1,
            deferred, 0L, false)
        assertTrue(photoDnaVisualChainContinues(null, pass(5), 1))
        assertTrue(photoDnaVisualChainContinues(6, pass(5), 1))
        assertTrue(!photoDnaVisualChainContinues(5, pass(5), 2))
        assertTrue(!photoDnaVisualChainContinues(null, pass(0), 1))
        assertTrue(!photoDnaVisualChainContinues(9, pass(5), PHOTO_DNA_MAX_PASSES_PER_OPENING))
        // A full cache cannot store new hashes, so a pass makes no progress and the chain ends.
        val full = PhotoDnaVisualCache(maxEntries = 1)
        val (source, visual, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        val budget = PhotoDnaVisualBudget(maxDecodes = 1)
        val first = planPhotoDnaVisual(source, tokens, result.plan, full, visual::decode, { true }, budget)
        val second = planPhotoDnaVisual(source, tokens, result.plan, full, visual::decode, { true }, budget)
        val third = planPhotoDnaVisual(source, tokens, result.plan, full, visual::decode, { true }, budget)
        assertTrue(photoDnaVisualChainContinues(null, first, 1))
        assertTrue(photoDnaVisualChainContinues(first.deferredFiles, second, 2))
        assertEquals(second.deferredFiles, third.deferredFiles)
        assertTrue(!photoDnaVisualChainContinues(second.deferredFiles, third, 3))
    }

    @Test fun flatImagesNeverPair() {
        val (source, _, tokens) = visualFixture()
        val result = bytePlan(source, tokens)
        val pass = planPhotoDnaVisual(source, tokens, result.plan, PhotoDnaVisualCache(),
            decode = { CurationVisualHash(0L, informative = false) }, stillAuthorized = { true })
        assertTrue(pass.evidence.isEmpty())
        assertEquals(0, pass.hashedFiles)
    }
}
