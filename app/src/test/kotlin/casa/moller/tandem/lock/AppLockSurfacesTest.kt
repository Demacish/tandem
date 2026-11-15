package casa.moller.tandem.lock

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.MainActivity
import casa.moller.tandem.core.data.security.AppLock
import casa.moller.tandem.core.sync.security.DefaultAppLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Fas 5 (las lane, plan §5.5, "Klart när": nothing on the alarm path behind the lock). The lock
 * lives in MainActivity only (its lock screen); the full-screen alarm is an activity of its own
 * that shows on the phone's lock screen, and neither it nor the timers' service and receivers
 * (Stop, +1 min, boot), the widgets' receivers, the tile or the watch's listener hold the lock.
 * The merged manifest and the classes are read, not a copy of them.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppLockSurfacesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun theAlarmIsItsOwnActivityOnThePhonesLockScreen() {
        val alarm = context.packageManager.getActivityInfo(
            ComponentName(context, ALARM_ACTIVITY),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertTrue("the alarm shows on the phone's lock screen", (alarm.flags and ActivityInfo.FLAG_SHOW_WHEN_LOCKED) != 0)
        assertNotEquals(MainActivity::class.java.name, alarm.name)
    }

    @Test
    fun nothingOnTheAlarmTimerReminderWidgetOrWatchPathHoldsTheLock() {
        EXEMPT_COMPONENTS.forEach { name ->
            val type = Class.forName(name)
            val holders = generateSequence(type) { it.superclass }
                // The class and Hilt's generated base class (same package); not the framework's.
                .takeWhile { it.name.startsWith("casa.moller.tandem.") }
                .flatMap { it.declaredFields.asSequence() }
                .filter { AppLock::class.java.isAssignableFrom(it.type) || it.type == DefaultAppLock::class.java }
                .toList()
            assertTrue("$name holds the app lock: $holders", holders.isEmpty())
        }
    }

    @Test
    fun onlyTheAppScreensAreBehindTheLock() {
        val activities = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            .activities
            .orEmpty()
            .map { it.name }
        assertTrue(MainActivity::class.java.name in activities)
        assertTrue(ALARM_ACTIVITY in activities)
        val main = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertFalse("MainActivity must not show on the phone's lock screen", (main.flags and ActivityInfo.FLAG_SHOW_WHEN_LOCKED) != 0)
    }

    private companion object {
        const val ALARM_ACTIVITY = "casa.moller.tandem.core.timer.ring.TimerAlarmActivity"

        val EXEMPT_COMPONENTS = listOf(
            ALARM_ACTIVITY,
            "casa.moller.tandem.core.timer.ring.TimerRingingService",
            "casa.moller.tandem.core.timer.alarm.TimerAlarmReceiver",
            "casa.moller.tandem.core.timer.alarm.TimerSystemEventReceiver",
            "casa.moller.tandem.feature.widgets.ShoppingListWidgetReceiver",
            "casa.moller.tandem.feature.widgets.TonightWidgetReceiver",
            "casa.moller.tandem.feature.widgets.ShoppingTileService",
            "casa.moller.tandem.core.watch.PhoneWearListenerService",
            "casa.moller.tandem.core.sync.storereminder.StoreGeofenceReceiver",
        )
    }
}
