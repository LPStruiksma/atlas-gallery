package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthetic StateFlow inventory. Check that one upstream StateFlow equality
 * check does not get repeated during Compose publication. Neither photos
 * nor the private People DB are opened.
 */
@RunWith(AndroidJUnit4::class)
class IdentityFlowCollectionTest {
    @get:Rule val rule = createComposeRule()

    private class CountingList<T>(
        private val items: List<T>,
        private val equalCalls: AtomicInteger
    ) : List<T> by items {
        override fun equals(other: Any?): Boolean {
            // StateFlow's own conflation (in the setter and in its collector
            // loop) is independent of the UI; count every other caller.
            // Attributing by stack keeps the check independent of when the
            // test dispatcher runs the collector (#530, v2 compose rule).
            val fromStateFlow = Throwable().stackTrace.any {
                it.className.startsWith("kotlinx.coroutines.flow.StateFlowImpl")
            }
            if (!fromStateFlow) equalCalls.incrementAndGet()
            return this === other
        }
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    @Test fun newMediaGenerationDoesNotRepeatLargeListEqualityOnComposeThread() {
        val calls = AtomicInteger()
        val before = CountingList((0 until 15_000).map { i ->
            MediaRecord(i.toLong(), "content://atlas.synthetic/$i",
                "fictional-$i", "DCIM/FAKE/", 1024, i.toLong(), false)
        }, calls)
        val flow = MutableStateFlow<List<MediaRecord>>(before)
        rule.setContent {
            val media by flow.collectAsIdentityState()
            MaterialTheme {
                Text("${media.first().name}:${media.size}",
                    Modifier.testTag("atlas-inventory-generation"))
            }
        }
        rule.onNodeWithTag("atlas-inventory-generation")
            .assertTextEquals("fictional-0:15000")

        val next = CountingList((0 until 15_000).map { i ->
            MediaRecord(i.toLong(), "content://atlas.synthetic/$i",
                if (i == 0) "updated-0" else "fictional-$i",
                "DCIM/FAKE/", 1024, i.toLong(), false)
        }, calls)
        val checksBefore = calls.get()
        rule.runOnIdle { flow.value = next }
        rule.waitUntil(10_000) {
            rule.onNodeWithTag("atlas-inventory-generation")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString("") { it.text } == "updated-0:15000"
        }
        rule.runOnIdle {
            // The UI collector must not call List.equals on the two samples.
            assertEquals(checksBefore, calls.get())
        }
    }
}
