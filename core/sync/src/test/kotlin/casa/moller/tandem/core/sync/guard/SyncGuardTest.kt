package casa.moller.tandem.core.sync.guard

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.testing.FakeClock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 4.1: circuit breaker, unauthorized and blocked pauses, imports. */
class SyncGuardTest {
    private val clock = FakeClock()
    private val guard = SyncGuard(clock)

    @Test
    fun fiveNotFoundAnswersWithin100SecondsPauseTheBackgroundSyncForFiveMinutes() {
        repeat(4) {
            guard.record(ACCOUNT, FINGERPRINT, TandemError.NotFound)
            clock.advance(10.seconds)
        }
        assertNull("four 404s are still fine", guard.check(ACCOUNT, background = true))

        guard.record(ACCOUNT, FINGERPRINT, TandemError.BadRequest())
        assertEquals(SyncGuard.BREAKER_ERROR, guard.check(ACCOUNT, background = true))
        assertNull("what the user starts still goes out", guard.check(ACCOUNT, background = false))
        assertEquals(SyncPause.CIRCUIT_BREAKER, guard.pauseAt(guard.state.value, ACCOUNT, clock.nowMillis()))

        clock.advance(SyncSchedule.BREAKER_PAUSE)
        assertNull(guard.check(ACCOUNT, background = true))
    }

    @Test
    fun notFoundAnswersSpreadOverMoreThan100SecondsDoNotTrip() {
        repeat(8) {
            guard.record(ACCOUNT, FINGERPRINT, TandemError.NotFound)
            clock.advance(30.seconds)
        }
        assertNull(guard.check(ACCOUNT, background = true))
    }

    @Test
    fun moreThanFiveErrorsWithinAMinuteTrip() {
        repeat(5) { guard.record(ACCOUNT, FINGERPRINT, TandemError.ServerError(502)) }
        assertNull(guard.check(ACCOUNT, background = true))
        guard.record(ACCOUNT, FINGERPRINT, TandemError.ServerError(500))
        assertEquals(SyncGuard.BREAKER_ERROR, guard.check(ACCOUNT, background = true))
    }

    @Test
    fun connectivityProblemsNeverCount() {
        repeat(20) { guard.record(ACCOUNT, FINGERPRINT, TandemError.NetworkUnavailable) }
        repeat(20) { guard.record(ACCOUNT, FINGERPRINT, TandemError.Timeout) }
        assertNull(guard.check(ACCOUNT, background = true))
    }

    @Test
    fun unauthorizedStopsEveryRequestOfTheAccountUntilTheTokenChanges() {
        val error = TandemError.Unauthorized(UnauthorizedSource.TANDOOR)
        guard.record(ACCOUNT, FINGERPRINT, error)

        assertEquals(error, guard.check(ACCOUNT, background = false))
        assertEquals(error, guard.check(ACCOUNT, background = true))
        assertNull("another account is not affected", guard.check("other", background = true))
        clock.advance(60.minutes)
        assertEquals("no time limit: only a new login helps", error, guard.check(ACCOUNT, background = true))

        assertFalse(guard.liftUnauthorizedIfTokenChanged(ACCOUNT, FINGERPRINT))
        assertTrue(guard.liftUnauthorizedIfTokenChanged(ACCOUNT, FINGERPRINT + 1))
        assertNull(guard.check(ACCOUNT, background = true))
    }

    @Test
    fun blockedPausesDoubleUpToOneHourAndResetOnSuccess() {
        guard.record(ACCOUNT, FINGERPRINT, TandemError.Blocked)
        assertEquals(TandemError.Blocked, guard.check(ACCOUNT, background = false))
        assertEquals(SyncSchedule.BLOCKED_FIRST_PAUSE.inWholeMilliseconds, guard.state.value.blockedPauseMillis)

        repeat(6) { guard.record(ACCOUNT, FINGERPRINT, TandemError.Blocked) }
        assertEquals(SyncSchedule.BLOCKED_MAX_PAUSE.inWholeMilliseconds, guard.state.value.blockedPauseMillis)

        clock.advance(SyncSchedule.BLOCKED_MAX_PAUSE)
        assertNull(guard.check(ACCOUNT, background = false))
        guard.record(ACCOUNT, FINGERPRINT, null)
        assertEquals(0L, guard.state.value.blockedPauseMillis)
    }

    @Test
    fun anImportHoldsBackOnlyBackgroundRequests() {
        guard.importStarted()
        assertEquals(SyncGuard.IMPORT_ERROR, guard.check(ACCOUNT, background = true))
        assertNull(guard.check(ACCOUNT, background = false))
        guard.importFinished()
        assertNull(guard.check(ACCOUNT, background = true))
    }

    private companion object {
        const val ACCOUNT = "account"
        const val FINGERPRINT = 42
    }
}
