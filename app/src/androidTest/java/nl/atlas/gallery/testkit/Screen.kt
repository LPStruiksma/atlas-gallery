package nl.atlas.gallery.testkit

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Before an evidence screenshot of real content (rule 1). Thumbnails and face
 * crops decode on real time, after Compose already reports idle, so a
 * screenshot right away can show empty tiles. This returns once the screen,
 * as UiAutomation captures it, has shown the same picture on at least three
 * captures over [stillMs] of real time.
 *
 * A focused text field's blinking cursor is not a change: two captures that
 * differ only inside a box of at most [CURSOR_WIDTH_PX] x [CURSOR_HEIGHT_PX]
 * count as the same picture. A thumbnail arriving is far larger than that.
 */
fun ComposeTestRule.awaitScreenStill(
    label: String, timeoutMs: Long = 15_000L, stillMs: Long = 500L
) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    var last: Bitmap? = null
    var since = 0L
    var same = 0
    try {
        awaitThat("the screen to hold still before $label", timeoutMs) {
            val shot = automation.takeScreenshot() ?: return@awaitThat false
            val now = SystemClock.uptimeMillis()
            val previous = last
            if (previous != null && samePicture(previous, shot)) {
                shot.recycle()
                same++
            } else {
                previous?.recycle()
                last = shot
                since = now
                same = 0
            }
            same >= 2 && now - since >= stillMs
        }
    } finally {
        last?.recycle()
    }
}

private const val CURSOR_WIDTH_PX = 16
private const val CURSOR_HEIGHT_PX = 160

/** Equal, or different only inside a cursor-sized box. */
private fun samePicture(a: Bitmap, b: Bitmap): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    if (a.sameAs(b)) return true
    val width = a.width
    val rowA = IntArray(width)
    val rowB = IntArray(width)
    var left = Int.MAX_VALUE
    var right = -1
    var top = -1
    var bottom = -1
    for (y in 0 until a.height) {
        a.getPixels(rowA, 0, width, 0, y, width, 1)
        b.getPixels(rowB, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            if (rowA[x] != rowB[x]) {
                if (x < left) left = x
                if (x > right) right = x
                if (top < 0) top = y
                bottom = y
            }
        }
        if (right - left + 1 > CURSOR_WIDTH_PX || (top >= 0 && bottom - top + 1 > CURSOR_HEIGHT_PX)) {
            return false
        }
    }
    return true
}
