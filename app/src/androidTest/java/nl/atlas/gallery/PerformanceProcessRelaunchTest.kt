package nl.atlas.gallery

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.testkit.awaitThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1021: the host runs these phases in separate instrumentation processes with
 * adb am force-stop between them. The ordinary sharded suite skips the phases.
 * The host starts with disposable, empty emulator app data.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceProcessRelaunchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val version get() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    private val marker get() = context.getSharedPreferences("perf_1021_fixture", Context.MODE_PRIVATE)
    private val metric = "grid_frame_ms"
    private val expected = listOf(20.0, 21.0, 22.0)

    private fun phase(name: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlasPerfPersistencePhase") == name)
        assertTrue("Process phases require a disposable emulator",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish") ||
                android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.FINGERPRINT.contains("emulator"))
    }

    @Test fun seedBeforeForceStop() {
        phase("seed")
        assertFalse("seed must start without a previous fixture", marker.contains("start"))
        assertTrue(marker.edit().putLong("start", Process.getStartElapsedRealtime()).commit())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitThat("startup performance restoration completed") {
                PerformanceSession.aggregate.hasRestored()
            }
            assertEquals(0, PerformanceSession.aggregate.snapshot(metric).count)
            scenario.onActivity {
                expected.forEach { PerformanceSession.recordGridFrame(it, 16.0, usageA2 = false) }
            }
            assertEquals(expected.size, PerformanceSession.aggregate.snapshot(metric).count)
            assertFalse("fewer than SAVE_EVERY samples must still be pending",
                PerformancePrefsStore(context, version).load().containsKey(metric))
            // This invokes AtlasApplication.onActivityPaused; never call flush from the test.
            scenario.moveToState(Lifecycle.State.STARTED)
            assertEquals("real Activity pause must save all three samples",
                expected, PerformancePrefsStore(context, version).load()[metric])
        }
    }

    @Test fun verifyAfterForceStop() {
        phase("verify")
        val previousStart = marker.getLong("start", -1L)
        assertTrue("seed phase must have executed", previousStart >= 0L)
        assertTrue("must be a new OS process", Process.getStartElapsedRealtime() > previousStart)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitThat("new process restored performance samples") {
                PerformanceSession.aggregate.hasRestored()
            }
            val restored = PerformanceSession.aggregate.snapshot(metric)
            assertEquals(3, restored.count)
            assertEquals(21.0, restored.p50!!, 0.0)
            assertEquals(22.0, restored.max!!, 0.0)
            assertEquals(expected, PerformancePrefsStore(context, version).load()[metric])
            val otherStore = PerformancePrefsStore(context, version + 1)
            val other = PerformanceAggregates(store = otherStore)
            other.restore(otherStore.load())
            assertEquals("another version must not inherit these samples", 0, other.snapshot(metric).count)
        }
    }
}
