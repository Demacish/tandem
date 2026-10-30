package casa.moller.tandem.feature.shell.settings

import androidx.annotation.StringRes
import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
import casa.moller.tandem.core.model.diagnostics.LogExport
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.settings.CookTextScale
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.sync.ForegroundPolling
import casa.moller.tandem.core.model.sync.OfflineStorage
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode

// Phase 1b (inställningar lane): the states of the settings sub-screens under "More" (plan 6.2
// "Inställningar"). Each screen is stateless (XScreen(state, onAction)); SettingsViewModel builds
// the states and runs the actions.

/** Mer → Utseende (plan 6.4). */
data class AppearanceUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val cookTextScale: Float = CookTextScale.DEFAULT,
    val cookModeAlwaysDark: Boolean = false,
)

/** Mer → Kokläge och timers ("Beteende"). */
data class CookingUiState(
    val timerMode: TimerMode = TimerMode.IN_APP,
    val capabilities: TimerCapabilities = TimerCapabilities.AllGranted,
) {
    /** The clock app can be chosen (or is chosen already, so it can be changed back). */
    val clockAppSelectable: Boolean
        get() = capabilities.clockAppAvailable || timerMode == TimerMode.CLOCK_APP
}

/** Mer → Synk och lagring (plan 6.2 "Synk"). */
data class SyncSettingsUiState(
    val isDemo: Boolean = false,
    val interval: SyncInterval = SyncInterval.DEFAULT,
    val downloadAll: Boolean = false,
    val storage: OfflineStorage = OfflineStorage.Empty,
    val status: SyncStatus = SyncStatus.Idle,
    val clearingImageCache: Boolean = false,
    // 0.3.0 review (fix-data): the account's shopping preferences, null in demo mode and before they are known.
    val shopping: ShoppingServerSettings? = null,
)

/**
 * 0.3.0 review (fix-data, plan 6.2 "Har hemma-effekten", #12): the shopping preferences of the
 * Tandoor account that change what the app does, shown read only on "Synk och lagring" (they are
 * changed on the Tandoor web).
 *
 * @property addOnHand `shopping_add_onhand`: a check marks the food "at home" for the household.
 * @property excludeOnHand `mealplan_autoexclude_onhand` (Tandoor's own adds).
 * @property autoAdd `mealplan_autoadd_shopping`: "Lägg på inköpslistan" is preselected when planning.
 * @property pollSeconds how often the open list is read; null when `shopping_auto_sync` is 0 (off).
 */
data class ShoppingServerSettings(val addOnHand: Boolean, val excludeOnHand: Boolean, val autoAdd: Boolean, val pollSeconds: Long?) {
    companion object {
        /**
         * 0.3.0 komplettering: [serverMinimumSeconds] is the server's `shopping_min_autosync_interval`
         * (`PreferencesRepository.observeShoppingMinAutoSyncSeconds`), so the shown interval is the
         * one the app polls with (core:sync `DefaultForegroundSync` uses the same formula).
         */
        fun of(preferences: UserPreferences, serverMinimumSeconds: Int? = null): ShoppingServerSettings = ShoppingServerSettings(
            addOnHand = preferences.shoppingAddOnHand,
            excludeOnHand = preferences.mealPlanAutoExcludeOnHand,
            autoAdd = preferences.mealPlanAutoAddShopping,
            pollSeconds = preferences.shoppingAutoSyncSeconds
                .takeIf { ForegroundPolling.pollsShoppingList(it) }
                ?.let { ForegroundPolling.intervalFor(serverMinimumSeconds = serverMinimumSeconds, userSeconds = it).inWholeSeconds },
        )
    }
}

/**
 * Mer → Behörigheter: an overview, never a forced step (docs/design/README.md).
 *
 * @property batteryUnrestricted Android does not optimise Tandem's battery use; null when unknown.
 * @property showBattery the battery row is shown on phones known to stop background work
 *   (Samsung and similar, design correction).
 * @property showFullScreen the build can show the alarm over the lock screen (sideload).
 * @property cameraAllowed the scanners may use the camera (Fas S); null when unknown or the device
 *   has no camera (no row).
 */
data class PermissionsUiState(
    val capabilities: TimerCapabilities = TimerCapabilities.AllGranted,
    val batteryUnrestricted: Boolean? = null,
    val showBattery: Boolean = false,
    val showFullScreen: Boolean = false,
    val cameraAllowed: Boolean? = null,
) {
    /** Exact alarms are granted by the build (sideload) and cannot be switched off. */
    val exactAlarmsFixed: Boolean
        get() = capabilities.exactAlarmsAllowed && !capabilities.exactAlarmsRequestable
}

/** Mer → Diagnostik. */
data class DiagnosticsUiState(val snapshot: DiagnosticsSnapshot? = null, val exporting: Boolean = false)

/** What the settings screens ask for. */
sealed interface SettingsAction {
    data class SetThemeMode(val mode: ThemeMode) : SettingsAction

    data class SetDynamicColor(val enabled: Boolean) : SettingsAction

    data class SetCookTextScale(val scale: Float) : SettingsAction

    data class SetCookModeAlwaysDark(val enabled: Boolean) : SettingsAction

    data class SetTimerMode(val mode: TimerMode) : SettingsAction

    data class SetSyncInterval(val interval: SyncInterval) : SettingsAction

    data class SetDownloadAll(val enabled: Boolean) : SettingsAction

    data object SyncNow : SettingsAction

    data object ClearImageCache : SettingsAction

    data object ExportLog : SettingsAction

    /**
     * The permissions screen came (back) to the foreground: read the permissions again (the user
     * may have changed them in Android's settings). [batteryUnrestricted] and [cameraAllowed] are
     * read by the screen.
     */
    data class RefreshPermissions(val batteryUnrestricted: Boolean?, val cameraAllowed: Boolean? = null) : SettingsAction
}

/** One-off results the screens show. */
sealed interface SettingsEffect {
    /** "Export log": open the share sheet with the log. */
    data class ShareLog(val export: LogExport) : SettingsEffect

    /** A short message (snackbar). */
    data class Message(@StringRes val text: Int) : SettingsEffect
}
