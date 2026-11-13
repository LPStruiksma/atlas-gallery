package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * A pure JVM proof of the production immutable review engine, not an
 * Android 120Hz, heap, thermal, or real-media performance benchmark.
 */
class DeclutterHistoryScalingTest {
    private fun image(id: Int, uri: String = "content://media/external/images/media/$id") =
        MediaRecord(id.toLong(), uri, "fictional-$id",
            "DCIM/ATLAS-SYNTHETIC/", id.toLong() * 1024, id.toLong(), false)

    private fun verify(session: DeclutterSession) {
        val expected = session.history.filter {
            it.stageForTrash && it.mediaUri !in session.unmarked
        }.map { it.media }
        assertEquals(session.history.size, session.position)
        assertEquals(expected, session.staged())
        assertEquals(expected.size, session.stagedCount)
        assertEquals(expected.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            session.stagedBytes())
    }

    @Test fun seededMixedHistoryUndoUnstageAndBranchingPreservesExactSnapshot() {
        val deck = (1..30_000).map(::image)
        val random = Random(20261018)
        var session = DeclutterSession()
        repeat(15_000) { step ->
            val action = random.nextInt(100)
            session = when {
                action < 18 && session.position > 0 -> session.undo()
                action < 31 && session.stagedCount > 0 ->
                    session.unstage(session.staged().random(random).uri)
                session.position < deck.size ->
                    session.decide(deck, random.nextBoolean())
                else -> session
            }
            if (step % 37 == 0) verify(session)
        }
        verify(session)
        val saved = session
        val before = saved.history
        val after = saved.decide(deck, true)
        assertEquals(before, saved.history) // new state never mutates old history
        assertEquals(before.size + 1, after.position)
        assertEquals(saved.staged(), after.undo().staged())
        verify(after.undo())
    }

    @Test fun longMonotoneMediaStoreUrisRemainCorrectWithRepeatUndo() {
        // Zero-padding makes String/AVL insertion keys strictly ascending,
        // the adversarial order that would degenerate an unbalanced BST.
        val deck = (1..30_000).map { id -> image(id,
            "content://media/external/images/media/${id.toString().padStart(6, '0')}") }
        var session = DeclutterSession()
        repeat(deck.size) { index ->
            session = session.decide(deck, index % 13 == 0)
        }
        verify(session)
        val expected = session.staged()
        repeat(10_000) { session = session.undo() }
        verify(session)
        repeat(10_000) { index ->
            session = session.decide(deck, index % 2 == 0)
        }
        verify(session)
        assertNotEquals(expected, session.staged())
        assertEquals(30_000, session.position)
        repeat(30_000) { session = session.undo() }
        assertSame(session, session.undo())
        assertEquals(0, session.position)
        assertTrue(session.staged().isEmpty())
        assertEquals(0L, session.stagedBytes())
    }

    @Test fun rawDeckMutationOrSwapFailsClosedAndFullRewindAllowsFreshSource() {
        val raw = mutableListOf(image(1), image(2), image(3))
        val first = DeclutterSession().decide(raw, true)
        val original = first.history
        val swapped = raw.toList()
        assertThrows(IllegalArgumentException::class.java) {
            first.decide(swapped, false)
        }
        // The caller must not change metadata behind the indexed snapshot.
        raw[1] = image(200)
        assertThrows(IllegalArgumentException::class.java) {
            first.decide(raw, false)
        }
        assertEquals(original, first.history)
        verify(first)
        val rewound = first.undo()
        assertEquals(0, rewound.position)
        val another = listOf(image(11), image(12))
        val fresh = rewound.decide(another, false)
        assertEquals(1, fresh.position)
        verify(fresh)
    }

    @Test fun productionDeckDeduplicationKeepsOriginalsAndStageOrder() {
        val folder = "DCIM/ATLAS-SYNTHETIC/"
        val a = image(3)
        val b = image(1)
        val duplicate = a.copy(name = "stale duplicate row")
        val outside = image(99).copy(relativePath = "DCIM/OTHER/")
        val originals = listOf(a, b, duplicate, outside)
        val deck = folderDeclutterDeck(originals, folder)
        assertEquals(2, deck.size)
        assertEquals(2, deck.map { it.uri }.distinct().size)
        assertTrue(deck.all { it.relativePath == folder })
        var session = DeclutterSession()
        repeat(deck.size) { session = session.decide(deck, true) }
        assertEquals(deck, session.staged())
        verify(session)
        assertEquals(originals, listOf(a, b, duplicate, outside))
    }

    @Test fun duplicateUriIsRejectedWithoutMutatingOldSessionEvenAfterUndo() {
        val first = image(1)
        val second = image(2)
        val duplicate = image(3, first.uri)
        val deck = listOf(first, second, duplicate)
        val baseline = DeclutterSession().decide(deck, true).decide(deck, false)
        verify(baseline)
        assertThrows(IllegalArgumentException::class.java) {
            baseline.decide(deck, true)
        }
        verify(baseline)
        val rewound = baseline.undo().undo()
        assertEquals(0, rewound.position)
        verify(rewound)
        val alternate = rewound.decide(deck, false)
        verify(alternate)
        assertEquals(1, alternate.position)
        assertEquals(listOf(first), baseline.staged())
    }

    @Test fun complete150kProductionDeckRemainsReversibleWithoutDeleteActions() {
        val input = (1..150_000).map(::image)
        val deck = folderDeclutterDeck(input, "DCIM/ATLAS-SYNTHETIC/")
        assertEquals(150_000, deck.size)
        var session = DeclutterSession()
        repeat(deck.size) { step ->
            session = session.decide(deck, step % 5 == 0)
        }
        assertEquals(150_000, session.position)
        assertEquals(30_000, session.stagedCount)
        assertEquals(deck.indices.filter { it % 5 == 0 }.sumOf {
            deck[it].sizeBytes
        }, session.stagedBytes())
        assertEquals(30_000, session.staged().size)
        repeat(deck.size) { session = session.undo() }
        assertEquals(0, session.position)
        assertEquals(0, session.stagedCount)
        assertEquals(0L, session.stagedBytes())
        assertTrue(session.staged().isEmpty())
        // MediaStore entries are immutable: staging and Undo never touch input.
        assertEquals(150_000, input.size)
        assertEquals(image(1), input.first())
        assertEquals(image(150_000), input.last())
    }

    @Test fun stagedSnapshotsPreserveApprovalOrderForMixedVideosAndPhotos() {
        val deck = (1..8).map {
            image(it).copy(isVideo = it % 2 == 0)
        }
        var session = DeclutterSession()
        repeat(deck.size) { index ->
            session = session.decide(deck, index in setOf(0, 2, 4, 7))
        }
        assertEquals(listOf(deck[0], deck[2], deck[4], deck[7]), session.staged())
        session = session.unstage(deck[2].uri)
        assertEquals(listOf(deck[0], deck[4], deck[7]), session.staged())
        session = session.undo() // last staged video restored if reviewed again
        verify(session)
        assertEquals(listOf(deck[0], deck[4]), session.staged())
    }
}
