package nl.atlas.gallery.featurestacks

import nl.atlas.gallery.featurestacks.StackGroupingRules.group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** STACK-0 (#574): synthetic records only; no personal media or real file names. */
class StackGroupingRulesTest {
    private val t0 = 1_700_000_000_000L
    private val dcim = "DCIM/Camera/"

    // Two 64-bit hashes 32 bits apart: different scenes.
    private val sceneA = 0x0F0F_0F0F_0F0F_0F0FL
    private val sceneB = 0x0F0F_0F0F_0F0F_0F0FL xor 0xFFFF_FFFF_0000_0000uL.toLong()

    private fun photo(
        id: Long,
        name: String,
        timeMs: Long? = t0,
        dir: String = dcim,
        size: Long = 1_000_000,
        hash: Long? = null,
        informative: Boolean = hash != null,
        w: Int = 4000,
        h: Int = 3000,
        sharp: Float? = null,
        face: Float? = null,
        originalId: Long? = null,
        kind: StackMediaKind = StackMediaKind.PHOTO
    ) = StackMediaRecord(id, dir, name, kind, timeMs, size, w, h, hash, informative, sharp, face, originalId)

    private fun flip(hash: Long, bits: Int): Long {
        var h = hash
        for (i in 0 until bits) h = h xor (1L shl (i * 7 % 64))
        return h
    }

    private fun onlyStack(projection: StackProjection): StackTile.Stack {
        assertEquals(1, projection.stacks.size)
        return projection.stacks.single()
    }

    // --- (a) vendor bursts -------------------------------------------------

    @Test fun samsungBurstFramesCollapseIntoOneTile() {
        val frames = (1..5).map { photo(it.toLong(), "20241105_143022_00$it.jpg", t0 + it * 100L) }
        val p = group(frames)
        val stack = onlyStack(p)
        assertEquals(StackReason.BURST, stack.reason)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), stack.memberIds)
        assertEquals(1, p.tiles.size)
    }

    @Test fun googleBurstWithCoverFrameCollapses() {
        val key = "20241105143022123"
        val p = group(
            listOf(
                photo(1, "00000IMG_00000_BURST${key}_COVER.jpg", t0),
                photo(2, "00001IMG_00001_BURST$key.jpg", t0 + 150),
                photo(3, "00002IMG_00002_BURST$key.jpg", t0 + 300)
            )
        )
        assertEquals(StackReason.BURST, onlyStack(p).reason)
        assertEquals(3, onlyStack(p).memberIds.size)
    }

    @Test fun unrelatedSequentialFileNamesAreNotABurst() {
        val p = group((1..4).map { photo(it.toLong(), "IMG_000$it.jpg", t0 + it * 200L) })
        assertTrue(p.stacks.isEmpty())
        assertEquals(4, p.tiles.size)
    }

    @Test fun burstNamePatternsAreSpecific() {
        assertEquals("samsung:20241105_143022", StackGroupingRules.burstKey("20241105_143022_001.jpg"))
        assertNull(StackGroupingRules.burstKey("20241105_143022.jpg"))
        assertNull(StackGroupingRules.burstKey("20241105_143022(1).jpg"))
        assertNull(StackGroupingRules.burstKey("IMG_20241105_143022_001_edit.jpg"))
        assertNull(StackGroupingRules.burstKey("holiday_burst_001.jpg"))
    }

    @Test fun sameBurstNameInDifferentFoldersStaysApart() {
        val p = group(
            listOf(
                photo(1, "20241105_143022_001.jpg", t0, dir = "DCIM/Camera/"),
                photo(2, "20241105_143022_002.jpg", t0 + 100, dir = "Pictures/Restored/")
            )
        )
        assertTrue(p.stacks.isEmpty())
    }

    @Test fun burstNeedsKnownTimesAndSplitsOnLongGaps() {
        val p = group(
            listOf(
                photo(1, "20241105_143022_001.jpg", t0),
                photo(2, "20241105_143022_002.jpg", t0 + 500),
                photo(3, "20241105_143022_003.jpg", null),
                photo(4, "20241105_143022_004.jpg", t0 + 60_000),
                photo(5, "20241105_143022_005.jpg", t0 + 60_400)
            )
        )
        assertEquals(listOf(listOf(1L, 2L), listOf(4L, 5L)), p.stacks.map { it.memberIds })
        assertTrue(p.tiles.contains(StackTile.Single(3)))
    }

    // --- (b) RAW + JPEG/HEIC ----------------------------------------------

    @Test fun rawAndJpegWithSameStemStackAndShowTheJpeg() {
        val p = group(
            listOf(
                photo(1, "DSC_0042.NEF", t0, size = 25_000_000, sharp = 0.9f),
                photo(2, "DSC_0042.JPG", t0 + 400, size = 6_000_000, sharp = 0.9f)
            )
        )
        val stack = onlyStack(p)
        assertEquals(StackReason.RAW_COMPANION, stack.reason)
        assertEquals(2L, stack.topPickId)
    }

    @Test fun rawAndHeicStack() {
        val p = group(listOf(photo(1, "IMG_7001.DNG", t0), photo(2, "IMG_7001.HEIC", t0)))
        assertEquals(StackReason.RAW_COMPANION, onlyStack(p).reason)
    }

    @Test fun sameNameInDifferentFoldersNeverStacks() {
        val p = group(
            listOf(
                photo(1, "DSC_0042.NEF", t0, dir = "DCIM/Camera/"),
                photo(2, "DSC_0042.JPG", t0, dir = "Download/")
            )
        )
        assertTrue(p.stacks.isEmpty())
    }

    @Test fun rawCompanionNeedsCloseKnownCaptureTimes() {
        // Camera counter wrapped: same stem, an hour apart.
        val apart = group(listOf(photo(1, "DSC_0001.ARW", t0), photo(2, "DSC_0001.JPG", t0 + 3_600_000)))
        assertTrue(apart.stacks.isEmpty())
        val unknown = group(listOf(photo(1, "DSC_0001.ARW", null), photo(2, "DSC_0001.JPG", t0)))
        assertTrue(unknown.stacks.isEmpty())
    }

    @Test fun twoJpegsWithoutRawAreNotARawPair() {
        val p = group(listOf(photo(1, "IMG_1.jpg", t0), photo(2, "IMG_1.jpeg", t0)))
        assertTrue(p.stacks.none { it.reason == StackReason.RAW_COMPANION })
    }

    // --- (c) edited copy with its original ---------------------------------

    @Test fun explicitEditLineageStacksAndShowsTheEdit() {
        val p = group(
            listOf(
                photo(1, "IMG_0100.jpg", t0, sharp = 0.95f),
                photo(2, "IMG_0100_atlas.jpg", t0 + 86_400_000, originalId = 1, sharp = 0.5f)
            )
        )
        val stack = onlyStack(p)
        assertEquals(StackReason.EDITED_ORIGINAL, stack.reason)
        assertEquals(2L, stack.topPickId)
    }

    @Test fun editedSuffixStacksOnlyWhenTheImageStillMatches() {
        val same = group(
            listOf(
                photo(1, "IMG_0200.jpg", t0, hash = sceneA),
                photo(2, "IMG_0200-edited.jpg", t0 + 5_000, hash = flip(sceneA, 10))
            )
        )
        assertEquals(StackReason.EDITED_ORIGINAL, onlyStack(same).reason)

        val different = group(
            listOf(
                photo(1, "IMG_0200.jpg", t0, hash = sceneA),
                photo(2, "IMG_0200-edited.jpg", t0 + 5_000, hash = sceneB)
            )
        )
        assertTrue(different.stacks.isEmpty())

        val noHash = group(listOf(photo(1, "IMG_0200.jpg", t0), photo(2, "IMG_0200_edited.jpg", t0)))
        assertTrue(noHash.stacks.isEmpty())
    }

    @Test fun genericSuffixIsNotTreatedAsAnEdit() {
        val p = group(
            listOf(
                photo(1, "IMG_0300.jpg", t0 + 0, hash = sceneA),
                photo(2, "IMG_0300_copy.jpg", t0 + 90_000, hash = sceneA)
            )
        )
        assertTrue(p.stacks.isEmpty())
    }

    @Test fun editLineageAcrossFoldersDoesNotStack() {
        val p = group(
            listOf(
                photo(1, "IMG_0400.jpg", t0, dir = "DCIM/Camera/"),
                photo(2, "IMG_0400_atlas.jpg", t0, dir = "Pictures/Edited/", originalId = 1)
            )
        )
        assertTrue(p.stacks.isEmpty())
    }

    // --- (d) near-identical shots -------------------------------------------

    @Test fun nearIdenticalShotsSecondsApartStack() {
        val p = group(
            listOf(
                photo(1, "IMG_1001.jpg", t0, hash = sceneA),
                photo(2, "IMG_1002.jpg", t0 + 1_500, hash = flip(sceneA, 2)),
                photo(3, "IMG_1003.jpg", t0 + 2_800, hash = flip(sceneA, 3))
            )
        )
        val stack = onlyStack(p)
        assertEquals(StackReason.NEAR_IDENTICAL, stack.reason)
        assertEquals(listOf(1L, 2L, 3L), stack.memberIds)
    }

    @Test fun sameSecondDifferentSceneNeverStacks() {
        val p = group(
            listOf(
                photo(1, "IMG_2001.jpg", t0, hash = sceneA),
                photo(2, "IMG_2002.jpg", t0, hash = sceneB)
            )
        )
        assertTrue(p.stacks.isEmpty())
    }

    @Test fun similarButNotNearIdenticalStaysApart() {
        // Five bits apart is a Photo DNA "possible match" but not a stack.
        val p = group(listOf(photo(1, "a.jpg", t0, hash = sceneA), photo(2, "b.jpg", t0 + 500, hash = flip(sceneA, 5))))
        assertTrue(p.stacks.isEmpty())
    }

    @Test fun timeAdjacencyWithoutHashNeverStacks() {
        val p = group(listOf(photo(1, "a.jpg", t0), photo(2, "b.jpg", t0 + 100)))
        assertTrue(p.stacks.isEmpty())
        val flat = group(
            listOf(
                photo(1, "a.jpg", t0, hash = 0L, informative = false),
                photo(2, "b.jpg", t0 + 100, hash = 0L, informative = false)
            )
        )
        assertTrue(flat.stacks.isEmpty())
    }

    @Test fun nearIdenticalNeverChainsThroughAMiddleFrame() {
        // A~B (3 bits), B~C (3 bits), but A-C is 6 bits apart.
        val a = sceneA
        val b = a xor 0b111L
        val c = b xor (0b111L shl 8)
        val p = group(
            listOf(
                photo(1, "a.jpg", t0, hash = a),
                photo(2, "b.jpg", t0 + 1_000, hash = b),
                photo(3, "c.jpg", t0 + 2_000, hash = c)
            )
        )
        val stack = onlyStack(p)
        assertEquals(listOf(1L, 2L), stack.memberIds)
        assertTrue(p.tiles.contains(StackTile.Single(3)))
    }

    @Test fun nearIdenticalRespectsGapSpanAndFolder() {
        val gap = group(listOf(photo(1, "a.jpg", t0, hash = sceneA), photo(2, "b.jpg", t0 + 3_001, hash = sceneA)))
        assertTrue(gap.stacks.isEmpty())

        val longRun = (0..6).map { photo(it + 1L, "f$it.jpg", t0 + it * 2_500L, hash = sceneA) }
        val spans = group(longRun).stacks.map { it.memberIds }
        assertEquals(listOf(listOf(1L, 2L, 3L, 4L, 5L), listOf(6L, 7L)), spans)

        val folders = group(
            listOf(
                photo(1, "a.jpg", t0, hash = sceneA, dir = "DCIM/Camera/"),
                photo(2, "a.jpg", t0, hash = sceneA, dir = "WhatsApp/Images/")
            )
        )
        assertTrue(folders.stacks.isEmpty())
    }

    @Test fun portraitAndLandscapeOrCropNeverStack() {
        val rotated = group(
            listOf(
                photo(1, "a.jpg", t0, hash = sceneA, w = 4000, h = 3000),
                photo(2, "b.jpg", t0 + 500, hash = sceneA, w = 3000, h = 4000)
            )
        )
        assertTrue(rotated.stacks.isEmpty())
        val cropped = group(
            listOf(
                photo(1, "a.jpg", t0, hash = sceneA, w = 4000, h = 3000),
                photo(2, "b.jpg", t0 + 500, hash = sceneA, w = 4000, h = 2250)
            )
        )
        assertTrue(cropped.stacks.isEmpty())
    }

    // --- videos ----------------------------------------------------------------

    @Test fun videoNextToPhotoNeverStacks() {
        val p = group(
            listOf(
                photo(1, "20241105_143022_001.jpg", t0, hash = sceneA),
                photo(2, "20241105_143022_002.mp4", t0 + 100, hash = sceneA, kind = StackMediaKind.VIDEO),
                photo(3, "VID_0001.jpg", t0 + 200, hash = sceneA),
                photo(4, "VID_0001.mp4", t0 + 200, hash = sceneA, kind = StackMediaKind.VIDEO)
            )
        )
        p.stacks.forEach { stack -> assertFalse(stack.memberIds.contains(2L) || stack.memberIds.contains(4L)) }
        assertTrue(p.tiles.contains(StackTile.Single(2)))
        assertTrue(p.tiles.contains(StackTile.Single(4)))
    }

    @Test fun videosNeverStackWithEachOther() {
        val p = group(
            listOf(
                photo(1, "a.mp4", t0, hash = sceneA, kind = StackMediaKind.VIDEO),
                photo(2, "b.mp4", t0 + 100, hash = sceneA, kind = StackMediaKind.VIDEO)
            )
        )
        assertTrue(p.stacks.isEmpty())
    }

    // --- ordering, reversibility, priority ------------------------------------------

    @Test fun stackSitsAtItsFirstMemberAndUnstackRestoresInputOrder() {
        // Newest-first grid order, with an unrelated folder interleaved.
        val records = listOf(
            photo(10, "x.jpg", t0 + 9_000, dir = "Download/"),
            photo(3, "20241105_143022_003.jpg", t0 + 200),
            photo(11, "y.jpg", t0 + 150, dir = "Download/"),
            photo(2, "20241105_143022_002.jpg", t0 + 100),
            photo(1, "20241105_143022_001.jpg", t0),
            photo(12, "z.jpg", t0 - 50_000, dir = "Download/")
        )
        val p = group(records)
        val stack = onlyStack(p)
        assertEquals(listOf(3L, 2L, 1L), stack.memberIds)
        assertEquals(
            listOf(StackTile.Single(10), stack, StackTile.Single(11), StackTile.Single(12)),
            p.tiles
        )
        assertEquals(records.map { it.id }, p.unstacked())
        assertEquals(records.map { it.id }.sorted(), p.tiles.flatMap { it.memberIds }.sorted())
    }

    @Test fun groupingIsDeterministicForTheSameInput() {
        val records = (1..6).map { photo(it.toLong(), "20241105_143022_00$it.jpg", t0 + it * 50L, hash = sceneA) }
        assertEquals(group(records), group(records))
        // A different input order moves tiles but never changes membership.
        val shuffled = group(records.shuffled(java.util.Random(7)))
        assertEquals(group(records).stacks.map { it.memberIds.toSet() }, shuffled.stacks.map { it.memberIds.toSet() })
    }

    @Test fun rawPairIsClaimedBeforeNearIdentical() {
        val p = group(
            listOf(
                photo(1, "DSC_0042.NEF", t0, hash = sceneA),
                photo(2, "DSC_0042.JPG", t0, hash = sceneA),
                photo(3, "DSC_0043.JPG", t0 + 800, hash = sceneA)
            )
        )
        val stack = onlyStack(p)
        assertEquals(StackReason.RAW_COMPANION, stack.reason)
        assertEquals(listOf(1L, 2L), stack.memberIds)
        assertTrue(p.tiles.contains(StackTile.Single(3)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsAreRejected() {
        group(listOf(photo(1, "a.jpg"), photo(1, "b.jpg")))
    }

    // --- top pick ----------------------------------------------------------------

    @Test fun topPickPrefersSharpestThenFaceThenSizeThenId() {
        fun pick(vararg r: StackMediaRecord) = StackGroupingRules.topPick(StackReason.BURST, r.toList())
        assertEquals(2L, pick(photo(1, "a", sharp = 0.60f), photo(2, "b", sharp = 0.80f)))
        assertEquals(1L, pick(photo(1, "a", sharp = 0.801f, face = 0.9f), photo(2, "b", sharp = 0.799f, face = 0.4f)))
        assertEquals(2L, pick(photo(1, "a", sharp = 0.7f, size = 9), photo(2, "b", sharp = 0.7f, size = 10)))
        assertEquals(1L, pick(photo(1, "a", size = 5), photo(2, "b", size = 5)))
    }

    @Test fun missingQualityNeverOutranksMeasuredQuality() {
        val p = StackGroupingRules.topPick(
            StackReason.BURST,
            listOf(photo(1, "a", sharp = null, size = 99_000_000), photo(2, "b", sharp = 0.05f, size = 1))
        )
        assertEquals(2L, p)
    }

    @Test fun userOverrideWinsAndIgnoresNonMembers() {
        val frames = (1..3).map { photo(it.toLong(), "20241105_143022_00$it.jpg", t0 + it * 100L, sharp = it / 10f) }
        assertEquals(3L, onlyStack(group(frames)).topPickId)

        val overridden = onlyStack(group(frames, topPickOverrides = mapOf(2L to 1L)))
        assertEquals(1L, overridden.topPickId)
        assertTrue(overridden.userPicked)
        assertEquals(1L, overridden.coverId)

        val stale = onlyStack(group(frames, topPickOverrides = mapOf(2L to 99L)))
        assertEquals(3L, stale.topPickId)
        assertFalse(stale.userPicked)
    }

    // --- aggregate --------------------------------------------------------------

    @Test fun aggregateCountsOnlyTilesSaved() {
        val records = (1..4).map { photo(it.toLong(), "20241105_143022_00$it.jpg", t0 + it * 100L) } +
            listOf(photo(5, "DSC_0001.ARW", t0), photo(6, "DSC_0001.JPG", t0)) +
            (7..10).map { photo(it.toLong(), "IMG_$it.jpg", t0 + it * 60_000L) }
        val a = StackGroupingRules.aggregate(group(records))
        assertEquals(StackAggregate(originalTiles = 10, stackCount = 2, itemsInStacks = 6, tilesSaved = 4), a)
        assertEquals(40.0, a.percentSaved, 1e-9)
        assertEquals(0.0, StackGroupingRules.aggregate(group(emptyList())).percentSaved, 0.0)
    }

    @Test fun largeSyntheticLibraryStaysLinearEnough() {
        val n = 150_000
        val records = (0 until n).map { i ->
            photo(i.toLong(), "IMG_$i.jpg", t0 + i * 4_000L, dir = "DCIM/${i % 20}/", hash = sceneA xor i.toLong().shl(20))
        }
        val start = System.nanoTime()
        val p = group(records)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(n, p.unstacked().size)
        assertTrue("grouping 150k took $ms ms", ms < 10_000)
    }
}
