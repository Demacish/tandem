package casa.moller.tandem.core.timer.engine

import android.os.SystemClock
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Time since boot, including deep sleep (`SystemClock.elapsedRealtime`). It does not jump when
 * the user changes the wall clock, so it anchors running timers while the process lives
 * ([casa.moller.tandem.core.model.timer.CookTimer] explains why `endsAt` is wall-clock time).
 */
internal interface MonotonicClock {
    fun elapsed(): Duration
}

internal class SystemMonotonicClock @Inject constructor() : MonotonicClock {
    override fun elapsed(): Duration = SystemClock.elapsedRealtime().milliseconds
}
