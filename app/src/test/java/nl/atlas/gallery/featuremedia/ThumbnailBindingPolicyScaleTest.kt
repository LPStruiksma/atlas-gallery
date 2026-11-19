package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic-only policy proof. No Android media, device timing, or real identifiers. */
class ThumbnailBindingPolicyScaleTest {
    private fun identity(id: Long, video: Boolean = false, volume: String = "external_primary") =
        MediaIdentity(
            "content://synthetic/${if (video) "video" else "image"}/$id",
            volume,
            video,
            id
        )

    /** Independent truth table for the published stage precedence. */
    @Test fun allSixteenGestureSettledPreviewSharpCombinationsMatchContract() {
        for (mask in 0 until 16) {
            val fast = mask and 1 != 0
            val settled = mask and 2 != 0
            val preview = mask and 4 != 0
            val sharp = mask and 8 != 0
            val expected = if (fast || !settled || sharp) {
                ThumbnailStage.MEMORY_OR_PLACEHOLDER
            } else if (preview) {
                ThumbnailStage.VISIBLE_SHARPEN
            } else {
                ThumbnailStage.VISIBLE_DECODE
            }
            assertEquals("mask=$mask", expected, chooseThumbnailStage(fast, settled, preview, sharp))
        }
    }

    @Test fun admissionRejectsEveryStaleOrRevokedDimension() {
        val completed = ThumbnailBindingTicket(3L, identity(17), 4L, 9L)
        assertTrue(mayPublishThumbnailResult(completed, completed.copy(), true))
        assertFalse(mayPublishThumbnailResult(completed, null, true))
        assertFalse(mayPublishThumbnailResult(completed, completed, false))
        assertFalse(mayPublishThumbnailResult(completed, completed.copy(slot = 4L), true))
        assertFalse(mayPublishThumbnailResult(completed, completed.copy(identity = identity(18)), true))
        assertFalse(mayPublishThumbnailResult(completed, completed.copy(identity = identity(17, video = true)), true))
        assertFalse(mayPublishThumbnailResult(completed, completed.copy(identity = identity(17, volume = "synthetic_sd")), true))
        assertFalse(mayPublishThumbnailResult(completed, completed.copy(catalogueEpoch = 5L), true))
        assertFalse(mayPublishThumbnailResult(completed, completed.copy(sequence = 10L), true))
        assertFalse(mayPublishThumbnailResult(completed.copy(catalogueEpoch = -1L), completed.copy(catalogueEpoch = -1L), true))
        assertFalse(mayPublishThumbnailResult(completed.copy(sequence = -1L), completed.copy(sequence = -1L), true))
        assertTrue(mayPublishThumbnailResult(completed.copy(catalogueEpoch = 0L, sequence = 0L), completed.copy(catalogueEpoch = 0L, sequence = 0L), true))
    }

    /** Admission parity with existing pure-policy expression, including negative tokens. */
    @Test fun stageAndAdmissionParityAcrossDeterministicInputs() {
        val base = ThumbnailBindingTicket(0L, identity(0), 0L, 0L)
        val candidates = listOf(
            null,
            base,
            base.copy(slot = 1L),
            base.copy(sequence = 1L),
            base.copy(catalogueEpoch = 1L),
            base.copy(identity = identity(1)),
            base.copy(sequence = -1L),
            base.copy(catalogueEpoch = -1L)
        )
        for (completed in candidates.filterNotNull()) {
            for (current in candidates) {
                for (granted in listOf(false, true)) {
                    val expected = granted && current == completed &&
                        completed.catalogueEpoch >= 0L && completed.sequence >= 0L
                    assertEquals(expected, mayPublishThumbnailResult(completed, current, granted))
                }
            }
        }
    }

    @Test fun twoHundredFiftyThousandCompletionsRetainOnlyVisibleCellTickets() {
        val visibleCells = 12
        val librarySize = 250_000
        // Only visible cells have current state, never one ticket per catalogue entry.
        val live = arrayOfNulls<ThumbnailBindingTicket>(visibleCells)
        var accepted = 0
        var staleRejected = 0
        var revokedRejected = 0
        for (ordinal in 0 until librarySize) {
            val slot = ordinal % visibleCells
            val current = ThumbnailBindingTicket(
                slot.toLong(), identity(ordinal.toLong()), (ordinal / 50_000).toLong(), ordinal.toLong()
            )
            val previous = live[slot]
            live[slot] = current
            assertTrue(mayPublishThumbnailResult(current, live[slot], true))
            accepted++
            if (previous != null) {
                assertFalse(mayPublishThumbnailResult(previous, live[slot], true))
                staleRejected++
            }
            if (ordinal % 97 == 0) {
                assertFalse(mayPublishThumbnailResult(current, live[slot], false))
                revokedRejected++
            }
            assertEquals(visibleCells, live.size)
        }
        assertEquals(librarySize, accepted)
        assertEquals(librarySize - visibleCells, staleRejected)
        assertEquals((librarySize - 1) / 97 + 1, revokedRejected)
        assertEquals(visibleCells, live.count { it != null })
        for (slot in live.indices) {
            assertFalse(mayPublishThumbnailResult(live[slot]!!, null, true))
        }
    }
}
