package casa.moller.tandem.core.data.time

import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fas S (hemma-ui lane): the device's day across midnight and time zones (expiry colours, plan §8.8). */
class SystemCalendarDaysTest {

    private class MovableClock(var current: Instant) : Clock {
        override fun now(): Instant = current
    }

    private val stockholm = TimeZone.of("Europe/Stockholm")

    @Test
    fun theDayIsTheDevicesDayNotUtc() {
        // 23:30 UTC on 29 October is 00:30 on the 30th in Stockholm (UTC+1 after the clock change).
        val clock = MovableClock(Instant.parse("2026-10-29T23:30:00Z"))
        assertEquals(LocalDate(2026, 10, 29), SystemCalendarDays(clock, { TimeZone.UTC }).today())
        assertEquals(LocalDate(2026, 10, 30), SystemCalendarDays(clock, { stockholm }).today())
        assertEquals(LocalDate(2026, 10, 30), SystemCalendarDays(clock, { TimeZone.of("Pacific/Auckland") }).today())
        assertEquals(LocalDate(2026, 10, 29), SystemCalendarDays(clock, { TimeZone.of("America/Los_Angeles") }).today())
    }

    @Test
    fun wakesUpJustAfterMidnightOrAtTheRecheck() {
        val beforeMidnight = Instant.parse("2026-10-29T22:50:00Z") // 23:50 in Stockholm
        assertEquals(10.minutes + 1.seconds, SystemCalendarDays.nextCheck(beforeMidnight, stockholm, 15.minutes))
        assertEquals(15.minutes, SystemCalendarDays.nextCheck(Instant.parse("2026-10-29T10:00:00Z"), stockholm, 15.minutes))
    }

    @Test
    fun emitsTheNextDayAfterMidnight() = runTest {
        val clock = MovableClock(Instant.parse("2026-10-29T22:50:00Z"))
        val days = SystemCalendarDays(clock, { stockholm }, recheck = 15.minutes)
        // The flow sleeps on the test scheduler; the clock is moved past midnight meanwhile.
        val result = flow {
            days.observeToday().collect { day ->
                emit(day)
                clock.current = Instant.parse("2026-10-29T23:00:01Z")
            }
        }.take(2).toList()
        assertEquals(listOf(LocalDate(2026, 10, 29), LocalDate(2026, 10, 30)), result)
    }

    @Test
    fun aChangedTimeZoneIsNoticedAtTheRecheck() = runTest {
        val clock = MovableClock(Instant.parse("2026-10-29T12:00:00Z"))
        var zone: TimeZone = TimeZone.UTC
        val days = SystemCalendarDays(clock, { zone }, recheck = 1.hours)
        val result = flow {
            days.observeToday().collect { day ->
                emit(day)
                zone = TimeZone.of("Pacific/Kiritimati") // UTC+14: already the 30th
            }
        }.take(2).toList()
        assertEquals(listOf(LocalDate(2026, 10, 29), LocalDate(2026, 10, 30)), result)
    }
}
