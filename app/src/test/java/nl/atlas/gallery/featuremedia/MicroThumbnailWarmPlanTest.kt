package nl.atlas.gallery.featuremedia

import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** #668: exercise the actual UI decision, limit, and WorkManager dispatch path. */
class MicroThumbnailWarmPlanTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun realFillLoopHonorsBatteryLimitForNewestPhotos() = runBlocking {
        val store = MicroThumbnailStore(tmp.newFolder("bounded"), budgetBytes = 20_000)
        val items = (0 until 600).map { i ->
            MediaRecord(
                id = i.toLong(), uri = "content://media/external/images/media/$i",
                name = "IMG_$i.jpg", relativePath = "DCIM/Camera/",
                sizeBytes = 100_000L, takenAtMillis = i.toLong() * 60_000L,
                isVideo = false, modifiedAtSeconds = 1_700_000_000L,
                volumeName = "external_primary", providerVersion = "v1",
                generationModified = i.toLong() + 1,
                widthPixels = 4000, heightPixels = 3000, orientationDegrees = 0
            )
        }
        val made = mutableListOf<Long>()
        val report = fillMicroStore(
            store, items, active = { true }, yieldWhile = { false },
            limit = microWarmPlan(3_001, charging = false).immediateLimit
        ) { media, key ->
            made += media.id
            store.write(key, byteArrayOf(1))
        }
        assertEquals(MICRO_WARM_BATTERY_LIMIT, report.checked)
        assertEquals(MICRO_WARM_BATTERY_LIMIT, report.generated)
        assertEquals((599L downTo 88L).toList(), made)
        assertFalse(store.contains(microThumbnailDiskKey(items.first())!!))
    }

    @Test fun smallTopUpChecksAllEntriesAndCancelsDeferredWork() = runBlocking {
        val events = mutableListOf<String>()
        val items = (0 until 3_000).toList()
        microWarmForCatalog(
            items = items, charging = false,
            isMissing = { it < 2_999 }, authorized = { true },
            warm = { events += "warm:$it" },
            deferRemainder = { events += "enqueue" },
            cancelDeferred = { events += "cancel" }
        )
        assertEquals(listOf("warm:${Int.MAX_VALUE}", "cancel"), events)
    }

    @Test fun thresholdAndLargeBatteryFillActuallyLimitAndEnqueue() = runBlocking {
        val boundary = microWarmPlan(missing = 3_000, charging = false)
        assertEquals(Int.MAX_VALUE, boundary.immediateLimit)
        assertFalse(boundary.deferRemainder)
        val events = mutableListOf<String>()
        var scanned = 0
        microWarmForCatalog(
            items = (0 until 3_002).toList(), charging = false,
            isMissing = { scanned++; true }, authorized = { true },
            warm = { events += "warm:$it" },
            deferRemainder = { events += "enqueue" },
            cancelDeferred = { events += "cancel" }
        )
        assertEquals(3_001, scanned) // stop once the threshold is exceeded
        assertEquals(listOf("warm:512", "enqueue"), events)
    }

    @Test fun mostlyCachedLargeCatalogueScansOffCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        var scannedOnCaller = false
        var inspected = 0
        val events = mutableListOf<String>()
        microWarmForCatalog(
            items = (0 until 150_000).toList(), charging = false,
            isMissing = { index ->
                if (Thread.currentThread() === caller) scannedOnCaller = true
                inspected++
                index >= 149_900 // only 100 missing: full cache scan, no deferral
            },
            authorized = { true },
            warm = { events += "warm:$it" },
            deferRemainder = { events += "enqueue" },
            cancelDeferred = { events += "cancel" }
        )
        assertEquals(150_000, inspected)
        assertFalse(scannedOnCaller)
        assertEquals(listOf("warm:${Int.MAX_VALUE}", "cancel"), events)
    }

    @Test fun grantChangeDuringScanPreventsWarmAndScheduling() = runBlocking {
        var permitted = true
        var calls = 0
        microWarmForCatalog(
            items = (0 until 3_002).toList(), charging = false,
            isMissing = { permitted = false; true },
            authorized = { permitted },
            warm = { calls++ },
            deferRemainder = { calls++ },
            cancelDeferred = { calls++ }
        )
        assertEquals(0, calls)
    }

    @Test fun chargingSkipsDiskScanAndWarmsUnbounded() = runBlocking {
        val events = mutableListOf<String>()
        microWarmForCatalog(
            items = (0 until 150_000).toList(), charging = true,
            isMissing = { error("charging must not scan the store") },
            authorized = { true },
            warm = { events += "warm:$it" },
            deferRemainder = { events += "enqueue" },
            cancelDeferred = { events += "cancel" }
        )
        assertEquals(listOf("warm:${Int.MAX_VALUE}", "cancel"), events)
    }

    @Test fun deferredRequestRequiresCharging() {
        val constraints = MicroThumbnailWarmScheduler.request().workSpec.constraints
        assertTrue(constraints.requiresCharging())
    }
}
