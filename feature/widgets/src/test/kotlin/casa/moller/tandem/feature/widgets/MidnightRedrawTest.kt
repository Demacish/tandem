package casa.moller.tandem.feature.widgets

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "I dag" widget is redrawn just after the local midnight, also when a day is not 24 hours. */
class MidnightRedrawTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")

    private fun at(text: String): Instant = ZonedDateTime.parse(text).toInstant()

    @Test
    fun theNextRedrawIsJustAfterTheLocalMidnight() {
        val expected = at("2026-11-07T00:01:00+01:00[Europe/Stockholm]").toEpochMilli()
        assertEquals(expected, MidnightRedraw.nextAt(at("2026-11-06T14:30:00+01:00[Europe/Stockholm]"), stockholm))
    }

    @Test
    fun aRedrawAtMidnightPointsAtTheNextOne() {
        val expected = at("2026-11-08T00:01:00+01:00[Europe/Stockholm]").toEpochMilli()
        assertEquals(expected, MidnightRedraw.nextAt(at("2026-11-07T00:01:00+01:00[Europe/Stockholm]"), stockholm))
    }

    @Test
    fun aDayWithTheClockChangeStillEndsAtMidnight() {
        val expected = at("2026-10-26T00:01:00+01:00[Europe/Stockholm]").toEpochMilli()
        assertEquals(expected, MidnightRedraw.nextAt(at("2026-10-25T01:00:00+02:00[Europe/Stockholm]"), stockholm))
    }
}
