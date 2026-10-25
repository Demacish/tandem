package casa.moller.tandem.core.timer.alarm

import android.app.AlarmManager
import android.content.Context
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.timer.TimerIntents
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.time.Instant

/**
 * [AlarmScheduler] on `AlarmManager` (plan 8.1, research 04 §15).
 *
 * - **Exact (sideload with `USE_EXACT_ALARM`, play after the user allowed
 *   `SCHEDULE_EXACT_ALARM`):** `setAlarmClock`. It is never deferred by Doze, may start the
 *   ringing foreground service from the background, and lets the system clock UI open the
 *   timer. (`setExactAndAllowWhileIdle` is rate limited in Doze, so two timers ending close
 *   together could ring late.)
 * - **Inexact (play without the permission):** `setAndAllowWhileIdle`; the UI warns
 *   ([casa.moller.tandem.core.model.timer.TimerCapabilities.alarmMayBeLate]).
 *
 * The permission can be revoked between the check and the call, so a `SecurityException` falls
 * back to the inexact alarm.
 */
internal class AndroidAlarmScheduler @Inject constructor(@ApplicationContext private val context: Context) : AlarmScheduler {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun scheduleRing(timer: CookTimer): Boolean {
        val at = timer.endsAt?.toEpochMilliseconds() ?: return false
        val operation = TimerIntents.ringPendingIntent(context, timer)
        if (alarmManager.canScheduleExactAlarms()) {
            val show = TimerIntents.openTimerPendingIntent(context, timer) ?: operation
            try {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
                return true
            } catch (e: SecurityException) {
                // Revoked just now: fall through to the inexact alarm.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        return false
    }

    override fun scheduleAutoSilence(timerId: Long, at: Instant) {
        val operation = TimerIntents.receiverPendingIntent(context, TimerIntents.ACTION_AUTO_SILENCE, timerId)
        val atMillis = at.toEpochMilliseconds()
        if (alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                return
            } catch (e: SecurityException) {
                // Revoked just now: fall through to the inexact alarm.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
    }

    override fun cancelRing(timerId: Long) = cancel(TimerIntents.ACTION_RING, timerId)

    override fun cancelAutoSilence(timerId: Long) = cancel(TimerIntents.ACTION_AUTO_SILENCE, timerId)

    private fun cancel(action: String, timerId: Long) {
        val operation = TimerIntents.existingReceiverPendingIntent(context, action, timerId) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }
}
