package casa.moller.tandem.core.timer.platform

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.timer.TimerCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * [TimerSystem] on Android. The flavor is never asked: the build's manifest decides which
 * permissions exist (sideload: `USE_EXACT_ALARM` + `USE_FULL_SCREEN_INTENT`; play:
 * `SCHEDULE_EXACT_ALARM`), and the system answers what is granted now.
 */
@Singleton
internal class AndroidTimerSystem @Inject constructor(@ApplicationContext private val context: Context) : TimerSystem {

    private val notifications = NotificationManagerCompat.from(context)
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /** The permissions this build declares (fixed for an installed APK). */
    private val declaredPermissions: Set<String> by lazy {
        try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
            info.requestedPermissions.orEmpty().toSet()
        } catch (e: PackageManager.NameNotFoundException) {
            emptySet()
        }
    }

    override fun capabilities(notificationPermissionAsked: Boolean): TimerCapabilities {
        val exact = exactAlarmsAllowed()
        return TimerCapabilities(
            notificationsAllowed = notificationsAllowed(),
            notificationPermissionAsked = notificationPermissionAsked,
            alarmChannelEnabled = alarmChannelAudible(),
            exactAlarmsAllowed = exact,
            exactAlarmsRequestable = !exact && Manifest.permission.SCHEDULE_EXACT_ALARM in declaredPermissions,
            fullScreenAlarmAllowed = fullScreenAllowed(),
            clockAppAvailable = clockAppAvailable(),
        )
    }

    override fun notificationsAllowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notifications.areNotificationsEnabled()

    override fun exactAlarmsAllowed(): Boolean = alarmManager.canScheduleExactAlarms()

    override fun fullScreenAllowed(): Boolean =
        Manifest.permission.USE_FULL_SCREEN_INTENT in declaredPermissions && notifications.canUseFullScreenIntent()

    override fun alarmNotificationAudible(): Boolean = notificationsAllowed() && alarmChannelAudible()

    /**
     * True when an alarm notification on `timer_alarm` makes a sound: the channel does not exist
     * yet (it is created with high importance and the alarm sound before the first alarm), or its
     * importance is at least "default" and it still has a sound. A user who long-presses an alarm
     * and picks "Silent" lowers the channel to "low"; setting the sound to "None" keeps the
     * importance. Either way the alarm would ring without a sound, so the UI warns
     * ([TimerCapabilities.alarmMayBeSilent]) and the ringing service plays the sound itself.
     */
    private fun alarmChannelAudible(): Boolean {
        val channel = notifications.getNotificationChannelCompat(TandemNotificationChannel.TIMER_ALARM.id) ?: return true
        return channel.importance >= NotificationManagerCompat.IMPORTANCE_DEFAULT && channel.sound != null
    }

    private fun clockAppAvailable(): Boolean =
        context.packageManager.resolveActivity(clockIntent(), PackageManager.MATCH_DEFAULT_ONLY) != null

    override fun startClockAppTimer(label: String, duration: Duration): Boolean {
        // The engine keeps longer timers in Tandem (TimerSystem.MAX_CLOCK_APP_DURATION); this is only a safety net.
        val seconds = duration.inWholeSeconds.coerceIn(1L, TimerSystem.MAX_CLOCK_APP_DURATION.inWholeSeconds).toInt()
        val intent = clockIntent()
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun clockIntent(): Intent = Intent(AlarmClock.ACTION_SET_TIMER)
}
