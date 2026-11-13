package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

class GridAnchorCommitTest {
    private val oldSource = List(150_000) { "media:$it" }
    private val epoch = GridCatalogIdentity(oldSource)
    private val command = GridAnchorCommit(epoch, "photos", GridDensity.SMALL, 393, 810,
        oldSource[123_456], 123_456, GridScrollTarget(123_452, 40, false))
    private fun allows(
        source: GridCatalogIdentity = epoch,
        view: String = "photos",
        density: GridDensity = GridDensity.SMALL,
        width: Int = 393,
        height: Int = 810,
        key: String? = oldSource[123_456]
    ): Boolean = command.isCurrent(source, view, density, width, height, key)

    @Test fun originalImmutableCatalogueAcceptsItsOwnAnchor() {
        assertTrue(allows())
        assertEquals(epoch, GridCatalogIdentity(oldSource))
    }

    @Test fun valueEqual150kReplacementAndPermissionGrantChangeRejectOldAnchor() {
        val sameValues = oldSource.toList()
        assertTrue(oldSource == sameValues)
        assertFalse(allows(source = GridCatalogIdentity(sameValues)))
        assertFalse(allows(source = GridCatalogIdentity(emptyList<String>())))
    }

    @Test fun changingViewOrDensityMustCancelDeferredScroll() {
        assertFalse(allows(view = "search"))
        assertFalse(allows(density = GridDensity.MEDIUM))
        assertFalse(allows(density = GridDensity.LARGE))
    }

    @Test fun rotationKeyboardInsetsAndViewportReflowRejectOldGeometry() {
        assertFalse(allows(width = 392))
        assertFalse(allows(width = 810, height = 393))
        assertFalse(allows(height = 700))
    }

    @Test fun changedOrAbsentMediaAtOriginalIndexRejectsOldAnchor() {
        assertFalse(allows(key = "media:0"))
        assertFalse(allows(key = null))
    }

    @Test fun invalidAnchorFieldsNeverAuthorizeScrolling() {
        assertFalse(command.copy(mediaKey = " ").isCurrent(epoch, "photos",
            GridDensity.SMALL, 393, 810, " "))
        assertFalse(command.copy(destination = GridScrollTarget(-1, 0, false))
            .isCurrent(epoch, "photos", GridDensity.SMALL, 393, 810, oldSource[123_456]))
        assertFalse(command.copy(destination = GridScrollTarget(5, -1, false))
            .isCurrent(epoch, "photos", GridDensity.SMALL, 393, 810, oldSource[123_456]))
    }

    @Test fun reusedImmutableListAndIdenticalUriRejectAfterScopeRepublication() {
        val old = GridCatalogIdentity(oldSource, 21L)
        val command = this.command.copy(publication = old)
        assertTrue(command.isCurrent(GridCatalogIdentity(oldSource, 21L),
            "photos", GridDensity.SMALL, 393, 810, oldSource[123_456]))
        assertFalse(command.isCurrent(GridCatalogIdentity(oldSource, 22L),
            "photos", GridDensity.SMALL, 393, 810, oldSource[123_456]))
    }
}
