package casa.moller.tandem.feature.cook.ui

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Justera tiden": how much − and + change a timer, and its limits. */
class TimerAdjustTest {

    @Test
    fun theStepGrowsWithTheTime() {
        assertEquals(1.minutes + 40.seconds, adjusted(1.minutes + 30.seconds, longer = true))
        assertEquals(26.minutes, adjusted(25.minutes, longer = true))
        assertEquals(24.minutes, adjusted(25.minutes, longer = false))
        assertEquals(35.minutes, adjusted(30.minutes, longer = true))
        assertEquals(1.hours + 10.minutes, adjusted(1.hours + 15.minutes, longer = false))
    }

    @Test
    fun goingDownUsesTheStepBelow() {
        // 30 min is the first time with 5-min steps; one step down is 29 min, not 25 min.
        assertEquals(29.minutes, adjusted(30.minutes, longer = false))
        assertEquals(1.minutes + 50.seconds, adjusted(2.minutes, longer = false))
    }

    @Test
    fun theTimeStaysWithinTheLimits() {
        assertEquals(MIN_TIMER, adjusted(15.seconds, longer = false))
        assertEquals(MIN_TIMER, adjusted(MIN_TIMER, longer = false))
        assertEquals(MAX_TIMER, adjusted(MAX_TIMER, longer = true))
    }
}
