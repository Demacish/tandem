package casa.moller.tandem.core.timer.platform

import casa.moller.tandem.core.model.timer.TimerCapabilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

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

    /**
     * The alarm notification itself makes the alarm sound: notifications are allowed and the
     * `timer_alarm` channel can sound (it does not exist yet, or the user left its importance at
     * "default" or higher and its sound on). When false, the ringing service plays the alarm sound
     * and vibration itself, so a timer never ends in silence (fix-data, 0.2.0 review).
     */
    fun alarmNotificationAudible(): Boolean

    /**
     * Hands a timer to the clock app (`AlarmClock.ACTION_SET_TIMER`, no UI); false when no app
     * takes it. Never called with more than [MAX_CLOCK_APP_DURATION].
     */
    fun startClockAppTimer(label: String, duration: Duration): Boolean

    companion object {
        /** `AlarmClock.EXTRA_LENGTH` takes at most 86400 seconds; longer timers stay in Tandem. */
        val MAX_CLOCK_APP_DURATION: Duration = 24.hours
    }
}
