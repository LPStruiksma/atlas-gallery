package nl.atlas.gallery

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #438 crop geometry, cache fence and album-close rule; synthetic values only. */
class PeopleFaceCropsTest {
    private fun inside(p: FaceCropPlan) {
        assertTrue(p.decodeWidth >= 1 && p.decodeHeight >= 1)
        assertTrue(p.left in 0 until p.right && p.right <= p.decodeWidth)
        assertTrue(p.top in 0 until p.bottom && p.bottom <= p.decodeHeight)
    }

    @Test fun centredFaceGetsItsMarginAndADownsampledDecode() {
        // 4000x3000 photo, face box 400x300 px in the middle.
        val p = FaceCropGeometry.plan(4000, 3000, IndexedFaceBox(0.45f, 0.45f, 0.55f, 0.55f))!!
        inside(p)
        // Box + 25% each side = 600x450 source px; long edge scaled to 192.
        assertEquals(192, p.right - p.left, 2)
        assertEquals(144, p.bottom - p.top, 2)
        assertEquals(1280, p.decodeWidth)
        assertEquals(960, p.decodeHeight)
        // The crop is centred on the box.
        assertEquals(p.decodeWidth / 2, (p.left + p.right) / 2, 1)
        assertEquals(p.decodeHeight / 2, (p.top + p.bottom) / 2, 1)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected±$tolerance, was $actual",
            actual in expected - tolerance..expected + tolerance)

    @Test fun marginIsClampedToTheImageAtEveryEdge() {
        for (box in listOf(
            IndexedFaceBox(0f, 0f, 0.2f, 0.2f),
            IndexedFaceBox(0.8f, 0.8f, 1f, 1f),
            IndexedFaceBox(0f, 0.4f, 1f, 0.6f),
            IndexedFaceBox(0f, 0f, 1f, 1f))) {
            val p = FaceCropGeometry.plan(1000, 800, box)!!
            inside(p)
        }
        val corner = FaceCropGeometry.plan(1000, 1000, IndexedFaceBox(0f, 0f, 0.2f, 0.2f))!!
        assertEquals(0, corner.left)
        assertEquals(0, corner.top)
        val whole = FaceCropGeometry.plan(1000, 1000, IndexedFaceBox(0f, 0f, 1f, 1f))!!
        assertEquals(whole.decodeWidth, whole.right)
        assertEquals(whole.decodeHeight, whole.bottom)
    }

    @Test fun smallImageIsNeverUpscaled() {
        val p = FaceCropGeometry.plan(120, 90, IndexedFaceBox(0.25f, 0.25f, 0.75f, 0.75f))!!
        assertEquals(120, p.decodeWidth)
        assertEquals(90, p.decodeHeight)
        inside(p)
    }

    @Test fun tinyFaceInAHugePhotoStaysWithinTheDecodeCap() {
        val p = FaceCropGeometry.plan(12000, 9000, IndexedFaceBox(0.5f, 0.5f, 0.505f, 0.505f))!!
        assertTrue(maxOf(p.decodeWidth, p.decodeHeight) <= FaceCropGeometry.MAX_DECODE_EDGE)
        inside(p)
    }

    /**
     * Orientation is normalized by the decoder: boxes are relative to the
     * EXIF-oriented frame the scanner saw, and the plan takes the oriented
     * size the decoder reports. A portrait (rotated) photo therefore maps the
     * same box to a portrait crop with no rotation applied here.
     */
    @Test fun boxesAreRelativeToTheOrientedFrame() {
        val box = IndexedFaceBox(0.1f, 0.6f, 0.3f, 0.8f)
        val landscape = FaceCropGeometry.plan(4000, 3000, box)!!
        val portrait = FaceCropGeometry.plan(3000, 4000, box)!!
        inside(landscape); inside(portrait)
        assertTrue(portrait.decodeHeight > portrait.decodeWidth)
        // The box sits in the lower-left of the oriented frame in both.
        for (p in listOf(landscape, portrait)) {
            assertTrue(p.left < p.decodeWidth / 2)
            assertTrue(p.top > p.decodeHeight / 2)
        }
        // A portrait box (taller in pixels) gives a taller crop.
        assertTrue(portrait.bottom - portrait.top > portrait.right - portrait.left)
    }

    @Test fun roundingAddsAtMostTwoPixelsToTheTargetEdge() {
        var worst = 0
        for (w in listOf(480, 640, 1000, 3000, 4032)) for (h in listOf(480, 777, 3024))
            for (i in 0 until 40) {
                val l = 0.2f + i * 0.004f
                val t = 0.25f + i * 0.003f
                val box = IndexedFaceBox(l, t, l + 0.2f + i * 0.003f, t + 0.25f + i * 0.002f)
                val p = FaceCropGeometry.plan(w, h, box)!!
                inside(p)
                worst = maxOf(worst, p.right - p.left, p.bottom - p.top)
            }
        assertTrue("worst crop edge $worst", worst <= FaceCropGeometry.TARGET_EDGE + 2)
    }

    @Test fun unusableSizeHasNoPlan() {
        assertNull(FaceCropGeometry.plan(0, 100, IndexedFaceBox(0f, 0f, 1f, 1f)))
        assertNull(FaceCropGeometry.plan(100, -1, IndexedFaceBox(0f, 0f, 1f, 1f)))
    }

    private fun key(epoch: String, n: Int) =
        FaceCropKey(epoch, n.toString(16).padStart(32, '0'), "atlas-face-media-v1:k$n", "r1")

    @Test fun anotherEpochEmptiesTheCache() {
        val cache = FaceCropCache<String>()
        cache.put(key("e1", 1), "crop-1")
        cache.put(key("e1", 2), "crop-2")
        assertEquals("crop-1", cache.get(key("e1", 1)))
        // A lookup under a new epoch fences: nothing of e1 survives.
        assertNull(cache.get(key("e2", 1)))
        assertEquals(0, cache.size)
        assertNull(cache.get(key("e1", 2)))
    }

    @Test fun clearOnRevokeAndARevisionIsPartOfTheKey() {
        val cache = FaceCropCache<String>()
        cache.put(key("e1", 1), "crop-1")
        assertNull(cache.get(key("e1", 1).copy(revision = "r2")))
        cache.clear()
        assertNull(cache.get(key("e1", 1)))
    }

    @Test fun cacheIsBoundedLeastRecentlyUsed() {
        val cache = FaceCropCache<String>(maxEntries = 2)
        cache.put(key("e", 1), "1")
        cache.put(key("e", 2), "2")
        cache.get(key("e", 1))
        cache.put(key("e", 3), "3")
        assertEquals(2, cache.size)
        assertEquals("1", cache.get(key("e", 1)))
        assertNull(cache.get(key("e", 2)))
    }

    private fun face(n: Int) = NativeFaceObservationId(n.toString(16).padStart(32, '0'))
    private val ada = PersonKey("synthetic-person-ada")

    private fun unnamed() = PeopleClusterReviewState("synthetic-grant", "synthetic-generation",
        FaceClusterer.update(
            FaceClusterState.empty("synthetic-v1", 2, ClusterPolicy.SFACE_CONSERVATIVE),
            add = listOf(FaceEmbedding(face(1), "synthetic-v1", floatArrayOf(1f, 0f)))))

    @Test fun albumClosesWhenThePersonOrTheOptInIsGone() {
        val before = unnamed()
        val group = before.clusters.unknownClusters.single()
        val named = reviewPeopleCluster(before,
            PeopleReviewAction.NameCluster(group.id, ada, "Ada"))!!
        assertFalse(nativePersonAlbumGone(true, named, ada))
        // Undo of the only naming: the live snapshot no longer has Ada.
        val undone = reviewPeopleCluster(named, PeopleReviewAction.Undo)!!
        assertTrue(nativePersonAlbumGone(true, undone, ada))
        // Revoke drops the opt-in.
        assertTrue(nativePersonAlbumGone(false, named, ada))
        assertTrue(nativePersonAlbumGone(false, null, ada))
        // No snapshot yet (loading, pending refresh): stay open.
        assertFalse(nativePersonAlbumGone(true, null, ada))
    }
}
