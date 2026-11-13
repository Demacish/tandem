package casa.moller.tandem.core.domain.reminder

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * One planned run of a daily reminder: the local [date] it is for and the [time] of day (wall
 * clock) it should not start before.
 */
data class DailyRun(val date: LocalDate, val time: LocalTime) {
    /** The instant of this run in [zone] (see [WallClockSchedule.occurrence]). */
    fun at(zone: TimeZone): Instant = WallClockSchedule.occurrence(date, time, zone)
}

/**
 * Fas 5 (planera lane), plan §8.3 "Påminnelse kvällen innan" and §8.8 "Använd upp": the pure rules
 * of the daily reminders that run at a wall-clock time of the user's choice (the morning pantry
 * check, the evening "Tina ut" reminder). Every run is planned as a local DATE and TIME, never as
 * "24 hours after the last one", so a daylight-saving change (a 23- or 25-hour day) or a new time
 * zone never moves it to 07:00 or 09:00: the instant is worked out from the date and time in the
 * zone the phone is in when it is needed ([occurrence]), and a run that finds itself early in its
 * zone (the zone changed while it waited) waits again ([isEarly]).
 */
object WallClockSchedule {
    /** A run this much before its time still counts as on time (WorkManager may start a little early). */
    val EARLY_TOLERANCE: Duration = 1.minutes

    /**
     * [time] on [date] in [zone]. A time that does not exist that day (the spring gap, 02:30 in
     * Europe/Stockholm on the last Sunday of March) is the instant just after the gap (03:30); a
     * time that exists twice (the autumn overlap) is the earlier one.
     */
    fun occurrence(date: LocalDate, time: LocalTime, zone: TimeZone): Instant = LocalDateTime(date, time).toInstant(zone)

    /** The calendar day of [now] in [zone]. */
    fun today(now: Instant, zone: TimeZone): LocalDate = now.toLocalDateTime(zone).date

    /**
     * The next run at [time]: today's when it is still ahead of [now], otherwise tomorrow's, and
     * never on or before [after] (the date of the run that just ended, so a run that started a
     * little early does not plan itself again for the same day).
     */
    fun next(now: Instant, zone: TimeZone, time: LocalTime, after: LocalDate? = null): DailyRun {
        var date = today(now, zone)
        if (occurrence(date, time, zone) <= now) date = date.plus(1, DateTimeUnit.DAY)
        if (after != null && date <= after) date = after.plus(1, DateTimeUnit.DAY)
        return DailyRun(date, time)
    }

    /** How long from [now] until [run] in [zone] (zero when it is due). */
    fun delayUntil(run: DailyRun, now: Instant, zone: TimeZone): Duration = (run.at(zone) - now).coerceAtLeast(Duration.ZERO)

    /** [run] started before its time in [zone] (beyond [EARLY_TOLERANCE]): it should wait again instead of running. */
    fun isEarly(run: DailyRun, now: Instant, zone: TimeZone): Boolean = now < run.at(zone) - EARLY_TOLERANCE

    /** [now] is still on [run]'s date in [zone] (a run deferred past midnight is too late for its day). */
    fun isSameDay(run: DailyRun, now: Instant, zone: TimeZone): Boolean = today(now, zone) == run.date

    /** "HH:mm" stored as minutes after midnight, and back ([fromMinutes] coerces garbage into the day). */
    fun minutesOf(time: LocalTime): Int = time.hour * MINUTES_PER_HOUR + time.minute

    fun fromMinutes(minutes: Int): LocalTime {
        val inDay = minutes.mod(MINUTES_PER_DAY)
        return LocalTime(inDay / MINUTES_PER_HOUR, inDay % MINUTES_PER_HOUR)
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
}
