package casa.moller.tandem.core.data.assistant

import casa.moller.tandem.core.model.assistant.AssistantSettings
import casa.moller.tandem.core.model.error.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (funktioner lane, plan §8.7): Mer → Assistenten. Implemented in core:data on the non-secret
 * settings file (like `SettingsRepository`); the App Functions in :app and the settings page in
 * feature:shell use it.
 *
 * - **Threading:** main-safe.
 * - **Reading:** [settings] emits the stored values (both off until chosen) and never throws.
 * - **Writing:** the setters fail with `DataProblem.SETTINGS_NOT_SAVED` when the settings file
 *   cannot be written; the old value stays.
 */
interface AssistantSettingsRepository {
    val settings: Flow<AssistantSettings>

    /** "Låt assistenten använda Tandem". */
    suspend fun setEnabled(enabled: Boolean): Outcome<Unit>

    /** "Tillåt assistenten när appen är låst". */
    suspend fun setAllowWhenLocked(allowed: Boolean): Outcome<Unit>
}
