package nl.atlas.gallery.featureface

import nl.atlas.gallery.FaceCropGeometry
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * VS-V1c (#468) JVM evidence: photos and videos spend separate, named
 * catalogue budgets, so a library with more videos than the video budget
 * still catalogues every photo; the directory admits exactly the rows the
 * publisher admits; and the pure geometry for video face crops. Invented
 * MediaStore rows only; no media is decoded.
 */
class VideoFaceBudgetTest {
    private val epoch = "grant-epoch-budget"

    private fun row(id: Long, video: Boolean, provider: String? = "synthetic-provider-1") =
        FaceVisibleMedia(
            uri = "content://media/external_primary/${if (video) "video" else "images"}/media/$id",
            volumeName = "external_primary", isVideo = video, id = id,
            providerVersion = provider, generationModified = 10 + id,
            sizeBytes = 1_000L + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
            widthPixels = 1920, heightPixels = 1080, orientationDegrees = 0,
            durationMs = if (video) 4_000L else 0L
        )

    @Test fun budgetsAreNamedAndFitTheDirectoryIndex() {
        assertEquals(500_000, FaceCatalogLimits.MAX_PHOTOS)
        assertEquals(100_000, FaceCatalogLimits.MAX_VIDEOS)
        assertTrue(FaceCatalogLimits.MAX_PHOTOS + FaceCatalogLimits.MAX_VIDEOS <= 1 shl 20)
    }

    @Test fun videosPastTheirBudgetAreDeferredAndPhotosAllAdmitted() {
        val admission = FaceCatalogAdmission(maxPhotoRows = 10, maxVideoRows = 2)
        // Videos first, then photos: the video overflow must not touch photos.
        val inventory = (1L..5L).map { row(it, video = true) } +
            row(6, video = true, provider = null) + // incomplete: ignored, not deferred
            (7L..10L).map { row(it, video = false) }
        val admitted = inventory.mapNotNull { admission.admit(it) }
        assertEquals(6, admitted.size)
        assertEquals(4, admission.photoRows)
        assertEquals(2, admission.videoRows)
        assertEquals(3, admission.deferredVideoRows)
        assertEquals(1, admission.ignoredVideoRows)
        assertEquals(4, admission.leftOutVideoRows)
        // The first complete videos in inventory order win: deterministic.
        assertEquals(listOf(1L, 2L).map { row(it, true).toDurableFaceKey() },
            admitted.take(2))
    }

    @Test fun zeroVideoBudgetCataloguesPhotosOnly() {
        val admission = FaceCatalogAdmission(maxPhotoRows = 5, maxVideoRows = 0)
        assertNull(admission.admit(row(1, video = true)))
        assertNotNull(admission.admit(row(2, video = false)))
        assertEquals(1, admission.deferredVideoRows)
    }

    /** #508/#536: over-cap photos are deferred; incomplete metadata in-cap is weak. */
    @Test fun photoPastCapIsDeferredAndIncompletePhotoUsesWeakRevision() {
        val admission = FaceCatalogAdmission(maxPhotoRows = 1, maxVideoRows = 1)
        assertNotNull(admission.admit(row(1, video = false)))
        assertNull(admission.admit(row(2, video = false)))
        assertEquals(1, admission.photoRows)
        assertEquals(1, admission.deferredPhotoRows)
        val weakAdmission = FaceCatalogAdmission()
        val weak = row(3, video = false, provider = null)
        assertNotNull(weakAdmission.admit(weak))
        assertEquals(1, weakAdmission.photoRows)
        assertEquals(1, weakAdmission.weakPhotoRows)
        assertEquals(0, weakAdmission.ignoredPhotoRows)
        assertEquals(1, weakAdmission.missingPhotoFields["providerVersion"])
    }

    /** #508: publisher and directory keep the same newest photos past the cap. */
    @Test fun directoryDefersTheSamePhotosAsThePublisherCap() {
        val photos = (1L..6L).map { row(it, video = false) }
        val directory = FaceAuthorizedMediaDirectory.build(epoch, photos, { it },
            activeEpoch = { epoch }, providerSnapshotCurrent = { true }, maxPhotoRows = 4)
        assertEquals(4, directory.mediaCount)
        assertTrue(directory.sourceRevisionCurrent(photos[3].toDurableFaceKey()))
        assertFalse(directory.sourceRevisionCurrent(photos[4].toDurableFaceKey()))
    }

    /** #508 review: an incomplete photo past the cap is deferred, not a reason to refuse. */
    @Test fun incompletePhotoPastTheCapIsDeferredNotAborted() {
        val newest = (1L..4L).map { row(it, video = false) }
        val oldBroken = row(5, video = false, provider = null)
        val admission = FaceCatalogAdmission(maxPhotoRows = 4)
        assertEquals(4, (newest + oldBroken).mapNotNull { admission.admit(it) }.size)
        assertEquals(1, admission.deferredPhotoRows)
        val directory = FaceAuthorizedMediaDirectory.build(epoch, newest + oldBroken, { it },
            activeEpoch = { epoch }, providerSnapshotCurrent = { true }, maxPhotoRows = 4)
        assertEquals(4, directory.mediaCount)
        // Inside the window it is admitted using its weak revision.
        val wider = FaceCatalogAdmission(maxPhotoRows = 5)
        assertEquals(5, (newest + oldBroken).mapNotNull { wider.admit(it) }.size)
        assertEquals(1, wider.weakPhotoRows)
        assertEquals(1, wider.missingPhotoFields["providerVersion"])
        assertEquals(0, wider.deferredPhotoRows)
        val widerDirectory = FaceAuthorizedMediaDirectory.build(epoch, newest + oldBroken, { it },
            activeEpoch = { epoch }, providerSnapshotCurrent = { true }, maxPhotoRows = 5)
        assertEquals(5, widerDirectory.mediaCount)
        assertEquals(oldBroken.uri, widerDirectory.authorizedUri(oldBroken.toDurableFaceKey()))
    }

    @Test fun budgetsAreBounded() {
        for ((photos, videos) in listOf(0 to 1, FaceCatalogLimits.MAX_PHOTOS + 1 to 1,
                1 to -1, 1 to FaceCatalogLimits.MAX_VIDEOS + 1)) {
            try {
                FaceCatalogAdmission(photos, videos); fail("$photos/$videos accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    /**
     * The #479 receipt's case at full scale: one video more than the budget,
     * plus photos after them. The directory builds (before VS-V1c the
     * publisher threw and the whole face scan, photos included, stopped),
     * resolves every photo and the first MAX_VIDEOS videos, and refuses the
     * deferred video like any source outside the grant.
     */
    @Test fun moreVideosThanTheBudgetNeverBlockPhotosInTheDirectory() {
        val videos = FaceCatalogLimits.MAX_VIDEOS + 1
        val inventory = ArrayList<FaceVisibleMedia>(videos + 3)
        for (id in 1L..videos.toLong()) inventory.add(row(id, video = true))
        val photos = (1L..3L).map { row(1_000_000L + it, video = false) }
        inventory.addAll(photos)
        val directory = FaceAuthorizedMediaDirectory.build(epoch, inventory, { it },
            activeEpoch = { epoch }, providerSnapshotCurrent = { true })
        assertEquals(FaceCatalogLimits.MAX_VIDEOS + photos.size, directory.mediaCount)
        for (photo in photos) {
            assertEquals(photo.uri, directory.authorizedUri(photo.toDurableFaceKey()))
        }
        assertEquals(inventory[0].uri, directory.authorizedUri(inventory[0].toDurableFaceKey()))
        val lastKept = inventory[FaceCatalogLimits.MAX_VIDEOS - 1]
        assertTrue(directory.sourceRevisionCurrent(lastKept.toDurableFaceKey()))
        val deferred = inventory[FaceCatalogLimits.MAX_VIDEOS]
        try {
            directory.authorizedUri(deferred.toDurableFaceKey()); fail("deferred video resolved")
        } catch (_: SecurityException) { }
    }

    @Test fun directoryAdmitsExactlyThePublisherRows() {
        val inventory = listOf(row(1, true), row(2, false), row(3, true, provider = null),
            row(4, true), row(5, false))
        val admission = FaceCatalogAdmission()
        val catalogued = inventory.mapNotNull { admission.admit(it) }.toSet()
        val directory = FaceAuthorizedMediaDirectory.build(epoch, inventory, { it },
            activeEpoch = { epoch }, providerSnapshotCurrent = { true })
        assertEquals(catalogued.size, directory.mediaCount)
        for (item in inventory) {
            val key = item.durableFaceKeyOrNull() ?: continue
            assertEquals(key in catalogued, directory.sourceRevisionCurrent(key))
        }
    }

    // ---- #538: one MediaStore item listed twice -----------------------------

    /**
     * #538 failed-before/passed-after: the store already stages a repeated
     * (same key, same revision) row once, but the directory used to refuse
     * the whole build with "Duplicate canonical media identity", which the
     * People host shows as "The private face index could not be prepared".
     * Now the repeat resolves through the first row and nothing else moves.
     */
    @Test fun repeatedIdenticalRowsResolveOnceInsteadOfRefusingTheBuild() {
        val photo = row(41, video = false)
        val video = row(42, video = true)
        val other = row(43, video = false)
        val inventory = listOf(photo, video, photo.copy(), other, video.copy(), photo.copy())
        val directory = FaceAuthorizedMediaDirectory.build(epoch, inventory, { it },
            activeEpoch = { epoch }, providerSnapshotCurrent = { true })
        assertEquals(3, directory.mediaCount)
        assertEquals(3, directory.repeatedRows)
        assertEquals(photo.uri, directory.authorizedUri(photo.toDurableFaceKey()))
        assertEquals(video.uri, directory.authorizedUri(video.toDurableFaceKey()))
        assertEquals(other.uri, directory.authorizedUri(other.toDurableFaceKey()))
    }

    /** Same canonical key, different revision: still a contradiction, still refused. */
    @Test fun repeatedKeyWithAnotherRevisionStillRefusesTheBuild() {
        val photo = row(51, video = false)
        val edited = photo.copy(sizeBytes = photo.sizeBytes + 1)
        assertEquals(photo.toDurableFaceKey().canonicalMediaKey,
            edited.toDurableFaceKey().canonicalMediaKey)
        for (inventory in listOf(listOf(photo, edited), listOf(photo, photo.copy(), edited),
                listOf(photo, row(52, false), edited))) {
            try {
                FaceAuthorizedMediaDirectory.build(epoch, inventory, { it },
                    activeEpoch = { epoch }, providerSnapshotCurrent = { true })
                fail("contradictory revisions were published")
            } catch (e: IllegalArgumentException) {
                assertEquals("Contradictory duplicate canonical media identity", e.message)
            }
        }
    }

    /**
     * No wrong source: the kept row is the first in inventory order, a stale
     * revision of a repeated item never resolves, and an item outside the
     * inventory never resolves through a repeat.
     */
    @Test fun repeatedRowsNeverResolveAStaleRevisionOrAnotherItem() {
        val first = row(61, video = false)
        val inventory = listOf(first, row(62, false), first.copy())
        val directory = FaceAuthorizedMediaDirectory.build(epoch, inventory, { it },
            activeEpoch = { epoch }, providerSnapshotCurrent = { true })
        assertTrue(directory.sourceRevisionCurrent(first.toDurableFaceKey()))
        assertFalse(directory.sourceRevisionCurrent(
            first.copy(sizeBytes = 1).toDurableFaceKey()))
        assertFalse(directory.sourceRevisionCurrent(row(63, false).toDurableFaceKey()))
        assertEquals(0, FaceAuthorizedMediaDirectory.build(epoch, listOf(first, row(62, false)),
            { it }, activeEpoch = { epoch }, providerSnapshotCurrent = { true }).repeatedRows)
    }

    /** The photo cap still counts each listed row, identically on both sides. */
    @Test fun repeatedRowsSpendThePhotoCapLikeThePublisherAdmission() {
        val a = row(71, false)
        val b = row(72, false)
        val inventory = listOf(a, a.copy(), b)
        val admission = FaceCatalogAdmission(maxPhotoRows = 2)
        val admitted = inventory.mapNotNull { admission.admit(it) }
        assertEquals(2, admitted.size)
        val directory = FaceAuthorizedMediaDirectory.build(epoch, inventory, { it },
            activeEpoch = { epoch }, providerSnapshotCurrent = { true }, maxPhotoRows = 2)
        for (item in inventory) {
            val key = item.toDurableFaceKey()
            assertEquals(key in admitted.toSet(), directory.sourceRevisionCurrent(key))
        }
        assertEquals(1, directory.mediaCount)
    }

    // ---- Video face crop geometry (pure) -----------------------------------

    private val box = IndexedFaceBox(0.4f, 0.3f, 0.6f, 0.5f)

    @Test fun rotatedVideosSwapTheirFrameSize() {
        assertEquals(1920 to 1080, FaceCropGeometry.orientedSize(1920, 1080, 0))
        assertEquals(1080 to 1920, FaceCropGeometry.orientedSize(1920, 1080, 90))
        assertEquals(1920 to 1080, FaceCropGeometry.orientedSize(1920, 1080, 180))
        assertEquals(1080 to 1920, FaceCropGeometry.orientedSize(1920, 1080, 270))
        assertEquals(1080 to 1920, FaceCropGeometry.orientedSize(1920, 1080, -90))
    }

    @Test fun frameRequestIsBoundedAndCropFollowsTheReturnedFrame() {
        val request = FaceCropGeometry.frameRequest(3840, 2160, box)!!
        assertTrue(maxOf(request.decodeWidth, request.decodeHeight) <=
            FaceCropGeometry.MAX_DECODE_EDGE)
        // The retriever may return a different size; the crop is relative to it.
        val crop = FaceCropGeometry.frameCrop(480, 270, box)!!
        assertEquals(480, crop.decodeWidth)
        assertEquals(270, crop.decodeHeight)
        // Box 0.4..0.6 x 0.3..0.5 plus a 25% margin: 0.35..0.65 x 0.25..0.55,
        // i.e. 168..312 x 67.5..148.5 px, rounded outwards (float: +-1 px).
        assertTrue(crop.left in 167..168 && crop.right in 312..313)
        assertTrue(crop.top in 66..67 && crop.bottom in 149..150)
        assertNull(FaceCropGeometry.frameCrop(0, 270, box))
    }
}
