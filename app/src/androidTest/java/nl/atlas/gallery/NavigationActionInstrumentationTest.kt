package nl.atlas.gallery

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import nl.atlas.gallery.testkit.tap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic host exercises the shared completion hook only; not app-route coverage. */
@RunWith(AndroidJUnit4::class)
class NavigationActionInstrumentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Host() {
        val timers = remember { NavigationActionTimers() }
        var personVisible by remember { mutableStateOf(false) }
        var searchVisible by remember { mutableStateOf(false) }
        NavigationActionSampleEffect(timers, personVisible, searchVisible)
        Column {
            listOf("viewer-person", "native-person", "face-person").forEach { tag ->
                Button({ timers.startPerson(); personVisible = true }, Modifier.testTag(tag)) {
                    Text(tag)
                }
            }
            listOf("bottom-search", "similar-search", "face-search").forEach { tag ->
                Button({ timers.startSearch(); searchVisible = true }, Modifier.testTag(tag)) {
                    Text(tag)
                }
            }
        }
    }

    @Test fun personNavigationActionsIncreaseA10SampleCount() {
        val before = PerformanceSession.aggregate.snapshot("action_a10_ms").count
        compose.setContent { Host() }
        listOf("viewer-person", "native-person", "face-person").forEach { tag ->
            compose.tap(hasTestTag(tag))
            compose.waitForIdle()
        }
        assertEquals(before + 3, PerformanceSession.aggregate.snapshot("action_a10_ms").count)
    }

    @Test fun searchNavigationActionsIncreaseA11SampleCount() {
        val before = PerformanceSession.aggregate.snapshot("action_a11_ms").count
        compose.setContent { Host() }
        listOf("bottom-search", "similar-search", "face-search").forEach { tag ->
            compose.tap(hasTestTag(tag))
            compose.waitForIdle()
        }
        assertEquals(before + 3, PerformanceSession.aggregate.snapshot("action_a11_ms").count)
    }
}
