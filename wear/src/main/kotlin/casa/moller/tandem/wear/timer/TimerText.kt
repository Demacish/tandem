package casa.moller.tandem.wear.timer

import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState

/** Time left on a mirrored timer, as the watch shows it. */
object TimerText {
    private const val SECOND = 1000L
    private const val MINUTE = 60
    private const val HOUR = 3600

    /** What is left of [timer] at [nowMillis] (wall clock): never negative, zero once it rang. */
    fun remainingMillis(timer: MirroredTimer, nowMillis: Long): Long = when (timer.state) {
        MirroredTimerState.RUNNING -> ((timer.endsAtEpochMillis ?: nowMillis) - nowMillis).coerceAtLeast(0L)
        MirroredTimerState.PAUSED -> (timer.remainingMillis ?: 0L).coerceAtLeast(0L)
        MirroredTimerState.RINGING, MirroredTimerState.FINISHED -> 0L
    }

    /** "4:05", "1:02:03": seconds rounded up, so a running timer never shows 0:00 before it rings. */
    fun clock(millis: Long): String {
        val seconds = ((millis.coerceAtLeast(0L) + SECOND - 1) / SECOND).toInt()
        val hours = seconds / HOUR
        val minutes = seconds % HOUR / MINUTE
        val rest = seconds % MINUTE
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
        } else {
            "$minutes:${rest.toString().padStart(2, '0')}"
        }
    }
}
