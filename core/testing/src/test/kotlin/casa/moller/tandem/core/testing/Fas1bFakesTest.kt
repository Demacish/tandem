package casa.moller.tandem.core.testing

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.FakeCookLogRepository
import casa.moller.tandem.core.testing.data.FakeDiagnosticsRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.testing.time.TestClock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Fas1bFakesTest {

    @Test
    fun twoTimersRingIndependently() = runTest {
        val timers = FakeTimerController()
        val pasta = (timers.start("Pasta", 10.minutes, recipeId = 101, stepIndex = 2) as TimerStartResult.Started).timer
        val sauce = (timers.start("Sås", 4.minutes) as TimerStartResult.Started).timer

        timers.advanceTimeBy(4.minutes)
        assertEquals(listOf(TimerState.RUNNING, TimerState.RINGING), timers.timers.value.map { it.state })

        timers.addOneMinute(sauce.id)
        timers.pause(pasta.id)
        timers.advanceTimeBy(1.minutes)
        assertEquals(listOf(TimerState.PAUSED, TimerState.RINGING), timers.timers.value.map { it.state })
        assertEquals(6.minutes, timers.timers.value[0].remaining(timers.clock.now()))

        timers.stopRinging(sauce.id)
        timers.clearFinished()
        assertEquals(listOf(pasta.id), timers.timers.value.map { it.id })
    }

    @Test
    fun startRefusesInvalidAndTooManyTimersAndHonoursTheClockApp() = runTest {
        val timers = FakeTimerController()
        assertEquals(TimerStartResult.Invalid, timers.start("x", 0.seconds))
        repeat(8) { timers.start("T$it", 1.minutes) }
        assertEquals(TimerStartResult.TooManyTimers(8), timers.start("nine", 1.minutes))

        timers.mode = TimerMode.CLOCK_APP
        assertEquals(TimerStartResult.HandedToClockApp, timers.start("Ägg", 7.minutes))
        assertEquals(listOf("Ägg" to 7.minutes), timers.clockAppRequests)
        timers.setCapabilities(TimerCapabilities.AllGranted.copy(clockAppAvailable = false))
        assertEquals(TimerStartResult.ClockAppUnavailable, timers.start("Ägg", 7.minutes))
    }

    @Test
    fun notificationPermissionIsAskedOnce() = runTest {
        val timers = FakeTimerController(capabilities = TimerCapabilities.FreshInstall)
        assertTrue(timers.capabilities.value.shouldAskForNotifications)
        timers.onNotificationPermissionResult(granted = false)
        assertFalse(timers.capabilities.value.shouldAskForNotifications)
        assertTrue(timers.capabilities.value.alarmMayBeSilent)
    }

    @Test
    fun cookLogCountsPendingEntries() = runTest {
        val cookLog = FakeCookLogRepository()
        val draft = CookLogDraft(recipeId = 101, servings = 4, rating = 5, comment = "Gott!", cookedAt = TestClock().now())
        cookLog.logCooked(draft)
        cookLog.logCooked(draft)
        assertEquals(2, cookLog.observePendingCount().first())
        cookLog.markAllSent()
        assertEquals(0, cookLog.observePendingCount().first())
        assertEquals(listOf(draft, draft), cookLog.logged)
    }

    @Test
    fun settingsAndDiagnostics() = runTest {
        val settings = FakeSettingsRepository()
        settings.setCookTextScale(1.2f)
        assertEquals(1.15f, settings.settings.first().cookTextScale)
        settings.failWrites = true
        assertEquals(DataProblem.SETTINGS_NOT_SAVED, settings.setDynamicColor(true).errorOrNull()?.dataProblem())

        val diagnostics = FakeDiagnosticsRepository()
        diagnostics.recordColdStart(900.milliseconds)
        diagnostics.recordFullSync(42.seconds, recipeCount = 312)
        val snapshot = diagnostics.observeDiagnostics().first()
        assertEquals(312, snapshot.lastFullSync?.recipeCount)
        assertEquals(TestClock.DEFAULT_START, snapshot.lastColdStart?.measuredAt)
        assertTrue(diagnostics.exportLog().getOrNull()!!.text.contains("0.2.0-dev"))
    }
}
