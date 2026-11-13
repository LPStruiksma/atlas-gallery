package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream

/**
 * E2d (#445) end to end in the real Activity, three byte-identical invented
 * PNGs: pick a keeper → leave → reopen, the group shows as decided; a fresh
 * catalogue publication re-plans from the saved receipt; then one copy's
 * bytes change and the old receipt is ignored. Nothing is trashed.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaKeepJourneyTest {
    private val fixtures = KeepFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun keeperIsRememberedUntilTheFamilyChanges() {
        waitFor("gallery shown", hasTestTag("atlas-nav-albums"))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor("Albums tab", hasTestTag("atlas-header-more"))
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor("Photo DNA menu", hasTestTag("atlas-albums-menu-photo-dna"))
        compose.onNodeWithTag("atlas-albums-menu-photo-dna").performClick()
        waitFor("three copies grouped", hasText("3 copies", substring = true))
        waitFor("default keeper", hasTestTag("photo-dna-keeper") and isSelected())

        // Members are ordered by URI, so index 1 is the second inserted copy.
        compose.onAllNodes(hasTestTag("photo-dna-keeper"))[1].performClick()
        compose.onAllNodes(hasTestTag("photo-dna-keeper"))[1].assertIsSelected()

        // Leave and reopen: still decided.
        compose.onNodeWithTag("atlas-photo-dna-back").performClick()
        waitFor("back on Albums", hasTestTag("atlas-header-more"))
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor("Photo DNA menu", hasTestTag("atlas-albums-menu-photo-dna"))
        compose.onNodeWithTag("atlas-albums-menu-photo-dna").performClick()
        waitFor("decided after reopen", hasTestTag("photo-dna-keeper") and
            hasText("ATLAS_DNA_KEEP_B.png") and isSelected())

        // A new catalogue publication (an unrelated pair of another size)
        // plans again from scratch; the receipt still matches the family.
        fixtures.insertUnrelatedPair()
        // The app rescans a changed MediaStore when it comes back to the front.
        waitAfterResume("re-planned with the new pair") {
            exists(hasText("Duplicates · 2 groups", substring = true))
        }
        waitFor("decided after re-plan", hasTestTag("photo-dna-keeper") and
            hasText("ATLAS_DNA_KEEP_B.png") and isSelected())

        // One copy's bytes change: the family is now two copies, receipt ignored.
        fixtures.rewriteThirdCopy()
        // #572: wait for the re-planned screen itself, judged on ONE snapshot.
        // Separate reads ("Checked" in one, no "3 copies" in the
        // next) can straddle a moment where the plan is briefly not shown,
        // and "Keeping the first copy" also matches the unrelated pair's card, so
        // the old plan (still current until the rescan lands, Keep included)
        // used to satisfy this wait and then fail the receipt check.
        var replanned: List<String> = emptyList()
        waitAfterResume("re-planned without the three-copy family") {
            visibleTexts().takeIf(::isPlanWithoutThreeCopyFamily)?.also { replanned = it } != null
        }
        // The original default A is keeper again after the family changes,
        // rather than the saved B from the old three-copy family.
        waitFor("default A keeper restored", hasTestTag("photo-dna-keeper") and
            hasText("ATLAS_DNA_KEEP_A.png") and isSelected())
        compose.onAllNodes(hasTestTag("photo-dna-keeper") and
            hasText("ATLAS_DNA_KEEP_B.png"))[0].assertIsNotSelected()
    }

    /**
     * The plan after the rewrite: finished ("Checked …"), two groups, and no
     * three-copy group. The old plan always shows its three-copy group first
     * (largest potential), so it can never pass this.
     */
    private fun isPlanWithoutThreeCopyFamily(texts: List<String>) =
        texts.any { it == "Photo DNA" } &&
            texts.any { it.contains("Duplicates · 2 groups") } &&
            texts.any { it.startsWith("2 copies") } &&
            texts.none { it.contains("3 copies") }

    /** Every visible text, read in one idle-synced semantics fetch. */
    private fun visibleTexts(): List<String> =
        compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) {
                    emptyList()
                }.map { it.text }
            }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /**
     * MediaStore notifications only mark the catalogue dirty; it is rescanned
     * on ON_RESUME. Send the Activity to the background and back (as a person
     * switching apps would) until the condition holds, at most a minute.
     */
    private fun waitAfterResume(step: String, condition: () -> Boolean) {
        repeat(5) {
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            try {
                compose.waitUntil(12_000L, condition)
                return
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                // The change notification may not have landed yet; resume again.
            }
        }
        waitUntil(step, condition)
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) = waitUntil(step) { exists(matcher) }

    /** A timeout names its step and the visible texts, on one line for the CI log. */
    private fun waitUntil(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(60_000L, condition)
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val texts = runCatching {
                compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
                    .fetchSemanticsNodes().flatMap { node ->
                        node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) {
                            emptyList()
                        }.map { it.text }
                    }.distinct().joinToString(" | ").take(1500)
            }.getOrElse { "unreadable: ${it.message}" }
            throw AssertionError("Step '$step' timed out. Visible texts: $texts", timeout)
        }
    }
}

/** Invented, locally drawn images only; never real photos. */
private class KeepFixtures : TestRule {
    private val inserted = mutableListOf<Uri>()
    private val copies = mutableListOf<Uri>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                listOf(Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO).forEach {
                    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
                }
                // UI-evidence screenshots that earlier tests in this run left
                // in MediaStore (for CI to pull) can be byte-identical and form
                // their own Photo DNA groups, which shifts this test's groups,
                // counts and keeper index. Hide them for this test only.
                val hidden = hideUiEvidence()
                clearGalleryInventoryCacheForTest(context)
                try {
                    val same = png(120, 90, Color.rgb(40, 90, 160))
                    listOf("A", "B", "C").forEach {
                        copies += insert("ATLAS_DNA_KEEP_$it.png", same)
                    }
                    base.evaluate()
                } finally {
                    inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    setPending(hidden, false)
                }
            }
        }

    /** Marks this app's UI-evidence images pending (invisible to the scan); returns them. */
    private fun hideUiEvidence(): List<Uri> {
        val found = mutableListOf<Uri>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/ATLAS-UI-Evidence/%"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                found += android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            }
        }
        setPending(found, true)
        return found
    }

    /** Best effort per item; restoring publishes the evidence again for the CI pull. */
    private fun setPending(uris: List<Uri>, pending: Boolean) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
        }
        uris.forEach { runCatching { context.contentResolver.update(it, values, null, null) } }
    }

    fun insertUnrelatedPair() {
        val other = png(64, 64, Color.rgb(220, 200, 40))
        insert("ATLAS_DNA_KEEP_OTHER_1.png", other)
        insert("ATLAS_DNA_KEEP_OTHER_2.png", other)
    }

    /** Same pixel size, different colour: different bytes, new revision. */
    fun rewriteThirdCopy() {
        val bytes = png(120, 90, Color.rgb(160, 40, 90))
        requireNotNull(context.contentResolver.openOutputStream(copies[2], "wt")).use {
            it.write(bytes)
        }
    }

    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun insert(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-PhotoDna-Keep/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not create synthetic Photo DNA fixture $name"
        }
        inserted += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        return uri
    }
}
