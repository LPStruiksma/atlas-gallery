package nl.atlas.gallery.featuredesign

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * #448: the bottom padding MainActivity's scaffold already applied above the
 * current screen: the floating tab pill, its margins and the navigation bar
 * (about 350 px on the S24+). 0 on screens that draw under the bar.
 *
 * The keyboard inset is measured from the bottom of the window, so it already
 * covers that area. A screen that adds the full keyboard inset on top of this
 * padding leaves a dead band above the keyboard (350 px on the Unknown faces
 * group sheet). [atlasImePadding] subtracts it first.
 */
val LocalAtlasScaffoldBottomPadding = compositionLocalOf { 0.dp }

/**
 * #448: [imePadding] for content inside MainActivity's scaffold. It marks the
 * scaffold's applied bottom padding as consumed before the keyboard inset is
 * applied, as Compose's insets guidance asks ("consume what you applied"), so
 * the content ends exactly at the keyboard's top edge. With the keyboard closed
 * the result is 0, as before. Outside the scaffold (tests, full-screen routes)
 * the local is 0 and this is plain [imePadding].
 */
fun Modifier.atlasImePadding(): Modifier = composed {
    atlasImePadding(LocalAtlasScaffoldBottomPadding.current)
}

/** [atlasImePadding] with the already-applied bottom padding passed explicitly. */
fun Modifier.atlasImePadding(appliedBottom: Dp): Modifier =
    consumeWindowInsets(PaddingValues(bottom = appliedBottom)).imePadding()
