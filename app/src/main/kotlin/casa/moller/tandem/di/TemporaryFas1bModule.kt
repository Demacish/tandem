package casa.moller.tandem.di

import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.data.cooklog.CookLogRepository
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.diagnostics.DiagnosticsRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.diagnostics.ColdStartTiming
import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import casa.moller.tandem.core.model.diagnostics.LogExport
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * TEMPORARY (Phase 1b grund lane): stand-in bindings for the Phase 1b contracts in core:data, so
 * the app builds and runs end to end while the lanes work in parallel (docs/BYGGE.md
 * "Fas 1b – kontrakt"). They keep everything in memory: no alarms, no notifications, no outbox,
 * nothing survives process death.
 *
 * Each lane that binds the real implementation (@Binds in its own module) DELETES the matching
 * module below in the same change, otherwise Hilt reports a duplicate binding:
 * - timer lane (core:timer): TemporaryFas1bTimerModule,
 * - cook-log lane (core:sync): TemporaryFas1bCookLogModule,
 * - settings/diagnostics lane: TemporaryFas1bDiagnosticsModule.
 * The integration lane deletes this file. Nothing here may ship in 0.2.0.
 */

@Module
@InstallIn(SingletonComponent::class)
internal interface TemporaryFas1bTimerModule {
    @Binds
    fun bindTimerController(impl: TemporaryTimerController): TimerController
}

@Module
@InstallIn(SingletonComponent::class)
internal interface TemporaryFas1bCookLogModule {
    @Binds
    fun bindCookLogRepository(impl: TemporaryCookLogRepository): CookLogRepository
}

@Module
@InstallIn(SingletonComponent::class)
internal interface TemporaryFas1bDiagnosticsModule {
    @Binds
    fun bindDiagnosticsRepository(impl: TemporaryDiagnosticsRepository): DiagnosticsRepository
}

/** In-memory timers that ring only while the process lives (no alarm, no notification). */
@Singleton
internal class TemporaryTimerController @Inject constructor(private val clock: Clock, @ApplicationScope scope: CoroutineScope) :
    TimerController {
    private val mutableTimers = MutableStateFlow<List<CookTimer>>(emptyList())
    private val mutableCapabilities = MutableStateFlow(
        TimerCapabilities(
            notificationsAllowed = false,
            notificationPermissionAsked = false,
            alarmChannelEnabled = true,
            exactAlarmsAllowed = false,
            exactAlarmsRequestable = false,
            fullScreenAlarmAllowed = false,
            clockAppAvailable = false,
        ),
    )
    private var nextId = 1L

    override val timers: StateFlow<List<CookTimer>> = mutableTimers.asStateFlow()
    override val capabilities: StateFlow<TimerCapabilities> = mutableCapabilities.asStateFlow()

    init {
        // Rings due timers twice a second, only while one is running.
        scope.launch {
            mutableTimers.map { list -> list.any { it.state == TimerState.RUNNING } }
                .distinctUntilChanged()
                .collectLatest { running ->
                    while (running) {
                        delay(TICK_MILLIS)
                        val now = clock.now()
                        mutableTimers.update { list -> list.map { it.ring(now) } }
                    }
                }
        }
    }

    override suspend fun start(label: String, duration: Duration, recipeId: Long?, stepIndex: Int?): TimerStartResult {
        if (label.isBlank() || duration < CookTimer.MIN_DURATION) return TimerStartResult.Invalid
        var result: TimerStartResult = TimerStartResult.TooManyTimers(CookTimer.MAX_PARALLEL_TIMERS)
        mutableTimers.update { list ->
            if (list.count { it.state != TimerState.FINISHED } >= CookTimer.MAX_PARALLEL_TIMERS) {
                list
            } else {
                val timer = CookTimer.start(nextId++, label, duration, clock.now(), recipeId, stepIndex)
                result = TimerStartResult.Started(timer)
                list + timer
            }
        }
        return result
    }

    override suspend fun pause(id: Long) = change(id) { it.pause(clock.now()) }

    override suspend fun resume(id: Long) = change(id) { it.resume(clock.now()) }

    override suspend fun addOneMinute(id: Long) = change(id) { it.plusOneMinute(clock.now()) }

    override suspend fun cancel(id: Long) = mutableTimers.update { list -> list.filterNot { it.id == id } }

    override suspend fun stopRinging(id: Long) = change(id) { it.stopRinging() }

    override suspend fun clearFinished() = mutableTimers.update { list -> list.filterNot { it.state == TimerState.FINISHED } }

    override suspend fun onNotificationPermissionResult(granted: Boolean) {
        mutableCapabilities.update { it.copy(notificationsAllowed = granted, notificationPermissionAsked = true) }
    }

    override fun refreshCapabilities() = Unit

    private fun change(id: Long, transform: (CookTimer) -> CookTimer) {
        mutableTimers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private companion object {
        const val TICK_MILLIS = 500L
    }
}

/** Keeps "Klart!" entries in memory only; they are NEVER sent to Tandoor. */
@Singleton
internal class TemporaryCookLogRepository @Inject constructor() : CookLogRepository {
    private val pending = MutableStateFlow<List<CookLogDraft>>(emptyList())

    override suspend fun logCooked(draft: CookLogDraft): Outcome<Unit> {
        pending.update { it + draft }
        return Outcome.Success(Unit)
    }

    override fun observePendingCount(): Flow<Int> = pending.map { it.size }
}

/** Versions from the build and timings in memory; the export has the versions only. */
@Singleton
internal class TemporaryDiagnosticsRepository @Inject constructor(private val buildInfo: AppBuildInfo, private val clock: Clock) :
    DiagnosticsRepository {
    private val snapshot = MutableStateFlow(
        DiagnosticsSnapshot(
            appVersion = buildInfo.versionName,
            appVersionCode = buildInfo.versionCode,
            distribution = buildInfo.distribution,
            androidSdk = buildInfo.sdkInt,
        ),
    )

    override fun observeDiagnostics(): Flow<DiagnosticsSnapshot> = snapshot

    override suspend fun recordColdStart(duration: Duration) {
        snapshot.update { it.copy(lastColdStart = ColdStartTiming(duration, clock.now())) }
    }

    override suspend fun recordFullSync(duration: Duration, recipeCount: Int) {
        snapshot.update { it.copy(lastFullSync = FullSyncTiming(duration, recipeCount, clock.now())) }
    }

    override suspend fun exportLog(): Outcome<LogExport> {
        val text = "Tandem ${buildInfo.versionName} (${buildInfo.versionCode}, ${buildInfo.distribution}), Android ${buildInfo.sdkInt}\n"
        return Outcome.Success(LogExport(text = text, fileName = "tandem-diagnostics.txt"))
    }
}
