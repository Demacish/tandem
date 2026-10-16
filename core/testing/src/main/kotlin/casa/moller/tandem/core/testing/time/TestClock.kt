package casa.moller.tandem.core.testing.time

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A deterministic wall clock for tests: time only moves when the test says so. Inject it where
 * the app injects `kotlin.time.Clock` (core:data `ClockModule`).
 */
class TestClock(start: Instant = DEFAULT_START) : Clock {
    @Volatile
    var current: Instant = start
        private set

    override fun now(): Instant = current

    fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "a test clock only moves forward; use set() for a clock change" }
        current += duration
    }

    /** Jumps to [instant] (also backwards: a manual clock change by the user). */
    fun set(instant: Instant) {
        current = instant
    }

    companion object {
        /** The same reference day as the recorded fixtures and the demo data (2000-01-15), at 18:00 UTC. */
        val DEFAULT_START: Instant = Instant.parse("2000-01-15T18:00:00Z")
    }
}
