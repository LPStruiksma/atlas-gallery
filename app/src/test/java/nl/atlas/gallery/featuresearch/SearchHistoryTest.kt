package nl.atlas.gallery.featuresearch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIND-3a (#182): synthetic queries only. No real names, media or embeddings. */
class SearchHistoryTest {
    private val g = SearchHistoryScope.GLOBAL
    private fun e(text: String, vararg keys: String) = SearchHistoryEntry(text, keys.toList())
    private fun full() = SearchHistoryState().onGrant(SearchGrantLevel.FULL)

    @Test fun newestFirstOneEntryPerQueryIgnoringCase() {
        val s = full().remember(g, e("beach")).remember(g, e("snow")).remember(g, e("  Beach "))
        assertEquals(listOf("Beach", "snow"), s.entries(g).map { it.text })
    }

    @Test fun sameWordsWithDifferentPeopleAreDifferentEntries() {
        val s = full().remember(g, e("2019", "p1")).remember(g, e("2019"))
            .remember(g, e("2019", "p1"))
        assertEquals(listOf(e("2019", "p1"), e("2019")), s.entries(g))
        // People order does not make a new entry.
        val t = full().remember(g, e("", "p1", "p2")).remember(g, e("", "p2", "p1"))
        assertEquals(1, t.entries(g).size)
    }

    @Test fun keepsFiftyShowsTen() {
        var s = full()
        repeat(70) { s = s.remember(g, e("q$it")) }
        assertEquals(SearchHistoryState.KEPT, s.entries(g).size)
        assertEquals("q69", s.entries(g).first().text)
        assertEquals("q20", s.entries(g).last().text)
        assertEquals((69 downTo 60).map { "q$it" }, s.shown(g).map { it.text })
    }

    @Test fun scopesAreIsolated() {
        val s = full().remember(g, e("beach")).remember(SearchHistoryScope.ALBUM, e("cat"))
        assertEquals(listOf("beach"), s.entries(g).map { it.text })
        assertEquals(listOf("cat"), s.entries(SearchHistoryScope.ALBUM).map { it.text })
        val cleared = s.clear(SearchHistoryScope.ALBUM)
        assertTrue(cleared.entries(SearchHistoryScope.ALBUM).isEmpty())
        assertEquals(listOf("beach"), cleared.entries(g).map { it.text })
        assertTrue(SearchHistoryScope.entries.map { it.key }.containsAll(
            listOf("global", "photos", "albums", "album", "people", "person", "stories")))
    }

    @Test fun inadmissibleQueriesAreNotSavedOrTruncated() {
        val tooLong = "a".repeat(MAX_SEARCH_QUERY_CHARS + 1)
        val tooMany = (1..MAX_SEARCH_QUERY_TERMS + 1).joinToString(" ") { "w$it" }
        val s = full().remember(g, e("   ")).remember(g, e(tooLong)).remember(g, e(tooMany))
        assertTrue(s.entries(g).isEmpty())
    }

    @Test fun removeOneAndUndoRestoresOrder() {
        val s = full().remember(g, e("a")).remember(g, e("b")).remember(g, e("c"))
        val removed = s.remove(g, e("b"))
        assertEquals(listOf("c", "a"), removed.entries(g).map { it.text })
        assertEquals(s, removed.restore(s))
    }

    @Test fun clearAllAndUndo() {
        val s = full().remember(g, e("a")).remember(SearchHistoryScope.PEOPLE, e("b"))
        val cleared = s.clearAll()
        assertTrue(cleared.isEmpty)
        assertEquals(s, cleared.restore(s))
    }

    @Test fun undoKeepsWhatWasSavedSince() {
        val s = full().remember(g, e("a")).remember(g, e("b"))
        val after = s.clear(g).remember(g, e("new"))
        assertEquals(listOf("new", "b", "a"), after.restore(s).entries(g).map { it.text })
    }

    @Test fun offStopsSavingAndClearsEveryScopeAndUndoRestoresBoth() {
        val s = full().remember(g, e("a")).remember(SearchHistoryScope.STORIES, e("b"))
        val off = s.withEnabled(false)
        assertFalse(off.enabled)
        assertTrue(off.isEmpty)
        assertEquals(off, off.remember(g, e("ignored")))
        assertTrue(off.shown(g).isEmpty())
        val undone = off.restore(s)
        assertTrue(undone.enabled)
        assertEquals(s, undone)
        // Back on starts empty but saves again.
        assertEquals(listOf("x"), off.withEnabled(true).remember(g, e("x")).entries(g).map { it.text })
    }

    @Test fun reducedGrantClearsEveryScopeMoreAccessKeeps() {
        val s = SearchHistoryState().onGrant(SearchGrantLevel.PARTIAL).remember(g, e("a"))
        val up = s.onGrant(SearchGrantLevel.FULL)
        assertEquals(listOf("a"), up.entries(g).map { it.text })
        assertEquals(up, up.onGrant(SearchGrantLevel.FULL))
        assertTrue(up.onGrant(SearchGrantLevel.PARTIAL).isEmpty)
        assertTrue(up.onGrant(SearchGrantLevel.NONE).isEmpty)
        assertTrue(s.onGrant(SearchGrantLevel.NONE).isEmpty)
        // The first grant ever seen is adopted without clearing.
        val first = SearchHistoryState().remember(g, e("a")).onGrant(SearchGrantLevel.PARTIAL)
        assertEquals(1, first.entries(g).size)
    }

    @Test fun undoNeverCrossesAGrantChange() {
        val s = full().remember(g, e("a"))
        val reduced = s.clear(g).onGrant(SearchGrantLevel.PARTIAL)
        assertEquals(reduced, reduced.restore(s))
    }

    @Test fun grantLevelFromFlags() {
        assertEquals(SearchGrantLevel.FULL, searchGrantLevel(true, true, false))
        assertEquals(SearchGrantLevel.FULL, searchGrantLevel(true, true, true))
        assertEquals(SearchGrantLevel.PARTIAL, searchGrantLevel(false, false, true))
        assertEquals(SearchGrantLevel.PARTIAL, searchGrantLevel(true, false, false))
        assertEquals(SearchGrantLevel.NONE, searchGrantLevel(false, false, false))
    }

    @Test fun codecRoundTripsTabsNewlinesAndUnicode() {
        val s = full().remember(g, e("Zoë\tstrand")).remember(g, e("a,b", "key,1", "k\n2"))
            .remember(SearchHistoryScope.PERSON, e("verjaardag 2019"))
        val text = encodeSearchHistory(s)
        assertEquals(s, decodeSearchHistory(text))
        assertFalse(text.contains("Zoë"))
    }

    @Test fun corruptOrForeignFilesReadAsNull() {
        assertNull(decodeSearchHistory(""))
        assertNull(decodeSearchHistory("hello"))
        assertNull(decodeSearchHistory("ATLAS-SEARCH-HISTORY 1\ne\tglobal\t%%%\t\n"))
    }

    @Test fun decodeDropsUnknownScopesBadRowsAndCapsEachScope() {
        val rows = (0 until 60).joinToString("\n") { "e\tglobal\t${b64("q$it")}\t" }
        val text = "ATLAS-SEARCH-HISTORY 1\nenabled\t1\ngrant\tFULL\n" +
            "e\tmars\t${b64("x")}\t\ne\tglobal\n$rows\n"
        val s = decodeSearchHistory(text)!!
        assertEquals(SearchHistoryState.KEPT, s.entries(g).size)
        assertEquals(setOf(g), s.scopes.keys)
        assertEquals(SearchGrantLevel.FULL, s.grantLevel)
    }

    private fun b64(v: String) =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(v.toByteArray())

    private class MemoryFile(var text: String? = null, var failWrites: Boolean = false) : SearchHistoryFile {
        var writes = 0
        override fun read(): String? = text
        override fun write(text: String) {
            if (failWrites) throw java.io.IOException("disk full")
            writes++
            this.text = text
        }
    }

    @Test fun storePersistsAcrossRestart() {
        val file = MemoryFile()
        val store = SearchHistoryStore(file)
        store.load()
        store.update { it.onGrant(SearchGrantLevel.FULL) }
        store.update { it.remember(g, e("beach")) }
        store.flush()
        val restarted = SearchHistoryStore(file).also { it.load() }
        assertEquals(listOf("beach"), restarted.state.value.entries(g).map { it.text })
    }

    @Test fun storeReplaysEarlyChangesOnTopOfTheFile() {
        // Access was reduced while the app was closed: the first grant seen
        // before the file finished loading still clears the saved history.
        val file = MemoryFile(encodeSearchHistory(full().remember(g, e("old"))))
        val store = SearchHistoryStore(file)
        assertFalse(store.update { it.onGrant(SearchGrantLevel.PARTIAL) })
        store.flush()
        assertEquals(0, file.writes)
        store.load()
        assertTrue(store.state.value.isEmpty)
        assertEquals(SearchGrantLevel.PARTIAL, store.state.value.grantLevel)
        store.flush()
        assertEquals(1, file.writes)
        assertTrue(decodeSearchHistory(file.text!!)!!.isEmpty)
    }

    @Test fun storeTreatsCorruptFileAsEmptyAndRewritesOnNextSave() {
        val file = MemoryFile("\u0000garbage")
        val store = SearchHistoryStore(file)
        store.load()
        assertTrue(store.state.value.isEmpty)
        store.flush()
        assertEquals("\u0000garbage", file.text)
        store.update { it.remember(g, e("new")) }
        store.flush()
        assertEquals(listOf("new"), decodeSearchHistory(file.text!!)!!.entries(g).map { it.text })
    }

    @Test fun storeSurvivesReadAndWriteErrors() {
        val broken = object : SearchHistoryFile {
            override fun read(): String? = throw java.io.IOException("unreadable")
            override fun write(text: String) = throw java.io.IOException("unwritable")
        }
        val store = SearchHistoryStore(broken)
        store.load()
        assertTrue(store.update { it.remember(g, e("a")) })
        store.flush()
        assertEquals(listOf("a"), store.state.value.entries(g).map { it.text })
    }

    @Test fun offThenAppKillLeavesAConsistentFile() {
        // The off switch is written as one file: an app kill inside the Undo
        // window leaves "off and empty", never a half-restored store.
        val file = MemoryFile()
        val store = SearchHistoryStore(file).also { it.load() }
        store.update { it.onGrant(SearchGrantLevel.FULL).remember(g, e("a")) }
        store.flush()
        store.update { it.withEnabled(false) }
        store.flush()
        val reread = decodeSearchHistory(file.text!!)!!
        assertFalse(reread.enabled)
        assertTrue(reread.isEmpty)
    }

    @Test fun atomicFileWritesAndReplaces() {
        val dir = Files.createTempDirectory("atlas-search-history").toFile()
        try {
            val target = File(dir, "search-history-v1.txt")
            val file = SearchHistoryFile.atomic { target }
            assertNull(file.read())
            file.write("one")
            file.write("two")
            assertEquals("two", file.read())
            assertFalse(File(dir, "search-history-v1.txt.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
