package nl.atlas.gallery

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Media3 players are mounted only after the viewer settles. This is the
 * pure lifecycle event policy, not proof of physical audio-output behavior.
 * An Android emulator must separately verify the actual player lifecycle.
 */
class ViewerVideoLifecycleTest {
    @Test fun leavingForegroundPausesAndReturningNeverAutoStartsVideo() {
        for (event in Lifecycle.Event.entries) {
            val expected = event == Lifecycle.Event.ON_PAUSE ||
                event == Lifecycle.Event.ON_STOP
            assertEquals("Wrong background playback handling for $event",
                expected, viewerVideoMustPause(event))
        }
    }
}
