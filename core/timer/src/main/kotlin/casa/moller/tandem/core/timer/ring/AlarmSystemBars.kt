package casa.moller.tandem.core.timer.ring

import android.view.Window
import androidx.core.view.WindowCompat

/**
 * Dark system bars around the dark lock-screen alarm (device test 2026-11-14, B14): light icons on
 * the status and navigation bar and no contrast scrim behind them, whatever the app theme (light,
 * dark or the system's). The theme `TandemTimer.Alarm` says the same; this also covers a window
 * whose decor was set up before the theme took effect.
 */
internal object AlarmSystemBars {
    fun apply(window: Window) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
}
