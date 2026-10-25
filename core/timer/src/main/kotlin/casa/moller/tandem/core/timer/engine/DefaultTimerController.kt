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
 * - **Storage trouble** (fix-data, 0.2.0 review): a file that cannot be read is tried again
 *   ([ensureLoaded]) and never overwritten while it is unread; a write that fails is tried again
 *   with the next change. Each ring alarm also carries the timer itself, so an alarm whose timer
 *   never reached the file still rings in a new process ([onAlarm]).
 * - **Removed stays removed** (komplettering, 0.2.0): a timer that was cancelled or cleared is
 *   never brought back by a ring alarm that was already on its way ([onAlarm], [mayRestore]).
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
    private var unsaved = false
    private var nextId = 1L

    /** `next_id` of the last file this process read or wrote; 0 while no file was read. */
    private var persistedNextId = 0L

    /** Ids removed in this process (cancel, clearFinished, any other removal): never restored ([mayRestore]). */
    private val removedIds = HashSet<Long>()

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

    override suspend fun start(label: String, duration: Duration, recipeId: Long?, stepIndex: Int?, sourceKey: String?): TimerStartResult {
        val name = label.trim()
        if (name.isEmpty() || duration < CookTimer.MIN_DURATION || (stepIndex != null && stepIndex < 0)) {
            return TimerStartResult.Invalid
        }
        // The clock app takes at most 24 h; a longer timer (e.g. "Marinera 48 timmar") runs in Tandem
        // instead of ringing a day early in the clock app.
        if (settings.settings.first().timerMode == TimerMode.CLOCK_APP && duration <= TimerSystem.MAX_CLOCK_APP_DURATION) {
            val handed = system.startClockAppTimer(name, duration)
            return if (handed) TimerStartResult.HandedToClockApp else TimerStartResult.ClockAppUnavailable
        }
        return mutate { list, now ->
            if (list.count { it.state != TimerState.FINISHED } >= CookTimer.MAX_PARALLEL_TIMERS) {
                Change(list, TimerStartResult.TooManyTimers(CookTimer.MAX_PARALLEL_TIMERS))
            } else {
                val timer = CookTimer.start(nextId++, name, duration, now, recipeId, stepIndex, sourceKey)
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
    suspend fun loadedTimers(): List<CookTimer> = mutate { list, _ -> Change(list, list) }

    /** "Stoppa alla" on the ringing service's notification. */
    suspend fun stopAllRinging() = mutate { list, _ -> Change(list.map { it.stopRinging() }, Unit) }

    /**
     * A timer's alarm fired (or the in-process ticker): every due timer rings, the rest are re-armed.
     *
     * @param scheduled the timer the alarm was set for (from the alarm's intent). When the stored
     *   timers lack it (its write failed, or the file was lost), it is added back so it still rings,
     *   unless it was removed on purpose ([mayRestore]).
     */
    suspend fun onAlarm(scheduled: CookTimer? = null) = mutate(force = true) { list, now ->
        val restored = if (scheduled != null && mayRestore(scheduled, list)) {
            nextId = maxOf(nextId, scheduled.id + 1)
            (list + scheduled).sortedWith(compareBy({ it.createdAt }, { it.id }))
        } else {
            list
        }
        Change(ringDue(restored, now), Unit)
    }

    /**
     * Whether [scheduled] (the snapshot in a ring alarm) is a timer that got lost and must still
     * ring, rather than one the user removed. A cancel cancels the alarm too, but an alarm that
     * already fired can wait for [mutex] while the cancel runs, and its broadcast then carries the
     * removed timer (komplettering, 0.2.0: a cancelled timer must never come back and ring). So it
     * is restored only when it is RUNNING and missing, was not removed in this process, and was
     * never in a file this process read or wrote: every write holds the whole list, so a file
     * whose `next_id` is above the id and that lacks the timer means it was removed (in an earlier
     * process too). A lost file reads as empty with `next_id` 1, so its timers still ring.
     */
    private fun mayRestore(scheduled: CookTimer, list: List<CookTimer>): Boolean =
        scheduled.state == TimerState.RUNNING &&
            list.none { it.id == scheduled.id } &&
            scheduled.id !in removedIds &&
            scheduled.id >= persistedNextId

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
        // The first load in this process puts every stored alarm and notification back.
        val justLoaded = ensureLoaded()
        val now = clock.now()
        val before = mutableTimers.value
        val change = transform(before, now)
        val after = change.timers
        val kept = after.mapTo(HashSet()) { it.id }
        before.filterNot { it.id in kept }.mapTo(removedIds) { it.id }
        // Never while the file is unread: an empty list must not replace timers that are on disk.
        if (loaded && (after != before || unsaved)) persist(after)
        mutableTimers.value = after
        applyEffects(before, after, now, force || justLoaded)
        change.result
    }

    /**
     * Loads the stored timers once per process; returns true when this call loaded them. A read
     * that fails is tried again a few times; if the file stays unreadable, the engine goes on with
     * the timers in memory, does not write, and reads again on the next call. Timers started in the
     * meantime are kept next to the stored ones (they get ids far above the stored ones).
     */
    private suspend fun ensureLoaded(): Boolean {
        if (loaded) return false
        val stored = readWithRetry() ?: run {
            nextId = maxOf(nextId, UNREAD_FIRST_ID)
            return false
        }
        val restored = stored.toTimers()
        val storedIds = restored.mapTo(HashSet()) { it.id }
        val memoryOnly = mutableTimers.value.filter { it.id !in storedIds }
        val merged = (restored + memoryOnly).sortedWith(compareBy({ it.createdAt }, { it.id }))
        nextId = maxOf(nextId, stored.nextId, (merged.maxOfOrNull { it.id } ?: 0L) + 1)
        mutableTimers.value = merged
        loaded = true
        persistedNextId = stored.nextId
        unsaved = memoryOnly.isNotEmpty()
        return true
    }

    private suspend fun readWithRetry(): StoredTimers? {
        repeat(READ_ATTEMPTS) { attempt ->
            try {
                return store.read()
            } catch (e: IOException) {
                if (attempt < READ_ATTEMPTS - 1) delay(READ_RETRY_DELAY * (attempt + 1))
            }
        }
        return null
    }

    /** Writes [timers]; a failure (e.g. a full disk) is remembered and the next change writes again. */
    private suspend fun persist(timers: List<CookTimer>) {
        unsaved = try {
            store.write(StoredTimers.of(nextId, timers))
            persistedNextId = nextId
            false
        } catch (e: IOException) {
            // The timers keep running in this process, and their alarms carry them (onAlarm).
            true
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
        applyRinging(before.filter { it.state == TimerState.RINGING }, after.filter { it.state == TimerState.RINGING }, force)
        restartTicker(after, now)
    }

    /** The ringing service, and the sound of the alarms that still ring when another one stopped. */
    private fun applyRinging(ringingBefore: List<CookTimer>, ringingAfter: List<CookTimer>, force: Boolean) {
        if (ringingAfter.isEmpty()) {
            // A new process has no service of its own to stop, so only on a change.
            if (ringingBefore.isNotEmpty()) ringer.stop()
            return
        }
        if (force || ringingAfter != ringingBefore) ringer.ring(ringingAfter)
        // Android plays one notification sound at a time: the alarm that stopped may have been the
        // one sounding, so the newest one that still rings is posted again and sounds again.
        val stillRinging = ringingAfter.mapTo(HashSet()) { it.id }
        if (ringingBefore.any { it.id !in stillRinging }) notifier.realertAlarm(ringingAfter.maxBy { it.endsAt!! })
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

        /** Reads of the timers file before the engine goes on without it ([ensureLoaded]). */
        const val READ_ATTEMPTS = 3
        val READ_RETRY_DELAY: Duration = 100.milliseconds

        /** Ids of timers started while the file was unreadable: far above any stored id. */
        const val UNREAD_FIRST_ID = 1_000_000L
    }
}
