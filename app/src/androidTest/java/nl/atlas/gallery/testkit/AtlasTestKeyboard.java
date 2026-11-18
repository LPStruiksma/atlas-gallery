package nl.atlas.gallery.testkit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * A keyboard for emulator tests only: one flat panel, 918 px tall on a
 * 1080x2340 display (the height of the emulator's own keyboard there) and
 * the same share of any other display, or the height KeyboardRule names. It never asks for a permission, never shows a suggestion
 * strip and has no first-run screens, so the keyboard inset a test sees is
 * the same on every run (see docs/testing/EMULATOR_TESTS.md).
 *
 * Java on purpose. The system starts this service in the test package's own
 * process, which holds only the test APK: the app's classes, AndroidX and the
 * Kotlin standard library are not there. Use nothing but android.* here.
 *
 * It draws no keys and commits no text: tests type through semantics
 * (performTextInput). It lives in the androidTest APK and never ships.
 */
public class AtlasTestKeyboard extends InputMethodService {
    /** Default share of the display height: 918 px of 2340. */
    public static final float HEIGHT_FRACTION = 918f / 2340f;

    /** Global setting with the height in px, written and removed by KeyboardRule. */
    public static final String HEIGHT_SETTING = "atlas_test_keyboard_height_px";

    @Override
    public View onCreateInputView() {
        return new Panel(this, panelHeight());
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        // Also with a hardware keyboard attached to the emulator.
        return true;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        // No full-screen extract editor in landscape.
        return false;
    }

    private int panelHeight() {
        try {
            int asked = Settings.Global.getInt(getContentResolver(), HEIGHT_SETTING, 0);
            if (asked > 0) {
                return asked;
            }
        } catch (RuntimeException unreadable) {
            // Fall back to the default share.
        }
        int display;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display = getSystemService(WindowManager.class)
                    .getMaximumWindowMetrics().getBounds().height();
        } else {
            display = getResources().getDisplayMetrics().heightPixels;
        }
        return Math.round(display * HEIGHT_FRACTION);
    }

    private static final class Panel extends View {
        private final int panelHeight;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Panel(Context context, int panelHeight) {
            super(context);
            this.panelHeight = panelHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), panelHeight);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(0x2B, 0x2D, 0x31));
            paint.setColor(Color.rgb(0xC4, 0xC7, 0xCC));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(getHeight() / 12f);
            canvas.drawText("Test keyboard", getWidth() / 2f, getHeight() / 2f, paint);
        }
    }
}
