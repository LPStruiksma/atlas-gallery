package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #417: the StrictMode baseline names what each main-thread violation opened. */
class StrictModeSiteTest {
    private fun f(cls: String, method: String = "m", file: String? = null, line: Int = 1) =
        StackTraceElement(cls, method, file, line)

    private val guard = listOf(
        f("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk"),
        f("libcore.io.BlockGuardOs", "open"),
        f("libcore.io.ForwardingOs", "open"),
        f("libcore.io.IoBridge", "open")
    )

    @Test fun sqliteOpenFromAnAtlasStoreIsSqliteWithItsAppFrame() {
        val stack = guard + listOf(
            f("android.database.sqlite.SQLiteConnection", "open"),
            f("android.database.sqlite.SQLiteOpenHelper", "getReadableDatabase"),
            f("nl.atlas.gallery.featurepeople.FaceIndexStore", "load", "FaceIndexStore.kt", 88),
            f("android.os.Looper", "loop")
        )
        assertEquals("sqlite", StrictModeSites.opening("DiskReadViolation", stack))
        assertEquals("android.database.sqlite.SQLiteConnection.open", StrictModeSites.trigger(stack))
        assertEquals("nl.atlas.gallery.featurepeople.FaceIndexStore.load(FaceIndexStore.kt:88)",
            StrictModeSites.appFrame(stack))
        assertFalse(StrictModeSites.harness(stack))
    }

    @Test fun sharedPreferencesWaitIsSharedPrefs() {
        val stack = listOf(
            f("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk"),
            f("android.app.SharedPreferencesImpl", "awaitLoadedLocked"),
            f("android.app.SharedPreferencesImpl", "getBoolean"),
            f("nl.atlas.gallery.SettingsStore", "read", "SettingsStore.kt", 5)
        )
        assertEquals("shared-prefs", StrictModeSites.opening("DiskReadViolation", stack))
        assertEquals("android.app.SharedPreferencesImpl.awaitLoadedLocked",
            StrictModeSites.trigger(stack))
    }

    @Test fun assetAndModelAndPlainFileAreSeparated() {
        assertEquals("asset", StrictModeSites.opening("DiskReadViolation",
            guard + f("android.content.res.AssetManager", "open")))
        assertEquals("model", StrictModeSites.opening("DiskReadViolation",
            guard + f("ai.onnxruntime.OrtSession", "<init>")))
        assertEquals("file", StrictModeSites.opening("DiskWriteViolation",
            guard + f("java.io.FileOutputStream", "<init>")))
        assertEquals("network", StrictModeSites.opening("NetworkViolation", guard))
        assertEquals("other", StrictModeSites.opening("DiskReadViolation",
            listOf(f("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk"))))
    }

    @Test fun frameworkOnlyStackHasNoAppFrameAndInstallerFramesAreSkipped() {
        val stack = guard + listOf(
            f("nl.atlas.gallery.StrictModeBaseline", "record"),
            f("android.app.ActivityThread", "handleBindApplication")
        )
        assertEquals("none", StrictModeSites.appFrame(stack))
    }

    @Test fun runnerStacksAreHarness() {
        assertTrue(StrictModeSites.harness(guard + f("androidx.test.runner.AndroidJUnitRunner", "onCreate")))
    }

    @Test fun groupingCountsRepeatsPerPhaseAndKeepsFirstTime() {
        val stack = guard + f("nl.atlas.gallery.Foo", "bar", "Foo.kt", 3)
        val records = listOf(
            StrictModeBaseline.Record("grid-first-open", 900, "DiskReadViolation", stack),
            StrictModeBaseline.Record("grid-first-open", 400, "DiskReadViolation", stack),
            StrictModeBaseline.Record("people-first-open", 1200, "DiskReadViolation", stack),
            StrictModeBaseline.Record("process-start", 50, "DiskWriteViolation", stack)
        )
        val sites = StrictModeSites.group(records)
        assertEquals(listOf("process-start", "grid-first-open", "people-first-open"),
            sites.map { it.phase })
        assertEquals(2, sites[1].count)
        assertEquals(400L, sites[1].firstMs)
        assertEquals("file", sites[0].opening)
    }
}
