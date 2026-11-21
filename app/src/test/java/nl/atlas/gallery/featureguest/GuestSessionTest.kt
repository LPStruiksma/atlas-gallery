package nl.atlas.gallery.featureguest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GUEST-1 (#589): JVM proof of the confinement rules. Every guest → owner
 * edge is enumerated here; only a confirmed exit for the current session and
 * nonce, written durably, reaches the owner. Storage faults are injected at
 * every write, commit and read boundary.
 */
class GuestSessionTest {
    private fun img(id: Long) = GuestCandidate(id, "content://media/external/images/media/$id", false)
    private fun vid(id: Long) = GuestCandidate(id, "content://media/external/video/media/$id", true)

    /** In-memory stores with switchable faults; a "crash" is a new store over the same disk. */
    private class Disk {
        var sentinel: Boolean? = null
        var record: String? = null
        var failCommit = false
        var commitThrows = false
        var failWrite = false
        var writeLiesSuccess = false
        var readThrows = false
        var sentinelReadThrows = false
        var crashAfterCommits = Int.MAX_VALUE
        var crashAfterWrites = Int.MAX_VALUE
        var commits = 0
        var writes = 0
    }

    // An Error, not an Exception: like a process kill it is never caught by the store.
    private class Crash : Error()

    private var tokens = 0
    private fun token() = "t%031d".format(++tokens)

    private fun store(disk: Disk) = GuestSessionStore(
        object : GuestSentinelBackend {
            override fun read(): Boolean? {
                if (disk.sentinelReadThrows) throw java.io.IOException()
                return disk.sentinel
            }

            override fun commit(active: Boolean): Boolean {
                if (disk.commitThrows) throw java.io.IOException()
                if (disk.failCommit) return false
                if (disk.commits >= disk.crashAfterCommits) throw Crash()
                disk.commits++
                disk.sentinel = active
                return true
            }
        },
        object : GuestRecordBackend {
            override fun read(): String? {
                if (disk.readThrows) throw java.io.IOException()
                return disk.record
            }

            override fun write(text: String): Boolean {
                if (disk.failWrite) return false
                if (disk.writeLiesSuccess) return true
                if (disk.writes >= disk.crashAfterWrites) throw Crash()
                disk.writes++
                disk.record = text
                return true
            }
        },
        ::token
    )

    private fun started(disk: Disk, vararg ids: Long): GuestRecord {
        val plan = GuestEntry.plan(ids.map(::img), deviceSecure = true) as GuestEntryPlan.Start
        val result = store(disk).start(plan.items, "") as GuestStartResult.Started
        return result.record
    }

    // ---- entry ----

    @Test fun zeroItemsDoNothing() {
        assertEquals(GuestEntryPlan.Refuse(null), GuestEntry.plan(emptyList(), true))
    }

    @Test fun mixedOrVideoSelectionRefusesWholeRequest() {
        assertEquals(GuestEntryPlan.Refuse(GuestCopy.PHOTOS_ONLY), GuestEntry.plan(listOf(img(1), vid(2)), true))
        assertEquals(GuestEntryPlan.Refuse(GuestCopy.PHOTOS_ONLY), GuestEntry.plan(listOf(vid(2)), true))
        // A stale item (not found in the owner's source) arrives as a video and refuses too.
        assertEquals(GuestEntryPlan.Refuse(GuestCopy.PHOTOS_ONLY),
            GuestEntry.plan(listOf(img(1), GuestCandidate(-1, "content://media/external/images/media/9", true)), true))
        assertEquals(GuestEntryPlan.Refuse(GuestCopy.PHOTOS_ONLY),
            GuestEntry.plan(listOf(GuestCandidate(3, "file:///sdcard/a.jpg", false)), true))
    }

    @Test fun noSecureLockNeverEnters() {
        assertEquals(GuestEntryPlan.Refuse(GuestCopy.NEEDS_LOCK), GuestEntry.plan(listOf(img(1)), false))
    }

    @Test fun tapOrderKeptAndDuplicatesDropped() {
        val plan = GuestEntry.plan(listOf(img(3), img(1), img(3), img(2)), true) as GuestEntryPlan.Start
        assertEquals(listOf(3L, 1L, 2L), plan.items.map { it.id })
    }

    @Test fun viewerOffersGuestOnlyOnStillPhotos() {
        assertTrue(GuestEntry.offeredInViewer(img(1)))
        assertFalse(GuestEntry.offeredInViewer(vid(1)))
    }

    // ---- boot guard ----

    @Test fun freshInstallIsOwner() {
        assertEquals(GuestBoot.Owner, store(Disk()).boot())
    }

    @Test fun liveSessionBootsToItsFrozenSetAtTheSavedPage() {
        val disk = Disk()
        val record = started(disk, 5, 6, 7)
        store(disk).update { it.copy(index = 2) }
        val boot = store(disk).boot() as GuestBoot.Guest
        assertEquals(record.items, boot.record.items)
        assertEquals(2, boot.record.index)
    }

    @Test fun onlyTwoStoreStatesReachTheOwner() {
        val active = GuestRecord(GuestState.ACTIVE, "a".repeat(32), listOf(GuestItem(1, img(1).uri)), 0)
        val exited = active.copy(state = GuestState.EXITED)
        val empty = active.copy(items = emptyList())
        val sentinels = listOf<StoreRead<Boolean>>(StoreRead.Absent, StoreRead.Unreadable,
            StoreRead.Value(true), StoreRead.Value(false))
        val records = listOf<StoreRead<GuestRecord>>(StoreRead.Absent, StoreRead.Unreadable,
            StoreRead.Value(active), StoreRead.Value(exited), StoreRead.Value(empty))
        val owner = mutableListOf<Pair<StoreRead<Boolean>, StoreRead<GuestRecord>>>()
        for (s in sentinels) for (r in records) {
            if (GuestGuard.boot(s, r) == GuestBoot.Owner) owner += s to r
            else assertTrue(GuestGuard.routesToGuest(GuestGuard.boot(s, r)))
        }
        assertEquals(listOf<Pair<StoreRead<Boolean>, StoreRead<GuestRecord>>>(
            StoreRead.Absent to StoreRead.Absent,
            StoreRead.Value(false) to StoreRead.Value(exited)), owner)
        // A lost sentinel beside a live record is still the guest's set.
        assertEquals(GuestBoot.Guest(active), GuestGuard.boot(StoreRead.Absent, StoreRead.Value(active)))
        // Sentinel false beside a live record is a conflict: shell.
        assertEquals(GuestBoot.RecoveryShell, GuestGuard.boot(StoreRead.Value(false), StoreRead.Value(active)))
    }

    @Test fun killAfterSentinelBeforeRecordBootsToShell() {
        val disk = Disk().apply { crashAfterWrites = 0 }
        try {
            store(disk).start(listOf(GuestItem(1, img(1).uri)), "")
            fail("expected crash")
        } catch (_: Crash) { }
        assertEquals(true, disk.sentinel)
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
    }

    @Test fun failedEntryWritesNeverShowTheSet() {
        val items = listOf(GuestItem(1, img(1).uri))
        assertEquals(GuestStartResult.Shell, store(Disk().apply { failCommit = true }).start(items, ""))
        assertEquals(GuestStartResult.Shell, store(Disk().apply { commitThrows = true }).start(items, ""))
        val failedWrite = Disk().apply { failWrite = true }
        assertEquals(GuestStartResult.Shell, store(failedWrite).start(items, ""))
        assertEquals(GuestBoot.RecoveryShell, store(failedWrite).boot())
        // AtomicFile.finishWrite can log instead of throw: the read-back catches it.
        val lying = Disk().apply { writeLiesSuccess = true }
        assertEquals(GuestStartResult.Shell, store(lying).start(items, ""))
        assertEquals(GuestBoot.RecoveryShell, store(lying).boot())
    }

    @Test fun killImmediatelyAfterCommitBeforeFirstFrameRestoresGuest() {
        val disk = Disk()
        val record = started(disk, 1, 2)
        // Nothing else ran: a new process reads the committed session.
        assertEquals(GuestBoot.Guest(record), store(disk).boot())
    }

    @Test fun truncatedCorruptOrUnknownRecordsAreShell() {
        val disk = Disk()
        started(disk, 1, 2, 3)
        val full = disk.record!!
        for (cut in 0 until full.length) {
            disk.record = full.substring(0, cut)
            assertEquals("cut at $cut", GuestBoot.RecoveryShell, store(disk).boot())
        }
        disk.record = full.replace("atlas-guest v1", "atlas-guest v2")
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
        disk.record = full.replace("count=3", "count=2")
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
        disk.record = full
        disk.readThrows = true
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
        disk.readThrows = false
        disk.sentinelReadThrows = true
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
    }

    @Test fun freshInstallIsDistinctFromCorruptAfterActive() {
        assertEquals(GuestBoot.Owner, store(Disk()).boot())
        val disk = Disk().apply { sentinel = true; record = "" }
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
        val noRecord = Disk().apply { sentinel = false }
        assertEquals(GuestBoot.RecoveryShell, store(noRecord).boot())
    }

    // ---- exit ----

    @Test fun confirmedExitForCurrentSessionAndNonceReachesOwner() {
        val disk = Disk()
        started(disk, 1, 2)
        val request = store(disk).prepareAuth()!!
        assertTrue(store(disk).exit(resultOk = true, launched = request))
        assertEquals(false, disk.sentinel)
        assertEquals(GuestBoot.Owner, store(disk).boot())
    }

    @Test fun cancelErrorAndProcessDeathKeepGuest() {
        val disk = Disk()
        val record = started(disk, 1)
        val request = store(disk).prepareAuth()!!
        assertFalse(store(disk).exit(resultOk = false, launched = request))
        // Process death: the in-memory request is gone, a redelivered RESULT_OK is not enough.
        assertFalse(store(disk).exit(resultOk = true, launched = null))
        assertEquals(record.items, (store(disk).boot() as GuestBoot.Guest).record.items)
    }

    @Test fun mismatchedOrReplayedNonceKeepsGuest() {
        val disk = Disk()
        started(disk, 1)
        val first = store(disk).prepareAuth()!!
        val second = store(disk).prepareAuth()!!
        assertFalse(store(disk).exit(true, first))
        assertFalse(store(disk).exit(true, second.copy(sessionId = "b".repeat(32))))
        assertFalse(store(disk).exit(true, second.copy(nonce = "")))
        assertTrue(store(disk).exit(true, second))
        // Replaying the accepted request after a new session starts does nothing.
        started(disk, 2)
        assertFalse(store(disk).exit(true, second))
        assertTrue(GuestGuard.routesToGuest(store(disk).boot()))
    }

    @Test fun failedExitWritesStayGuest() {
        val disk = Disk()
        started(disk, 1)
        val request = store(disk).prepareAuth()!!
        disk.failWrite = true
        assertFalse(store(disk).exit(true, request))
        disk.failWrite = false
        disk.failCommit = true
        assertFalse(store(disk).exit(true, request))
        // EXITED landed but the sentinel did not: still guest after a restart.
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
    }

    @Test fun killBetweenExitWritesBootsToShell() {
        val disk = Disk()
        started(disk, 1)
        val request = store(disk).prepareAuth()!!
        disk.crashAfterCommits = disk.commits
        try {
            store(disk).exit(true, request)
            fail("expected crash")
        } catch (_: Crash) { }
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
    }

    @Test fun recoveryShellExitIsBoundToANonceToo() {
        val disk = Disk().apply { sentinel = true; record = "garbage" }
        assertEquals(GuestBoot.RecoveryShell, store(disk).boot())
        assertFalse(store(disk).exit(true, GuestAuthRequest("c".repeat(32), "d".repeat(32))))
        val request = store(disk).prepareAuth()!!
        assertTrue(store(disk).exit(true, request))
        assertEquals(GuestBoot.Owner, store(disk).boot())
    }

    @Test fun noNonceIsIssuedWhenItCannotBePersisted() {
        val disk = Disk()
        started(disk, 1)
        disk.failWrite = true
        assertNull(store(disk).prepareAuth())
    }

    // ---- paging and frozen set ----

    @Test fun pagingStaysInsideTheFrozenSet() {
        assertEquals(0, GuestPaging.previous(0, 3))
        assertEquals(2, GuestPaging.next(2, 3))
        assertEquals(1, GuestPaging.next(0, 3))
        assertEquals(0, GuestPaging.next(0, 1))
        assertNull(GuestPaging.tap(3, 3))
        assertNull(GuestPaging.tap(-1, 3))
        assertEquals(2, GuestPaging.tap(2, 3))
        assertEquals(2, GuestPaging.clamp(99, 3))
    }

    @Test fun bookkeepingCannotChangeTheSet() {
        val disk = Disk()
        started(disk, 1, 2)
        try {
            store(disk).update { it.copy(items = it.items + GuestItem(3, img(3).uri)) }
            fail("set must be frozen")
        } catch (_: IllegalArgumentException) { }
        try {
            store(disk).update { it.copy(index = 5) }
            fail("index must stay inside the set")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun hugeSelectionRoundTrips() {
        val items = (1L..5000L).map { GuestItem(it, img(it).uri) }
        val record = GuestRecord(GuestState.ACTIVE, "e".repeat(32), items, 4999, "f".repeat(32), true,
            img(7).uri)
        assertEquals(record, GuestRecordCodec.decode(GuestRecordCodec.encode(record)))
    }

    @Test fun recordLinesCannotBeInjected() {
        val bad = GuestRecord(GuestState.ACTIVE, "e".repeat(32),
            listOf(GuestItem(1, "content://media/external/images/media/1\nitem=2 x")), 0)
        assertNull(GuestRecordCodec.decode(GuestRecordCodec.encode(bad)))
    }
}
