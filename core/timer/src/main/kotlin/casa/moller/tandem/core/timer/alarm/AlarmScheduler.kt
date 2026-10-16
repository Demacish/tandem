package casa.moller.tandem.core.timer.alarm

import casa.moller.tandem.core.model.timer.CookTimer
import kotlin.time.Instant

/** The system alarms of the timers. Android: [AndroidAlarmScheduler]. Every call replaces an earlier one for the same timer. */
internal interface AlarmScheduler {
    /**
     * Rings [timer] at its `endsAt`: an exact alarm clock (`setAlarmClock`) when exact alarms
     * are allowed, otherwise an inexact `setAndAllowWhileIdle` (play build without the permission;
     * [casa.moller.tandem.core.model.timer.TimerCapabilities.alarmMayBeLate] warns about it).
     *
     * @return true when the alarm is exact.
     */
    fun scheduleRing(timer: CookTimer): Boolean

    /** Silences a ringing timer at [at] if nobody stopped it (the alarm must not ring forever). */
    fun scheduleAutoSilence(timerId: Long, at: Instant)

    fun cancelRing(timerId: Long)

    fun cancelAutoSilence(timerId: Long)
}
