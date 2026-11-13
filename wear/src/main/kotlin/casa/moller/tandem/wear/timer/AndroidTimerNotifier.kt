package casa.moller.tandem.wear.timer

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.core.watchlink.TimerActionKind
import casa.moller.tandem.wear.R
import casa.moller.tandem.wear.ui.WearMainActivity

/**
 * The watch's timer notifications, in the plan's channels (§8.7, the same ids as on the phone):
 * "Pågående timrar" (silent, an ongoing activity with the countdown on the watch face) and
 * "Timrar (larm)" (vibrates, "Stoppa"). Posted only when the user allowed notifications on the
 * watch (asked in the app, next to the timers).
 */
class AndroidTimerNotifier(private val context: Context, private val wallClock: () -> Long) : TimerNotifier {
    private val manager = NotificationManagerCompat.from(context)

    override fun show(timers: List<MirroredTimer>) {
        val wanted = mutableSetOf<Int>()
        if (allowed()) {
            ensureChannels()
            timers.forEach { timer ->
                when (timer.state) {
                    MirroredTimerState.RUNNING, MirroredTimerState.PAUSED -> {
                        val id = TandemNotificationIds.timerOngoing(timer.id)
                        if (post(id) { ongoing(timer, id) }) wanted += id
                    }

                    MirroredTimerState.RINGING -> {
                        val id = TandemNotificationIds.timerAlarm(timer.id)
                        if (post(id) { alarm(timer) }) wanted += id
                    }

                    MirroredTimerState.FINISHED -> Unit
                }
            }
        }
        // Everything of ours that is no longer wanted goes (also after a process restart).
        val system = context.getSystemService(NotificationManager::class.java)
        system?.activeNotifications.orEmpty()
            .map { it.id }
            .filter { it in TIMER_IDS && it !in wanted }
            .forEach { manager.cancel(it) }
    }

    private fun ongoing(timer: MirroredTimer, id: Int): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, TandemNotificationChannel.TIMER_ONGOING.id)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(timer.label)
            .setSubText(timer.recipeName)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.timer_cancel), action(timer.id, TimerActionKind.CANCEL))
        val status = when (timer.state) {
            MirroredTimerState.RUNNING -> {
                val endsAt = timer.endsAtEpochMillis ?: wallClock()
                builder.setWhen(endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                Status.Builder()
                    .addTemplate(context.getString(R.string.timer_status_running))
                    .addPart(STATUS_TIME, Status.TimerPart(SystemClock.elapsedRealtime() + (endsAt - wallClock())))
                    .build()
            }

            else -> {
                val left = TimerText.clock(timer.remainingMillis ?: 0L)
                builder.setContentText(context.getString(R.string.timer_paused, left))
                Status.Builder().addTemplate(context.getString(R.string.timer_paused, left)).build()
            }
        }
        OngoingActivity.Builder(context, id, builder)
            .setStaticIcon(R.drawable.ic_timer)
            .setTouchIntent(openApp())
            .setStatus(status)
            .build()
            .apply(context)
        return builder
    }

    private fun alarm(timer: MirroredTimer): NotificationCompat.Builder =
        NotificationCompat.Builder(context, TandemNotificationChannel.TIMER_ALARM.id)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(timer.label)
            .setContentText(context.getString(R.string.timer_ringing))
            .setSubText(timer.recipeName)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            .setDeleteIntent(action(timer.id, TimerActionKind.STOP_RINGING))
            .addAction(0, context.getString(R.string.timer_stop), action(timer.id, TimerActionKind.STOP_RINGING))

    private fun post(id: Int, build: () -> NotificationCompat.Builder): Boolean = try {
        manager.notify(id, build().build())
        true
    } catch (e: SecurityException) {
        // Revoked between the check and the call: the timers still show in the app.
        false
    }

    private fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, WearMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(timerId: Long, kind: TimerActionKind): PendingIntent = PendingIntent.getBroadcast(
        context,
        (timerId.mod(REQUEST_RANGE) * 2 + kind.ordinal).toInt(),
        TimerActionReceiver.intent(context, timerId, kind),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The two timer channels (idempotent). */
    fun ensureChannels() {
        val alarm = TandemNotificationChannel.TIMER_ALARM
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(alarm.id, NotificationManagerCompat.IMPORTANCE_HIGH)
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
                .build(),
        )
        val ongoing = TandemNotificationChannel.TIMER_ONGOING
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(ongoing.id, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.timer_channel_ongoing_name))
                .setDescription(context.getString(R.string.timer_channel_ongoing_description))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build(),
        )
    }

    private companion object {
        const val STATUS_TIME = "time"
        const val REQUEST_RANGE = 100_000L

        /** The ids [TandemNotificationIds] gives timers (ongoing 3000–3999, alarm 4000–4999). */
        val TIMER_IDS: IntRange = TandemNotificationIds.timerOngoing(0)..TandemNotificationIds.timerAlarm(TIMER_LAST)

        private const val TIMER_LAST = 999L
    }
}
