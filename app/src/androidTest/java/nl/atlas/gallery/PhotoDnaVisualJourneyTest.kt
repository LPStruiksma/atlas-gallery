package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * E2e (#450) end to end in the real Activity: one invented pattern saved as a
 * small PNG and as a larger JPEG (different bytes, same picture) and one
 * unrelated pattern. Photo DNA shows the first two as one possible visual
 * match from the on-device perceptual hash, and never the unrelated one.
 * Nothing is trashed; fixtures are emulator-only and deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaVisualJourneyTest {
    private val fixtures = VisualFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun nearIdenticalImagesShowAsOneVisualPair() {
        waitFor("gallery shown", hasTestTag("atlas-nav-albums"))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor("Albums tab", hasTestTag("atlas-header-more"))
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor("Photo DNA menu", hasTestTag("atlas-albums-menu-photo-dna"))
        compose.onNodeWithTag("atlas-albums-menu-photo-dna").performClick()
        waitFor("byte plan shown", hasTestTag("atlas-photo-dna-title") and
            hasText("Photo DNA"))

        val ourPair = hasTestTag("photo-dna-visual-pair") and
            hasAnyDescendant(hasText(VisualFixtures.NEAR_SMALL)) and
            hasAnyDescendant(hasText(VisualFixtures.NEAR_LARGE))
        // #701: the "Similar photos" header is a LazyColumn item placed after
        // every exact-copy group. Evidence screenshots that earlier tests left
        // in MediaStore form such groups, and with two of them the header sits
        // below the fold and is never composed, so it can't be found by
        // waiting alone. Bring it into view on each check instead.
        val similarHeader = hasText("Similar photos", substring = true)
        waitFor("visual pass done", similarHeader and !hasText("· 0 pairs", substring = true)) {
            if (compose.onAllNodes(similarHeader).fetchSemanticsNodes().isEmpty()) {
                runCatching { compose.onNodeWithTag("photo-dna-list").performScrollToNode(similarHeader) }
            }
        }
        compose.onNodeWithTag("photo-dna-list").performScrollToNode(ourPair)
        waitFor("the near pair", ourPair)
        check(compose.onAllNodes(hasTestTag("photo-dna-visual-pair") and
            hasAnyDescendant(hasText(VisualFixtures.UNRELATED))).fetchSemanticsNodes().isEmpty()) {
            "the unrelated pattern was paired"
        }
        // A visual pair never offers a Trash selection of its own.
        check(compose.onAllNodes(ourPair and hasAnyDescendant(hasTestTag("photo-dna-stage")))
            .fetchSemanticsNodes().isEmpty()) { "a visual pair exposed a Trash action" }
    }

    private val startedAt = System.nanoTime()

    private fun waitFor(step: String, matcher: SemanticsMatcher, beforeCheck: () -> Unit = {}) {
        val stepStart = System.nanoTime()
        try {
            compose.waitUntil(60_000L) {
                beforeCheck()
                compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }
            android.util.Log.i("PhotoDnaVisual", "step '$step' after ${msSince(stepStart)} ms " +
                "(test at ${msSince(startedAt)} ms)")
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val texts = runCatching {
                compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
                    .fetchSemanticsNodes().flatMap { node ->
                        node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) {
                            emptyList()
                        }.map { it.text }
                    }.distinct().joinToString(" | ").take(1500)
            }.getOrElse { "unreadable: ${it.message}" }
            throw AssertionError("Step '$step' timed out after ${msSince(stepStart)} ms " +
                "(test at ${msSince(startedAt)} ms). ${scanDiagnostics()} Visible texts: $texts", timeout)
        }
    }

    private fun msSince(start: Long) = (System.nanoTime() - start) / 1_000_000

    /**
     * What the scan could see: on-screen group and pair cards, and the image
     * rows in MediaStore (names are synthetic fixtures and evidence files).
     */
    private fun scanDiagnostics(): String {
        fun count(tag: String) = runCatching {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
        }.getOrDefault(-1)
        val names = runCatching {
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }.orEmpty()
        }.getOrElse { listOf("unreadable: ${it.message}") }
        val fixtures = listOf(VisualFixtures.NEAR_SMALL, VisualFixtures.NEAR_LARGE,
            VisualFixtures.UNRELATED).filter { it in names }
        return "Composed: ${count("photo-dna-exact-group")} exact groups, " +
            "${count("photo-dna-visual-pair")} visual pairs. MediaStore: ${names.size} images, " +
            "fixtures present $fixtures, others ${names.filterNot { it in fixtures }.take(40)}."
    }
}

/** Invented, locally drawn patterns only; never real photos. */
private class VisualFixtures : TestRule {
    companion object {
        const val NEAR_SMALL = "ATLAS_DNA_NEAR_SMALL.png"
        const val NEAR_LARGE = "ATLAS_DNA_NEAR_LARGE.jpg"
        const val UNRELATED = "ATLAS_DNA_UNRELATED.png"
    }
    private val inserted = mutableListOf<Uri>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                listOf(Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO).forEach {
                    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
                }
                clearGalleryInventoryCacheForTest(context)
                try {
                    insert(NEAR_SMALL, "image/png", encode(160, 120, ::nearPattern,
                        Bitmap.CompressFormat.PNG))
                    insert(NEAR_LARGE, "image/jpeg", encode(320, 240, ::nearPattern,
                        Bitmap.CompressFormat.JPEG))
                    insert(UNRELATED, "image/png", encode(160, 120, ::otherPattern,
                        Bitmap.CompressFormat.PNG))
                    base.evaluate()
                } finally {
                    inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                }
            }
        }

    private fun nearPattern(u: Double, v: Double) =
        128 + 90 * sin(2 * PI * (2.2 * u + 0.7 * v)) + 30 * cos(2 * PI * 1.6 * v)

    private fun otherPattern(u: Double, v: Double) =
        128 + 90 * sin(2 * PI * (0.5 * u - 2.5 * v)) + 30 * cos(2 * PI * 1.1 * u)

    private fun encode(
        width: Int, height: Int, pattern: (Double, Double) -> Double, format: Bitmap.CompressFormat
    ): ByteArray {
        val pixels = IntArray(width * height) { index ->
            val g = pattern((index % width + 0.5) / width, (index / width + 0.5) / height)
                .roundToInt().coerceIn(0, 255)
            (0xff shl 24) or (g shl 16) or (g shl 8) or (255 - g / 2)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(format, 92, it)) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun insert(name: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-PhotoDna-Visual/")
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
    }
}
