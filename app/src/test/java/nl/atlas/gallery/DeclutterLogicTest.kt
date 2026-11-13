package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

class DeclutterLogicTest {
    private fun media(id: Long, folder: String, bytes: Long, video: Boolean = false) =
        MediaRecord(
            id, "content://media/external/${if (video) "video" else "images"}/media/$id",
            "fictional-$id.jpg", folder, bytes, id, video
        )

    @Test fun folderStrictlyScopedAndLargestFirstEvenWithVideos() {
        val a = media(1, "DCIM/Target/", 2_000_000)
        val b = media(2, "DCIM/Target/", 8_000_000, true)
        val c = media(3, "DCIM/Target/Sub/", 40_000_000)
        val d = media(4, "Pictures/Other/", 99_000_000)
        val deck = folderDeclutterDeck(listOf(a, b, c, d, b), "DCIM/Target/")
        assertEquals(listOf(b, a), deck)
        assertEquals(2, deck.distinctBy { it.uri }.size)
        with(arrayOf("", "DCIM/Target", " ")) {
            forEach { folder ->
                try {
                    folderDeclutterDeck(listOf(a), folder)
                    fail("Expected invalid folder to be rejected")
                } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun keepStageUndoAndUnmarkNeverModifyOriginals() {
        val deck = listOf(media(2, "DCIM/Synthetic/", 10_000_000, true),
            media(1, "DCIM/Synthetic/", 3_000_000))
        val originals = deck.toList()
        var session = DeclutterSession()
        assertEquals(0, session.position)
        session = session.decide(deck, true)
        assertEquals(1, session.position)
        assertEquals(10_000_000L, session.stagedBytes())
        session = session.decide(deck, false)
        assertEquals(2, session.position)
        assertEquals(1, session.staged().size)
        session = session.unstage(deck[0].uri)
        assertTrue(session.staged().isEmpty())
        session = session.undo()
        assertEquals(1, session.position)
        assertEquals(0L, session.stagedBytes())
        session = session.undo()
        assertEquals(0, session.position)
        assertEquals(0L, session.stagedBytes())
        assertSame(session, session.undo())
        assertEquals(originals, deck)
        assertThrows(IllegalArgumentException::class.java) {
            session.unstage(deck[1].uri)
        }
    }

    @Test fun stagedCountersMatchFullSnapshotAcrossMixedUndoAndUnstage() {
        val deck = (1L..400L).map { media(it, "DCIM/CounterFixture/", it * 1024L) }
        val random = kotlin.random.Random(20261017)
        var session = DeclutterSession()
        repeat(900) {
            val action = random.nextInt(100)
            session = when {
                action < 18 && session.position > 0 -> session.undo()
                action < 32 && session.stagedCount > 0 -> {
                    val picked = session.staged().random(random)
                    session.unstage(picked.uri)
                }
                session.position < deck.size -> session.decide(deck, random.nextBoolean())
                session.position > 0 -> session.undo()
                else -> session
            }
            val actual = session.staged()
            assertEquals(actual.size, session.stagedCount)
            assertEquals(actual.sumOf { it.sizeBytes.coerceAtLeast(0L) },
                session.stagedBytes())
            assertEquals(session.history.size, session.position)
        }
        val staged = session.staged()
        if (staged.isNotEmpty()) {
            val once = session.unstage(staged.first().uri)
            assertSame(once, once.unstage(staged.first().uri))
        }
    }

    @Test fun heavyMediaStopsOnAnyDragOrPendingTrashVerification() {
        assertFalse(shouldRenderDeclutterHeavy(false, false, false, false))
        assertTrue(shouldRenderDeclutterHeavy(true, false, false, false))
        assertFalse(shouldRenderDeclutterHeavy(true, true, false, false))
        assertFalse(shouldRenderDeclutterHeavy(true, false, true, false))
        assertFalse(shouldRenderDeclutterHeavy(true, false, false, true))
        // All 16 distinct state combinations, not repetitive pseudo-load.
        for (mask in 0..15) {
            val settled = mask and 1 == 1
            val dragging = mask and 2 == 2
            val reviewing = mask and 4 == 4
            val verifying = mask and 8 == 8
            val ready = shouldRenderDeclutterHeavy(settled, dragging, reviewing, verifying)
            assertEquals(settled && !dragging && !reviewing && !verifying, ready)
        }
    }

    @Test fun boundedPrefetchTargetsOnlyUnseenItemsAndNeverScansTheArchive() {
        assertEquals(listOf(1, 2), nextDeclutterPreviewIndices(0, 150_000))
        assertEquals(listOf(99_999, 100_000),
            nextDeclutterPreviewIndices(99_998, 150_000))
        assertEquals(listOf(149_999),
            nextDeclutterPreviewIndices(149_998, 150_000))
        assertTrue(nextDeclutterPreviewIndices(149_999, 150_000).isEmpty())
        assertTrue(nextDeclutterPreviewIndices(150_000, 150_000).isEmpty())
        assertTrue(nextDeclutterPreviewIndices(-1, 150_000).isEmpty())
        assertTrue(nextDeclutterPreviewIndices(0, 1).isEmpty())
        assertTrue(nextDeclutterPreviewIndices(0, 0).isEmpty())
        assertTrue(nextDeclutterPreviewIndices(Int.MAX_VALUE - 1, Int.MAX_VALUE).isEmpty())
        // At most two Coil poster/previews can be warmed per focus.
        for (n in 0..1_000) {
            val next = nextDeclutterPreviewIndices(n, 150_000)
            assertTrue(next.size <= 2)
            assertTrue(next.all { it > n && it < 150_000 })
        }
    }


    @Test fun prefetchSkipsUnavailablePreviewsButNeverSwallowsCancellation() {
        kotlinx.coroutines.runBlocking {
            val attempted = mutableListOf<Int>()
            prewarmDeclutterPreviewIndices(listOf(1, 2)) { index ->
                attempted.add(index)
                if (index == 1) throw IllegalStateException("synthetic stale URI")
            }
            assertEquals(listOf(1, 2), attempted)
            val cancelled = assertThrows(
                java.util.concurrent.CancellationException::class.java
            ) {
                kotlinx.coroutines.runBlocking {
                    prewarmDeclutterPreviewIndices(listOf(1, 2)) { _ ->
                        throw java.util.concurrent.CancellationException("card switched")
                    }
                }
            }
            assertEquals("card switched", cancelled.message)
        }
    }

    @Test fun synthetic150kLibraryOnlySelectsExactFolderAndNeverProcessesOthers() {
        val library = (1L..150_000L).map {
            media(it, if (it % 101L == 0L) "DCIM/Chosen/" else "DCIM/Elsewhere/",
                it * 1024L, it % 9L == 0L)
        }
        val deck = folderDeclutterDeck(library, "DCIM/Chosen/")
        assertEquals(150_000 / 101, deck.size)
        assertTrue(deck.zipWithNext().all { (x, y) -> x.sizeBytes >= y.sizeBytes })
        assertTrue(deck.all { it.relativePath == "DCIM/Chosen/" })
        assertEquals(library.size, 150_000)
        val snapshot = deck.toList()
        var s = DeclutterSession()
        repeat(120) { i -> s = s.decide(deck, i % 3 == 0) }
        assertEquals(40, s.staged().size)
        assertEquals(120, s.position)
        assertEquals(snapshot, deck)
    }
}
