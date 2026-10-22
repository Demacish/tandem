package casa.moller.tandem.feature.shell.settings

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.data.sync.OfflineStorageRepository
import casa.moller.tandem.core.model.diagnostics.ColdStartTiming
import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.sync.OfflineStorage
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Phase 1b (inställningar lane): fixed states for the settings screens' tests and screenshots.
// The time stamps are on the sample day of core:testing (2000-01-15).

internal val SAMPLE_INSTANT: Instant = Instant.parse("2000-01-15T17:42:00Z")

internal val SAMPLE_STORAGE = OfflineStorage(
    recipeCount = 312,
    downloadedCount = 87,
    databaseBytes = 14_200_000,
    imageCacheBytes = 128_000_000,
    estimatedDownloadBytes = 96_000_000,
)

internal val SAMPLE_SYNC_STATUS = SyncStatus(lastSuccess = SAMPLE_INSTANT, pendingChanges = 2)

internal val SAMPLE_SYNC = SyncSettingsUiState(
    interval = SyncInterval.FIFTEEN_MINUTES,
    downloadAll = false,
    storage = SAMPLE_STORAGE,
    status = SAMPLE_SYNC_STATUS,
)

/** "Download all recipes" is on and halfway; the sync runs. */
internal val SAMPLE_SYNC_DOWNLOADING = SAMPLE_SYNC.copy(
    interval = SyncInterval.ONE_HOUR,
    downloadAll = true,
    storage = SAMPLE_STORAGE.copy(downloadedCount = 156),
    status = SAMPLE_SYNC_STATUS.copy(running = true),
)

/** The sync is held back by the circuit breaker; everything is downloaded. */
internal val SAMPLE_SYNC_PAUSED = SAMPLE_SYNC.copy(
    downloadAll = true,
    storage = SAMPLE_STORAGE.copy(downloadedCount = 312, estimatedDownloadBytes = 0),
    status = SAMPLE_SYNC_STATUS.copy(pause = SyncPause.CIRCUIT_BREAKER, pendingChanges = 0),
)

internal val SAMPLE_APPEARANCE = AppearanceUiState(themeMode = ThemeMode.SYSTEM, dynamicColor = false, cookTextScale = 1.3f)

internal val SAMPLE_COOKING = CookingUiState()

/** Play build without exact alarms and with the alarm channel switched off. */
internal val CAPABILITIES_LIMITED = TimerCapabilities.AllGranted.copy(
    alarmChannelEnabled = false,
    exactAlarmsAllowed = false,
    exactAlarmsRequestable = true,
    fullScreenAlarmAllowed = false,
)

/** Sideload: exact alarms come with the build, the alarm can fill the lock screen. */
internal val SAMPLE_PERMISSIONS = PermissionsUiState(
    capabilities = TimerCapabilities.AllGranted.copy(exactAlarmsRequestable = false),
    batteryUnrestricted = false,
    showBattery = true,
    showFullScreen = true,
)

internal val SAMPLE_DIAGNOSTICS = DiagnosticsUiState(
    snapshot = DiagnosticsSnapshot(
        appVersion = "0.2.0",
        appVersionCode = 20,
        distribution = "sideload",
        androidSdk = 36,
        serverVersion = "2.6.13",
        lastColdStart = ColdStartTiming(843.milliseconds, SAMPLE_INSTANT),
        lastFullSync = FullSyncTiming(41.seconds, 312, SAMPLE_INSTANT),
    ),
)

/** Nothing measured yet (a new install), in demo mode. */
internal val SAMPLE_DIAGNOSTICS_EMPTY = DiagnosticsUiState(
    snapshot = DiagnosticsSnapshot(appVersion = "0.2.0", appVersionCode = 20, distribution = "play", androidSdk = 35),
)

/**
 * The stateless settings screens with fixed states, so the shell's tests never need Hilt. Every
 * action is recorded in [actions].
 */
internal class FixedSettingsPages(
    private val appearance: AppearanceUiState = SAMPLE_APPEARANCE,
    private val cooking: CookingUiState = SAMPLE_COOKING,
    private val sync: SyncSettingsUiState = SAMPLE_SYNC,
    private val permissions: PermissionsUiState = SAMPLE_PERMISSIONS,
    private val diagnostics: DiagnosticsUiState = SAMPLE_DIAGNOSTICS,
) : SettingsPages {
    val actions = mutableListOf<SettingsAction>()
    val openedSystemSettings = mutableListOf<SystemSetting>()

    @Composable
    override fun Appearance(onNavigateBack: () -> Unit) {
        AppearanceSettingsScreen(state = appearance, onAction = { actions += it }, onNavigateBack = onNavigateBack)
    }

    @Composable
    override fun Cooking(onNavigateBack: () -> Unit, onOpenPermissions: () -> Unit, onOpenAppearance: () -> Unit) {
        CookingSettingsScreen(
            state = cooking,
            onAction = { actions += it },
            onNavigateBack = onNavigateBack,
            onOpenPermissions = onOpenPermissions,
            onOpenAppearance = onOpenAppearance,
        )
    }

    @Composable
    override fun Sync(onNavigateBack: () -> Unit) {
        SyncSettingsScreen(state = sync, onAction = { actions += it }, onNavigateBack = onNavigateBack)
    }

    @Composable
    override fun Permissions(onNavigateBack: () -> Unit) {
        PermissionsScreen(state = permissions, onOpenSystemSetting = { openedSystemSettings += it }, onNavigateBack = onNavigateBack)
    }

    @Composable
    override fun Diagnostics(onNavigateBack: () -> Unit) {
        DiagnosticsScreen(state = diagnostics, onAction = { actions += it }, onNavigateBack = onNavigateBack)
    }
}

/** [OfflineStorageRepository] over [storage]; [clearResult] is what clearing the image cache returns. */
internal class FakeOfflineStorageRepository(initial: OfflineStorage = SAMPLE_STORAGE) : OfflineStorageRepository {
    val storage = MutableStateFlow(initial)
    var clearResult: Outcome<Unit> = Outcome.Success(Unit)
    var clears = 0

    override fun observeStorage(): Flow<OfflineStorage> = storage

    override suspend fun clearImageCache(): Outcome<Unit> {
        clears++
        if (clearResult is Outcome.Success) storage.value = storage.value.copy(imageCacheBytes = 0)
        return clearResult
    }

    companion object {
        val FAILED: Outcome<Unit> = Outcome.Failure(TandemError.Unknown("tandem.storage.clear"))
    }
}
