package casa.moller.tandem.feature.cook.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

/**
 * Window state that cook mode changes while it is shown: with "Kokläge alltid mörkt", light system
 * bar icons on the dark theme. (The screen kept on, plan 6.2 "Skärmen hålls på", is core:ui's
 * `KeepScreenOn`, shared with store mode since the Phase 2 review, fix-ui.)
 *
 * Reference counted per window (the activity's root view), never set and cleared by each cook
 * entry on its own: Navigation 3 keeps the outgoing entry composed during the transition, so when
 * one cook mode replaces another (a timer notification for another recipe, or "Klart!" popping
 * back to the cook mode below) the new entry's effect runs before the old one's `onDispose`. An
 * unconditional reset there would give the visible cook mode dark icons on its dark bars.
 */
internal class CookWindowState {
    private var darkBarHolders = 0
    private var savedLightStatus: Boolean? = null
    private var savedLightNavigation: Boolean? = null

    /** Light icons on the bars while any always-dark cook mode is shown; the first holder remembers the app's look. */
    fun acquireDarkBars(controller: WindowInsetsControllerCompat?) {
        if (darkBarHolders++ == 0) {
            savedLightStatus = controller?.isAppearanceLightStatusBars
            savedLightNavigation = controller?.isAppearanceLightNavigationBars
        }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
    }

    /** The last holder puts the app's own bar appearance back. */
    fun releaseDarkBars(controller: WindowInsetsControllerCompat?) {
        darkBarHolders = (darkBarHolders - 1).coerceAtLeast(0)
        if (darkBarHolders > 0) return
        savedLightStatus?.let { controller?.isAppearanceLightStatusBars = it }
        savedLightNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
        savedLightStatus = null
        savedLightNavigation = null
    }

    companion object {
        private val byRoot = WeakHashMap<View, CookWindowState>()

        /** The state of [view]'s window (one per root view, shared by every cook entry in it). */
        fun of(view: View): CookWindowState = synchronized(byRoot) { byRoot.getOrPut(view.rootView) { CookWindowState() } }
    }
}

/** Light system bar icons while [enabled] ("Kokläge alltid mörkt"); see [CookWindowState]. */
@Composable
internal fun DarkSystemBars(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val window = CookWindowState.of(view)
        val controller = view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        window.acquireDarkBars(controller)
        onDispose { window.releaseDarkBars(controller) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
