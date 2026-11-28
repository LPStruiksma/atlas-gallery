package nl.atlas.gallery.featureguest

import androidx.compose.runtime.compositionLocalOf

/**
 * GUEST-1 (#589): what the owner's screens may ask of guest view. Selection
 * More and viewer More call [start] with the chosen items in tap order; after a
 * confirmed exit the activity hands back [returnViewerUri] once, so an entry
 * from the viewer reopens that photo for the owner.
 */
class GuestPort(
    val start: (selectedInTapOrder: List<GuestCandidate>, returnViewerUri: String?) -> Unit,
    val returnViewerUri: String?,
    val consumeReturn: () -> Unit
)

val LocalGuestPort = compositionLocalOf<GuestPort?> { null }
