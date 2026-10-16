package casa.moller.tandem.core.timer.platform

import casa.moller.tandem.core.model.timer.TimerCapabilities
import kotlin.time.Duration

/** What the system lets timers do right now, and the clock app. Android: [AndroidTimerSystem]. */
internal interface TimerSystem {
    /** Reads the permissions and the alarm channel again ([TimerCapabilities]). */
    fun capabilities(notificationPermissionAsked: Boolean): TimerCapabilities

    /** `POST_NOTIFICATIONS` granted and the app's notifications switched on. */
    fun notificationsAllowed(): Boolean

    /** Exact alarms may be scheduled (and the ringing service may be `systemExempted`). */
    fun exactAlarmsAllowed(): Boolean

    /** The alarm may take over a locked screen (sideload build, unless the user revoked it). */
    fun fullScreenAllowed(): Boolean

    /** Hands a timer to the clock app (`AlarmClock.ACTION_SET_TIMER`, no UI); false when no app takes it. */
    fun startClockAppTimer(label: String, duration: Duration): Boolean
}
