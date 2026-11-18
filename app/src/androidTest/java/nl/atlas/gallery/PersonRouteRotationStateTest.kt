package nl.atlas.gallery

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #404: the person route must outlive an Activity-only configuration change.
 * Uses a fictional key and never opens a real person's photos.
 */
@RunWith(AndroidJUnit4::class)
class PersonRouteRotationStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun retainedViewModelKeepsPersonRouteAcrossActivityRecreation() {
        val request = NativePersonAlbumRequest(PersonKey("synthetic-rotation-person"), "Synthetic")
        val before = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
        compose.runOnUiThread { before.nativePersonAlbumState.value = request }
        compose.activityRule.scenario.recreate()
        val after = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
        assertSame(before, after)
        assertEquals(request, after.nativePersonAlbumState.value)
        compose.runOnUiThread { after.nativePersonAlbumState.value = null }
    }
}
