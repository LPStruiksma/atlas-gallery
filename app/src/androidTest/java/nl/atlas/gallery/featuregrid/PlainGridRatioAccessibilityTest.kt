package nl.atlas.gallery.featuregrid

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.MediaRecord
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #502: TalkBack's custom action is available with Timeline & dates disabled. */
@RunWith(AndroidJUnit4::class)
class PlainGridRatioAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun plainGridActionsMenuRequestsOriginalAndRetainsFocalIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val items = (0 until 12).map { i ->
            MediaRecord(i.toLong(), Uri.parse("content://test.invalid/photo/$i").toString(),
                "Synthetic $i", "DCIM/TEST/", 1L, 1_600_000_000_000L + i, false)
        }
        var requested: GalleryZoomRequest? = null
        rule.setContent {
            MaterialTheme {
                AtlasFeatureGrid(
                    items = items,
                    state = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
                    busy = false,
                    onOpen = {},
                    viewKey = "plain-ratio-accessibility-test",
                    catalogueEpoch = 9L,
                    onOriginalProportionsRequested = { requested = it }
                )
            }
        }
        rule.waitForIdle()
        val actions = rule.onNodeWithTag("atlas-feature-grid")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val showOriginal = actions.first { it.label == ORIGINAL_PROPORTIONS_ACTION }
        rule.runOnIdle { assertEquals(true, showOriginal.action()) }
        rule.runOnIdle {
            val request = requested
            assertNotNull("Custom TalkBack action must reach the host", request)
            assertEquals(GalleryZoomStop.ORIGINAL, request?.destination)
            assertEquals(9L, request?.catalogueEpoch)
            assertNotNull("Measured grid should preserve a focal photo", request?.focal)
        }
    }
}
