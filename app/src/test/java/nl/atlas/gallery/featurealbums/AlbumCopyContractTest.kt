package nl.atlas.gallery.featurealbums

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class AlbumCopyContractTest {
    private val destination = AlbumCopyDestination.Existing("external_primary", "Pictures/Trip/")
    private val source = AlbumCopySource(
        uri = "content://media/external/images/media/12",
        displayName = "image.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 3,
        volumeName = "external_primary",
        accessEpoch = 7L
    )

    private class FakeStore : AlbumCopyStore {
        var rowsCreated = 0
        var deleted = 0
        var published = 0
        var openSource = 0
        var originalTouched = false
        var permissionEpoch = 7L
        var writable = true
        var sourceBytes = byteArrayOf(1, 2, 3)
        var sourceError = false
        var destinationError = false
        var insertError = false
        var publishError = false
        var publishThenThrow = false
        var closeError = false
        var nameConflict = false
        var deleteError = false
        var written = ByteArrayOutputStream()
        override fun currentAccessEpoch(): Long = permissionEpoch
        override fun destinationWritable(volumeName: String, relativePath: String): Boolean = writable
        override fun nameExists(volumeName: String, relativePath: String, displayName: String): Boolean = nameConflict
        override fun createPending(
            volumeName: String, relativePath: String, displayName: String, mimeType: String
        ): String? {
            if (insertError) return null
            rowsCreated++
            return "content://media/new/$rowsCreated"
        }
        override fun openSource(uri: String): InputStream? {
            openSource++
            if (sourceError) throw IOException("source denied")
            return ByteArrayInputStream(sourceBytes)
        }
        override fun openDestination(uri: String): OutputStream? {
            if (destinationError) throw IOException("destination denied")
            return object : OutputStream() {
                override fun write(b: Int) { written.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { written.write(b, off, len) }
                override fun close() { if (closeError) throw IOException("close failure") }
            }
        }
        override fun publish(uri: String) {
            if (publishError) throw IOException("publish failure")
            published++
            if (publishThenThrow) throw IOException("committed then callback failed")
        }
        override fun deletePending(uri: String) {
            if (deleteError) throw IOException("cleanup denied")
            deleted++
        }
    }

    @Test fun destinationPickerOffersExactExistingAlbumsPlusNewAndCancellation() {
        val route = AlbumCopyPicker(
            albums = listOf(
                AlbumCopyAlbum("external_primary", "DCIM/Camera/", "Camera"),
                AlbumCopyAlbum("ABCD-1234", "DCIM/Camera/", "Camera")
            ), accessEpoch = 7
        )
        assertEquals(3, route.choices.size)
        assertNotEquals(route.choices[0].destination, route.choices[1].destination)
        assertTrue(route.choices.last().destination is AlbumCopyDestination.NewAlbum)
        assertEquals(AlbumCopyPickerOutcome.Cancelled, route.cancel())
        assertEquals(AlbumCopyPickerOutcome.Invalid, route.choose(
            AlbumCopyDestination.Existing("external_primary", "Pictures/Unknown/"), 7))
        assertEquals(AlbumCopyPickerOutcome.Stale, route.choose(
            route.choices.first().destination, 8))
    }

    @Test fun validatesNewAlbumNameAndDestination() {
        val picker = AlbumCopyPicker(emptyList(), 7L)
        assertEquals(AlbumCopyPickerOutcome.Invalid, picker.choose(
            AlbumCopyDestination.NewAlbum("external_primary", "Pictures/", "../escape"), 7))
        assertEquals(AlbumCopyPickerOutcome.Invalid, picker.choose(
            AlbumCopyDestination.NewAlbum("external_primary", "Pictures/", "   "), 7))
        assertEquals(AlbumCopyPickerOutcome.Invalid, picker.choose(
            AlbumCopyDestination.NewAlbum("external_primary", "Pictures/", "Bad/name"), 7))
        assertEquals(AlbumCopyPickerOutcome.Invalid, picker.choose(
            AlbumCopyDestination.NewAlbum("external_primary", "../", "Trip"), 7))
        assertEquals(AlbumCopyPickerOutcome.Selected(
            AlbumCopyDestination.Existing("external_primary", "Pictures/Trip/")
        ), picker.choose(AlbumCopyDestination.NewAlbum("external_primary", "Pictures/", "Trip"), 7))
    }

    @Test fun copyingLeavesOriginalUntouchedAndPublishesOnlyCompletedRow() {
        val fake = FakeStore()
        assertEquals(AlbumCopyResult.Copied("content://media/new/1"),
            copyToAlbum(source, destination, fake))
        assertArrayEquals(byteArrayOf(1, 2, 3), fake.written.toByteArray())
        assertEquals(1, fake.rowsCreated)
        assertEquals(1, fake.published)
        assertEquals(0, fake.deleted)
        assertFalse(fake.originalTouched)
    }

    @Test fun refusalAndCancelNeverCreateRows() {
        val fake = FakeStore()
        assertEquals(AlbumCopyResult.Cancelled, copyToAlbum(source, null, fake))
        assertEquals(0, fake.rowsCreated)
        fake.writable = false
        assertEquals(AlbumCopyResult.DestinationUnavailable,
            copyToAlbum(source, destination, fake))
        assertEquals(0, fake.rowsCreated)
        fake.writable = true
        fake.permissionEpoch = 8
        assertEquals(AlbumCopyResult.StaleAccess, copyToAlbum(source, destination, fake))
        assertEquals(0, fake.rowsCreated)
    }

    @Test fun duplicateFileNamesFailClosedRatherThanOverwrite() {
        val fake = FakeStore().apply { nameConflict = true }
        assertEquals(AlbumCopyResult.DuplicateName, copyToAlbum(source, destination, fake))
        assertEquals(0, fake.rowsCreated)
    }

    @Test fun zeroByteAndStreamFailureDeleteOnlyPendingRow() {
        for (kind in listOf("zero", "source", "destination", "close")) {
            val fake = FakeStore()
            when (kind) {
                "zero" -> fake.sourceBytes = byteArrayOf()
                "source" -> fake.sourceError = true
                "destination" -> fake.destinationError = true
                "close" -> fake.closeError = true
            }
            assertEquals(kind, AlbumCopyResult.CopyFailed, copyToAlbum(source, destination, fake))
            assertEquals(kind, 1, fake.rowsCreated)
            assertEquals(kind, 1, fake.deleted)
            assertEquals(kind, 0, fake.published)
            assertFalse(kind, fake.originalTouched)
        }
    }

    @Test fun publicationExceptionNeverDeletesPotentiallyVisibleCopy() {
        for (committed in listOf(false, true)) {
            val fake = FakeStore().apply {
                publishError = !committed
                publishThenThrow = committed
            }
            assertEquals(AlbumCopyResult.PublicationUncertain("content://media/new/1"),
                copyToAlbum(source, destination, fake))
            assertEquals(1, fake.rowsCreated)
            assertEquals(if (committed) 1 else 0, fake.published)
            assertEquals(0, fake.deleted)
            assertFalse(fake.originalTouched)
        }
    }

    @Test fun cleanupFailureIsExplicitAndNeverPresentedAsFullyCleaned() {
        val fake = FakeStore().apply { sourceError = true; deleteError = true }
        assertEquals(AlbumCopyResult.CleanupFailed("content://media/new/1"),
            copyToAlbum(source, destination, fake))
        assertEquals(0, fake.published)
        assertFalse(fake.originalTouched)
    }

    @Test fun epochChangeDuringTransferCannotPublish() {
        val fake = FakeStore()
        val store = object : AlbumCopyStore by fake {
            override fun openDestination(uri: String): OutputStream? {
                fake.permissionEpoch = 8
                return fake.openDestination(uri)
            }
        }
        assertEquals(AlbumCopyResult.StaleAccess, copyToAlbum(source, destination, store))
        assertEquals(1, fake.deleted)
        assertEquals(0, fake.published)
    }
}
