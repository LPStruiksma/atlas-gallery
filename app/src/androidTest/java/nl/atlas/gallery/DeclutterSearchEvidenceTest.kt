package nl.atlas.gallery

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.provider.MediaStore
import android.util.Log
import android.view.WindowInsets
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.isRoot
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.testkit.awaitKeyboardShown
import nl.atlas.gallery.testkit.onMainThread
import nl.atlas.gallery.testkit.rootViews
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.keyboardInsetPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * FIND-5 (#341): implementation renders of the Design PASS frames
 * (https://github.com/LPStruiksma/atlas-gallery/issues/341#issuecomment-6991033025)
 * at S24+ size, light and dark. Search filters only the staged list, the Trash
 * count never follows the filter, and Whole library asks before it drops
 * staged choices. Owned synthetic images only; never personal media.
 */
@RunWith(AndroidJUnit4::class)
class DeclutterSearchEvidenceTest {
    private val rule = createComposeRule()

    // 324 dp at density 450: the portrait keyboard height the frames assume.
    @get:Rule val chain: RuleChain = RuleChain
        .outerRule(KeyboardRule(TestKeyboard.Fixed, fixedHeightPx = 911))
        .around(S24DisplayRule()).around(rule)

    @Test fun portraitFramesBCD1S2EInBothThemes() = withSyntheticDeck { deck ->
        var dark by mutableStateOf(false)
        var left = 0
        var trashRequests = 0
        rule.setContent {
            AtlasGalleryTheme(dark = dark) {
                DeclutterScreen("Pictures/ATLAS-Declutter-341/", deck,
                    onExit = {}, onSearchWholeLibrary = { left++ },
                    onTrashRequest = { trashRequests++ })
            }
        }
        // Stage the first four (two beach, two receipts), keep swiping position 4.
        repeat(STAGED) { rule.onNodeWithTag("atlas-declutter-discard").performClick() }

        for (theme in listOf("light", "dark")) {
            rule.runOnIdle { dark = theme == "dark" }
            rule.hidePanelKeyboard()

            // E: search while swiping never filters the deck.
            rule.onNodeWithTag("atlas-search-expand").performClick()
            rule.awaitThat("search panel") { rule.exists(hasTestTag("atlas-search-expanded")) }
            rule.onNodeWithText("Searches your $STAGED staged files.").assertExists()
            rule.onNodeWithTag("atlas-declutter-card").assertExists()
            capture("declutter-341-E-swipe-search-$theme.png")
            rule.onNodeWithTag("atlas-declutter-search-show-staged").performClick()
            rule.awaitThat("review list") { rule.exists(hasTestTag("atlas-declutter-trash")) }
            rule.onNodeWithText("Review staged files").assertExists()

            // B: expanded, This screen selected, staged rows still visible below.
            rule.onNodeWithTag("atlas-search-scope-screen").assertIsSelected()
            rule.onNodeWithTag("atlas-search-scope-library").assertIsNotSelected()
            assertEquals(STAGED, rule.onAllNodesWithTag("atlas-declutter-thumbnail")
                .fetchSemanticsNodes().size)
            capture("declutter-341-B-expanded-$theme.png")

            // C: typing filters the staged list only; Move still counts all of them.
            rule.typeInSearchPanel("beach")
            rule.awaitThat("two beach rows") {
                rule.onAllNodesWithTag("atlas-declutter-thumbnail")
                    .fetchSemanticsNodes().size == 2
            }
            rule.onNodeWithTag("atlas-declutter-trash")
                .assertTextContains("Move $STAGED to Android Trash", substring = true)
            rule.assertTagsAboveIme(listOf("atlas-declutter-trash"))
            capture("declutter-341-C-keyboard-$theme.png")

            // D1: Whole library with staged choices asks first; Stay keeps everything.
            rule.onNodeWithTag("atlas-search-scope-library").performClick()
            rule.awaitThat("leave dialog") { rule.exists(hasTestTag("atlas-declutter-leave-dialog")) }
            rule.onNodeWithText("Your $STAGED staged choices will be cleared", substring = true)
                .assertExists()
            capture("declutter-341-D1-confirm-$theme.png")
            rule.onNodeWithTag("atlas-declutter-leave-stay").performClick()
            rule.awaitThat("dialog closed") { !rule.exists(hasTestTag("atlas-declutter-leave-dialog")) }
            rule.onNodeWithTag("atlas-search-scope-screen").assertIsSelected()
            assertEquals("Stay must not leave Declutter", 0, left)

            // S2: no match; the filter is a view, Move still says all staged.
            rule.onNodeWithTag("atlas-scoped-search-field").performTextClearance()
            rule.onNodeWithTag("atlas-scoped-search-field").performTextInput("zzz")
            rule.hidePanelKeyboard()
            rule.awaitThat("no-match message") { rule.exists(hasTestTag("atlas-declutter-search-empty")) }
            rule.onNodeWithText("No matching staged files").assertExists()
            rule.onNodeWithText("Clear the search to see all $STAGED.").assertExists()
            rule.onNodeWithTag("atlas-declutter-trash")
                .assertTextContains("Move $STAGED to Android Trash", substring = true)
            capture("declutter-341-S2-no-match-$theme.png")

            // Close clears the query and collapses the panel; back to swiping.
            rule.onNodeWithTag("atlas-search-close").performClick()
            rule.awaitThat("panel closed") { !rule.exists(hasTestTag("atlas-search-expanded")) }
            assertEquals(STAGED, rule.onAllNodesWithTag("atlas-declutter-thumbnail")
                .fetchSemanticsNodes().size)
            rule.onNodeWithText("Return to swiping").performClick()
        }

        // Search library: leaves through the host callback, never through Trash.
        rule.onNodeWithTag("atlas-search-expand").performClick()
        rule.onNodeWithTag("atlas-search-scope-library").performClick()
        rule.onNodeWithTag("atlas-declutter-leave-search").performClick()
        rule.runOnIdle {
            assertEquals(1, left)
            assertEquals("leaving must never request Trash", 0, trashRequests)
        }
    }

    private fun withSyntheticDeck(body: (List<MediaRecord>) -> Unit) =
        syntheticDeck("Pictures/ATLAS-Declutter-341/", body)

    private fun capture(name: String) {
        rule.waitForIdle()
        saveUiEvidence(name)
    }

    private companion object { const val STAGED = 4 }
}

/**
 * FIND-5 frame L: landscape with the keyboard up. The panel switches to one row
 * (field, This screen, Whole library, Close) that sits above the keyboard, and
 * Whole library works without dismissing the keyboard first.
 */
@RunWith(AndroidJUnit4::class)
class DeclutterSearchLandscapeEvidenceTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    // 184 dp at density 450: the landscape keyboard height the frames assume.
    @get:Rule val chain: RuleChain = RuleChain
        .outerRule(KeyboardRule(TestKeyboard.Fixed, fixedHeightPx = 518))
        .around(S24DisplayRule()).around(rule)

    @Test fun landscapeKeyboardKeepsEveryControlAboveTheIme() =
        syntheticDeck("Pictures/ATLAS-Declutter-341L/") { deck ->
            var dark by mutableStateOf(false)
            var left = 0
            // Rotate, then recreate explicitly so the Activity starts in landscape,
            // as PersonPageJourneyTest does (an earlier wm-size swap broke a later
            // album test in the same shard). Restored to portrait in finally.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            try {
            rule.activityRule.scenario.recreate()
            rule.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    AtlasGalleryTheme(dark = dark) {
                        DeclutterScreen("Pictures/ATLAS-Declutter-341L/", deck,
                            onExit = {}, onSearchWholeLibrary = { left++ }, onTrashRequest = {})
                    }
                }
            }
            rule.awaitThat("Declutter laid out in landscape") {
                val bounds = rule.onAllNodes(isRoot()).fetchSemanticsNodes()
                    .maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }?.boundsInRoot
                bounds != null && bounds.width > bounds.height &&
                    rule.exists(hasTestTag("atlas-declutter-discard"))
            }
            repeat(3) { rule.onNodeWithTag("atlas-declutter-discard").performClick() }
            rule.onNodeWithTag("atlas-declutter-review").performClick()
            for (theme in listOf("light", "dark")) {
                rule.runOnIdle { dark = theme == "dark" }
                rule.onNodeWithTag("atlas-search-expand").performClick()
                rule.awaitThat("search panel") { rule.exists(hasTestTag("atlas-search-expanded")) }
                rule.typeInSearchPanel("beach")
                rule.awaitThat("single-row panel above the keyboard") {
                    rule.exists(hasTestTag("atlas-search-single-row"))
                }
                rule.waitForIdle()
                saveUiEvidence("declutter-341-L-landscape-keyboard-$theme.png")
                rule.assertTagsAboveIme(listOf("atlas-scoped-search-field",
                    "atlas-search-scope-screen", "atlas-search-scope-library",
                    "atlas-search-close", "atlas-header-back"))

                // Whole library is reachable with the keyboard still up.
                rule.onNodeWithTag("atlas-search-scope-library").performClick()
                rule.awaitThat("leave dialog") {
                    rule.exists(hasTestTag("atlas-declutter-leave-dialog"))
                }
                rule.onNodeWithTag("atlas-declutter-leave-stay").performClick()
                rule.awaitThat("dialog closed") {
                    !rule.exists(hasTestTag("atlas-declutter-leave-dialog"))
                }
                rule.onNodeWithTag("atlas-search-close").performClick()
                rule.hidePanelKeyboard()
                rule.awaitThat("panel closed") { !rule.exists(hasTestTag("atlas-search-expanded")) }
            }
            rule.runOnIdle { assertEquals(0, left) }
            } finally {
                automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            }
        }
}

/**
 * Types into the AtlasSearch field, which lives in a DropdownMenu popup, and
 * waits until the test keyboard is up. The kit's typeText asks the Activity
 * window for the keyboard, but the focused window here is the popup, so ask
 * the popup's own window instead (seen on CI: "the keyboard never came up").
 */
internal fun ComposeTestRule.typeInSearchPanel(text: String) {
    onNodeWithTag("atlas-scoped-search-field").performClick()
    onNodeWithTag("atlas-scoped-search-field").performTextInput(text)
    rootViews().lastOrNull()?.let { popup ->
        onMainThread { popup.windowInsetsController?.show(WindowInsets.Type.ime()) }
    }
    awaitKeyboardShown()
}

/** Hides the keyboard through whichever window has focus (popup first) and waits for inset 0. */
internal fun ComposeTestRule.hidePanelKeyboard() {
    awaitThat("the keyboard to be hidden") {
        val views = rootViews()
        val focused = onMainThread { views.lastOrNull { it.hasWindowFocus() } } ?: views.lastOrNull()
        if (keyboardInsetPx() > 0) {
            focused?.let { onMainThread { it.windowInsetsController?.hide(WindowInsets.Type.ime()) } }
        }
        keyboardInsetPx() == 0
    }
}

/** Inserts six owned, flat-colour PNGs; deletes them afterwards. */
internal fun syntheticDeck(folder: String, body: (List<MediaRecord>) -> Unit) {
    val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    val specs = listOf(
        "BEACH_SYNTHETIC_01.png" to AndroidColor.rgb(33, 145, 192),
        "RECEIPT_SYNTHETIC_02.png" to AndroidColor.rgb(214, 196, 160),
        "BEACH_SYNTHETIC_03.png" to AndroidColor.rgb(237, 178, 92),
        "RECEIPT_SYNTHETIC_04.png" to AndroidColor.rgb(150, 160, 170),
        "SKY_SYNTHETIC_05.png" to AndroidColor.rgb(120, 170, 230),
        "FOREST_SYNTHETIC_06.png" to AndroidColor.rgb(60, 130, 80)
    )
    val uris = specs.map { (name, colour) ->
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ))
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(colour)
            resolver.openOutputStream(uri)!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        uri
    }
    try {
        body(specs.mapIndexed { i, (name, _) ->
            MediaRecord(341L + i, uris[i].toString(), name, folder,
                (6 - i) * 2_000_000L, 341L + i, false)
        })
    } finally {
        uris.forEach { resolver.delete(it, null, null) }
    }
}

/**
 * Every tagged control ends above the keyboard window, in screen coordinates.
 * The search panel is a DropdownMenu popup, so each node's own window offset
 * is added to its window position; the IME top comes from its window bounds.
 */
internal fun ComposeTestRule.assertTagsAboveIme(tags: List<String>) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.serviceInfo = automation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    var imeTop = -1
    awaitThat("the keyboard window in the accessibility tree", 5_000L) {
        automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { window -> Rect().also { window.getBoundsInScreen(it) } }
            ?.takeIf { it.height() > 0 }?.let { imeTop = it.top }
        imeTop > 0
    }
    val report = tags.map { tag ->
        val node = onNodeWithTag(tag).fetchSemanticsNode("no node tagged $tag")
        val view = (node.root as ViewRootForTest).view
        val origin = onMainThread { IntArray(2).also { view.getLocationOnScreen(it) } }
        val top = origin[1] + node.positionInWindow.y
        val bottom = top + node.size.height
        Log.i("ATLAS_EVIDENCE", "declutter-341 $tag x=${origin[0] + node.positionInWindow.x} " +
            "top=$top bottom=$bottom w=${node.size.width} imeTop=$imeTop")
        Triple(tag, bottom, node.size.width)
    }
    val bad = report.filter { (_, bottom, width) -> bottom > imeTop || width <= 0 }
    assertTrue("controls under the keyboard (top $imeTop px) or zero-width: $bad; all: $report",
        bad.isEmpty())
}
