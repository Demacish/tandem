package casa.moller.tandem.core.timer

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.timer.alarm.TimerAlarmReceiver
import casa.moller.tandem.core.timer.alarm.TimerSystemEventReceiver
import casa.moller.tandem.core.timer.engine.SystemEvent
import casa.moller.tandem.core.timer.platform.AndroidTimerSystem
import casa.moller.tandem.core.timer.ring.TimerAlarmActivity
import casa.moller.tandem.core.timer.ring.TimerRingingService
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** The Android edges of core:timer: what the system allows, the clock app, the receivers' events and the manifest. */
@RunWith(AndroidJUnit4::class)
class TimerPlatformTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun capabilitiesFollowTheSystem() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val system = AndroidTimerSystem(context)

        val fresh = system.capabilities(notificationPermissionAsked = false)
        assertFalse(fresh.notificationsAllowed)
        assertTrue("asked in context at the first timer", fresh.shouldAskForNotifications)
        assertTrue(fresh.exactAlarmsAllowed)
        assertTrue(fresh.alarmChannelEnabled)
        assertFalse("this module declares no full-screen permission (the sideload app does)", fresh.fullScreenAlarmAllowed)
        assertFalse(fresh.clockAppAvailable)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val later = system.capabilities(notificationPermissionAsked = true)
        assertTrue(later.notificationsAllowed)
        assertFalse(later.shouldAskForNotifications)
        assertFalse(later.exactAlarmsAllowed)
        assertTrue("an inexact alarm may be late", later.alarmMayBeLate)
        // The library declares SCHEDULE_EXACT_ALARM (the sideload app removes it, play keeps it).
        assertTrue("the user can allow exact alarms", later.exactAlarmsRequestable)
    }

    @Test
    fun aSwitchedOffAlarmChannelIsReported() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val switchedOff = NotificationChannel(TandemNotificationChannel.TIMER_ALARM.id, "Timers", NotificationManager.IMPORTANCE_NONE)
        manager.createNotificationChannel(switchedOff)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val capabilities = AndroidTimerSystem(context).capabilities(notificationPermissionAsked = true)

        assertFalse(capabilities.alarmChannelEnabled)
        assertTrue(capabilities.alarmMayBeSilent)
    }

    @Test
    fun theClockAppGetsLengthMessageAndNoUi() {
        val system = AndroidTimerSystem(context)
        assertFalse("no clock app", system.startClockAppTimer("Ägg", 6.minutes))

        val clockApp = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.clock"
                name = "com.example.clock.SetTimer"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(AlarmClock.ACTION_SET_TIMER), clockApp)
        assertTrue(system.capabilities(notificationPermissionAsked = false).clockAppAvailable)

        assertTrue(system.startClockAppTimer("Ägg", 6.minutes))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_TIMER, intent.action)
        assertEquals(360, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertEquals("Ägg", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))

        // The clock app takes at most 24 h.
        assertTrue(system.startClockAppTimer("Surdeg", 30.hours))
        assertEquals(86_400, shadowOf(context).nextStartedActivity.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
    }

    @Test
    fun systemEventsAreRecognisedAndAnythingElseIgnored() {
        assertEquals(SystemEvent.BOOT, TimerSystemEventReceiver.eventFor(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(SystemEvent.PACKAGE_REPLACED, TimerSystemEventReceiver.eventFor(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(SystemEvent.CLOCK_CHANGED, TimerSystemEventReceiver.eventFor(Intent.ACTION_TIME_CHANGED))
        assertEquals(SystemEvent.TIME_ZONE_CHANGED, TimerSystemEventReceiver.eventFor(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(
            SystemEvent.EXACT_ALARM_PERMISSION_CHANGED,
            TimerSystemEventReceiver.eventFor(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
        )
        assertNull(TimerSystemEventReceiver.eventFor("com.example.SPOOFED"))
        assertNull(TimerSystemEventReceiver.eventFor(null))
    }

    @Test
    fun theRingingServiceIsSystemExemptedOnlyWithExactAlarms() {
        val withExact = TimerRingingService.foregroundServiceType(exactAlarmsAllowed = true)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, withExact)
        val withoutExact = TimerRingingService.foregroundServiceType(exactAlarmsAllowed = false)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE, withoutExact)
    }

    @Test
    fun theManifestDeclaresTheSharedPermissionsAndComponents() {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_ACTIVITIES
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        val permissions = info.requestedPermissions.orEmpty().toSet()
        listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.VIBRATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            "android.permission.POST_PROMOTED_NOTIFICATIONS",
            "com.android.alarm.permission.SET_ALARM",
            // Both exact-alarm permissions; each flavor removes one (app/src/sideload, app/src/play).
            Manifest.permission.USE_EXACT_ALARM,
            Manifest.permission.SCHEDULE_EXACT_ALARM,
        ).forEach { assertTrue(it, it in permissions) }
        assertFalse("only the sideload app adds it", Manifest.permission.USE_FULL_SCREEN_INTENT in permissions)

        val service = info.services.orEmpty().single { it.name == TimerRingingService::class.java.name }
        assertFalse(service.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED or ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            service.foregroundServiceType,
        )
        val receivers = info.receivers.orEmpty().associateBy { it.name }
        assertFalse("only our own PendingIntents", receivers.getValue(TimerAlarmReceiver::class.java.name).exported)
        assertTrue("system broadcasts", receivers.getValue(TimerSystemEventReceiver::class.java.name).exported)
        val alarm = info.activities.orEmpty().single { it.name == TimerAlarmActivity::class.java.name }
        assertFalse(alarm.exported)
    }
}
