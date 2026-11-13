package nl.atlas.gallery

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Invented id far above any emulator row; its URI cannot be opened. */
private const val GHOST_ID = 987_654_321L

private fun ghostRecord() = MediaRecord(
    GHOST_ID, "content://media/external/images/media/$GHOST_ID",
    "IMG_GHOST_FIXTURE.png", "Pictures/ATLAS-Ghost-Fixture/",
    10L, 1_000L, false, volumeName = "external_primary")

/** Seeds the cold-start cache with a revoked (nonexistent) item before launch. */
private fun seedGhostCache(context: android.content.Context) {
    GalleryInventoryCacheFile(File(context.noBackupFilesDir, "gallery-inventory.bin"))
        .write(GalleryMediaGrant(images = true, videos = true, selected = false),
            listOf(ghostRecord()))
}

/**
 * #505: a cold start publishes the cached catalogue with no scan at all, and
 * the background scan then replaces it; the revoked item disappears.
 */
@RunWith(AndroidJUnit4::class)
class GalleryColdStartCacheTest {
    private val fixtures = SyntheticHeaderFixtures(
        sizes = listOf(120 to 90, 90 to 120), beforeLaunch = ::seedGhostCache)

    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixtures)

    @Test fun cachedCatalogueShowsBeforeAnyScanAndRefreshDropsRevokedItem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val store = ViewModelStore()
        lateinit var vm: GalleryViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app))[GalleryViewModel::class.java]
        }
        try {
            // No refresh was requested: only the cache can publish this.
            waitUntil { vm.publishedInventory.value.fromCache }
            val cached = vm.publishedInventory.value
            assertEquals(listOf(GHOST_ID), cached.items.map { it.id })
            val full = GalleryMediaGrant(true, true, false)
            assertTrue(cached.showable(full))
            assertEquals(null, cached.scanBoundGrant)

            instrumentation.runOnMainSync { vm.refresh() }
            waitUntil { !vm.publishedInventory.value.fromCache &&
                vm.publishedInventory.value.observedGrant != null }
            val fresh = vm.publishedInventory.value.items
            assertFalse(fresh.any { it.id == GHOST_ID })
            assertEquals(2, fresh.count { it.name.startsWith("IMG_HEADER_FIXTURE_") })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000L
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(50L)
        }
    }
}

/** #505 in the real Activity: no blocking access screen, ghost tile is harmless. */
@RunWith(AndroidJUnit4::class)
class GalleryColdStartJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(
        sizes = listOf(120 to 90, 90 to 120), beforeLaunch = ::seedGhostCache)
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun coldStartOpensGridWithoutAccessBlockerAndSurvivesRevokedItem() {
        waitFor(hasTestTag("atlas-timeline-grid"))
        compose.onNodeWithText("Updating your allowed photos…").assertDoesNotExist()
        compose.onNodeWithTag("atlas-first-scan").assertDoesNotExist()
        // The background scan lands and the grid stays mounted throughout.
        waitFor(hasText("photos & videos", substring = true))
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-timeline-grid").assertExists()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}
