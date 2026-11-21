package nl.atlas.gallery.testkit

import android.app.Activity
import android.os.Bundle

/**
 * Synthetic share recipient for the USAGE-0 (#173) chooser capture: handles ACTION_SEND of a
 * JSON file so Android's share sheet shows the file name and a real target. It reads nothing
 * and finishes at once. Declared in the androidTest manifest only; it never ships in ATLAS.
 */
class UsageShareTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
