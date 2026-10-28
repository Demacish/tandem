package casa.moller.tandem.feature.shell.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.data.diagnostics.DiagnosticsRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.sync.OfflineStorageRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.feature.shell.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The settings sub-screens under "More" (plan 6.2 "Inställningar"). Every screen gets its own
 * instance (one per navigation entry) and collects only its own state; the states are built
 * while collected ([SharingStarted.WhileSubscribed]), so the storage sizes, for example, are only
 * measured while "Sync and storage" is on screen.
 *
 * Every change is written at once (no "Save" button, unlike the Stitch tablet sketch); a failed
 * write keeps the old value and says so ([SettingsEffect.Message]).
 */
@HiltViewModel
class SettingsViewModel internal constructor(
    private val settingsRepository: SettingsRepository,
    private val appearanceRepository: AppearanceRepository,
    private val timers: TimerController,
    private val syncRepository: SyncRepository,
    private val offlineStorage: OfflineStorageRepository,
    private val diagnostics: DiagnosticsRepository,
    sessionRepository: SessionRepository,
    buildInfo: AppBuildInfo,
    manufacturer: String,
    // 0.3.0 review (fix-data): the account's shopping preferences, shown on "Synk och lagring".
    preferences: PreferencesRepository,
) : ViewModel() {

    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        appearanceRepository: AppearanceRepository,
        timers: TimerController,
        syncRepository: SyncRepository,
        offlineStorage: OfflineStorageRepository,
        diagnostics: DiagnosticsRepository,
        sessionRepository: SessionRepository,
        buildInfo: AppBuildInfo,
        preferences: PreferencesRepository,
    ) : this(
        settingsRepository,
        appearanceRepository,
        timers,
        syncRepository,
        offlineStorage,
        diagnostics,
        sessionRepository,
        buildInfo,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        preferences = preferences,
    )

    private val effects = Channel<SettingsEffect>(Channel.BUFFERED)

    /** One-off results: the log to share, short messages. */
    val effect: Flow<SettingsEffect> = effects.receiveAsFlow()

    private val settings = settingsRepository.settings
    private val isDemo = sessionRepository.session.map { it is Session.Demo }.distinctUntilChanged()
    private val clearingImageCache = MutableStateFlow(false)
    private val exporting = MutableStateFlow(false)
    private val batteryUnrestricted = MutableStateFlow<Boolean?>(null)
    private val showBattery = BatteryAdvice.appliesTo(manufacturer)
    private val showFullScreen = buildInfo.distribution == SIDELOAD

    val appearance: StateFlow<AppearanceUiState> = combine(appearanceRepository.themeMode, settings) { theme, current ->
        AppearanceUiState(
            themeMode = theme,
            dynamicColor = current.dynamicColor,
            cookTextScale = current.cookTextScale,
            cookModeAlwaysDark = current.cookModeAlwaysDark,
        )
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, AppearanceUiState())

    val cooking: StateFlow<CookingUiState> = combine(settings, timers.capabilities) { current, capabilities ->
        CookingUiState(timerMode = current.timerMode, capabilities = capabilities)
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, CookingUiState())

    val sync: StateFlow<SyncSettingsUiState> = combine(
        combine(settings, isDemo) { current, demo -> current to demo },
        offlineStorage.observeStorage(),
        syncRepository.status,
        clearingImageCache,
        // 0.3.0 komplettering: with the server's polling floor, as the list is really polled.
        combine(preferences.observeUserPreferences(), preferences.observeShoppingMinAutoSyncSeconds()) { prefs, floor ->
            ShoppingServerSettings.of(prefs, floor)
        }.distinctUntilChanged(),
    ) { (current, demo), storage, status, clearing, shopping ->
        SyncSettingsUiState(
            isDemo = demo,
            interval = current.syncInterval,
            downloadAll = current.downloadAllRecipes,
            storage = storage,
            status = status,
            clearingImageCache = clearing,
            shopping = shopping.takeUnless { demo },
        )
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, SyncSettingsUiState())

    val permissions: StateFlow<PermissionsUiState> = combine(timers.capabilities, batteryUnrestricted) { capabilities, battery ->
        PermissionsUiState(
            capabilities = capabilities,
            batteryUnrestricted = battery,
            showBattery = showBattery,
            showFullScreen = showFullScreen,
        )
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, PermissionsUiState(showBattery = showBattery, showFullScreen = showFullScreen))

    val diagnosticsState: StateFlow<DiagnosticsUiState> = combine(diagnostics.observeDiagnostics(), exporting) { snapshot, busy ->
        DiagnosticsUiState(snapshot = snapshot, exporting = busy)
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, DiagnosticsUiState())

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetThemeMode -> save { appearanceRepository.setThemeMode(action.mode) }

            is SettingsAction.SetDynamicColor -> save { settingsRepository.setDynamicColor(action.enabled) }

            is SettingsAction.SetCookTextScale -> save { settingsRepository.setCookTextScale(action.scale) }

            is SettingsAction.SetCookModeAlwaysDark -> save { settingsRepository.setCookModeAlwaysDark(action.enabled) }

            is SettingsAction.SetTimerMode -> save { settingsRepository.setTimerMode(action.mode) }

            is SettingsAction.SetSyncInterval -> save { settingsRepository.setSyncInterval(action.interval) }

            is SettingsAction.SetDownloadAll -> viewModelScope.launch {
                val result = settingsRepository.setDownloadAllRecipes(action.enabled)
                if (result is Outcome.Failure) {
                    effects.send(SettingsEffect.Message(R.string.shell_settings_not_saved))
                } else if (action.enabled) {
                    // Start at once instead of at the next periodic sync.
                    syncRepository.requestSync(SyncReason.USER_REFRESH)
                }
            }

            SettingsAction.SyncNow -> syncRepository.requestSync(SyncReason.USER_REFRESH)

            SettingsAction.ClearImageCache -> clearImageCache()

            SettingsAction.ExportLog -> exportLog()

            is SettingsAction.RefreshPermissions -> {
                batteryUnrestricted.value = action.batteryUnrestricted
                timers.refreshCapabilities()
            }
        }
    }

    private fun save(write: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            if (write() is Outcome.Failure) effects.send(SettingsEffect.Message(R.string.shell_settings_not_saved))
        }
    }

    private fun clearImageCache() {
        if (clearingImageCache.value) return
        clearingImageCache.value = true
        viewModelScope.launch {
            val result = offlineStorage.clearImageCache()
            clearingImageCache.value = false
            val message = when (result) {
                is Outcome.Success -> R.string.settings_image_cache_cleared
                is Outcome.Failure -> R.string.settings_image_cache_not_cleared
            }
            effects.send(SettingsEffect.Message(message))
        }
    }

    private fun exportLog() {
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            val result = diagnostics.exportLog()
            exporting.value = false
            effects.send(
                when (result) {
                    is Outcome.Success -> SettingsEffect.ShareLog(result.value)
                    is Outcome.Failure -> SettingsEffect.Message(R.string.settings_diagnostics_export_failed)
                },
            )
        }
    }

    internal companion object {
        const val SIDELOAD = "sideload"
        private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)
    }
}

/**
 * Phones known to stop apps in the background more than Android itself does (Samsung and similar,
 * see dontkillmyapp.com): only there the permissions overview shows the battery row (design
 * correction "Batteri-kortet visas bara på Samsung och liknande").
 */
internal object BatteryAdvice {
    private val MANUFACTURERS = setOf(
        "samsung",
        "xiaomi",
        "redmi",
        "poco",
        "huawei",
        "honor",
        "oneplus",
        "oppo",
        "realme",
        "vivo",
        "iqoo",
        "meizu",
        "asus",
        "sony",
        "nokia",
        "hmd global",
        "lenovo",
        "motorola",
        "tecno",
        "infinix",
        "itel",
        "wiko",
    )

    fun appliesTo(manufacturer: String): Boolean = manufacturer.trim().lowercase() in MANUFACTURERS
}
