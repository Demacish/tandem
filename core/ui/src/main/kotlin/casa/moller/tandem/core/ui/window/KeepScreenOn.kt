package casa.moller.tandem.core.ui.window

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * The window's "keep the screen on" flag, reference counted per window (the activity's root view)
 * and shared by every screen that needs it: cook mode (plan 6.2 "Skärmen hålls på") and store mode
 * (plan 6.2 "Butiksläge").
 *
 * Never set and cleared by each screen on its own: Navigation 3 keeps the outgoing entry composed
 * during the transition, so when one such screen replaces another (a timer notification opens cook
 * mode over store mode, or "Klart!" pops back to the screen below) the new entry's effect runs
 * before the old one's `onDispose`. A save-and-restore of the raw flag there would turn the screen
 * off under the visible screen (fix-ui, Phase 2 review).
 */
class ScreenOnHolders internal constructor() {
    private var holders = 0

    fun acquire(view: View) {
        holders++
        view.keepScreenOn = true
    }

    fun release(view: View) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) view.keepScreenOn = false
    }

    companion object {
        private val byRoot = WeakHashMap<View, ScreenOnHolders>()

        /** The holders of [view]'s window (one per root view, shared by every screen in it). */
        fun of(view: View): ScreenOnHolders = synchronized(byRoot) { byRoot.getOrPut(view.rootView) { ScreenOnHolders() } }
    }
}

/** Keeps the display on while this composable is shown; see [ScreenOnHolders]. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val holders = ScreenOnHolders.of(view)
        holders.acquire(view)
        onDispose { holders.release(view) }
    }
}
