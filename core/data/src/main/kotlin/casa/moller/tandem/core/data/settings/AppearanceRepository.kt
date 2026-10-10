package casa.moller.tandem.core.data.settings

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Appearance settings (plan 6.2 "Inställningar → Utseende"). Phase 0 only has the theme; dynamic
 * colour and the cooking-mode text size come later.
 */
interface AppearanceRepository {

    /** The chosen theme; [ThemeMode.SYSTEM] until the user picks one. */
    val themeMode: Flow<ThemeMode>

    /** Stores [mode]. Fails with `DataProblem.SETTINGS_NOT_SAVED` when the settings file cannot be written. */
    suspend fun setThemeMode(mode: ThemeMode): Outcome<Unit>
}
