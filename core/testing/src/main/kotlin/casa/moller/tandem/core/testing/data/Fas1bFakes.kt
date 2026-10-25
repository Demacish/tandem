package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.cooklog.CookLogRepository
import casa.moller.tandem.core.data.diagnostics.DiagnosticsRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.diagnostics.ColdStartTiming
import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import casa.moller.tandem.core.model.diagnostics.LogExport
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.CookTextScale
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.time.TestClock
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Phase 1b (grund lane): fakes of the cook mode, timer, cook log, settings and diagnostics
// contracts in core:data. Deterministic: time comes from a TestClock and only moves when the test
// calls advanceTimeBy.

/**
 * In-memory [TimerController] with the same state changes as the real one ([CookTimer]'s
 * transitions). Due timers ring only when the test calls [advanceTimeBy] or [ringDueTimers].
 *
 * @property mode what [start] does; [TimerMode.CLOCK_APP] records the request in [clockAppRequests].
 */
class FakeTimerController(
    val clock: TestClock = TestClock(),
    capabilities: TimerCapabilities = TimerCapabilities.AllGranted,
    var mode: TimerMode = TimerMode.IN_APP,
) : TimerController {
    private val mutableTimers = MutableStateFlow<List<CookTimer>>(emptyList())
    private val mutableCapabilities = MutableStateFlow(capabilities)
    private var nextId = 1L

    override val timers: StateFlow<List<CookTimer>> = mutableTimers.asStateFlow()
    override val capabilities: StateFlow<TimerCapabilities> = mutableCapabilities.asStateFlow()

    /** Labels and durations handed to the clock app in [TimerMode.CLOCK_APP]. */
    val clockAppRequests = mutableListOf<Pair<String, Duration>>()

    /** Answers given to [onNotificationPermissionResult], in order. */
    val permissionResults = mutableListOf<Boolean>()

    var refreshCount: Int = 0
        private set

    /** Replaces what the system allows (e.g. the user switched off the alarm channel). */
    fun setCapabilities(capabilities: TimerCapabilities) {
        mutableCapabilities.value = capabilities
    }

    /** Moves the clock and rings every timer that became due. */
    fun advanceTimeBy(duration: Duration) {
        clock.advanceBy(duration)
        ringDueTimers()
    }

    fun ringDueTimers() {
        val now = clock.now()
        mutableTimers.update { list -> list.map { it.ring(now) } }
    }

    override suspend fun start(label: String, duration: Duration, recipeId: Long?, stepIndex: Int?, sourceKey: String?): TimerStartResult {
        if (label.isBlank() || duration < CookTimer.MIN_DURATION) return TimerStartResult.Invalid
        if (mode == TimerMode.CLOCK_APP) {
            if (!capabilities.value.clockAppAvailable) return TimerStartResult.ClockAppUnavailable
            clockAppRequests += label to duration
            return TimerStartResult.HandedToClockApp
        }
        val active = mutableTimers.value.count { it.state != TimerState.FINISHED }
        if (active >= CookTimer.MAX_PARALLEL_TIMERS) return TimerStartResult.TooManyTimers(CookTimer.MAX_PARALLEL_TIMERS)
        val timer = CookTimer.start(nextId++, label, duration, clock.now(), recipeId, stepIndex, sourceKey)
        mutableTimers.update { it + timer }
        return TimerStartResult.Started(timer)
    }

    override suspend fun pause(id: Long) = change(id) { it.pause(clock.now()) }

    override suspend fun resume(id: Long) = change(id) { it.resume(clock.now()) }

    override suspend fun addOneMinute(id: Long) = change(id) { it.plusOneMinute(clock.now()) }

    override suspend fun cancel(id: Long) {
        mutableTimers.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun stopRinging(id: Long) = change(id) { it.stopRinging() }

    override suspend fun clearFinished() {
        mutableTimers.update { list -> list.filterNot { it.state == TimerState.FINISHED } }
    }

    override suspend fun onNotificationPermissionResult(granted: Boolean) {
        permissionResults += granted
        mutableCapabilities.update { it.copy(notificationsAllowed = granted, notificationPermissionAsked = true) }
    }

    override fun refreshCapabilities() {
        refreshCount++
    }

    private fun change(id: Long, transform: (CookTimer) -> CookTimer) {
        mutableTimers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}

/**
 * In-memory [CookLogRepository]: [logCooked] appends to [logged] and, unless [sendImmediately],
 * counts it as pending until [markAllSent].
 */
class FakeCookLogRepository : CookLogRepository {
    val logged = mutableListOf<CookLogDraft>()
    private val pending = MutableStateFlow(0)

    /** What [logCooked] returns; on failure nothing is logged. */
    var result: Outcome<Unit> = Outcome.Success(Unit)

    /** True: entries count as already sent (pending stays 0). */
    var sendImmediately: Boolean = false

    override suspend fun logCooked(draft: CookLogDraft): Outcome<Unit> {
        if (result is Outcome.Failure) return result
        logged += draft
        if (!sendImmediately) pending.update { it + 1 }
        return result
    }

    override fun observePendingCount(): Flow<Int> = pending

    /** The outbox sent everything. */
    fun markAllSent() {
        pending.value = 0
    }
}

/**
 * In-memory [SettingsRepository] over [state]; with [failWrites] every setter fails with
 * [DataProblem.SETTINGS_NOT_SAVED] and changes nothing.
 */
class FakeSettingsRepository(initial: AppSettings = AppSettings.Default) : SettingsRepository {
    val state = MutableStateFlow(initial)
    var failWrites: Boolean = false

    override val settings: Flow<AppSettings> = state

    override suspend fun setDynamicColor(enabled: Boolean) = write { it.copy(dynamicColor = enabled) }

    override suspend fun setCookTextScale(scale: Float) = write { it.copy(cookTextScale = CookTextScale.coerce(scale)) }

    override suspend fun setCookModeAlwaysDark(enabled: Boolean) = write { it.copy(cookModeAlwaysDark = enabled) }

    override suspend fun setTimerMode(mode: TimerMode) = write { it.copy(timerMode = mode) }

    override suspend fun setSyncInterval(interval: SyncInterval) = write { it.copy(syncInterval = interval) }

    override suspend fun setDownloadAllRecipes(enabled: Boolean) = write { it.copy(downloadAllRecipes = enabled) }

    override suspend fun setNotificationPermissionAsked(asked: Boolean) = write { it.copy(notificationPermissionAsked = asked) }

    override suspend fun setExactAlarmPromptDismissed(dismissed: Boolean) = write { it.copy(exactAlarmPromptDismissed = dismissed) }

    private fun write(transform: (AppSettings) -> AppSettings): Outcome<Unit> {
        if (failWrites) return Outcome.Failure(DataProblem.SETTINGS_NOT_SAVED.toError())
        state.update(transform)
        return Outcome.Success(Unit)
    }
}

/**
 * In-memory [DiagnosticsRepository]. Recorded timings use [clock] for their time stamps;
 * [exportLog] returns [exportResult] (by default a short text built from the snapshot).
 */
class FakeDiagnosticsRepository(
    val clock: TestClock = TestClock(),
    initial: DiagnosticsSnapshot = DiagnosticsSnapshot(
        appVersion = "0.2.0-dev",
        appVersionCode = 4,
        distribution = "sideload",
        androidSdk = 36,
    ),
) : DiagnosticsRepository {
    val snapshot = MutableStateFlow(initial)

    /** What [exportLog] returns; null builds a text from [snapshot]. */
    var exportResult: Outcome<LogExport>? = null

    var exportCount: Int = 0
        private set

    override fun observeDiagnostics(): Flow<DiagnosticsSnapshot> = snapshot

    override suspend fun recordColdStart(duration: Duration) {
        snapshot.update { it.copy(lastColdStart = ColdStartTiming(duration, clock.now())) }
    }

    override suspend fun recordFullSync(duration: Duration, recipeCount: Int) {
        snapshot.update { it.copy(lastFullSync = FullSyncTiming(duration, recipeCount, clock.now())) }
    }

    override suspend fun exportLog(): Outcome<LogExport> {
        exportCount++
        exportResult?.let { return it }
        val text = snapshot.value.let { "Tandem ${it.appVersion} (${it.distribution}), Android ${it.androidSdk}" }
        return Outcome.Success(LogExport(text = text, fileName = "tandem-diagnostics.txt"))
    }
}
