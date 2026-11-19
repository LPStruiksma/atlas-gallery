package nl.atlas.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastCrashReportTest {
    @Test fun redactsExceptionMessageFileNamesAndUnknownFrames() {
        val error = IllegalStateException("content://media/private/Jane-Doe.jpg /sdcard/DCIM/secret.jpg")
        error.stackTrace = arrayOf(
            StackTraceElement("nl.atlas.gallery.PhotoDnaHostKt", "planPhotoDna", "/sdcard/secret-name.jpg", 123),
            StackTraceElement("com.example.JaneDoe", "privateName", "content://secret", 20),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 55)
        )
        val report = formatCrashReport(1_700_000_000_000L, "0.3.0", "background", error)
        assertTrue(report.contains("Exception: java.lang.IllegalStateException"))
        assertTrue(report.contains("nl.atlas.gallery.PhotoDnaHostKt.planPhotoDna:123"))
        assertTrue(report.contains("android.os.Handler.dispatchMessage:55"))
        assertFalse(report.contains("Jane"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("/sdcard/"))
        assertFalse(report.contains("com.example"))
        assertFalse(report.contains("Handler.java"))
    }

    @Test fun unknownVersionAndThreadLabelsCannotLeakPrivateValues() {
        val report = formatCrashReport(
            1_700_000_000_000L, "1.0 content://private", "person-name/private",
            RuntimeException("private")
        )
        assertTrue(report.contains("Version: unknown"))
        assertTrue(report.contains("Thread: background"))
        assertFalse(report.contains("person-name"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("private"))
    }

    @Test fun stackCaptureIsBoundedAndNoCauseMessagesAreIncluded() {
        val cause = RuntimeException("face-123")
        val error = IllegalStateException("photo-456", cause)
        error.stackTrace = Array(1000) { index ->
            StackTraceElement("nl.atlas.gallery.MainActivity", "render", "name.jpg", index)
        }
        val report = formatCrashReport(1_700_000_000_000L, "0.3.0", "main", error)
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("Caused by: java.lang.RuntimeException"))
        assertFalse(report.contains("face-123"))
        assertFalse(report.contains("photo-456"))
        assertTrue(report.lines().count { it.startsWith("  at ") } <= 48)
        assertTrue(report.length <= 8_192)
    }
}
