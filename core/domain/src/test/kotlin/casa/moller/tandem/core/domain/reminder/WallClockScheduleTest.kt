package casa.moller.tandem.core.domain.reminder

import casa.moller.tandem.core.domain.pantry.ExpiryReminder
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fas 5 (planera lane): the daily reminders run at a wall-clock time in the phone's zone, also
 * across the Europe/Stockholm daylight-saving changes (29 March and 25 October 2026) and when the
 * phone changes zone while a run waits.
 */
class WallClockScheduleTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val newYork = TimeZone.of("America/New_York")
    private val evening = LocalTime(19, 0)
    private val morning = LocalTime(8, 0)

    private fun at(time: String, zone: TimeZone = stockholm): Instant = LocalDateTime.parse(time).toInstant(zone)

    private fun local(instant: Instant, zone: TimeZone = stockholm): LocalDateTime = instant.toLocalDateTime(zone)

    @Test
    fun theNextRunIsTodayWhileItsTimeIsAheadOtherwiseTomorrow() {
        assertEquals(DailyRun(LocalDate(2026, 11, 13), evening), WallClockSchedule.next(at("2026-11-13T18:59"), stockholm, evening))
        assertEquals(DailyRun(LocalDate(2026, 11, 14), evening), WallClockSchedule.next(at("2026-11-13T19:00"), stockholm, evening))
        assertEquals(DailyRun(LocalDate(2026, 11, 14), evening), WallClockSchedule.next(at("2026-11-13T23:59"), stockholm, evening))
    }

    @Test
    fun aRunThatStartedALittleEarlyDoesNotPlanItselfAgainForTheSameDay() {
        val early = at("2026-11-13T18:59:30")
        val friday = LocalDate(2026, 11, 13)
        assertEquals(DailyRun(LocalDate(2026, 11, 14), evening), WallClockSchedule.next(early, stockholm, evening, after = friday))
        // Days the phone was off are skipped: the next run is the coming one, not one in the past.
        assertEquals(
            DailyRun(LocalDate(2026, 11, 20), evening),
            WallClockSchedule.next(at("2026-11-20T10:00"), stockholm, evening, after = LocalDate(2026, 11, 13)),
        )
    }

    @Test
    fun theOctoberChangeKeepsTheWallClockTime() {
        // Saturday 24 October 08:00 CEST → Sunday 25 October 08:00 CET: a 25-hour day.
        val run = WallClockSchedule.next(at("2026-10-24T08:00"), stockholm, morning, after = LocalDate(2026, 10, 24))
        assertEquals(LocalDate(2026, 10, 25), run.date)
        assertEquals(25.hours, WallClockSchedule.delayUntil(run, at("2026-10-24T08:00"), stockholm))
        assertEquals(LocalTime(8, 0), local(run.at(stockholm)).time)
        // And the evening after: 19:00 again, not 18:00 or 20:00.
        val sunday = WallClockSchedule.next(at("2026-10-24T19:00"), stockholm, evening, after = LocalDate(2026, 10, 24))
        assertEquals(LocalTime(19, 0), local(sunday.at(stockholm)).time)
        assertEquals(25.hours, sunday.at(stockholm) - at("2026-10-24T19:00"))
    }

    @Test
    fun theMarchChangeKeepsTheWallClockTime() {
        // Saturday 28 March 08:00 CET → Sunday 29 March 08:00 CEST: a 23-hour day.
        val run = WallClockSchedule.next(at("2026-03-28T08:00"), stockholm, morning, after = LocalDate(2026, 3, 28))
        assertEquals(23.hours, WallClockSchedule.delayUntil(run, at("2026-03-28T08:00"), stockholm))
        assertEquals(LocalTime(8, 0), local(run.at(stockholm)).time)
        // Every day of the week around it stays at 08:00.
        var now = at("2026-03-25T08:00")
        var after = LocalDate(2026, 3, 25)
        repeat(7) {
            val next = WallClockSchedule.next(now, stockholm, morning, after = after)
            assertEquals(LocalTime(8, 0), local(next.at(stockholm)).time)
            now = next.at(stockholm)
            after = next.date
        }
    }

    @Test
    fun aTimeInTheSpringGapIsTheFirstInstantAfterIt() {
        val gap = DailyRun(LocalDate(2026, 3, 29), LocalTime(2, 30))
        assertEquals(LocalTime(3, 30), local(gap.at(stockholm)).time)
        // The autumn overlap takes the earlier 02:30 (still summer time).
        val overlap = DailyRun(LocalDate(2026, 10, 25), LocalTime(2, 30))
        assertEquals(at("2026-10-25T00:30", TimeZone.UTC), overlap.at(stockholm))
    }

    @Test
    fun aRunThatFindsItselfEarlyInANewZoneWaitsForItsLocalTime() {
        // Planned for 19:00 in Stockholm (13:00 in New York); the phone is in New York when it starts.
        val run = DailyRun(LocalDate(2026, 11, 13), evening)
        val started = at("2026-11-13T19:00")
        assertTrue(WallClockSchedule.isEarly(run, started, newYork))
        assertEquals(6.hours, WallClockSchedule.delayUntil(run, started, newYork))
        assertFalse(WallClockSchedule.isEarly(run, started, stockholm))
        // Within the tolerance it counts as on time.
        assertFalse(WallClockSchedule.isEarly(run, started - 30.minutes / 60, stockholm))
        assertTrue(WallClockSchedule.isEarly(run, started - 5.minutes, stockholm))
    }

    @Test
    fun aRunDeferredPastMidnightIsNoLongerOnItsDay() {
        val run = DailyRun(LocalDate(2026, 11, 13), evening)
        assertTrue(WallClockSchedule.isSameDay(run, at("2026-11-13T23:59"), stockholm))
        assertFalse(WallClockSchedule.isSameDay(run, at("2026-11-14T00:01"), stockholm))
    }

    @Test
    fun minutesRoundTrip() {
        assertEquals(19 * 60 + 30, WallClockSchedule.minutesOf(LocalTime(19, 30)))
        assertEquals(LocalTime(19, 30), WallClockSchedule.fromMinutes(19 * 60 + 30))
        assertEquals(LocalTime(0, 10), WallClockSchedule.fromMinutes(24 * 60 + 10))
        assertEquals(LocalTime(23, 0), WallClockSchedule.fromMinutes(-60))
    }

    @Test
    fun theMorningCheckFollowsTheChosenTime() {
        // The default keeps Fas 4's quiet hours (21–07).
        assertTrue(ExpiryReminder.isQuiet(at("2026-11-05T06:59"), stockholm))
        assertFalse(ExpiryReminder.isQuiet(at("2026-11-05T07:00"), stockholm))
        // An early morning moves the end of the night; a late one moves its start to midnight.
        assertFalse(ExpiryReminder.isQuiet(at("2026-11-05T06:00"), stockholm, LocalTime(6, 0)))
        assertTrue(ExpiryReminder.isQuiet(at("2026-11-05T05:59"), stockholm, LocalTime(6, 0)))
        assertFalse(ExpiryReminder.isQuiet(at("2026-11-05T21:30"), stockholm, LocalTime(21, 30)))
        assertFalse(ExpiryReminder.isQuiet(at("2026-11-05T23:59"), stockholm, LocalTime(22, 0)))
        assertTrue(ExpiryReminder.isQuiet(at("2026-11-05T21:30"), stockholm))
        // The retry after "Tandem was on screen" is two hours later the same day, unless that is night.
        assertEquals(DailyRun(LocalDate(2026, 11, 5), LocalTime(10, 15)), ExpiryReminder.retryRun(at("2026-11-05T08:15"), stockholm))
        assertNull(ExpiryReminder.retryRun(at("2026-11-05T19:30"), stockholm))
        assertNull(ExpiryReminder.retryRun(at("2026-11-05T23:00"), stockholm, LocalTime(22, 0)))
    }
}
