package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DAILY-3: executable name-parity and candidate-probe bounds; no MediaStore writes. */
class AlbumTransferLargeBatchTest {
    private val target = AlbumTransferTarget("external_primary", "Pictures/Trip/", "Trip")

    private fun record(
        id: Long,
        name: String,
        path: String = "DCIM/Camera/",
        volume: String = "external_primary"
    ) = MediaRecord(
        id, "content://media/$volume/images/media/$id", name, path, 100L, 0L, false,
        volumeName = volume
    )

    @Test fun tenThousandRepeatedNamesSkipOccupiedSuffixesWithBoundedCandidateLookups() {
        val sources = (1..10_000).map { record(it.toLong(), "IMG.jpg") }
        val targetNames = (1..4_000).mapTo(mutableSetOf("img.JPG")) { "img($it).JpG" }
        var candidateLookups = 0

        val plan = planAlbumTransferWithLookupCounter(
            AlbumTransferMode.COPY, target, sources, targetNames
        ) { candidateLookups++ }

        assertEquals(10_000, plan.steps.size)
        assertEquals("IMG(4001).jpg", plan.steps.first().targetName)
        assertEquals("IMG(14000).jpg", plan.steps.last().targetName)
        assertEquals(sources, plan.steps.map { it.source })
        assertEquals(10_000, plan.steps.map { it.targetName.lowercase() }.toSet().size)
        assertTrue(plan.alreadyThere.isEmpty())
        assertTrue(plan.otherVolume.isEmpty())
        // Original-name probe + at most one suffix probe per source, not ~100m retries.
        assertTrue("Candidate lookups: $candidateLookups", candidateLookups <= 22_000)
        assertEquals(4_001, targetNames.size)
        assertEquals("IMG.jpg", sources.first().name)
    }

    @Test fun numberedNamesAndHolesKeepSourceOrderAndCaseInsensitiveCollisions() {
        val names = listOf("IMG.jpg", "img.JPG", "IMG.jpg", "IMG.JPG", "IMG(2).jpg", "IMG.jpg")
        val plan = planAlbumTransfer(
            AlbumTransferMode.COPY, target,
            names.mapIndexed { i, name -> record((i + 1).toLong(), name) },
            setOf("img.jpg", "img(1).JPG", "IMG(3).jpg", "img(6).jpg")
        )

        assertEquals(
            listOf("IMG(2).jpg", "img(4).JPG", "IMG(5).jpg",
                "IMG(7).JPG", "IMG(8).jpg", "IMG(9).jpg"),
            plan.steps.map { it.targetName }
        )
    }

    @Test fun extensionsHiddenNamesSpacesAndLeadingZeroSuffixesStayCompatible() {
        val names = listOf(
            "PHOTO.archive.jpg", "PHOTO.archive.jpg", ".nomedia", ".nomedia",
            "IMG (1).JPG", "IMG(009).JPG", "README", "README", "clip."
        )
        val plan = planAlbumTransfer(
            AlbumTransferMode.COPY, target,
            names.mapIndexed { i, name -> record((i + 1).toLong(), name) },
            setOf("PHOTO.archive.jpg", ".NOMEDIA", "IMG (1).jpg",
                "IMG(2).JPG", "IMG(009).jpg", "readme", "clip.")
        )

        assertEquals(
            listOf(
                "PHOTO.archive(1).jpg", "PHOTO.archive(2).jpg",
                ".nomedia(1)", ".nomedia(2)", "IMG(3).JPG",
                "IMG(10).JPG", "README(1)", "README(2)", "clip(1)."
            ),
            plan.steps.map { it.targetName }
        )
    }

    @Test fun copyAndMoveKeepAlreadyThereAndCrossVolumeAccounting() {
        val here = record(1, "SHARED.jpg", "Pictures/Trip/")
        val alias = record(2, "SHARED.jpg", volume = "external")
        val foreign = record(3, "SHARED.jpg", volume = "1234-abcd")
        val camera = record(4, "SHARED.jpg")
        val records = listOf(here, alias, foreign, camera)
        val existing = setOf("shared.JPG")

        val move = planAlbumTransfer(AlbumTransferMode.MOVE, target, records, existing)
        assertEquals(listOf(alias, camera), move.steps.map { it.source })
        assertEquals(listOf("SHARED(1).jpg", "SHARED(2).jpg"), move.steps.map { it.targetName })
        assertEquals(listOf(here), move.alreadyThere)
        assertEquals(listOf(foreign), move.otherVolume)

        val copy = planAlbumTransfer(AlbumTransferMode.COPY, target, records, existing)
        assertEquals(records, copy.steps.map { it.source })
        assertEquals(
            listOf("SHARED(1).jpg", "SHARED(2).jpg", "SHARED(3).jpg", "SHARED(4).jpg"),
            copy.steps.map { it.targetName }
        )
        assertTrue(copy.alreadyThere.isEmpty())
        assertTrue(copy.otherVolume.isEmpty())
        assertEquals(setOf("shared.JPG"), existing)
        assertEquals("SHARED.jpg", here.name)
    }
}
