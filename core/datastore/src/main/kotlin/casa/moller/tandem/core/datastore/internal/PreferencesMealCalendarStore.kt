package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import casa.moller.tandem.core.datastore.LocalCalendarRecord
import casa.moller.tandem.core.datastore.MealCalendarStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** [MealCalendarStore] on a Preferences DataStore. A corrupt file is replaced by an empty one (off). */
internal class PreferencesMealCalendarStore(private val dataStore: DataStore<Preferences>) : MealCalendarStore {

    override val state: Flow<LocalCalendarRecord> = dataStore.data
        .map { preferences ->
            LocalCalendarRecord(
                enabled = preferences[Keys.ENABLED] ?: false,
                lastWrittenMillis = preferences[Keys.LAST_WRITTEN],
                events = preferences[Keys.EVENTS] ?: 0,
                problem = preferences[Keys.PROBLEM],
            )
        }
        .resubscribeOnFailure(fallback = LocalCalendarRecord())
        .distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) = write { preferences ->
        preferences[Keys.ENABLED] = enabled
        if (!enabled) {
            preferences.remove(Keys.LAST_WRITTEN)
            preferences.remove(Keys.EVENTS)
            preferences.remove(Keys.PROBLEM)
        }
    }

    override suspend fun recordWrite(atMillis: Long, events: Int, problem: String?) = write { preferences ->
        if (problem == null) {
            preferences[Keys.LAST_WRITTEN] = atMillis
            preferences[Keys.EVENTS] = events
            preferences.remove(Keys.PROBLEM)
        } else {
            preferences[Keys.PROBLEM] = problem
        }
    }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: IOException) {
            // A setting that cannot be stored is dropped; the calendar is written again later.
        }
    }

    internal object Keys {
        val ENABLED = booleanPreferencesKey("local_calendar")
        val LAST_WRITTEN = longPreferencesKey("last_written_millis")
        val EVENTS = intPreferencesKey("events")
        val PROBLEM = stringPreferencesKey("problem")
    }

    companion object {
        fun create(produceFile: () -> File, scope: CoroutineScope): PreferencesMealCalendarStore = PreferencesMealCalendarStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }
}
