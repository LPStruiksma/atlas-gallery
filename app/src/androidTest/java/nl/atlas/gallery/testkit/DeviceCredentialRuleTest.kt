package nl.atlas.gallery.testkit

import android.app.KeyguardManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Exercise both outcomes on the disposable emulator, never an owner device. */
class DeviceCredentialRuleTest {
    private val keyguard get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(KeyguardManager::class.java)
    private fun settings() = listOf("lock_to_app_enabled", "lock_to_app_exit_locked")
        .map { testShell("settings get secure $it") }

    private fun assertRestored(before: List<String>) {
        assertEquals(before, settings())
        assertFalse(keyguard.isDeviceSecure)
        assertFalse(keyguard.isKeyguardLocked)
    }

    private fun exercise(action: () -> Unit) {
        DeviceCredentialRule("5729").apply(object : Statement() {
            override fun evaluate() {
                assertTrue(keyguard.isDeviceSecure)
                assertEquals(listOf("1", "0"), settings())
                action()
            }
        }, Description.createTestDescription(javaClass, "synthetic cleanup probe")).evaluate()
    }

    @Test fun successRestoresDevice() {
        val before = settings()
        var entered = false
        exercise { entered = true }
        assertTrue(entered)
        assertRestored(before)
    }

    @Test fun failurePreservesPrimaryAndRestoresDevice() {
        val before = settings()
        val primary = AssertionError("synthetic body failure")
        val caught = runCatching { exercise { throw primary } }.exceptionOrNull()
        assertSame(primary, caught)
        assertTrue("cleanup itself must succeed", primary.suppressed.isEmpty())
        assertRestored(before)
    }
}
