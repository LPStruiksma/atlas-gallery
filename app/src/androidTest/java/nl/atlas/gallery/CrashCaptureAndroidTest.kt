package nl.atlas.gallery

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The debug-only probe crashes a separate app process; the test runner survives. */
@RunWith(AndroidJUnit4::class)
class CrashCaptureAndroidTest {
    private fun awaitFile(file: File) {
        val end = SystemClock.uptimeMillis() + 20_000L
        while (!file.exists() && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100L)
        }
        assertTrue("Probe did not persist expected file", file.exists())
    }

    @Test fun backgroundUncaughtCrashSurvivesAppProcessRelaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "crash")
        val last = File(folder, "last.txt")
        val relaunched = File(folder, "probe-relaunched")
        last.delete()
        relaunched.delete()
        val launch = Intent(context, CrashProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent(launch).setAction(CrashProbeActivity.ACTION_CRASH))
        awaitFile(last)
        val report = last.readText()
        assertTrue(report.contains("Exception: java.lang.IllegalStateException"))
        assertTrue(report.contains("Thread: background"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("/sdcard/"))
        assertFalse(report.contains("person-name"))
        // Fresh crash-probe process; the new instance can still read the persisted trace.
        context.startActivity(Intent(launch).setAction(CrashProbeActivity.ACTION_RELAUNCH))
        awaitFile(relaunched)
        assertTrue(LastCrashRecorder.read(context)?.contains("IllegalStateException") == true)
    }
}
