package nl.atlas.gallery.uitest;

import android.inputmethodservice.InputMethodService;

/**
 * The "no keyboard" input method for emulator tests: a text field can take
 * focus and text, and nothing ever slides up or resizes the screen.
 * Java and android.* only, for the reason given in {@link AtlasTestKeyboard}.
 */
public class AtlasNoKeyboard extends InputMethodService {
    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return false;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }
}
