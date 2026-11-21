package nl.atlas.gallery.testkit

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage

/**
 * Failure-only diagnostic capture of the exact currently visible viewport.
 *
 * Unlike captureWhole(), this deliberately does not scroll or wait: a timeout
 * caused by a clipped tile or moving header would lose its evidence if the
 * capture helper changed the layout first. Never use for regular UI assertions
 * or acceptance screenshots, which must use captureWhole().
 */
fun SemanticsNodeInteraction.captureTimeoutViewport(): ImageBitmap = captureToImage()
