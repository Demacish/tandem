package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** [SettingsStore] on a Preferences DataStore. Missing or unknown values fall back to defaults. */
internal class PreferencesSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {

    // A read error (other than a corrupt file, which the corruption handler resets) gives the
    // defaults for now and reads again later; the flow never completes, so theme, demo mode and
    // the active account keep updating afterwards.
    override val settings: Flow<AppSettings> = dataStore.data
        .map { it.toAppSettings() }
        .resubscribeOnFailure(fallback = AppSettings.Default)
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences -> preferences.write(transform(preferences.toAppSettings())) }
    }

    internal object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")
    }

    companion object {
        fun create(produceFile: () -> File, health: StorageHealthMonitor, scope: CoroutineScope): PreferencesSettingsStore =
            PreferencesSettingsStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler<Preferences> {
                        health.report(RecoveryReason.SETTINGS_UNREADABLE)
                        emptyPreferences()
                    },
                    scope = scope,
                    produceFile = produceFile,
                ),
            )
    }
}

private fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings.Default
    return AppSettings(
        themeMode = this[PreferencesSettingsStore.Keys.THEME_MODE]
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: defaults.themeMode,
        onboardingCompleted = this[PreferencesSettingsStore.Keys.ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
        demoMode = this[PreferencesSettingsStore.Keys.DEMO_MODE] ?: defaults.demoMode,
        activeAccountId = this[PreferencesSettingsStore.Keys.ACTIVE_ACCOUNT_ID],
    )
}

private fun MutablePreferences.write(settings: AppSettings) {
    this[PreferencesSettingsStore.Keys.THEME_MODE] = settings.themeMode.name
    this[PreferencesSettingsStore.Keys.ONBOARDING_COMPLETED] = settings.onboardingCompleted
    this[PreferencesSettingsStore.Keys.DEMO_MODE] = settings.demoMode
    val activeAccountId = settings.activeAccountId
    if (activeAccountId == null) {
        remove(PreferencesSettingsStore.Keys.ACTIVE_ACCOUNT_ID)
    } else {
        this[PreferencesSettingsStore.Keys.ACTIVE_ACCOUNT_ID] = activeAccountId
    }
}
