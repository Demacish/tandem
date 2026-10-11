package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.server.WritePolicy
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.demo.DemoSeeder
import casa.moller.tandem.core.sync.engine.EngineState
import casa.moller.tandem.core.sync.engine.SyncEngine
import casa.moller.tandem.core.sync.engine.SyncKeys
import casa.moller.tandem.core.sync.guard.GuardState
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [SyncRepository] for the UI: [status] combines the engine's run, the outbox count, the last
 * successful sync, the guard's pauses, the network and safe mode; [requestSync] hands the work to
 * WorkManager (expedited one-off work, so it survives the screen that asked for it).
 *
 * Pauses, in this order: unauthorized, blocked, circuit breaker, offline, import running, safe
 * mode (plan 3.6: the outbox waits while the server version is untested).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultSyncRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val demo: DemoSeeder,
    private val guard: SyncGuard,
    private val network: NetworkStatus,
    private val clock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
) : SyncRepository {

    override val status: StateFlow<SyncStatus> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Remote -> remoteStatus(source)
                ActiveSource.Demo, ActiveSource.Loading, ActiveSource.None -> flowOf(SyncStatus.Idle)
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, SyncStatus.Idle)

    private fun remoteStatus(remote: ActiveSource.Remote): Flow<SyncStatus> {
        val database = databases.get(remote.kind)
        val lastSuccess = database.syncStateDao().observe(remote.accountId).map { rows ->
            rows.firstOrNull { it.entity == SyncKeys.LAST_SUCCESS }?.lastFullRelistEpochMillis
        }
        val local = combine(engine.state, database.outboxDao().observeCount(remote.accountId), lastSuccess) { engineState, pending, last ->
            Triple(engineState, pending, last)
        }
        val conditions = combine(guard.state, network.online, clock.ticks(PAUSE_TICK_MILLIS)) { guardState, online, _ ->
            guardState to online
        }
        return combine(local, conditions) { (engineState, pending, last), (guardState, online) ->
            statusOf(remote, engineState, pending, last, guardState, online)
        }
    }

    private fun statusOf(
        remote: ActiveSource.Remote,
        engineState: EngineState,
        pending: Int,
        lastSuccessMillis: Long?,
        guardState: GuardState,
        online: Boolean,
    ): SyncStatus {
        val pause = guard.pauseAt(guardState, remote.accountId, clock.nowMillis())
            ?: SyncPause.OFFLINE.takeIf { !online }
            ?: SyncPause.SAFE_MODE.takeIf { remote.writePolicy != WritePolicy.SEND }
        return SyncStatus(
            running = engineState.running,
            progress = engineState.progress,
            lastSuccess = lastSuccessMillis?.let { Instant.fromEpochMilliseconds(it) },
            lastError = engineState.lastError,
            pendingChanges = pending,
            pause = pause,
        )
    }

    override fun requestSync(reason: SyncReason) {
        scope.launch {
            when (sources.current()) {
                is ActiveSource.Remote -> scheduler.requestSync(reason)
                // Demo mode has no server; asking for a sync only refreshes the demo data's dates.
                ActiveSource.Demo -> demo.ensureSeeded()
                ActiveSource.Loading, ActiveSource.None -> Unit
            }
        }
    }

    override fun firstSync(): Flow<SyncProgress> = flow {
        when (sources.current()) {
            is ActiveSource.Remote -> emitAll(engine.firstSync())

            ActiveSource.Demo -> {
                demo.ensureSeeded()
                emit(SyncProgress(SyncPhase.DONE))
            }

            ActiveSource.Loading, ActiveSource.None -> emit(SyncProgress(SyncPhase.FAILED, error = DataProblem.NO_ACCOUNT.toError()))
        }
    }

    private companion object {
        /** Time-limited pauses (blocked, breaker) end on screen within this long. */
        const val PAUSE_TICK_MILLIS = 30_000L
    }
}
