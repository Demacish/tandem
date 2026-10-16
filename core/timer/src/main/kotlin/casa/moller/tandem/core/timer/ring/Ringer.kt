package casa.moller.tandem.core.timer.ring

import casa.moller.tandem.core.model.timer.CookTimer

/** Starts and stops the short foreground service while alarms ring. Android: [AndroidRinger]. */
internal interface Ringer {
    /** At least one timer rings: start the service, or update it when [ringing] changed. */
    fun ring(ringing: List<CookTimer>)

    /** Nothing rings any more. */
    fun stop()
}
