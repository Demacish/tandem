package casa.moller.tandem.core.timer.engine

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.timer.alarm.AlarmScheduler
import casa.moller.tandem.core.timer.notification.TimerNotifier
import casa.moller.tandem.core.timer.platform.TimerSystem
import casa.moller.tandem.core.timer.ring.Ringer
import casa.moller.tandem.core.timer.store.StoredTimers
import casa.moller.tandem.core.timer.store.TimerStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tunables of [DefaultTimerController]. */
internal data class TimerEngineConfig(
    /**
     * Also ring due timers from inside the process (a play build's inexact alarm can be late while
     * the app is open). Tests with a fake clock turn it off and fire the alarms themselves.
     */
    val inProcessTicker: Boolean = true,
    /** How long an alarm rings before it is silenced automatically (it must never ring forever). */
    val ringTimeout: Duration = DEFAULT_RING_TIMEOUT,
) {
    companion object {
        val DEFAULT_RING_TIMEOUT: Duration = 5.minutes
    }
}

/** System broadcasts that move or clear alarms ([casa.moller.tandem.core.timer.alarm.TimerSystemEventReceiver]). */
internal enum class SystemEvent {
    BOOT,
    PACKAGE_REPLACED,
    CLOCK_CHANGED,
    TIME_ZONE_CHANGED,
    EXACT_ALARM_PERMISSION_CHANGED,
}

/**
 * The real [TimerController] (plan 8.1, features #6/#7).
 *
 * - **One lock, one source of truth:** every change runs under [mutex]: load once from the
 *   [TimerStore], transform with [CookTimer]'s transitions, persist, publish [timers], then bring
 *   the system in line (alarms, notifications, ringing service). Persisting comes first, so a
 *   process killed right after a change finds it on disk.
 * - **Alarms:** a RUNNING timer has an alarm at `endsAt` ([AlarmScheduler]); when it fires, every
 *   due timer rings ([onAlarm]). A RINGING timer has an auto-silence alarm at
 *   `endsAt + ringTimeout`. Nothing runs in the background during the countdown.
 * - **Process start, reboot, update, clock change:** [reconcile] / [onSystemEvent] re-arm every
 *   alarm, ring timers whose end passed while nothing ran, and re-post the notifications. A manual
 *   clock change moves running timers by their in-memory monotonic anchor (see
 *   [CookTimer]: "a manual clock change ... recomputes the end"); after a reboot the stored
 *   `endsAt` counts.
 * - **Without the notification permission** timers still run and ring: [timers] shows RINGING, the
 *   ringing service plays the alarm itself, and the UI tells the user ([TimerCapabilities]).
 */
@Singleton
internal class DefaultTimerController @Inject constructor(
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val store: TimerStore,
    private val scheduler: AlarmScheduler,
    private val notifier: TimerNotifier,
    private val ringer: Ringer,
    private val system: TimerSystem,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    private val config: TimerEngineConfig,
) : TimerController {

    private class Change<out R>(val timers: List<CookTimer>, val result: R)

    private val mutex = Mutex()
    private val mutableTimers = MutableStateFlow<List<CookTimer>>(emptyList())
    private val mutableCapabilities = MutableStateFlow(TimerCapabilities.FreshInstall)

    // Guarded by mutex.
    private var loaded = false
    private var nextId = 1L
    private val monotonicEnds = HashMap<Long, Duration>()
    private var tickerJob: Job? = null

    @Volatile
    private var notificationPermissionAsked = false

    override val timers: StateFlow<List<CookTimer>> = mutableTimers.asStateFlow()

    override val capabilities: StateFlow<TimerCapabilities> = mutableCapabilities.asStateFlow()

    init {
        refreshCapabilities()
        scope.launch {
            settings.settings.map { it.notificationPermissionAsked }.distinctUntilChanged().collect { asked ->
                notificationPermissionAsked = asked
                refreshCapabilities()
            }
        }
        // Process start: load the stored timers and put their alarms and notifications back.
        scope.launch { reconcile() }
    }

    override suspend fun start(label: String, duration: Duration, recipeId: Long?, stepIndex: Int?): TimerStartResult {
        val name = label.trim()
        if (name.isEmpty() || duration < CookTimer.MIN_DURATION || (stepIndex != null && stepIndex < 0)) {
            return TimerStartResult.Invalid
        }
        if (settings.settings.first().timerMode == TimerMode.CLOCK_APP) {
            val handed = system.startClockAppTimer(name, duration)
            return if (handed) TimerStartResult.HandedToClockApp else TimerStartResult.ClockAppUnavailable
        }
        return mutate { list, now ->
            if (list.count { it.state != TimerState.FINISHED } >= CookTimer.MAX_PARALLEL_TIMERS) {
                Change(list, TimerStartResult.TooManyTimers(CookTimer.MAX_PARALLEL_TIMERS))
            } else {
                val timer = CookTimer.start(nextId++, name, duration, now, recipeId, stepIndex)
                Change(list + timer, TimerStartResult.Started(timer))
            }
        }
    }

    override suspend fun pause(id: Long) = update(id) { timer, now -> timer.pause(now) }

    override suspend fun resume(id: Long) = update(id) { timer, now -> timer.resume(now) }

    override suspend fun addOneMinute(id: Long) = update(id) { timer, now -> timer.plusOneMinute(now) }

    override suspend fun cancel(id: Long) = mutate { list, _ -> Change(list.filterNot { it.id == id }, Unit) }

    override suspend fun stopRinging(id: Long) = update(id) { timer, _ -> timer.stopRinging() }

    override suspend fun clearFinished() = mutate { list, _ -> Change(list.filterNot { it.state == TimerState.FINISHED }, Unit) }

    override suspend fun onNotificationPermissionResult(granted: Boolean) {
        notificationPermissionAsked = true
        settings.setNotificationPermissionAsked(true)
        refreshCapabilities()
        // Post the countdown of the timers that already run.
        if (granted) mutate(force = true) { list, _ -> Change(list, Unit) }
    }

    override fun refreshCapabilities() {
        mutableCapabilities.value = system.capabilities(notificationPermissionAsked)
    }

    /** The timers once they are loaded from disk ([timers] starts empty in a new process). */
    suspend fun loadedTimers(): List<CookTimer> = mutex.withLock {
        ensureLoaded()
        mutableTimers.value
    }

    /** "Stoppa alla" on the ringing service's notification. */
    suspend fun stopAllRinging() = mutate { list, _ -> Change(list.map { it.stopRinging() }, Unit) }

    /** A timer's alarm fired (or the in-process ticker): every due timer rings, the rest are re-armed. */
    suspend fun onAlarm() = mutate(force = true) { list, now -> Change(ringDue(list, now), Unit) }

    /** The auto-silence alarm: timers that rang for [TimerEngineConfig.ringTimeout] stop and leave a quiet notice. */
    suspend fun onAutoSilence() {
        val silenced = mutate { list, now -> silenceExpired(list, now) }
        silenced.forEach(notifier::showMissed)
    }

    /** Boot, app update, clock or time zone change, exact-alarm permission change: re-arm everything. */
    suspend fun onSystemEvent(event: SystemEvent) {
        if (event == SystemEvent.EXACT_ALARM_PERMISSION_CHANGED) refreshCapabilities()
        val silenced = mutate(force = true) { list, now ->
            val moved = if (event == SystemEvent.CLOCK_CHANGED) followClockChange(list, now) else list
            silenceExpired(ringDue(moved, now), now)
        }
        silenced.forEach(notifier::showMissed)
    }

    private suspend fun reconcile() {
        val silenced = mutate(force = true) { list, now -> silenceExpired(ringDue(list, now), now) }
        silenced.forEach(notifier::showMissed)
    }

    /**
     * Every due timer rings. One that rings late (the phone was off, or a play build's inexact
     * alarm came late) rings from [now], so it gets its full ringing time before the auto-silence.
     */
    private fun ringDue(list: List<CookTimer>, now: Instant): List<CookTimer> = list.map { timer ->
        val rung = timer.ring(now)
        if (rung.state == TimerState.RINGING && timer.state == TimerState.RUNNING && rung.endsAt!! + LATE_RING <= now) {
            rung.copy(endsAt = now)
        } else {
            rung
        }
    }

    private fun silenceExpired(list: List<CookTimer>, now: Instant): Change<List<CookTimer>> {
        val expired = list.filter { it.state == TimerState.RINGING && it.endsAt!! + config.ringTimeout <= now + AUTO_SILENCE_SLACK }
            .mapTo(HashSet()) { it.id }
        if (expired.isEmpty()) return Change(list, emptyList())
        val after = list.map { if (it.id in expired) it.stopRinging() else it }
        return Change(after, after.filter { it.id in expired })
    }

    /** A manual clock change: running timers keep the time they had left, measured on the monotonic clock. */
    private fun followClockChange(list: List<CookTimer>, now: Instant): List<CookTimer> {
        val elapsed = monotonicClock.elapsed()
        return list.map { timer ->
            val anchor = monotonicEnds[timer.id]
            if (timer.state == TimerState.RUNNING && anchor != null) {
                timer.copy(endsAt = now + (anchor - elapsed).coerceAtLeast(Duration.ZERO))
            } else {
                timer
            }
        }
    }

    private suspend fun update(id: Long, transform: (CookTimer, Instant) -> CookTimer) = mutate { list, now ->
        Change(list.map { if (it.id == id) transform(it, now) else it }, Unit)
    }

    private suspend fun <R> mutate(force: Boolean = false, transform: (List<CookTimer>, Instant) -> Change<R>): R = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        val before = mutableTimers.value
        val change = transform(before, now)
        val after = change.timers
        if (after != before) persist(after)
        mutableTimers.value = after
        applyEffects(before, after, now, force)
        change.result
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = try {
            store.read()
        } catch (e: IOException) {
            StoredTimers()
        }
        val restored = stored.toTimers()
        nextId = maxOf(stored.nextId, (restored.maxOfOrNull { it.id } ?: 0L) + 1)
        mutableTimers.value = restored
        loaded = true
    }

    private suspend fun persist(timers: List<CookTimer>) {
        try {
            store.write(StoredTimers.of(nextId, timers))
        } catch (e: IOException) {
            // The timers keep running in this process; the next change writes the file again.
        }
    }

    /** Brings alarms, notifications and the ringing service in line with [after]; [force] redoes every timer. */
    private fun applyEffects(before: List<CookTimer>, after: List<CookTimer>, now: Instant, force: Boolean) {
        val previous = before.associateBy { it.id }
        val currentIds = after.mapTo(HashSet()) { it.id }
        for (gone in before) {
            if (gone.id in currentIds) continue
            scheduler.cancelRing(gone.id)
            scheduler.cancelAutoSilence(gone.id)
            notifier.cancelOngoing(gone.id)
            notifier.cancelAlarm(gone.id)
            monotonicEnds.remove(gone.id)
        }
        for (timer in after) {
            val old = previous[timer.id]
            if (!force && old == timer) continue
            when (timer.state) {
                TimerState.RUNNING -> {
                    scheduler.cancelAutoSilence(timer.id)
                    scheduler.scheduleRing(timer)
                    monotonicEnds[timer.id] = monotonicClock.elapsed() + (timer.endsAt!! - now)
                    if (old != null && old.state != TimerState.RUNNING) notifier.cancelAlarm(timer.id)
                    notifier.showOngoing(timer)
                }

                TimerState.PAUSED -> {
                    scheduler.cancelRing(timer.id)
                    scheduler.cancelAutoSilence(timer.id)
                    monotonicEnds.remove(timer.id)
                    if (old != null && old.state != TimerState.PAUSED) notifier.cancelAlarm(timer.id)
                    notifier.showOngoing(timer)
                }

                TimerState.RINGING -> {
                    scheduler.cancelRing(timer.id)
                    scheduler.scheduleAutoSilence(timer.id, timer.endsAt!! + config.ringTimeout)
                    monotonicEnds.remove(timer.id)
                    notifier.cancelOngoing(timer.id)
                    notifier.showAlarm(timer)
                }

                TimerState.FINISHED -> {
                    scheduler.cancelRing(timer.id)
                    scheduler.cancelAutoSilence(timer.id)
                    monotonicEnds.remove(timer.id)
                    notifier.cancelOngoing(timer.id)
                    // Only when it stops ringing now: a "rang at 18:45" notice stays until the timer is cleared.
                    if (old?.state == TimerState.RINGING) notifier.cancelAlarm(timer.id)
                }
            }
        }
        val ringingBefore = before.filter { it.state == TimerState.RINGING }
        val ringingAfter = after.filter { it.state == TimerState.RINGING }
        if (ringingAfter.isEmpty()) {
            // A new process has no service of its own to stop, so only on a change.
            if (ringingBefore.isNotEmpty()) ringer.stop()
        } else if (force || ringingAfter != ringingBefore) {
            ringer.ring(ringingAfter)
        }
        restartTicker(after, now)
    }

    private fun restartTicker(timers: List<CookTimer>, now: Instant) {
        tickerJob?.cancel()
        tickerJob = null
        if (!config.inProcessTicker) return
        val nextEnd = timers.filter { it.state == TimerState.RUNNING }.minOfOrNull { it.endsAt!! } ?: return
        val wait = (nextEnd - now).coerceAtLeast(Duration.ZERO) + TICK_MARGIN
        tickerJob = scope.launch {
            delay(wait)
            // A separate coroutine: onAlarm restarts (cancels) this ticker job.
            scope.launch { onAlarm() }
        }
    }

    private companion object {
        /** Wakes the in-process ticker just after the end, so the timer is due when it looks. */
        val TICK_MARGIN: Duration = 50.milliseconds

        /** A timer that rings this much after its end counts as late ([ringDue]). */
        val LATE_RING: Duration = 1.minutes

        /** An auto-silence alarm a moment early still silences (alarms are not that precise). */
        val AUTO_SILENCE_SLACK: Duration = 1.seconds
    }
}
