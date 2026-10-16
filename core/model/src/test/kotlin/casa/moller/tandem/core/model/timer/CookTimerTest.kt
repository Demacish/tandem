package casa.moller.tandem.core.model.timer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CookTimerTest {
    private val t0 = Instant.parse("2026-10-16T18:00:00Z")

    private fun pasta() = CookTimer.start(id = 1, label = "Pasta", duration = 10.minutes, now = t0, recipeId = 101, stepIndex = 2)

    @Test
    fun aStartedTimerRunsUntilItsWallClockEnd() {
        val timer = pasta()
        assertEquals(TimerState.RUNNING, timer.state)
        assertEquals(t0 + 10.minutes, timer.endsAt)
        assertEquals(4.minutes, timer.remaining(t0 + 6.minutes))
        assertFalse(timer.isDue(t0 + 9.minutes))
        assertTrue(timer.isDue(t0 + 10.minutes))
        // Past the end (e.g. the phone was off): never negative.
        assertEquals(Duration.ZERO, timer.remaining(t0 + 1.minutes + 10.minutes))
    }

    @Test
    fun pauseAndResumeKeepTheRemainingTime() {
        val paused = pasta().pause(t0 + 3.minutes)
        assertEquals(TimerState.PAUSED, paused.state)
        assertNull(paused.endsAt)
        assertEquals(7.minutes, paused.remaining(t0 + 30.minutes))

        val resumed = paused.resume(t0 + 30.minutes)
        assertEquals(TimerState.RUNNING, resumed.state)
        assertEquals(t0 + 37.minutes, resumed.endsAt)
        // Only RUNNING pauses and only PAUSED resumes.
        assertSame(resumed, resumed.resume(t0 + 31.minutes))
        assertSame(paused, paused.pause(t0 + 31.minutes))
    }

    @Test
    fun plusOneMinuteExtendsOrRestarts() {
        val running = pasta().plusOneMinute(t0)
        assertEquals(t0 + 11.minutes, running.endsAt)
        assertEquals(11.minutes, running.duration)

        val paused = pasta().pause(t0 + 1.minutes).plusOneMinute(t0 + 2.minutes)
        assertEquals(10.minutes, paused.pausedRemaining)

        val ringing = pasta().ring(t0 + 10.minutes)
        assertEquals(TimerState.RINGING, ringing.state)
        val again = ringing.plusOneMinute(t0 + 10.minutes + 20.seconds)
        assertEquals(TimerState.RUNNING, again.state)
        assertEquals(t0 + 11.minutes + 20.seconds, again.endsAt)
    }

    @Test
    fun ringsOnlyWhenDueAndStopsToFinished() {
        val timer = pasta()
        assertSame(timer, timer.ring(t0 + 5.minutes))
        val ringing = timer.ring(t0 + 10.minutes + 1.seconds)
        assertEquals(TimerState.RINGING, ringing.state)
        assertEquals(t0 + 10.minutes, ringing.endsAt)
        assertEquals(Duration.ZERO, ringing.remaining(t0 + 11.minutes))
        val finished = ringing.stopRinging()
        assertEquals(TimerState.FINISHED, finished.state)
        assertSame(finished, finished.stopRinging())
    }

    @Test
    fun invalidTimersAreRejected() {
        assertThrows<IllegalArgumentException> { CookTimer.start(id = 1, label = " ", duration = 1.minutes, now = t0) }
        assertThrows<IllegalArgumentException> { CookTimer.start(id = 1, label = "x", duration = Duration.ZERO, now = t0) }
        assertThrows<IllegalArgumentException> { pasta().copy(state = TimerState.PAUSED) }
    }

    @Test
    fun cookModeAllowsAtLeastTwoTimers() {
        // Phase 1 "Klart när": two timers at the same time ring on a locked screen.
        assertTrue(CookTimer.MAX_PARALLEL_TIMERS >= 2)
    }

    @Test
    fun capabilitiesAskForNotificationsOnlyOnce() {
        assertTrue(TimerCapabilities.FreshInstall.shouldAskForNotifications)
        assertTrue(TimerCapabilities.FreshInstall.alarmMayBeSilent)
        val denied = TimerCapabilities.FreshInstall.copy(notificationPermissionAsked = true)
        assertFalse(denied.shouldAskForNotifications)
        assertTrue(denied.alarmMayBeSilent)
        assertFalse(TimerCapabilities.AllGranted.alarmMayBeSilent)
        assertTrue(TimerCapabilities.AllGranted.copy(exactAlarmsAllowed = false).alarmMayBeLate)
    }

    @Test
    fun suggestionsCarryARangeOnlyForText() {
        val text = TimerSuggestion(
            label = "ca 20–25 min (25 min)",
            duration = 25.minutes,
            minDuration = 20.minutes,
            source = TimerSuggestionSource.TEXT,
            textRange = 6..17,
        )
        assertTrue(text.isRange)
        assertEquals(25.minutes, text.maxDuration)
        assertThrows<IllegalArgumentException> { text.copy(minDuration = 30.minutes) }
        val step = TimerSuggestion(label = "10 min", duration = 10.minutes, source = TimerSuggestionSource.STEP_TIME)
        assertFalse(step.isRange)
        assertThrows<IllegalArgumentException> { step.copy(textRange = 0..1) }
        assertThrows<IllegalArgumentException> { text.copy(textRange = null) }
    }
}
