package nl.atlas.gallery

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import nl.atlas.gallery.testkit.DisplayPrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * BRAND-L3 (#656): the Lapis launcher icon and cold-start splash.
 *
 * Evidence (scripts/ui-evidence.d/656-lapis-launcher.txt):
 * - lapis656-splash-light / -dark: real screen captures of MainActivity's platform splash on
 *   the CI emulator (API 31+ `windowSplashScreen*`), taken while the app starts, with legible
 *   light navigation-bar glyphs on the Lapis ground.
 * - lapis656-real-launcher-light / -dark: the installed icon in the emulator's own launcher
 *   app list, captured from the real screen.
 * - lapis656-launcher-light / -dark: the shipped adaptive icon drawn by Android at 48 dp on a
 *   synthetic 1080 x 2340 launcher (busy wallpaper, fictional apps) under round, squircle and
 *   the system's own mask, colour and themed (monochrome) variants.
 * - lapis656-splash-api29-30: the API 29-30 starting window, i.e. Theme.Atlas.Launch's
 *   values/ windowBackground layer-list, drawn by Android full screen. CI runs API 35, so the
 *   live API 29-30 starting window is not captured here.
 */
@RunWith(AndroidJUnit4::class)
class LapisLauncherResourcesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun applicationUsesLaunchThemeWithLapisSplashAndUnchangedPostLaunchWindow() {
        assertEquals("manifest <application> theme", R.style.Theme_Atlas_Launch, context.applicationInfo.theme)
        val launch = context.resources.newTheme().apply { applyStyle(R.style.Theme_Atlas_Launch, true) }
        val platform = context.resources.newTheme().apply {
            applyStyle(android.R.style.Theme_Material_NoActionBar, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val background = launch.resolve(android.R.attr.windowSplashScreenBackground)
            assertEquals("splash ground is Lapis", LAPIS, background.data)
            assertEquals(
                "splash icon is the launcher foreground mark",
                R.drawable.ic_launcher_foreground,
                launch.resolve(android.R.attr.windowSplashScreenAnimatedIcon).resourceId
            )
            // The window after launch keeps the platform theme's background.
            val before = platform.resolve(android.R.attr.windowBackground)
            val after = launch.resolve(android.R.attr.windowBackground)
            assertEquals("windowBackground type", before.type, after.type)
            assertEquals("windowBackground value", before.data, after.data)
            assertEquals("windowBackground resource", before.resourceId, after.resourceId)
        } else {
            assertEquals(
                R.drawable.atlas_launch_background,
                launch.resolve(android.R.attr.windowBackground).resourceId
            )
        }
    }

    @Test
    fun launcherIconIsAdaptiveWithLapisGroundAndMonochromeLayer() {
        for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = context.getDrawable(id)
            assertTrue("mipmap $id is adaptive", icon is AdaptiveIconDrawable)
            icon as AdaptiveIconDrawable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                assertNotNull("themed-icon monochrome layer", icon.monochrome)
            }
            // The ground is solid Lapis: sample the background layer at its centre and corner.
            val ground = render(icon.background, 108, 108)
            assertEquals(LAPIS, ground.getPixel(54, 54))
            assertEquals(LAPIS, ground.getPixel(2, 2))
            ground.recycle()
        }
        // Every opaque foreground pixel lies inside the r 33 safe circle of the 108 dp canvas.
        val size = 432 // 4 px per dp
        val fg = render(context.getDrawable(R.drawable.ic_launcher_foreground)!!, size, size)
        var farthest = 0.0
        var painted = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (Color.alpha(fg.getPixel(x, y)) > 8) {
                painted++
                val dx = (x + 0.5) / 4 - 54
                val dy = (y + 0.5) / 4 - 54
                farthest = maxOf(farthest, kotlin.math.sqrt(dx * dx + dy * dy))
            }
        }
        fg.recycle()
        assertTrue("foreground draws the mark ($painted px)", painted > 1000)
        assertTrue("mark reaches %.2f dp, inside r 33".format(farthest), farthest <= 33.0)
        assertTrue("mark is centre-scaled to about 32.66 dp, got %.2f".format(farthest), farthest > 32.0)
    }

    private fun render(d: Drawable, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bitmap))
        return bitmap
    }
}

/** The launcher sheets and the API 29-30 starting window, drawn by Android from the shipped resources. */
@RunWith(AndroidJUnit4::class)
class LapisLauncherSheetEvidenceTest {
    @get:Rule val display: org.junit.rules.TestRule = S24DisplayRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launcherSheetLight() = saveBitmapEvidence("lapis656-launcher-light", launcherSheet(dark = false))

    @Test
    fun launcherSheetDark() = saveBitmapEvidence("lapis656-launcher-dark", launcherSheet(dark = true))

    @Test
    fun legacyStartingWindow() {
        val bitmap = Bitmap.createBitmap(UI_EVIDENCE_WIDTH, UI_EVIDENCE_HEIGHT, Bitmap.Config.ARGB_8888)
        val window = context.getDrawable(R.drawable.atlas_launch_background)!!
        window.setBounds(0, 0, bitmap.width, bitmap.height)
        window.draw(Canvas(bitmap))
        assertEquals("ground is Lapis", LAPIS, bitmap.getPixel(40, 300))
        assertEquals("mark is centred", Color.WHITE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2 - dp(20f).toInt()))
        saveBitmapEvidence("lapis656-splash-api29-30", bitmap)
    }

    private fun dp(v: Float) = v * context.resources.displayMetrics.density

    private fun launcherSheet(dark: Boolean): Bitmap {
        val w = UI_EVIDENCE_WIDTH
        val h = UI_EVIDENCE_HEIGHT
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Busy synthetic wallpaper: a two-tone gradient with overlapping translucent blobs.
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            if (dark) intArrayOf(0xFF101826.toInt(), 0xFF3A1F3D.toInt(), 0xFF0E3B3A.toInt())
            else intArrayOf(0xFFF6E7C8.toInt(), 0xFF9FD3E6.toInt(), 0xFFE7B7C8.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        val random = java.util.Random(656L)
        repeat(60) {
            paint.color = Color.argb(
                if (dark) 70 else 90,
                random.nextInt(256), random.nextInt(256), random.nextInt(256)
            )
            canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h, 40f + random.nextFloat() * 220f, paint)
        }
        val ink = if (dark) Color.WHITE else 0xFF111111.toInt()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = dp(12f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
            setShadowLayer(dp(2f), 0f, 0f, if (dark) Color.BLACK else Color.WHITE)
        }
        val icon = dp(48f)
        val adaptive = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        // Home grid: 4 x 4 fictional apps with Atlas among them.
        val names = listOf(
            "Notes", "Clock", "Weather", "Music", "Maps", "Mail", "ATLAS", "Files",
            "Calendar", "Camera", "Chat", "Wallet", "News", "Fitness", "Recorder", "Radio"
        )
        val colW = w / 4f
        val top = dp(120f)
        val rowH = icon + dp(40f)
        for ((i, name) in names.withIndex()) {
            val cx = colW * (i % 4) + colW / 2
            val y = top + rowH * (i / 4)
            val shape = if (i % 2 == 0) Mask.SQUIRCLE else Mask.ROUND
            if (name == "ATLAS") drawAdaptive(canvas, adaptive, cx - icon / 2, y, icon, Mask.SQUIRCLE, null)
            else drawFictionalApp(canvas, cx - icon / 2, y, icon, shape, random, name[0])
            canvas.drawText(name, cx, y + icon + dp(16f), text)
        }
        // Matrix: each variant at 48 dp, labelled.
        val captions = Paint(text).apply { textSize = dp(10f) }
        val header = Paint(text).apply { textSize = dp(13f); typeface = Typeface.DEFAULT_BOLD }
        val matrixTop = top + rowH * 4 + dp(24f)
        canvas.drawText(
            "ATLAS 48 dp · ${if (dark) "dark" else "light"} launcher · Android-rendered resources",
            w / 2f, matrixTop, header
        )
        val tint = if (dark) THEMED_DARK else THEMED_LIGHT
        val variants = listOf<Triple<String, Mask, Pair<Int, Int>?>>(
            Triple("round · colour", Mask.ROUND, null),
            Triple("squircle · colour", Mask.SQUIRCLE, null),
            Triple("system mask", Mask.SYSTEM, null),
            Triple("round · themed", Mask.ROUND, tint),
            Triple("squircle · themed", Mask.SQUIRCLE, tint),
            Triple("round · mono layer", Mask.ROUND, MONO_LAYER),
            Triple("squircle · mono layer", Mask.SQUIRCLE, MONO_LAYER),
            Triple("system · themed", Mask.SYSTEM, tint)
        )
        for ((i, v) in variants.withIndex()) {
            val cx = colW * (i % 4) + colW / 2
            val y = matrixTop + dp(24f) + rowH * (i / 4)
            drawAdaptive(canvas, adaptive, cx - icon / 2, y, icon, v.second, v.third)
            canvas.drawText(v.first, cx, y + icon + dp(16f), captions)
        }
        return bitmap
    }

    private enum class Mask { ROUND, SQUIRCLE, SYSTEM }

    private fun maskPath(mask: Mask, size: Float): Path = Path().apply {
        if (mask == Mask.ROUND) {
            addCircle(size / 2, size / 2, size / 2, Path.Direction.CW)
        } else {
            // Superellipse |x|^4 + |y|^4 = 1: a launcher-style squircle.
            val r = size / 2
            for (step in 0..360) {
                val t = Math.toRadians(step.toDouble())
                val c = cos(t)
                val s = sin(t)
                val x = r + r * Math.signum(c) * abs(c).pow(0.5)
                val y = r + r * Math.signum(s) * abs(s).pow(0.5)
                if (step == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
            }
            close()
        }
    }

    /**
     * Draws the adaptive icon at [size] px. The 108 dp layers overhang the 72 dp visible mask by
     * a quarter of [size] on each side. [tones] = (ground, ink) draws the monochrome layer tinted,
     * as a themed-icon launcher does.
     */
    private fun drawAdaptive(
        canvas: Canvas, icon: AdaptiveIconDrawable, left: Float, top: Float, size: Float,
        mask: Mask, tones: Pair<Int, Int>?
    ) {
        canvas.save()
        canvas.translate(left, top)
        val s = size.toInt()
        val o = (size / 4).toInt()
        if (mask == Mask.SYSTEM && tones == null) {
            val copy = icon.constantState!!.newDrawable(context.resources).mutate()
            copy.setBounds(0, 0, s, s)
            copy.draw(canvas)
        } else {
            if (mask == Mask.SYSTEM) canvas.clipPath(scaledSystemMask(icon, size))
            else canvas.clipPath(maskPath(mask, size))
            if (tones == null) {
                for (layer in listOf(icon.background, icon.foreground)) {
                    val copy = layer.constantState!!.newDrawable(context.resources).mutate()
                    copy.setBounds(-o, -o, s + o, s + o)
                    copy.draw(canvas)
                }
            } else {
                canvas.drawColor(tones.first)
                val mono = requireNotNull(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) icon.monochrome else null
                ) { "monochrome layer needs API 33+" }
                val copy = mono.constantState!!.newDrawable(context.resources).mutate()
                copy.setTint(tones.second)
                copy.setBounds(-o, -o, s + o, s + o)
                copy.draw(canvas)
            }
        }
        canvas.restore()
    }

    /** The system's adaptive-icon mask at [size] px (the drawable scales it to its bounds). */
    private fun scaledSystemMask(icon: AdaptiveIconDrawable, size: Float): Path {
        val copy = icon.constantState!!.newDrawable(context.resources).mutate() as AdaptiveIconDrawable
        copy.setBounds(0, 0, size.toInt(), size.toInt())
        return Path(copy.iconMask)
    }

    private fun drawFictionalApp(
        canvas: Canvas, left: Float, top: Float, size: Float, mask: Mask, random: java.util.Random, glyph: Char
    ) {
        canvas.save()
        canvas.translate(left, top)
        canvas.clipPath(maskPath(mask, size))
        val hue = random.nextFloat() * 360f
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf((hue + 180f) % 360f, 0.35f, 1f))
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(glyph.toString(), size / 2, size * 0.68f, paint)
        canvas.restore()
    }
}

/** Real screen captures of the platform splash while MainActivity starts (API 31+). */
abstract class LapisColdStartSplashEvidence(private val dark: Boolean) {
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(DisplayPrefsRule(dark = dark))

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun coldStartShowsStaticLapisSplash() {
        val name = "lapis656-splash-${if (dark) "dark" else "light"}"
        var captured: Bitmap? = null
        var attempts = 0
        val seen = mutableListOf<String>()
        while (captured == null && attempts < 3) {
            attempts++
            val (frame, note) = launchAndCatchSplash()
            captured = frame
            seen += note
            finishMainActivities()
        }
        val frame = requireNotNull(captured) {
            "No Lapis splash frame with legible system-bar glyphs in $attempts launches of MainActivity " +
                "(${seen.joinToString("; ")}); ${describeFocus()}"
        }
        saveBitmapEvidence(name, frame)
    }

    /**
     * Launches MainActivity and returns the first frame that is the Lapis splash with legible
     * (near-white) navigation-bar glyphs, or null and what it saw instead (#763 review: the
     * dark-mode capture had grey glyphs at about 1.1:1 on Lapis).
     */
    private fun launchAndCatchSplash(): Pair<Bitmap?, String> {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The icon splash a launcher tap gets, not the solid-colour one.
            options.setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_ICON)
        }
        context.startActivity(intent, options.toBundle())
        var splashFrames = 0
        var bestGlyphs = 0
        val deadline = SystemClock.uptimeMillis() + 6_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val shot = instrumentation.uiAutomation.takeScreenshot() ?: continue
            val frame = if (shot.config == Bitmap.Config.HARDWARE) {
                shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() }
            } else shot
            if (isLapisSplash(frame)) {
                splashFrames++
                val glyphs = legibleNavGlyphPixels(frame)
                bestGlyphs = maxOf(bestGlyphs, glyphs)
                if (glyphs >= MIN_NAV_GLYPH_PIXELS) return frame to "ok"
            } else if (splashFrames > 0) {
                frame.recycle()
                break // the app's first frame replaced the splash
            }
            frame.recycle()
        }
        return null to "$splashFrames splash frames, best $bestGlyphs light glyph px (need $MIN_NAV_GLYPH_PIXELS)"
    }

    /**
     * Pixels in the bottom navigation band that are near white and so read on Lapis (>= 4.5:1;
     * Lapis has relative luminance ~0.07, so glyphs need luminance >= ~0.48, i.e. channels ~190+).
     */
    private fun legibleNavGlyphPixels(frame: Bitmap): Int {
        val band = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        var light = 0
        for (y in frame.height - band until frame.height step 2) for (x in 0 until frame.width step 2) {
            val c = frame.getPixel(x, y)
            if (Color.red(c) >= 190 && Color.green(c) >= 190 && Color.blue(c) >= 190) light++
        }
        return light
    }

    /** Mostly Lapis away from the centre and white mark pixels near it: the splash, not the app. */
    private fun isLapisSplash(frame: Bitmap): Boolean {
        var lapis = 0
        var samples = 0
        val margin = frame.height / 8
        for (y in margin until frame.height - margin step 60) for (x in 0 until frame.width step 60) {
            samples++
            if (near(frame.getPixel(x, y), LAPIS)) lapis++
        }
        var white = 0
        val cx = frame.width / 2
        val cy = frame.height / 2
        val r = frame.width / 6
        for (y in cy - r until cy + r step 6) for (x in cx - r until cx + r step 6) {
            if (near(frame.getPixel(x, y), Color.WHITE)) white++
        }
        return lapis * 10 >= samples * 7 && white > 20
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 12 && abs(Color.green(a) - Color.green(b)) <= 12 &&
            abs(Color.blue(a) - Color.blue(b)) <= 12

    private fun finishMainActivities() {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var open = 0
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                for (stage in listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED)) {
                    for (activity in monitor.getActivitiesInStage(stage)) {
                        if (activity is MainActivity) {
                            open++
                            if (!activity.isFinishing) activity.finish()
                        }
                    }
                }
            }
            if (open == 0) return
            instrumentation.waitForIdleSync()
        }
    }
}

/**
 * The installed icon in the emulator's own launcher (#763 review): home, swipe up to the app
 * list, wait until the launcher shows an "ATLAS" item, then capture the real screen.
 */
abstract class LapisRealLauncherEvidence(private val dark: Boolean) {
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(DisplayPrefsRule(dark = dark))

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Saves the very frame in which the installed icon was seen, twice in a row at the same
     * place (run 44858363133: the light test found the icon, then the launcher, recreated for
     * the night-mode change, closed its app list before a second screenshot was taken).
     */
    @Test
    fun installedIconInEmulatorLauncherAppList() {
        quietFor(10_000L)
        var frame: Bitmap? = null
        var tries = 0
        while (frame == null && tries < 4) {
            tries++
            nl.atlas.gallery.testkit.testShell("input keyevent KEYCODE_HOME")
            quietFor(5_000L)
            // Open the app list: a swipe up on the workspace, or the all-apps key on alternate tries.
            if (tries % 2 == 1) nl.atlas.gallery.testkit.testShell("input swipe 540 1900 540 500 300")
            else nl.atlas.gallery.testkit.testShell("input keyevent KEYCODE_ALL_APPS")
            quietFor(5_000L)
            frame = awaitStableIcon(8_000L)
        }
        val shot = requireNotNull(frame) {
            "ATLAS icon not seen in the emulator launcher after $tries tries ($lastScan); ${describeFocus()}"
        }
        uiEvidenceProblem(shot)?.let { problem ->
            shot.recycle()
            error("Launcher frame $problem")
        }
        saveBitmapEvidence("lapis656-real-launcher-${if (dark) "dark" else "light"}", shot)
        nl.atlas.gallery.testkit.testShell("input keyevent KEYCODE_HOME")
    }

    private fun quietFor(timeoutMs: Long) {
        runCatching { instrumentation.uiAutomation.waitForIdle(500L, timeoutMs) }
    }

    private var lastScan = "no scan"

    /** A frame with the icon at the same place as in the frame before it, or null. */
    private fun awaitStableIcon(timeoutMs: Long): Bitmap? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var previous: android.graphics.Rect? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val frame = screenshot() ?: continue
            val box = lapisIcon(frame)
            if (box != null && previous != null && near(box, previous)) {
                android.util.Log.i("ATLAS_LAPIS", "launcher icon at $box (dark=$dark)")
                return frame
            }
            frame.recycle()
            previous = box
            quietFor(1_000L)
        }
        android.util.Log.i("ATLAS_LAPIS", "no stable launcher icon: $lastScan")
        return null
    }

    private fun near(a: android.graphics.Rect, b: android.graphics.Rect) =
        abs(a.left - b.left) <= 6 && abs(a.top - b.top) <= 6 && abs(a.right - b.right) <= 6 && abs(a.bottom - b.bottom) <= 6

    private fun screenshot(): Bitmap? {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        return if (shot.config == Bitmap.Config.HARDWARE) {
            shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() }
        } else shot
    }

    /**
     * The installed icon on the real screen: a square, mostly filled, launcher-icon-sized patch
     * of exact Lapis ground with the white mark in its centre. No other emulator app uses Lapis.
     * (The launcher window is not in the accessibility window list on the CI image.)
     */
    private fun lapisIcon(frame: Bitmap): android.graphics.Rect? {
        val step = 2
        var count = 0
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until frame.height step step) for (x in 0 until frame.width step step) {
            val c = frame.getPixel(x, y)
            if (abs(Color.red(c) - Color.red(LAPIS)) <= 6 && abs(Color.green(c) - Color.green(LAPIS)) <= 6 &&
                abs(Color.blue(c) - Color.blue(LAPIS)) <= 6) {
                count++
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        if (count == 0) { lastScan = "no Lapis pixels"; return null }
        val box = android.graphics.Rect(left, top, right + 1, bottom + 1)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val sideOk = box.width() in (30 * density).toInt()..(90 * density).toInt() &&
            box.height() in (30 * density).toInt()..(90 * density).toInt()
        val square = box.width() * 4 >= box.height() * 3 && box.height() * 4 >= box.width() * 3
        val filled = count * step * step * 2 >= box.width() * box.height()
        var white = 0
        val inner = android.graphics.Rect(box).apply { inset(box.width() / 4, box.height() / 5) }
        for (y in inner.top until inner.bottom step step) for (x in inner.left until inner.right step step) {
            val c = frame.getPixel(x, y)
            if (Color.red(c) > 235 && Color.green(c) > 235 && Color.blue(c) > 235) white++
        }
        lastScan = "Lapis box $box, $count Lapis and $white white samples, side=$sideOk square=$square filled=$filled"
        return if (sideOk && square && filled && white > 20) box else null
    }
}

@RunWith(AndroidJUnit4::class)
class LapisRealLauncherLightEvidenceTest : LapisRealLauncherEvidence(dark = false)

@RunWith(AndroidJUnit4::class)
class LapisRealLauncherDarkEvidenceTest : LapisRealLauncherEvidence(dark = true)

@RunWith(AndroidJUnit4::class)
class LapisColdStartSplashLightEvidenceTest : LapisColdStartSplashEvidence(dark = false)

@RunWith(AndroidJUnit4::class)
class LapisColdStartSplashDarkEvidenceTest : LapisColdStartSplashEvidence(dark = true)

private val LAPIS = 0xFF2639C9.toInt()

/** Light glyph pixels (sampled every 2 px) the three-button nav bar shows at minimum. */
private const val MIN_NAV_GLYPH_PIXELS = 40

/** Illustrative themed-icon tones (ground, ink); real launchers derive theirs from the wallpaper. */
private val THEMED_LIGHT = 0xFFE3E6FA.toInt() to 0xFF2A3270.toInt()
private val THEMED_DARK = 0xFF2E3142.toInt() to 0xFFC9CEF5.toInt()
/** The raw monochrome layer on white, as the asset itself, not a launcher output. */
private val MONO_LAYER = Color.WHITE to Color.BLACK

private fun android.content.res.Resources.Theme.resolve(attr: Int): TypedValue =
    TypedValue().also { check(resolveAttribute(attr, it, true)) { "attribute $attr not in theme" } }

private fun saveBitmapEvidence(name: String, bitmap: Bitmap) {
    val png = try {
        ByteArrayOutputStream().also {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" }
        }.toByteArray()
    } finally {
        bitmap.recycle()
    }
    writeUiEvidenceFile(uiEvidenceDisplayName("$name.png"), png)
}
