package nl.atlas.gallery

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #72: handled Photo DNA failures must not evict an unread fatal crash. */
class LastCrashRetentionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(): CrashReportStore =
        CrashReportStore(File(temporary.newFolder("crash"), "last.txt"))

    @Test fun fatalThenHandledKeepsFatalFirstAndCopyable() {
        val store = store()
        val fatal = formatCrashReport(
            1_700_000_000_000L, "0.3.0", "main",
            IllegalStateException("content://media/private/Jane-Doe.jpg").apply {
                stackTrace = arrayOf(
                    StackTraceElement("nl.atlas.gallery.MainActivity", "onCreate",
                        "/sdcard/DCIM/private.jpg", 42)
                )
            }
        )
        val handled = formatCrashReport(
            1_700_000_100_000L, "0.3.0", "background",
            OutOfMemoryError("content://media/private/another-secret.jpg"),
            handled = true
        )

        store.writeFatal(fatal)
        store.writeHandled(handled)
        val copyable = requireNotNull(store.read())

        assertTrue(copyable, copyable.startsWith(fatal.trimEnd('\n')))
        assertTrue(copyable, copyable.contains("MainActivity.onCreate:42"))
        assertTrue(copyable, copyable.contains("Exception: java.lang.IllegalStateException"))
        assertTrue(copyable, copyable.contains("Outcome: handled, app kept running"))
        assertTrue(copyable, copyable.contains("java.lang.OutOfMemoryError"))
        assertFalse(copyable, copyable.contains("Jane-Doe"))
        assertFalse(copyable, copyable.contains("another-secret"))
        assertFalse(copyable, copyable.contains("content://"))
        assertFalse(copyable, copyable.contains("/sdcard/"))
        // Files survive a new reader instance, as after a process restart.
        assertTrue(CrashReportStore(File(temporary.root, "crash/last.txt"))
            .read()!!.contains("MainActivity.onCreate:42"))
    }

    @Test fun handledOnlyFailureRemainsReadableWithoutAFatal() {
        val store = store()
        val handled = formatCrashReport(
            1_700_000_000_000L, "0.3.0", "background",
            IllegalArgumentException("private message"), handled = true
        )
        store.writeHandled(handled)
        val report = requireNotNull(store.read())
        assertTrue(report.contains("Outcome: handled, app kept running"))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertFalse(report.contains("private message"))
    }

    @Test fun newerFatalReplacesOlderFatalWithoutTouchingHandled() {
        val store = store()
        store.writeFatal("older fatal")
        store.writeHandled("handled failure")
        store.writeFatal("newest fatal")
        val report = requireNotNull(store.read())
        assertTrue(report.startsWith("newest fatal"))
        assertTrue(report.contains("handled failure"))
        assertFalse(report.contains("older fatal"))
    }
}
