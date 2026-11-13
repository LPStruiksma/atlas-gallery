package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 150k fake metadata records, no device permissions or user's real media. */
class LargeCatalogSortTest {
    private fun record(id: Long, video: Boolean): MediaRecord {
        val kind = if (video) "video" else "images"
        return MediaRecord(
            id = id,
            uri = "content://media/external/$kind/media/$id",
            name = "Synthetic media $id",
            relativePath = "DCIM/ATLAS-SYNTHETIC/",
            sizeBytes = 1_000L + id,
            takenAtMillis = id * 1_000L,
            isVideo = video
        )
    }

    /**
     * Regression oracle from the pre-optimization comparator. Seeded random
     * order is a user-visible browsing contract: a performance refactor must
     * not reshuffle someone's photo list under the same seed.
     */
    private fun legacyRandomOrder(items: List<MediaRecord>, seed: Long): List<MediaRecord> {
        fun legacyKey(id: Long): Long {
            var x = id xor seed
            x = (x xor (x ushr 33)) * -49064778989728563L
            x = (x xor (x ushr 33)) * -4265267296055464877L
            return x xor (x ushr 33)
        }
        return items.sortedWith(compareBy<MediaRecord> {
            legacyKey((it.id shl 1) or (if (it.isVideo) 1L else 0L))
        }.thenBy { it.id }.thenBy { it.isVideo })
    }

    @Test fun optimizedRandomKeysPreserveExistingSeededOrdering() {
        // Same numeric ID can occur once in images and once in videos.
        // Include duplicate IDs and input permutations to cover comparator ties.
        val inventory = (0 until 400).map { index ->
            record((index / 2 + 1).toLong(), video = index % 2 != 0)
        }
        for (seed in listOf(0L, 1L, -1L, 42L, 987654321L, Long.MIN_VALUE)) {
            assertEquals(legacyRandomOrder(inventory, seed),
                sortMedia(inventory, SortOrder.RANDOM, seed))
            val reversed = inventory.reversed()
            assertEquals(legacyRandomOrder(reversed, seed),
                sortMedia(reversed, SortOrder.RANDOM, seed))
        }
    }

    @Test fun equalImageAndVideoIdsHaveIndependentSeededRandomPositions() {
        val image = record(42, false)
        val video = record(42, true)
        val control = record(99, false)
        val relative = (1L..64L).map { seed ->
            val order = sortMedia(listOf(image, video, control), SortOrder.RANDOM, seed)
            order.indexOf(image) < order.indexOf(video)
        }.toSet()
        assertEquals("Random seeds must be able to reverse a photo/video ID collision",
            setOf(true, false), relative)
        assertEquals(listOf(image, video, control).toSet(),
            sortMedia(listOf(image, video, control), SortOrder.RANDOM, 29).toSet())
    }

    @Test fun largeMixedCatalogueShufflesDeterministicallyAndPreservesEveryRecord() {
        val inventory = (0 until 150_000).map { index ->
            record(index.toLong() / 2L + 1L, video = index % 2 != 0)
        }
        val begin = System.nanoTime()
        val sorted = sortMedia(inventory, SortOrder.RANDOM, 987654321L)
        val elapsedMs = (System.nanoTime() - begin) / 1_000_000
        // Diagnostic only: CI is not a physical Samsung performance benchmark.
        println("ATLAS synthetic 150k-metadata sort: $elapsedMs ms (JVM CI only)")
        assertEquals(150_000, sorted.size)
        assertEquals(150_000, sorted.map { it.uri }.toSet().size)
        assertEquals(inventory.map { it.uri }.toSet(), sorted.map { it.uri }.toSet())
        assertEquals(sorted, sortMedia(inventory, SortOrder.RANDOM, 987654321L))
        assertNotEquals(sorted, sortMedia(inventory, SortOrder.RANDOM, 12345L))
        val selected = inventory[149_999]
        assertEquals(selected, sorted[selectionIndex(sorted, selected.uri)])
        assertTrue(nearbyPreviewIndices(0, sorted.size).size <= 4)
        assertTrue(nearbyPreviewIndices(sorted.lastIndex, sorted.size).size <= 4)
    }
}
