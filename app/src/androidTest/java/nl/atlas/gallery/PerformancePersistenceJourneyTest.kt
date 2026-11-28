package nl.atlas.gallery

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Real SharedPreferences boundary, using only synthetic numeric metrics. */
@RunWith(AndroidJUnit4::class)
class PerformancePersistenceJourneyTest {
    @Test fun pauseCommitSurvivesNewCollectorAndKeepsVersionsSeparate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val version = -1021L // isolated synthetic test namespace
        val otherVersion = -1022L
        val prefs = context.getSharedPreferences("atlas_perf", Context.MODE_PRIVATE)
        try {
            val store = PerformancePrefsStore(context, version)
            val first = PerformanceAggregates(store = store)
            first.restore(store.load())
            repeat(3) { first.record("viewer_ms", 20.0 + it) }
            first.flush() // Store-level durability check; real lifecycle proof is separate
            val afterKill = PerformanceAggregates(store = PerformancePrefsStore(context, version))
            afterKill.restore(PerformancePrefsStore(context, version).load())
            assertEquals(3, afterKill.snapshot("viewer_ms").count)
            assertEquals(22.0, afterKill.snapshot("viewer_ms").max!!, 0.0)
            val changedVersion = PerformanceAggregates(store = PerformancePrefsStore(context, otherVersion))
            changedVersion.restore(PerformancePrefsStore(context, otherVersion).load())
            assertEquals(0, changedVersion.snapshot("viewer_ms").count)
        } finally {
            prefs.edit().remove("v$version.viewer_ms").remove("v$otherVersion.viewer_ms").commit()
        }
    }
}
