package nl.atlas.gallery.testkit

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Emulator tests run on two clocks (docs/testing/EMULATOR_TESTS.md, rule 3).
 *
 * VIRTUAL: everything inside Compose. delay() in a LaunchedEffect, animations
 * and snackbar timeouts only move when the test clock is advanced. Sleeping
 * does not advance it, and waitForIdle() does not run a pending delay().
 *
 * REAL: everything outside Compose. The keyboard, image decodes, databases,
 * MediaStore and system windows take wall-clock time.
 *
 * Use this to let product timers elapse. Use waitForNode/waitUntil for real
 * work. Never use Thread.sleep to wait for either.
 */
fun ComposeTestRule.advanceVirtualTime(millis: Long) {
    mainClock.advanceTimeBy(millis)
    waitForIdle()
}
