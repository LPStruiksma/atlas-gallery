package nl.atlas.gallery

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurepeopleui.SamePersonSkipPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic, test-only prefs. Never touches the real review or Skip namespace. */
@RunWith(AndroidJUnit4::class)
class SamePersonSkipPreferencesTest {
    @Test fun scopeIsolationSurvivesReopeningAndResetTouchesOnlyCurrentScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "atlas-test-skip-preferences-only"
        context.deleteSharedPreferences(name)
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val store = SamePersonSkipPreferences(prefs)
            val first = "a".repeat(64)
            val second = "b".repeat(64)
            assertTrue(store.read("synthetic-grant-A").isEmpty())
            store.add("synthetic-grant-A", first)
            store.add("synthetic-grant-B", second)
            // A new store instance reads the persisted set, not a Compose cache.
            val reopened = SamePersonSkipPreferences(
                context.getSharedPreferences(name, Context.MODE_PRIVATE))
            assertEquals(setOf(first), reopened.read("synthetic-grant-A"))
            assertEquals(setOf(second), reopened.read("synthetic-grant-B"))
            assertFalse(prefs.all.keys.any { it.contains("synthetic-grant") })
            reopened.clear("synthetic-grant-A")
            assertTrue(store.read("synthetic-grant-A").isEmpty())
            assertEquals(setOf(second), store.read("synthetic-grant-B"))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
