package casa.moller.tandem.diagnostics

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Measures the cold start for Settings → Diagnostics (Phase 1 "Klart när": under 1.5 s on the
 * real phone): from the moment Android was asked to start the process
 * (`Process.getStartRequestedUptimeMillis`) to the first screen with content
 * (`reportFullyDrawn`, reported by Today or the recipe list with core:ui's `ReportContentReady`).
 *
 * Only a launch counts: when the process was started for something else (the periodic sync, a
 * timer alarm, a notification button) and an activity comes later, the time in between is no
 * start time. [onApplicationCreated] posts a message to the main thread; an activity that is
 * created before that message runs belongs to the launch that started the process (the system
 * queued its launch together with the process start). Only the first activity of a process is
 * measured, and nothing longer than [MAX_PLAUSIBLE] (e.g. the intro and a login came in between).
 */
internal object ColdStart {
    /** A first screen after this long is not a start any more (the user logged in first, ...). */
    val MAX_PLAUSIBLE: Duration = 30.seconds

    @Volatile
    private var launchWindowClosed = false

    @Volatile
    private var taken = false

    /** Call from `Application.onCreate`. */
    @MainThread
    fun onApplicationCreated() {
        Handler(Looper.getMainLooper()).post { launchWindowClosed = true }
    }

    /**
     * True exactly once per process: for the first activity, when it was created as part of the
     * launch that started the process. Call from the activity's `onCreate`.
     */
    @MainThread
    fun takeForFirstActivity(): Boolean {
        if (taken) return false
        taken = true
        return !launchWindowClosed
    }

    /** The time since the process start was requested, null when it is implausible. */
    fun elapsedSinceProcessStart(nowUptimeMillis: Long = SystemClock.uptimeMillis()): Duration? {
        val elapsed = (nowUptimeMillis - Process.getStartRequestedUptimeMillis()).milliseconds
        return elapsed.takeIf { !it.isNegative() && it <= MAX_PLAUSIBLE }
    }
}
