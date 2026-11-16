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
     * Fas 5 (fix-09, plan §5.5): only settings actually read from the file. While reading fails it
     * emits nothing and keeps trying, where [settings] emits [AppSettings.Default] for the moment;
     * for what must not fail open on a read error (the app lock). A corrupt file is still reset to
     * the defaults and reported, as for [settings].
     */
    val storedSettings: Flow<AppSettings>
        get() = settings

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
