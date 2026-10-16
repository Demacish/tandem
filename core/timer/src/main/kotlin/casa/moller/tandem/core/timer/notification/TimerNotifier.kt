package casa.moller.tandem.core.timer.notification

import android.app.Notification
import casa.moller.tandem.core.model.timer.CookTimer

/**
 * The timer notifications (plan 8.1, 8.7). Android: [AndroidTimerNotifier]. Without the
 * notification permission nothing is shown (the calls are no-ops); timers still run and ring.
 */
internal interface TimerNotifier {
    /** The silent countdown of a RUNNING timer (chronometer, Live Update on Android 16) or the PAUSED state. */
    fun showOngoing(timer: CookTimer)

    fun cancelOngoing(timerId: Long)

    /** The alarm of a RINGING timer (alarm channel, sound, full screen when allowed); not posted again while it is shown. */
    fun showAlarm(timer: CookTimer)

    /** A timer that was silenced automatically: a quiet "rang at 18:45" in place of the alarm. */
    fun showMissed(timer: CookTimer)

    fun cancelAlarm(timerId: Long)

    /** The notification of the foreground service while [ringing] timers ring (the group summary of the alarms). */
    fun ringingServiceNotification(ringing: List<CookTimer>): Notification
}
