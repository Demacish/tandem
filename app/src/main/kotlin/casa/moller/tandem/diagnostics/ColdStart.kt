package casa.moller.tandem.diagnostics

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
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
 * start time. So the first activity of a process is measured only when it is created at most
 * [ColdStartGate.LAUNCH_GAP] after `Application.onCreate` ([ColdStartGate]), and on Android 15+ only
 * when the system's own record of this process start (`ApplicationStartInfo`) says it was a cold
 * start for an activity ([isActivityColdStart]). Nothing longer than [MAX_PLAUSIBLE] counts either
 * (e.g. the intro and a login came in between).
 *
 * Enhetstestet B16 (Galaxy S21 FE, Android 16: "Inte mätt än" after five cold starts): before,
 * the launch was told apart by a message `Application.onCreate` posted to the main thread, and an
 * activity counted only when it was created before that message ran. Newer Android versions start
 * the launch's activity only after the app has reported that `Application.onCreate` is done
 * (`finishAttachApplication`), so the message always ran first and no start was ever measured.
 */
internal object ColdStart {
    /** A first screen after this long is not a start any more (the user logged in first, ...). */
    val MAX_PLAUSIBLE: Duration = 30.seconds

    private val gate = ColdStartGate()

    /** Call at the end of `Application.onCreate`. */
    @MainThread
    fun onApplicationCreated() {
        gate.onApplicationCreated(SystemClock.uptimeMillis())
    }

    /**
     * True exactly once per process: for the first activity, when it was created as part of the
     * launch that started the process. Call from the activity's `onCreate`.
     */
    @MainThread
    fun takeForFirstActivity(): Boolean = gate.takeForFirstActivity(SystemClock.uptimeMillis())

    /** The time since the process start was requested, null when it is implausible. */
    fun elapsedSinceProcessStart(nowUptimeMillis: Long = SystemClock.uptimeMillis()): Duration? =
        coldStartDuration(Process.getStartRequestedUptimeMillis(), nowUptimeMillis)

    /**
     * Android 15+: whether the system recorded this process start as a cold start for an activity;
     * null when it cannot tell (older Android, no record for this process). A binder call: call it
     * off the main thread, once (when the first screen is drawn).
     */
    @WorkerThread
    fun startedForAnActivity(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return runCatching { thisProcessStart(context) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun thisProcessStart(context: Context): Boolean? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val start = manager.getHistoricalProcessStartReasons(1).firstOrNull { it.pid == Process.myPid() } ?: return null
        return isActivityColdStart(start.startType, start.reason)
    }
}

/**
 * The time from the process start request to [nowUptimeMillis], null when it is negative or longer
 * than [ColdStart.MAX_PLAUSIBLE].
 */
internal fun coldStartDuration(processStartRequestedUptimeMillis: Long, nowUptimeMillis: Long): Duration? {
    val elapsed = (nowUptimeMillis - processStartRequestedUptimeMillis).milliseconds
    return elapsed.takeIf { !it.isNegative() && it <= ColdStart.MAX_PLAUSIBLE }
}

/** `ApplicationStartInfo`: a cold start for an activity (the launcher, recents, a notification, a link). */
internal fun isActivityColdStart(startType: Int, reason: Int): Boolean =
    startType == ApplicationStartInfo.START_TYPE_COLD && reason in ACTIVITY_START_REASONS

private val ACTIVITY_START_REASONS = setOf(
    ApplicationStartInfo.START_REASON_LAUNCHER,
    ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS,
    ApplicationStartInfo.START_REASON_START_ACTIVITY,
)

/**
 * Which activity of a process is a cold start (B16): only the first, and only when it is created
 * at most [launchGap] after `Application.onCreate` ended. A launch's activity follows within a few
 * hundred milliseconds at most (the system starts it right after the app reported that
 * `Application.onCreate` is done); a process started for background work gets its first activity
 * only when the user opens the app, seconds or hours later. A warm start (the process was running)
 * creates no first activity of a process, so it never counts.
 */
internal class ColdStartGate(private val launchGap: Duration = LAUNCH_GAP) {
    @Volatile
    private var applicationCreatedAt: Long? = null

    @Volatile
    private var taken = false

    fun onApplicationCreated(uptimeMillis: Long) {
        applicationCreatedAt = uptimeMillis
    }

    fun takeForFirstActivity(uptimeMillis: Long): Boolean {
        if (taken) return false
        taken = true
        val created = applicationCreatedAt ?: return false
        val gap = (uptimeMillis - created).milliseconds
        return !gap.isNegative() && gap <= launchGap
    }

    companion object {
        /** The longest time from `Application.onCreate` to the launch's activity. */
        val LAUNCH_GAP: Duration = 2.seconds
    }
}
