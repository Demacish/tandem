package casa.moller.tandem.core.data.time

import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

// Fas S (hemma-ui lane): "today" for expiry dates ("går ut i morgon", plan §8.8). A best-before
// day is a day on the kitchen's wall calendar, so it is counted in the DEVICE's time zone (not the
// server's, unlike the meal plan) and moves on at midnight while a screen is open.

/**
 * The device's current day. Screens that count days (the pantry's expiry colours, Idag's "Går ut
 * snart") collect [observeToday] instead of reading the clock once, so a list left open over
 * midnight, or a trip into another time zone, shows the right day. Tests use core:testing's
 * `FakeCalendarDays`.
 */
interface CalendarDays {
    /** Today now. */
    fun today(): LocalDate

    /** Today, again whenever the day changes (at the next midnight, or after a time zone or clock change). */
    fun observeToday(): Flow<LocalDate>
}

/**
 * [CalendarDays] from the wall clock and the system time zone. Wakes up right after the next
 * midnight, and at least every [recheck] so a changed time zone or clock is noticed soon.
 */
internal class SystemCalendarDays(
    private val clock: Clock,
    private val zone: () -> TimeZone,
    private val recheck: Duration = DEFAULT_RECHECK,
) : CalendarDays {
    @Inject
    constructor(clock: Clock) : this(clock, { TimeZone.currentSystemDefault() })

    override fun today(): LocalDate = clock.now().toLocalDateTime(zone()).date

    override fun observeToday(): Flow<LocalDate> = flow {
        while (true) {
            val now = clock.now()
            val currentZone = zone()
            emit(now.toLocalDateTime(currentZone).date)
            delay(nextCheck(now, currentZone, recheck))
        }
    }.distinctUntilChanged()

    companion object {
        val DEFAULT_RECHECK: Duration = 15.minutes

        /** A little after midnight, so the clock reads the new day when the flow wakes up. */
        private val AFTER_MIDNIGHT: Duration = 1.seconds

        /** How long to sleep after [now]: until just after the next midnight in [zone], at most [recheck]. */
        fun nextCheck(now: Instant, zone: TimeZone, recheck: Duration): Duration {
            val tomorrow = now.toLocalDateTime(zone).date.plus(DatePeriod(days = 1))
            val untilMidnight = tomorrow.atStartOfDayIn(zone) - now + AFTER_MIDNIGHT
            return minOf(untilMidnight, recheck).coerceAtLeast(AFTER_MIDNIGHT)
        }
    }
}
