package casa.moller.tandem.core.data.settings

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.timer.TimerMode
import kotlinx.coroutines.flow.Flow

/**
 * The Phase 1b settings (plan 6.2 "Inställningar": Utseende, Beteende, Synk) next to
 * [AppearanceRepository] (the theme, since Phase 0). Implemented in core:data on the
 * non-secret settings file of core:datastore; features read and write them only through here.
 *
 * - **Threading:** main-safe.
 * - **Reading:** [settings] emits the stored values ([AppSettings.Default] until changed) and
 *   never throws; values outside the allowed range are coerced on read.
 * - **Writing:** every setter fails with `DataProblem.SETTINGS_NOT_SAVED` when the settings file
 *   cannot be written; the old value stays.
 */
interface SettingsRepository {

    /** All app settings, re-emitted on every change. */
    val settings: Flow<AppSettings>

    /** "Dynamisk färg" (Material You). */
    suspend fun setDynamicColor(enabled: Boolean): Outcome<Unit>

    /** Cook mode's text size; coerced to the nearest [casa.moller.tandem.core.model.settings.CookTextScale.STEPS]. */
    suspend fun setCookTextScale(scale: Float): Outcome<Unit>

    /** Cook mode always dark. */
    suspend fun setCookModeAlwaysDark(enabled: Boolean): Outcome<Unit>

    /** In-app timers or the clock app. */
    suspend fun setTimerMode(mode: TimerMode): Outcome<Unit>

    /** The periodic sync's interval; core:sync re-schedules its periodic work when it changes. */
    suspend fun setSyncInterval(interval: SyncInterval): Outcome<Unit>

    /** "Ladda ner alla recept". */
    suspend fun setDownloadAllRecipes(enabled: Boolean): Outcome<Unit>

    /** The system's notification dialog was shown (plan 8.7: asked once, in context). */
    suspend fun setNotificationPermissionAsked(asked: Boolean): Outcome<Unit>

    /** Play build: the "allow exact alarms" prompt was dismissed. */
    suspend fun setExactAlarmPromptDismissed(dismissed: Boolean): Outcome<Unit>

    /** Fas S (drycker lane): "Sök på Systembolaget" on drinks, null = follow the UI language. */
    suspend fun setSystembolagetLink(shown: Boolean?): Outcome<Unit>
}
