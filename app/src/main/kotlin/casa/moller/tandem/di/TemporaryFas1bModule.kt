package casa.moller.tandem.di

import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.data.diagnostics.DiagnosticsRepository
import casa.moller.tandem.core.model.diagnostics.ColdStartTiming
import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import casa.moller.tandem.core.model.diagnostics.LogExport
import casa.moller.tandem.core.model.error.Outcome
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/*
 * TEMPORARY (Phase 1b grund lane): stand-in bindings for the Phase 1b contracts in core:data, so
 * the app builds and runs end to end while the lanes work in parallel (docs/BYGGE.md
 * "Fas 1b – kontrakt"). They keep everything in memory: no alarms, no notifications, no outbox,
 * nothing survives process death.
 *
 * Each lane that binds the real implementation (@Binds in its own module) DELETES the matching
 * module below in the same change, otherwise Hilt reports a duplicate binding:
 * - timer lane (core:timer): TemporaryFas1bTimerModule,
 * - cook-log lane (core:sync): TemporaryFas1bCookLogModule (removed: SyncModule binds OfflineFirstCookLogRepository),
 * - settings/diagnostics lane: TemporaryFas1bDiagnosticsModule.
 * The integration lane deletes this file. Nothing here may ship in 0.2.0.
 */

@Module
@InstallIn(SingletonComponent::class)
internal interface TemporaryFas1bDiagnosticsModule {
    @Binds
    fun bindDiagnosticsRepository(impl: TemporaryDiagnosticsRepository): DiagnosticsRepository
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
