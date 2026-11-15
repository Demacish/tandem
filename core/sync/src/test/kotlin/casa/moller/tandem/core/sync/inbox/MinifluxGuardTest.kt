package casa.moller.tandem.core.sync.inbox

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.sync.testing.FakeClock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fas 5 (rss lane, plan §5.4): the inbox's own guard of the user's Miniflux server. */
class MinifluxGuardTest {
    private val clock = FakeClock()
    private val guard = MinifluxGuard(clock)

    @Test
    fun aRefusedKeyStopsUntilAnotherKey() {
        guard.record(ACCOUNT, KEY, TandemError.Unauthorized(UnauthorizedSource.PROXY))
        assertEquals(InboxProblem.AUTH, guard.check(ACCOUNT, KEY, background = false))
        clock.advance(600.minutes)
        assertEquals(InboxProblem.AUTH, guard.check(ACCOUNT, KEY, background = false))
        assertNull(guard.check(ACCOUNT, OTHER_KEY, background = false))
        // Another account starts clean.
        assertNull(guard.check("b", KEY, background = true))
    }

    @Test
    fun throttlingWaitsForRetryAfterWithinItsBounds() {
        guard.record(ACCOUNT, KEY, TandemError.Throttled(5.seconds))
        assertEquals(InboxProblem.THROTTLED, guard.check(ACCOUNT, KEY, background = false))
        clock.advance(29.seconds)
        assertEquals("at least 30 s", InboxProblem.THROTTLED, guard.check(ACCOUNT, KEY, background = false))
        clock.advance(2.seconds)
        assertNull(guard.check(ACCOUNT, KEY, background = false))
    }

    @Test
    fun aFirewallBlockDoublesItsPause() {
        guard.record(ACCOUNT, KEY, TandemError.Blocked)
        clock.advance(5.minutes)
        assertNull(guard.check(ACCOUNT, KEY, background = false))
        guard.record(ACCOUNT, KEY, TandemError.Blocked)
        clock.advance(9.minutes)
        assertEquals(InboxProblem.PAUSED, guard.check(ACCOUNT, KEY, background = false))
        clock.advance(1.minutes)
        assertNull(guard.check(ACCOUNT, KEY, background = false))
    }

    @Test
    fun theBreakerPausesTheBackgroundOnlyAndConnectivityNeverCounts() {
        repeat(10) { guard.record(ACCOUNT, KEY, TandemError.NetworkUnavailable) }
        assertNull(guard.check(ACCOUNT, KEY, background = true))

        repeat(MinifluxGuard.BREAKER_MAX_ERRORS + 1) { guard.record(ACCOUNT, KEY, TandemError.ServerError(502)) }
        assertEquals(InboxProblem.PAUSED, guard.check(ACCOUNT, KEY, background = true))
        assertNull(guard.check(ACCOUNT, KEY, background = false))
        clock.advance(30.minutes)
        assertNull(guard.check(ACCOUNT, KEY, background = true))
    }

    @Test
    fun errorsOutsideTheWindowDoNotTrip() {
        repeat(MinifluxGuard.BREAKER_MAX_ERRORS + 1) {
            guard.record(ACCOUNT, KEY, TandemError.ServerError(500))
            clock.advance(4.minutes)
        }
        assertNull(guard.check(ACCOUNT, KEY, background = true))
        guard.reset()
        assertNull(guard.check(ACCOUNT, KEY, background = true))
    }

    private companion object {
        const val ACCOUNT = "a"
        const val KEY = 11
        const val OTHER_KEY = 12
    }
}
