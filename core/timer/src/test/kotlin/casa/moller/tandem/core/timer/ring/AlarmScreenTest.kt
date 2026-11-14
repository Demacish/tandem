package casa.moller.tandem.core.timer.ring

import android.app.Activity
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.timer.R
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Android's qualifier order: night mode before the density.
private const val PHONE_LIGHT = "w412dp-h915dp-port-notnight-xhdpi"
private const val PHONE_DARK = "w412dp-h915dp-port-night-xhdpi"
private const val PHONE_SMALL_LIGHT = "w360dp-h780dp-port-notnight-xhdpi"
private const val TABLET_LIGHT = "w1280dp-h800dp-land-notnight-hdpi"

/**
 * Device test 2026-11-14 (B14): the lock-screen alarm is dark with dark system bars in both app
 * themes. "notnight" is Tandem's light theme (or a light system), "night" the dark one; One UI
 * drew the old DeviceDefault parent light there, with a light navigation bar under the alarm.
 * The screenshots (prefix `devicetest_alarm_screen_`) show the alarm's own layout in the theme;
 * Robolectric draws no system bars, so the bars are checked on the window and in the theme.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmScreenTest {

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun lightAppThemeKeepsTheAlarmAndItsBarsDark() {
        val activity = alarmScreen("Pastan · 10 min (Krämig svamppasta)")
        assertDarkBars(activity)
        // Theme.Material's buttons are in capitals; the alarm keeps "Stop" as written (TalkBack, the smoke test).
        assertFalse(activity.findViewById<Button>(R.id.timer_alarm_stop).isAllCaps)
        assertFalse(activity.findViewById<Button>(R.id.timer_alarm_plus_one).isAllCaps)
        capture(activity, "phone_light_app_theme")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_DARK")
    fun darkAppThemeSwedish() {
        val activity = alarmScreen("Pastan · 10 min (Krämig svamppasta)", "Gräddning · 7 min (Kanelbullar)")
        assertDarkBars(activity)
        capture(activity, "phone_dark_app_theme_sv")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LIGHT")
    fun fontScale2Swedish() {
        RuntimeEnvironment.setFontScale(2f)
        val activity = alarmScreen("Pastan · 10 min (Krämig svamppasta)")
        assertDarkBars(activity)
        capture(activity, "phone_small_font2_light_app_theme_sv")
    }

    @Test
    @Config(qualifiers = TABLET_LIGHT)
    fun tabletLandscape() {
        val activity = alarmScreen("Pastan · 10 min (Krämig svamppasta)", "Gräddning · 7 min (Kanelbullar)")
        assertDarkBars(activity)
        capture(activity, "tablet_light_app_theme")
    }

    /** The alarm's theme, layout and window bars on a plain activity (the real one needs the Hilt app). */
    private fun alarmScreen(vararg labels: String): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.TandemTimer_Alarm)
        controller.setup()
        activity.setContentView(R.layout.timer_alarm_activity)
        AlarmSystemBars.apply(activity.window)
        val rows = activity.findViewById<LinearLayout>(R.id.timer_alarm_rows)
        labels.forEach { label ->
            val row = LayoutInflater.from(activity).inflate(R.layout.timer_alarm_row, rows, false)
            row.findViewById<TextView>(R.id.timer_alarm_label).text = label
            rows.addView(row)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun assertDarkBars(activity: Activity) {
        val attrs = activity.theme.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.isLightTheme,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar,
                android.R.attr.enforceNavigationBarContrast,
            ),
        )
        try {
            assertFalse("a dark theme", attrs.getBoolean(0, true))
            assertFalse("light status bar icons", attrs.getBoolean(1, true))
            assertFalse("light navigation bar icons", attrs.getBoolean(2, true))
            assertFalse("no light contrast scrim", attrs.getBoolean(3, true))
        } finally {
            attrs.recycle()
        }
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
        assertFalse(activity.window.isNavigationBarContrastEnforced)
    }

    private fun capture(activity: Activity, name: String) {
        activity.window.decorView.captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/timer/devicetest_alarm_screen_$name.png")
    }
}
