package casa.moller.tandem.core.data.preferences

import casa.moller.tandem.core.model.preferences.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * The user's Tandoor preferences (plan 3.2: the app follows them instead of keeping its own
 * copies). Stored in Room by the sync (small tables, refreshed on start and every 6 h).
 *
 * - **Threading:** main-safe.
 * - **Offline:** emits the stored values; [UserPreferences.Default] until the first sync stored
 *   any (and always in demo mode unless the demo data sets them).
 * - **Errors:** never throws for server or network problems.
 */
interface PreferencesRepository {

    /** The active account's preferences, re-emitted when the sync stores new values. */
    fun observeUserPreferences(): Flow<UserPreferences>
}
