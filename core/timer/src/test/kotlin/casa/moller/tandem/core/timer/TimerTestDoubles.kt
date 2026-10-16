package casa.moller.tandem.core.timer

import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.timer.engine.MonotonicClock
import casa.moller.tandem.core.timer.platform.TimerSystem
import casa.moller.tandem.core.timer.store.StoredTimers
import casa.moller.tandem.core.timer.store.TimerStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** The timers "file" in memory; a new engine on the same instance is a new process. */
internal class MemoryTimerStore(var stored: StoredTimers = StoredTimers()) : TimerStore {
    var writes: Int = 0
        private set

    override suspend fun read(): StoredTimers = stored

    override suspend fun write(timers: StoredTimers) {
        stored = timers
        writes++
    }
}

/** Time since boot; moves only when the test says so (and does not follow wall-clock changes). */
internal class FakeMonotonicClock(var now: Duration = 1.hours) : MonotonicClock {
    override fun elapsed(): Duration = now

    fun advanceBy(duration: Duration) {
        now += duration
    }
}

/** What the system allows; the defaults are the sideload build after the notification permission was granted. */
internal class FakeTimerSystem(
    var notifications: Boolean = true,
    var exactAlarms: Boolean = true,
    var fullScreen: Boolean = true,
    var clockApp: Boolean = true,
) : TimerSystem {
    val clockAppTimers = mutableListOf<Pair<String, Duration>>()

    override fun capabilities(notificationPermissionAsked: Boolean): TimerCapabilities = TimerCapabilities(
        notificationsAllowed = notifications,
        notificationPermissionAsked = notificationPermissionAsked,
        alarmChannelEnabled = true,
        exactAlarmsAllowed = exactAlarms,
        exactAlarmsRequestable = !exactAlarms,
        fullScreenAlarmAllowed = fullScreen,
        clockAppAvailable = clockApp,
    )

    override fun notificationsAllowed(): Boolean = notifications

    override fun exactAlarmsAllowed(): Boolean = exactAlarms

    override fun fullScreenAllowed(): Boolean = fullScreen

    override fun startClockAppTimer(label: String, duration: Duration): Boolean {
        if (!clockApp) return false
        clockAppTimers += label to duration
        return true
    }
}
