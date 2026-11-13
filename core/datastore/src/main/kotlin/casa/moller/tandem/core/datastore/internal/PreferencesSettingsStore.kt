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
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.CookTextScale
import casa.moller.tandem.core.model.settings.HandsfreeSettings
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
import kotlinx.datetime.LocalTime

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

        // --- Fas 4 (listnotiser) ---
        val SHOPPING_CHANGE_NOTIFICATIONS = booleanPreferencesKey("shopping_change_notifications")

        // --- Fas S (drycker lane): absent = not chosen (follows the UI language). ---
        val SYSTEMBOLAGET_LINK = booleanPreferencesKey("systembolaget_link")

        // --- Fas 5 (planera lane): reminder times as minutes after midnight, local wall clock. ---
        val PANTRY_EXPIRY_TIME = intPreferencesKey("pantry_expiry_time_minutes")
        val MEAL_REMINDERS = booleanPreferencesKey("meal_reminders")
        val MEAL_REMINDER_TIME = intPreferencesKey("meal_reminder_time_minutes")

        // --- Fas 4 (skafferinotis lane) ---
        val PANTRY_EXPIRY_NOTIFICATIONS = booleanPreferencesKey("pantry_expiry_notifications")
        val PANTRY_EXPIRY_OFFER_DISMISSED = booleanPreferencesKey("pantry_expiry_offer_dismissed")

        // --- Fas 5 (kok lane): cook mode's handsfree control ---
        val HANDSFREE_ENABLED = booleanPreferencesKey("handsfree_enabled")
        val HANDSFREE_VOICE = booleanPreferencesKey("handsfree_voice")
        val HANDSFREE_READ_STEPS = booleanPreferencesKey("handsfree_read_steps")
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

/** Fas 5 (planera lane): the settings with the reminders' keys read on top ([readReminders]). */
private fun Preferences.toAppSettings(): AppSettings = readReminders(baseSettings())

private fun Preferences.baseSettings(): AppSettings {
    val defaults = AppSettings.Default
    return AppSettings(
        themeMode = enumOrNull(PreferencesSettingsStore.Keys.THEME_MODE, ThemeMode.entries) ?: defaults.themeMode,
        onboardingCompleted = this[PreferencesSettingsStore.Keys.ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
        demoMode = this[PreferencesSettingsStore.Keys.DEMO_MODE] ?: defaults.demoMode,
        activeAccountId = this[PreferencesSettingsStore.Keys.ACTIVE_ACCOUNT_ID],
        versionNotice = readVersionNotice(),
        // --- Phase 1b (grund lane) ---
        dynamicColor = this[PreferencesSettingsStore.Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
        cookTextScale = this[PreferencesSettingsStore.Keys.COOK_TEXT_SCALE]?.let(CookTextScale::coerce) ?: defaults.cookTextScale,
        cookModeAlwaysDark = this[PreferencesSettingsStore.Keys.COOK_MODE_ALWAYS_DARK] ?: defaults.cookModeAlwaysDark,
        timerMode = enumOrNull(PreferencesSettingsStore.Keys.TIMER_MODE, TimerMode.entries) ?: defaults.timerMode,
        syncInterval = enumOrNull(PreferencesSettingsStore.Keys.SYNC_INTERVAL, SyncInterval.entries) ?: defaults.syncInterval,
        downloadAllRecipes = this[PreferencesSettingsStore.Keys.DOWNLOAD_ALL_RECIPES] ?: defaults.downloadAllRecipes,
        notificationPermissionAsked = this[PreferencesSettingsStore.Keys.NOTIFICATION_PERMISSION_ASKED]
            ?: defaults.notificationPermissionAsked,
        exactAlarmPromptDismissed = this[PreferencesSettingsStore.Keys.EXACT_ALARM_PROMPT_DISMISSED]
            ?: defaults.exactAlarmPromptDismissed,
        shoppingChangeNotifications = this[PreferencesSettingsStore.Keys.SHOPPING_CHANGE_NOTIFICATIONS]
            ?: defaults.shoppingChangeNotifications,
        // --- Fas S (drycker lane) ---
        systembolagetLink = this[PreferencesSettingsStore.Keys.SYSTEMBOLAGET_LINK],
        // --- Fas 4 (skafferinotis lane) ---
        pantryExpiryNotifications = this[PreferencesSettingsStore.Keys.PANTRY_EXPIRY_NOTIFICATIONS]
            ?: defaults.pantryExpiryNotifications,
        pantryExpiryOfferDismissed = this[PreferencesSettingsStore.Keys.PANTRY_EXPIRY_OFFER_DISMISSED]
            ?: defaults.pantryExpiryOfferDismissed,
        // --- Fas 5 (kok lane) ---
        handsfree = readHandsfree(defaults.handsfree),
    )
}

/** Fas 5 (kok lane): cook mode's handsfree control; a missing key reads as [defaults]'. */
private fun Preferences.readHandsfree(defaults: HandsfreeSettings): HandsfreeSettings = HandsfreeSettings(
    enabled = this[PreferencesSettingsStore.Keys.HANDSFREE_ENABLED] ?: defaults.enabled,
    voice = this[PreferencesSettingsStore.Keys.HANDSFREE_VOICE] ?: defaults.voice,
    readSteps = this[PreferencesSettingsStore.Keys.HANDSFREE_READ_STEPS] ?: defaults.readSteps,
)

/** Fas 5 (planera lane): the daily reminders' switch and times; a missing or broken key reads as [settings]' default. */
private fun Preferences.readReminders(settings: AppSettings): AppSettings = settings.copy(
    pantryExpiryTime = timeOrNull(PreferencesSettingsStore.Keys.PANTRY_EXPIRY_TIME) ?: settings.pantryExpiryTime,
    mealReminders = this[PreferencesSettingsStore.Keys.MEAL_REMINDERS] ?: settings.mealReminders,
    mealReminderTime = timeOrNull(PreferencesSettingsStore.Keys.MEAL_REMINDER_TIME) ?: settings.mealReminderTime,
)

/** Fas 5 (planera lane): a time of day stored as minutes after midnight; null when absent or outside the day. */
private fun Preferences.timeOrNull(key: Preferences.Key<Int>): LocalTime? =
    this[key]?.takeIf { it in 0 until MINUTES_PER_DAY }?.let { LocalTime(it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR) }

private fun LocalTime.minutesOfDay(): Int = hour * MINUTES_PER_HOUR + minute

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/** The enum stored by name under [key], or null when absent or unknown (a newer version, garbage). */
private fun <T : Enum<T>> Preferences.enumOrNull(key: Preferences.Key<String>, entries: List<T>): T? =
    this[key]?.let { stored -> entries.firstOrNull { it.name == stored } }

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
    this[PreferencesSettingsStore.Keys.SHOPPING_CHANGE_NOTIFICATIONS] = settings.shoppingChangeNotifications
    // --- Fas S (drycker lane) ---
    val systembolaget = settings.systembolagetLink
    if (systembolaget == null) {
        remove(PreferencesSettingsStore.Keys.SYSTEMBOLAGET_LINK)
    } else {
        this[PreferencesSettingsStore.Keys.SYSTEMBOLAGET_LINK] = systembolaget
    }
    // --- Fas 5 (planera lane) ---
    this[PreferencesSettingsStore.Keys.PANTRY_EXPIRY_TIME] = settings.pantryExpiryTime.minutesOfDay()
    this[PreferencesSettingsStore.Keys.MEAL_REMINDERS] = settings.mealReminders
    this[PreferencesSettingsStore.Keys.MEAL_REMINDER_TIME] = settings.mealReminderTime.minutesOfDay()
    // --- Fas 4 (skafferinotis lane) ---
    this[PreferencesSettingsStore.Keys.PANTRY_EXPIRY_NOTIFICATIONS] = settings.pantryExpiryNotifications
    this[PreferencesSettingsStore.Keys.PANTRY_EXPIRY_OFFER_DISMISSED] = settings.pantryExpiryOfferDismissed
    // --- Fas 5 (kok lane) ---
    this[PreferencesSettingsStore.Keys.HANDSFREE_ENABLED] = settings.handsfree.enabled
    this[PreferencesSettingsStore.Keys.HANDSFREE_VOICE] = settings.handsfree.voice
    this[PreferencesSettingsStore.Keys.HANDSFREE_READ_STEPS] = settings.handsfree.readSteps
}
