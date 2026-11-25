package nl.atlas.gallery.featureintents

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.MainActivity
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * BENCH-2 (#573) Phase B: the manifest makes ATLAS a choice for the standard
 * actions, and an implicit intent (no component, resolved by Android) reaches
 * the external viewer or picker. Media is one generated PNG.
 */
@RunWith(AndroidJUnit4::class)
class ExternalIntentFiltersTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = false)).around(compose)
    private val fixture = ExternalIntentFixture()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() = fixture.insertMedia()
    @After fun tearDown() = fixture.cleanUp()

    private fun resolvesToAtlas(intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent.setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            .any { it.activityInfo.name == MainActivity::class.java.name }

    @Test fun theStandardActionsResolveToAtlas() {
        val photo = Uri.parse(fixture.media)
        for (action in listOf(Intent.ACTION_VIEW, MediaStore.ACTION_REVIEW, MediaStore.ACTION_REVIEW_SECURE)) {
            assertTrue("$action image", resolvesToAtlas(Intent(action).setDataAndType(photo, "image/png")))
            // A camera often sends only the URI; Android resolves the type through the provider.
            assertTrue("$action untyped", resolvesToAtlas(Intent(action).setData(photo)))
        }
        assertTrue(resolvesToAtlas(Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)))
        assertTrue(resolvesToAtlas(Intent(Intent.ACTION_GET_CONTENT).setType("video/*")))
        assertTrue(resolvesToAtlas(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)))
        assertTrue(resolvesToAtlas(Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)))
    }

    @Test fun nonMediaAndNonContentIntentsDoNotListAtlas() {
        assertFalse(resolvesToAtlas(Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("https://example.com/a.jpg"), "image/jpeg")))
        assertFalse(resolvesToAtlas(Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("file:///sdcard/a.jpg"), "image/jpeg")))
        assertFalse(resolvesToAtlas(Intent(Intent.ACTION_GET_CONTENT).setType("text/plain")))
        assertFalse(resolvesToAtlas(Intent("com.android.camera.action.REVIEW").setDataAndType(
            Uri.parse(fixture.media), "image/png")))
    }

    @Test fun anImplicitOpenWithReachesTheReadOnlyViewer() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(fixture.media), "image/png")
            .setPackage(context.packageName)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = false)
        scenario.close()
    }

    @Test fun anImplicitGetContentReachesThePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE).setPackage(context.packageName)
        val scenario = ActivityScenario.launchActivityForResult<MainActivity>(intent)
        compose.awaitThat("the picker") {
            compose.exists(hasTestTag(ExternalPickerTags.TITLE) and hasText("Choose a photo"))
        }
        scenario.close()
    }
}
