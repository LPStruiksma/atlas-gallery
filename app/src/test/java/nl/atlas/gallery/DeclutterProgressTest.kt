package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

class DeclutterProgressTest {
    private fun media(id: Long, bytes: Long = 1000L + id, modified: Long = 5L) = MediaRecord(
        id, "content://media/external/images/media/$id", "fictional-$id.jpg",
        "DCIM/Synthetic/", bytes, id, false, modifiedAtSeconds = modified
    )

    @Test fun scopeKeyIsCanonicalAndSeparatesSourcesAndMonths() {
        val album = declutterScopeKey("album", "DCIM/Camera/", sort = "size")
        assertEquals(album, declutterScopeKey("album", "DCIM/Camera/", sort = "size"))
        assertNotEquals(album, declutterScopeKey("album", "DCIM/Camera/", sort = "date"))
        assertNotEquals(album, declutterScopeKey("month", "2026-11", filter = "DCIM/Camera/"))
        assertNotEquals(
            declutterScopeKey("search", "a|b", filter = "c"),
            declutterScopeKey("search", "a", filter = "b|c")
        )
        try { declutterScopeKey("", "x"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun sourceFingerprintChangesForMembershipOrderOrMediaRevision() {
        val source = listOf(media(1), media(2), media(3))
        val fingerprint = declutterSourceFingerprint(source)
        assertEquals(fingerprint, declutterSourceFingerprint(source.toList()))
        assertNotEquals(fingerprint, declutterSourceFingerprint(source.reversed()))
        assertNotEquals(fingerprint, declutterSourceFingerprint(source + media(4)))
        assertNotEquals(fingerprint,
            declutterSourceFingerprint(listOf(media(1, bytes = 99), media(2), media(3))))
        val a = declutterScopeKey("month", "header:MONTH:2026-11",
            filter = "pictures", snapshot = fingerprint)
        val b = declutterScopeKey("month", "header:MONTH:2026-11",
            filter = "pictures", snapshot = declutterSourceFingerprint(source + media(4)))
        assertNotEquals(a, b)
        assertTrue(a.startsWith("v2|"))
    }

    @Test fun progressCountsOnlyKeepsThatStillMatchAndResumesAtFirstUnreviewed() {
        val source = (1L..5L).map { media(it) }
        val kept = setOf(source[0], source[1], source[3]).map(::declutterKeepIdentity).toSet()
        val progress = DeclutterProgress.of(source, kept)
        assertEquals(5, progress.total)
        assertEquals(3, progress.reviewed)
        assertEquals(listOf(source[2], source[4]), progress.unreviewed)
        assertFalse(progress.complete)
    }

    @Test fun keepOfChangedOrRemovedFileDoesNotCountAndNothingAutoActs() {
        val original = media(1)
        val kept = setOf(declutterKeepIdentity(original), declutterKeepIdentity(media(99)))
        // The file was edited (other size and mtime): it comes back for review.
        val edited = media(1, bytes = 7777L, modified = 9L)
        val progress = DeclutterProgress.of(listOf(edited, media(2)), kept)
        assertEquals(0, progress.reviewed) // stale/removed Keeps never inflate the count
        assertEquals(2, progress.unreviewed.size)
    }

    @Test fun completeWhenEveryItemKeptAndEmptySourceIsNotComplete() {
        val source = listOf(media(1), media(2))
        val all = DeclutterProgress.of(source, source.map(::declutterKeepIdentity).toSet())
        assertTrue(all.complete)
        assertFalse(DeclutterProgress.of(emptyList(), emptySet()).complete)
    }

    @Test fun storeKeepsPerSourceAndClearsOnlyThatSource() {
        val store = InMemoryDeclutterKeepStore()
        val a = declutterScopeKey("album", "A/")
        val b = declutterScopeKey("album", "B/")
        val shared = declutterKeepIdentity(media(1))
        store.add(a, shared); store.add(b, shared); store.add(a, "x")
        assertEquals(setOf(shared, "x"), store.load(a))
        store.remove(a, "x")
        store.clear(b)
        assertEquals(setOf(shared), store.load(a))
        assertTrue(store.load(b).isEmpty())
    }

    @Test fun sourceDeckKeepsSourceOrderAndDropsDuplicatesAndFeedsASession() {
        val a = media(1, bytes = 10); val b = media(2, bytes = 99); val c = media(3, bytes = 50)
        val deck = sourceDeclutterDeck(listOf(a, b, c, b))
        assertEquals(listOf(a, b, c), deck) // not re-sorted by size
        var session = DeclutterSession().decide(deck, false)
        assertEquals(a, session.lastDecision?.media)
        session = session.decide(deck, true)
        assertEquals(1, session.stagedCount)
        assertEquals(b, session.lastDecision?.media)
        assertNull(DeclutterSession().lastDecision)
    }

    @Test fun resumeDeckExcludesKeptAndRestartedStagedItemsReturn() {
        val source = (1L..4L).map { media(it) }
        val store = InMemoryDeclutterKeepStore()
        val key = declutterScopeKey("album", "DCIM/Synthetic/")
        // Session 1: keep #1, stage #2 (never persisted), keep #3, then leave.
        store.add(key, declutterKeepIdentity(source[0]))
        store.add(key, declutterKeepIdentity(source[2]))
        // Session 2: resume shows the staged one and the untouched one only.
        val resumed = DeclutterProgress.of(source, store.load(key))
        assertEquals(listOf(source[1], source[3]), resumed.unreviewed)
        assertEquals(2, resumed.reviewed)
        // Start again clears this source only.
        store.clear(key)
        assertEquals(4, DeclutterProgress.of(source, store.load(key)).unreviewed.size)
    }
}
