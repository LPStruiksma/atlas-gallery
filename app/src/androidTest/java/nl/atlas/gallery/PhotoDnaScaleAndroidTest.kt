package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurecuration.CurationAsset
import nl.atlas.gallery.featurecuration.CurationReviewPlan
import nl.atlas.gallery.featurecuration.PhotoDnaReviewScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Source-independent scale regression: 2,000 invented image records in one
 * byte-identical family. No real MediaStore images or private user media.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaScaleAndroidTest {
    @get:Rule val rule = createComposeRule()

    @Test fun twoThousandSyntheticDuplicateImagesBuildOneVirtualizedGroup() {
        val digest = "a".repeat(64)
        val assets = (1..2_000).map { index ->
            CurationAsset(
                uri = "content://media/external/images/media/" + index,
                revision = "synthetic", bytes = 256,
                title = "Synthetic image " + index,
                sha256 = digest, sha256AtRevision = "synthetic"
            )
        }
        val plan = CurationReviewPlan.build("synthetic-scope", assets, emptyList())
        assertEquals(1, plan.exactGroups.size)
        assertEquals(2_000, plan.exactGroups.single().members.size)
        rule.setContent {
            MaterialTheme {
                PhotoDnaReviewScreen(
                    plan = plan, thumbnail = { _, modifier -> Box(modifier) },
                    onReviewRequested = {}, onComparePair = {}
                )
            }
        }
        rule.onNodeWithTag("photo-dna-list").assertExists()
        rule.onNodeWithTag("photo-dna-exact-group").assertExists()
    }
}
