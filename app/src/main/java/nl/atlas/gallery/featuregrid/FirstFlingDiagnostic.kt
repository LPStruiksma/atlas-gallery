package nl.atlas.gallery.featuregrid

import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Temporary diagnostic carrier for #1013. Disabled by default; the synthetic
 * emulator benchmark opts in before Activity launch. No media identifiers,
 * production logging, consumption, delays or gesture retries.
 * Remove this probe when the causal fix replaces the diagnostic head.
 */
object FirstFlingDiagnostic {
    @Volatile var enabled = false
    private const val LIMIT = 512
    private val entries = ArrayList<String>()
    private var active = false
    private var dropped = 0

    @Synchronized fun begin() {
        entries.clear()
        dropped = 0
        active = true
    }

    @Synchronized fun record(sample: String) {
        if (!enabled || !active) return
        if (entries.size < LIMIT) entries.add("observed=${SystemClock.uptimeMillis()} $sample")
        else dropped++
    }

    @Synchronized fun end(): List<String> {
        active = false
        return (entries.toList() + "dropped=$dropped").also { entries.clear() }
    }

    // Observe both ends of dispatch without consuming or claiming the pointer.
    // Positions here are grid-local; window samples use screen coordinates.
    val pointerModifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val initial = awaitPointerEvent(PointerEventPass.Initial)
                initial.changes.forEach { change ->
                    record("grid INITIAL t=${change.uptimeMillis} pressed=${change.pressed} prevPressed=${change.previousPressed} local=${change.position} consumed=${change.isConsumed}")
                }
                val final = awaitPointerEvent(PointerEventPass.Final)
                final.changes.forEach { change ->
                    record("grid FINAL t=${change.uptimeMillis} pressed=${change.pressed} prevPressed=${change.previousPressed} local=${change.position} consumed=${change.isConsumed}")
                }
            }
        }
    }
}
