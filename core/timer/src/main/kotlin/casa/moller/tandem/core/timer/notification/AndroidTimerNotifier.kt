package casa.moller.tandem.core.timer.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.model.notification.ChannelImportance
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.timer.R
import casa.moller.tandem.core.timer.TimerIntents
import casa.moller.tandem.core.timer.platform.TimerSystem
import casa.moller.tandem.core.timer.ring.TimerAlarmActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The timer notifications on Android (plan 8.1, 8.7; channels from core:model, fixed forever).
 *
 * - **Countdown** (`timer_ongoing`, low, silent): one ongoing notification per running or paused
 *   timer (id [TandemNotificationIds.timerOngoing]). A running timer shows a counting-down
 *   chronometer to `endsAt` and asks to be a promoted ongoing notification, which Android 16
 *   shows as a Live Update (status bar chip with the countdown); older versions ignore the
 *   request and show the same notification. Actions: Pause and "+1 min", or Resume and Cancel.
 * - **Alarm** (`timer_alarm`, high, alarm sound with `USAGE_ALARM`, vibration): one per ringing
 *   timer (id [TandemNotificationIds.timerAlarm]), insistent (the sound repeats until it is
 *   stopped or silenced), with "+1 min" and "Stop", and a full-screen intent to
 *   [TimerAlarmActivity] when the build may use it (sideload); otherwise a heads-up.
 * - **Ringing service** ([TandemNotificationIds.TIMER_RINGING_SERVICE]): "2 timers are ringing",
 *   "Stop all"; silent, on its own and deliberately NOT the summary of the alarms' group (plan
 *   8.1): when an app removes a group summary, Android removes the group's children too, so the
 *   service stopping would take the still ringing alarms and the "Rang at 18:45" notices with it.
 * - **One sound at a time:** Android plays one notification sound at a time, so when a second
 *   alarm posts, its sound replaces the first one's, and cancelling it leaves the first one silent.
 *   [realertAlarm] posts a still ringing alarm again whenever another one stops.
 *
 * Every post checks `POST_NOTIFICATIONS` first; without it nothing is shown (the timers still run).
 */
@Singleton
internal class AndroidTimerNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val system: TimerSystem,
    private val clock: Clock,
) : TimerNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun showOngoing(timer: CookTimer) {
        val builder = NotificationCompat.Builder(context, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tandem_timer)
            .setContentTitle(timer.label)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSortKey(sortKey(timer))
            .setContentIntent(TimerIntents.openTimerPendingIntent(context, timer))
        when (timer.state) {
            TimerState.RUNNING -> {
                val endsAt = timer.endsAt ?: return
                builder.setWhen(endsAt.toEpochMilliseconds())
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setContentText(context.getString(R.string.timer_notification_running, clockTime(endsAt)))
                    // Android 16 Live Update: ongoing + title + standard style + this request.
                    .setRequestPromotedOngoing(true)
                    .addAction(0, context.getString(R.string.timer_action_pause), action(TimerIntents.ACTION_PAUSE, timer.id))
                    .addAction(0, context.getString(R.string.timer_action_plus_one_minute), plusOneMinute(timer.id))
            }

            TimerState.PAUSED -> {
                val left = DateUtils.formatElapsedTime(timer.remaining(clock.now()).inWholeSeconds)
                builder.setShowWhen(false)
                    .setContentText(context.getString(R.string.timer_notification_paused, left))
                    .addAction(0, context.getString(R.string.timer_action_resume), action(TimerIntents.ACTION_RESUME, timer.id))
                    .addAction(0, context.getString(R.string.timer_action_cancel), action(TimerIntents.ACTION_CANCEL, timer.id))
            }

            TimerState.RINGING, TimerState.FINISHED -> return
        }
        post(TandemNotificationIds.timerOngoing(timer.id), builder.build())
    }

    override fun cancelOngoing(timerId: Long) = manager.cancel(TandemNotificationIds.timerOngoing(timerId))

    override fun showAlarm(timer: CookTimer) {
        val id = TandemNotificationIds.timerAlarm(timer.id)
        // Posting again would restart the sound and the heads-up; one alarm per ring is enough.
        if (isShown(id)) return
        postAlarm(id, timer)
    }

    private fun postAlarm(id: Int, timer: CookTimer) {
        val builder = alarmBuilder(timer)
            .setContentText(context.getString(R.string.timer_notification_ringing))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(action(TimerIntents.ACTION_STOP, timer.id))
            .addAction(0, context.getString(R.string.timer_action_plus_one_minute), plusOneMinute(timer.id))
            .addAction(0, context.getString(R.string.timer_action_stop), action(TimerIntents.ACTION_STOP, timer.id))
        if (system.fullScreenAllowed()) builder.setFullScreenIntent(alarmScreenIntent(), true)
        val notification = builder.build()
        // The alarm sound and vibration repeat until the notification goes away (Stop, +1 min, auto-silence).
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        post(id, notification)
    }

    override fun showMissed(timer: CookTimer) {
        val rangAt = timer.endsAt ?: return
        val notification = alarmBuilder(timer)
            .setContentText(context.getString(R.string.timer_notification_missed, clockTime(rangAt)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        post(TandemNotificationIds.timerAlarm(timer.id), notification)
    }

    override fun cancelAlarm(timerId: Long) = manager.cancel(TandemNotificationIds.timerAlarm(timerId))

    override fun realertAlarm(timer: CookTimer) {
        val id = TandemNotificationIds.timerAlarm(timer.id)
        // Not through showAlarm: the system removes a cancelled notification asynchronously, so
        // isShown could still see it and the alarm would vanish. The system handles the cancel and
        // the new post in order, so the new post is a fresh alarm that sounds again.
        manager.cancel(id)
        postAlarm(id, timer)
    }

    override fun ringingServiceNotification(ringing: List<CookTimer>): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tandem_timer)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.timer_notification_ringing_summary, ringing.size, ringing.size),
            )
            .setContentText(ringing.joinToString(", ") { it.label })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Never a group summary (see the class comment); the alarms themselves ring, this one never alerts.
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(TimerIntents.openTimerPendingIntent(context, ringing.firstOrNull()))
            .addAction(0, context.getString(R.string.timer_action_stop_all), action(TimerIntents.ACTION_STOP_ALL, null))
            .build()
    }

    /** Creates the two timer channels (idempotent; an existing channel only gets its name and description updated). */
    fun ensureChannels() {
        val alarmChannel = TandemNotificationChannel.TIMER_ALARM
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(alarmChannel.id, alarmChannel.importance.toAndroid())
                .setName(context.getString(R.string.timer_channel_alarm_name))
                .setDescription(context.getString(R.string.timer_channel_alarm_description))
                .setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setVibrationEnabled(true)
                .setVibrationPattern(ALARM_VIBRATION)
                .build(),
        )
        val ongoingChannel = TandemNotificationChannel.TIMER_ONGOING
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(ongoingChannel.id, ongoingChannel.importance.toAndroid())
                .setName(context.getString(R.string.timer_channel_ongoing_name))
                .setDescription(context.getString(R.string.timer_channel_ongoing_description))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build(),
        )
    }

    private fun alarmBuilder(timer: CookTimer): NotificationCompat.Builder = NotificationCompat.Builder(context, ALARM_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_tandem_timer)
        .setContentTitle(timer.label)
        .setWhen(timer.endsAt?.toEpochMilliseconds() ?: System.currentTimeMillis())
        .setShowWhen(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setGroup(ALARM_GROUP)
        .setSortKey(sortKey(timer))
        .setContentIntent(TimerIntents.openTimerPendingIntent(context, timer))

    private fun post(id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannels()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Revoked between the check and the call: the timer still runs and rings in the app.
        }
    }

    private fun isShown(id: Int): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id }

    private fun action(action: String, timerId: Long?): PendingIntent = TimerIntents.receiverPendingIntent(context, action, timerId)

    private fun plusOneMinute(timerId: Long): PendingIntent = action(TimerIntents.ACTION_PLUS_ONE_MINUTE, timerId)

    private fun alarmScreenIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, TimerAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun clockTime(instant: Instant): String = DateFormat.getTimeFormat(context).format(Date(instant.toEpochMilliseconds()))

    /** Oldest timer first, like cook mode lists them. */
    private fun sortKey(timer: CookTimer): String {
        val created = timer.createdAt.toEpochMilliseconds().toString().padStart(SORT_KEY_DIGITS, '0')
        return created + "-" + timer.id.toString().padStart(SORT_KEY_DIGITS, '0')
    }

    private fun ChannelImportance.toAndroid(): Int = when (this) {
        ChannelImportance.LOW -> NotificationManagerCompat.IMPORTANCE_LOW
        ChannelImportance.DEFAULT -> NotificationManagerCompat.IMPORTANCE_DEFAULT
        ChannelImportance.HIGH -> NotificationManagerCompat.IMPORTANCE_HIGH
    }

    companion object {
        val ALARM_CHANNEL: String = TandemNotificationChannel.TIMER_ALARM.id
        val ONGOING_CHANNEL: String = TandemNotificationChannel.TIMER_ONGOING.id

        /** The alarms and the "Rang at" notices form one group without an app summary (see the class comment). */
        const val ALARM_GROUP = "casa.moller.tandem.timer.ALARMS"

        private const val SORT_KEY_DIGITS = 20
        private val ALARM_VIBRATION = longArrayOf(0L, 600L, 400L, 600L, 400L, 600L, 1200L)
    }
}
