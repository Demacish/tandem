package casa.moller.tandem.core.datastore

import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Non-secret app settings in a Preferences DataStore. A corrupt file is reset to
 * [AppSettings.Default] and reported to [StorageHealthMonitor]; reading never throws.
 */
interface SettingsStore {
    /** Current settings; [AppSettings.Default] until something is changed. */
    val settings: Flow<AppSettings>

    /**
     * Atomically replaces the settings with the result of [transform].
     *
     * @throws java.io.IOException if the settings could not be written.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    suspend fun setOnboardingCompleted(completed: Boolean) = update { it.copy(onboardingCompleted = completed) }

    suspend fun setDemoMode(enabled: Boolean) = update { it.copy(demoMode = enabled) }

    suspend fun setActiveAccountId(accountId: String?) = update { it.copy(activeAccountId = accountId) }
}
