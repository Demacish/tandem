package casa.moller.tandem.core.timer.platform

import casa.moller.tandem.core.model.timer.TimerCapabilities
import kotlin.time.Duration

/** What the system lets timers do right now, and the clock app. Android: [AndroidTimerSystem]. */
internal interface TimerSystem {
    /** Reads the permissions and the alarm channel again ([TimerCapabilities]). */
    fun capabilities(notificationPermissionAsked: Boolean): TimerCapabilities

    /** Hands a timer to the clock app (`AlarmClock.ACTION_SET_TIMER`, no UI); false when no app takes it. */
    fun startClockAppTimer(label: String, duration: Duration): Boolean
}
