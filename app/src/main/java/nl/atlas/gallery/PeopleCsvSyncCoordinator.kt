package nl.atlas.gallery

import kotlinx.coroutines.sync.Mutex

/**
 * The native People database and the V4 link preferences form one logical
 * update state. Serialize all import/link/refresh mutations together.
 *
 * Every live refresh must re-read its saved link *inside* exclusive: taking a
 * snapshot before waiting could resurrect an obsolete linked CSV.
 * UI-read epochs are used only from the ViewModel's main dispatcher.
 */
internal class PeopleCsvSyncCoordinator {
    private val mutex = Mutex()
    private var latestSnapshotEpoch = 0L

    suspend fun <T> exclusive(block: suspend () -> T): T {
        mutex.lock()
        try { return block() }
        finally { mutex.unlock() }
    }

    fun nextPeopleSnapshotEpoch(): Long = ++latestSnapshotEpoch
    fun acceptsPeopleSnapshot(epoch: Long): Boolean = epoch == latestSnapshotEpoch
}
