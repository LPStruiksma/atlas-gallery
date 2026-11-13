package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Tokens stand in for bitmaps; never open real files or media. */
class ViewerTileResidentCacheTest {
    private data class Token(val id: Int)
    private val source = ViewerTileSource("content://synthetic/200mp", 42L, 0L)

    private fun tile(id: Int, owner: ViewerTileSource = source): ViewerTileKey =
        ViewerTileKey(owner, 1, ViewerSourceRect(id * 10, 0, (id + 1) * 10, 10),
            0, false)

    @Test fun lruEvictionAndViewportExitDisposeOnlyOwnedResources() {
        val disposed = mutableListOf<Token>()
        val cache = ViewerTileResidentCache<Token>(256L) { disposed += it }
        val a = tile(1)
        val b = tile(2)
        val c = tile(3)
        val first = Token(1)
        val second = Token(2)
        val third = Token(3)
        cache.show(source, setOf(a, b, c))
        assertTrue(cache.offer(a, first, 120L))
        assertTrue(cache.offer(b, second, 120L))
        assertSame(first, cache.get(a)) // mark A most recently used
        assertTrue(cache.offer(c, third, 120L))
        assertNull(cache.get(b))
        assertEquals(listOf(second), disposed)
        assertEquals(setOf(a, c), cache.residentKeys())
        assertEquals(240L, cache.usedBytes)
        cache.show(source, setOf(c))
        assertNull(cache.get(a))
        assertEquals(listOf(second, first), disposed)
        assertEquals(120L, cache.usedBytes)
        cache.close()
        cache.close() // disposal is idempotent
        assertEquals(listOf(second, first, third), disposed)
        assertEquals(0L, cache.usedBytes)
        assertTrue(cache.residentKeys().isEmpty())
    }

    @Test fun revokedRegrantedOrEditedSourceDropsBitmapsBeforeAnyReuse() {
        val disposed = mutableListOf<Token>()
        val cache = ViewerTileResidentCache<Token>(1_024L) { disposed += it }
        val old = tile(2)
        val image = Token(1)
        cache.show(source, setOf(old))
        assertTrue(cache.offer(old, image, 800L))
        cache.show(null, setOf(old))
        assertEquals(listOf(image), disposed)
        assertNull(cache.get(old))
        assertTrue(cache.residentKeys().isEmpty())

        val staleOutput = Token(2)
        assertFalse(cache.offer(old, staleOutput, 100L))
        assertEquals(listOf(image, staleOutput), disposed)
        val regrant = source.copy(grantEpoch = source.grantEpoch + 1)
        val newKey = tile(2, regrant)
        cache.show(regrant, setOf(newKey))
        assertFalse(cache.offer(old, Token(3), 100L))
        assertTrue(cache.offer(newKey, Token(4), 800L))
        assertEquals(1, cache.size)
        cache.show(regrant.copy(contentRevision = 1L), emptySet())
        assertEquals(0, cache.size)
        assertEquals(0L, cache.usedBytes)
        assertTrue(disposed.contains(Token(4)))
    }

    @Test fun malformedBytesDuplicateObjectsAndOutOfViewResultsAreSafe() {
        val disposed = mutableListOf<Token>()
        val cache = ViewerTileResidentCache<Token>(128L) { disposed += it }
        val a = tile(1)
        val b = tile(2)
        cache.show(source, setOf(a, b))
        assertFalse(cache.offer(a, Token(1), 129))
        assertFalse(cache.offer(a, Token(2), 0))
        assertFalse(cache.offer(tile(3), Token(3), 10))
        assertTrue(cache.offer(a, Token(4), 64))
        assertEquals(Token(4), cache.get(a))
        // Supplying the already resident object should not destroy it.
        val owned = requireNotNull(cache.get(a))
        assertTrue(cache.offer(a, owned, 20))
        assertSame(owned, cache.get(a))
        // One decoded resource object cannot be owned by two tile keys.
        assertFalse(cache.offer(b, owned, 64))
        assertTrue(disposed.none { it === owned })
        // Even a stale publication of the same object must never recycle it.
        assertFalse(cache.offer(tile(9), owned, 64))
        assertTrue(disposed.none { it === owned })
        // Replacing a key transfers ownership, releasing old resource.
        assertTrue(cache.offer(a, Token(5), 64))
        assertNull(cache.get(b))
        assertEquals(64L, cache.usedBytes)
        cache.close()
        assertEquals(disposed.size, disposed.distinct().size)
        assertTrue(disposed.containsAll(listOf(Token(1), Token(2), Token(3),
            Token(4), Token(5))))
    }

    @Test fun crashingDisposerCannotKeepRevokedOrOldViewportTilesReachable() {
        val attempts = mutableListOf<Int>()
        val cache = ViewerTileResidentCache<Token>(512L) { token ->
            attempts += token.id
            if (token.id == 2) throw IllegalStateException("synthetic recycling failure")
        }
        val a = tile(1)
        val b = tile(2)
        val c = tile(3)
        cache.show(source, setOf(a, b, c))
        assertTrue(cache.offer(a, Token(1), 100L))
        assertTrue(cache.offer(b, Token(2), 100L))
        assertTrue(cache.offer(c, Token(3), 100L))
        // All items detach before any callback; the failure of one disposer
        // must never expose another stale bitmap after permission revocation.
        cache.show(null, emptySet())
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(1, cache.disposalFailures)
        assertEquals(0L, cache.usedBytes)
        assertEquals(0, cache.size)
        assertTrue(cache.residentKeys().isEmpty())
        assertNull(cache.get(a))
        cache.close()
        assertEquals(3, attempts.size)
    }

    @Test fun failedEvictionDoesNotBlockRemainingEvictionsOrCorruptByteCount() {
        val attempted = mutableListOf<Int>()
        val cache = ViewerTileResidentCache<Token>(300L) { token ->
            attempted += token.id
            if (token.id == 1) throw IllegalArgumentException("synthetic recycle error")
        }
        val keys = (0..3).map(::tile)
        cache.show(source, keys.toSet())
        assertTrue(cache.offer(keys[0], Token(1), 100L))
        assertTrue(cache.offer(keys[1], Token(2), 100L))
        assertTrue(cache.offer(keys[2], Token(3), 100L))
        // 290-byte bitmap requires all three prior entries be evicted.
        assertTrue(cache.offer(keys[3], Token(4), 290L))
        assertEquals(listOf(1, 2, 3), attempted)
        assertEquals(1, cache.disposalFailures)
        assertEquals(290L, cache.usedBytes)
        assertEquals(setOf(keys[3]), cache.residentKeys())
        assertNull(cache.get(keys[0]))
        assertNull(cache.get(keys[1]))
        assertNull(cache.get(keys[2]))
        cache.close()
        assertEquals(listOf(1, 2, 3, 4), attempted)
        assertEquals(0L, cache.usedBytes)
    }

    @Test fun overlargeViewportFailsClosedWithoutRevealingOldBitmap() {
        val freed = mutableListOf<Token>()
        val cache = ViewerTileResidentCache<Token>(512) { freed += it }
        val first = tile(0)
        cache.show(source, setOf(first))
        assertTrue(cache.offer(first, Token(1), 100))
        val forged = (0..12).map { tile(it) }.toSet()
        cache.show(source, forged)
        assertNull(cache.get(first))
        assertEquals(listOf(Token(1)), freed)
        assertTrue(cache.residentKeys().isEmpty())
        cache.close()
    }

    @Test fun deterministicTenThousandGrantAndViewportTransitionsNeverOverbudget() {
        val rng = Random(20261024)
        val disposedIds = mutableSetOf<Int>()
        val cache = ViewerTileResidentCache<Token>(1_024L) {
            assertTrue("Double dispose: $it", disposedIds.add(it.id))
        }
        var nextId = 0
        repeat(10_000) { step ->
            val authorized = source.copy(grantEpoch = 1L + step / 61,
                contentRevision = (step / 127).toLong())
            val visible = (0 until 8).map { tile(it, authorized) }.toSet()
            cache.show(authorized, visible)
            val chosen = tile(rng.nextInt(8), authorized)
            val resource = Token(nextId++)
            val bytes = rng.nextLong(1L, 900L)
            assertTrue(cache.offer(chosen, resource, bytes))
            assertTrue(cache.usedBytes in 1L..1_024L)
            assertTrue(cache.size <= 8)
            assertTrue(cache.residentKeys().all { it.source == authorized })
            assertNull(cache.get(tile(0, source.copy(grantEpoch = -1))))
            if (step % 97 == 0) {
                cache.show(null, emptySet())
                assertEquals(0L, cache.usedBytes)
            }
        }
        cache.close()
        assertEquals(nextId, disposedIds.size)
    }
}
