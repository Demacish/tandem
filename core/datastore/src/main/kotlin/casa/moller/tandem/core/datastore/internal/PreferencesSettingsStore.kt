package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.CookTextScale
import casa.moller.tandem.core.model.settings.PendingVersionNotice
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.timer.TimerMode
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

        // The in-app "Tandoor was updated" notice (plan B18), stored as three strings.
        val VERSION_NOTICE_ACCOUNT = stringPreferencesKey("version_notice_account")
        val VERSION_NOTICE_PREVIOUS = stringPreferencesKey("version_notice_previous")
        val VERSION_NOTICE_NEW = stringPreferencesKey("version_notice_new")

        // --- Phase 1b (grund lane): cook mode, timer and sync settings (plan 6.2). Enums are
        // stored by name; an unknown name (newer version, garbage) reads as the default. ---
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val COOK_TEXT_SCALE = floatPreferencesKey("cook_text_scale")
        val COOK_MODE_ALWAYS_DARK = booleanPreferencesKey("cook_mode_always_dark")
        val TIMER_MODE = stringPreferencesKey("timer_mode")
        val SYNC_INTERVAL = stringPreferencesKey("sync_interval")
        val DOWNLOAD_ALL_RECIPES = booleanPreferencesKey("download_all_recipes")
        val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        val EXACT_ALARM_PROMPT_DISMISSED = booleanPreferencesKey("exact_alarm_prompt_dismissed")

        // --- Fas S (drycker lane): absent = not chosen (follows the UI language). ---
        val SYSTEMBOLAGET_LINK = booleanPreferencesKey("systembolaget_link")
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
        versionNotice = readVersionNotice(),
        // --- Phase 1b (grund lane) ---
        dynamicColor = this[PreferencesSettingsStore.Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
        cookTextScale = this[PreferencesSettingsStore.Keys.COOK_TEXT_SCALE]?.let(CookTextScale::coerce) ?: defaults.cookTextScale,
        cookModeAlwaysDark = this[PreferencesSettingsStore.Keys.COOK_MODE_ALWAYS_DARK] ?: defaults.cookModeAlwaysDark,
        timerMode = this[PreferencesSettingsStore.Keys.TIMER_MODE]
            ?.let { stored -> TimerMode.entries.firstOrNull { it.name == stored } }
            ?: defaults.timerMode,
        syncInterval = this[PreferencesSettingsStore.Keys.SYNC_INTERVAL]
            ?.let { stored -> SyncInterval.entries.firstOrNull { it.name == stored } }
            ?: defaults.syncInterval,
        downloadAllRecipes = this[PreferencesSettingsStore.Keys.DOWNLOAD_ALL_RECIPES] ?: defaults.downloadAllRecipes,
        notificationPermissionAsked = this[PreferencesSettingsStore.Keys.NOTIFICATION_PERMISSION_ASKED]
            ?: defaults.notificationPermissionAsked,
        exactAlarmPromptDismissed = this[PreferencesSettingsStore.Keys.EXACT_ALARM_PROMPT_DISMISSED]
            ?: defaults.exactAlarmPromptDismissed,
        // --- Fas S (drycker lane) ---
        systembolagetLink = this[PreferencesSettingsStore.Keys.SYSTEMBOLAGET_LINK],
    )
}

/** The pending version notice, or null when any of its three parts is missing. */
private fun Preferences.readVersionNotice(): PendingVersionNotice? {
    val accountId = this[PreferencesSettingsStore.Keys.VERSION_NOTICE_ACCOUNT] ?: return null
    val previous = this[PreferencesSettingsStore.Keys.VERSION_NOTICE_PREVIOUS] ?: return null
    val new = this[PreferencesSettingsStore.Keys.VERSION_NOTICE_NEW] ?: return null
    return PendingVersionNotice(accountId = accountId, previousVersion = previous, newVersion = new)
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
    val notice = settings.versionNotice
    if (notice == null) {
        remove(PreferencesSettingsStore.Keys.VERSION_NOTICE_ACCOUNT)
        remove(PreferencesSettingsStore.Keys.VERSION_NOTICE_PREVIOUS)
        remove(PreferencesSettingsStore.Keys.VERSION_NOTICE_NEW)
    } else {
        this[PreferencesSettingsStore.Keys.VERSION_NOTICE_ACCOUNT] = notice.accountId
        this[PreferencesSettingsStore.Keys.VERSION_NOTICE_PREVIOUS] = notice.previousVersion
        this[PreferencesSettingsStore.Keys.VERSION_NOTICE_NEW] = notice.newVersion
    }
    // --- Phase 1b (grund lane) ---
    this[PreferencesSettingsStore.Keys.DYNAMIC_COLOR] = settings.dynamicColor
    this[PreferencesSettingsStore.Keys.COOK_TEXT_SCALE] = CookTextScale.coerce(settings.cookTextScale)
    this[PreferencesSettingsStore.Keys.COOK_MODE_ALWAYS_DARK] = settings.cookModeAlwaysDark
    this[PreferencesSettingsStore.Keys.TIMER_MODE] = settings.timerMode.name
    this[PreferencesSettingsStore.Keys.SYNC_INTERVAL] = settings.syncInterval.name
    this[PreferencesSettingsStore.Keys.DOWNLOAD_ALL_RECIPES] = settings.downloadAllRecipes
    this[PreferencesSettingsStore.Keys.NOTIFICATION_PERMISSION_ASKED] = settings.notificationPermissionAsked
    this[PreferencesSettingsStore.Keys.EXACT_ALARM_PROMPT_DISMISSED] = settings.exactAlarmPromptDismissed
    // --- Fas S (drycker lane) ---
    val systembolaget = settings.systembolagetLink
    if (systembolaget == null) {
        remove(PreferencesSettingsStore.Keys.SYSTEMBOLAGET_LINK)
    } else {
        this[PreferencesSettingsStore.Keys.SYSTEMBOLAGET_LINK] = systembolaget
    }
}
