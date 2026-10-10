package casa.moller.tandem.core.timer

import org.junit.Assert.assertTrue
import org.junit.Test

class TimerModuleTest {
    @Test
    fun cookModeAllowsAtLeastTwoTimers() {
        // Phase 1 "Klart när": two timers at the same time ring on a locked screen.
        assertTrue(TimerModule.MAX_PARALLEL_TIMERS >= 2)
    }
}
