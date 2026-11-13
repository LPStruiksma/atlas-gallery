package nl.atlas.gallery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Invented strings only, no databases, Android providers or private content. */
class PeopleCsvSyncCoordinatorTest {
    @Test fun olderInFlightRefreshCannotResurrectRemovedLiveLink() = runBlocking {
        val coordinator = PeopleCsvSyncCoordinator()
        var linkedUri: String? = "content://fictional/old.csv"
        var people = "previous linked assignments"
        val importStarted = CompletableDeferred<Unit>()
        val allowImport = CompletableDeferred<Unit>()

        val manual = launch(Dispatchers.Default) {
            coordinator.exclusive {
                importStarted.complete(Unit)
                allowImport.await()
                people = "manually selected new assignments"
                linkedUri = null
            }
        }
        importStarted.await()
        val staleRefresh = async(Dispatchers.Default) {
            coordinator.exclusive {
                val currentLink = linkedUri // re-read AFTER lock, not before
                if (currentLink != null) people = "stale linked assignments"
                currentLink
            }
        }
        allowImport.complete(Unit)
        manual.join()
        assertNull(staleRefresh.await())
        assertNull(linkedUri)
        assertEquals("manually selected new assignments", people)
    }

    @Test fun manualOrNewLinkWinsOlderRefreshThatObtainedLockFirst() = runBlocking {
        val coordinator = PeopleCsvSyncCoordinator()
        var people = "previous"
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val oldRefresh = launch(Dispatchers.Default) {
            coordinator.exclusive {
                entered.complete(Unit)
                unblock.await()
                people = "old linked file"
            }
        }
        entered.await()
        val chosenManual = async(Dispatchers.Default) {
            coordinator.exclusive { people = "user-chosen file" }
        }
        unblock.complete(Unit)
        oldRefresh.join()
        chosenManual.await()
        assertEquals("user-chosen file", people)
    }

    @Test fun olderPeopleUiReadCannotReplaceFreshRead() {
        val coordinator = PeopleCsvSyncCoordinator()
        val oldRead = coordinator.nextPeopleSnapshotEpoch()
        assertTrue(coordinator.acceptsPeopleSnapshot(oldRead))
        val newerRead = coordinator.nextPeopleSnapshotEpoch()
        assertFalse(coordinator.acceptsPeopleSnapshot(oldRead))
        assertTrue(coordinator.acceptsPeopleSnapshot(newerRead))
    }

    @Test fun failedOperationReleasesLock() = runBlocking {
        val coordinator = PeopleCsvSyncCoordinator()
        try {
            coordinator.exclusive<Unit> { error("synthetic input failure") }
            fail("failure expected")
        } catch (_: IllegalStateException) { }
        assertEquals("later approved import",
            coordinator.exclusive { "later approved import" })
    }
}
