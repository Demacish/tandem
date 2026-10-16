package casa.moller.tandem.core.ui.timer

import casa.moller.tandem.core.model.timer.CookTimer
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerFormatTest {

    @Test
    fun countdownsRoundUpToWholeSeconds() {
        assertEquals("7:39", formatRemaining(7.minutes + 38.seconds + 1.milliseconds))
        assertEquals("0:01", formatRemaining(1.milliseconds))
        assertEquals("0:00", formatRemaining(0.seconds))
        assertEquals("0:00", formatRemaining((-5).seconds))
        assertEquals("10:00", formatRemaining(10.minutes))
        assertEquals("1:02:03", formatRemaining(1.hours + 2.minutes + 3.seconds))
    }

    @Test
    fun durationParts() {
        assertEquals(DurationParts(1, 15, 0), durationParts(75.minutes))
        assertEquals(DurationParts(0, 0, 45), durationParts(45.seconds))
    }

    @Test
    fun ringingTimersComeFirstThenOldestFirst() {
        val start = Instant.parse("2000-01-15T18:00:00Z")
        val a = CookTimer.start(1, "A", 10.minutes, start)
        val b = CookTimer.start(2, "B", 1.minutes, start + 1.seconds)
        val c = CookTimer.start(3, "C", 5.minutes, start + 2.seconds)
        val now = start + 2.minutes
        val list = listOf(a, b.ring(now), c)

        assertEquals(listOf(2L, 1L, 3L), list.sortedForDisplay().map { it.id })
        assertEquals(0.2f, a.progress(now), 0.001f)
        assertEquals(1f, b.ring(now).progress(now), 0.001f)
    }
}
