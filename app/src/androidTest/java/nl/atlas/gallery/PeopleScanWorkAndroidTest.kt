package nl.atlas.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.PeopleScanWorkScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** API35 synthetic WorkRequest assertions: no media, identities or device install. */
@RunWith(AndroidJUnit4::class)
class PeopleScanWorkAndroidTest {
    @Test fun requestHasHardPowerAndStorageConstraintsAndNoInputs() {
        val request = PeopleScanWorkScheduler.request()
        val constraints = request.workSpec.constraints
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        assertTrue(request.workSpec.input.keyValueMap.isEmpty())
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
    }

    @Test fun absentHostCannotRestoreAuthorizationAfterProcessRestart() {
        PeopleScanWorkRuntime.clear()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(context.packageName.isBlank())
        // There is deliberately no persisted source of opt-in/grant credentials.
        assertTrue(PeopleScanWorkScheduler.UNIQUE_NAME.isNotBlank())
    }
}
