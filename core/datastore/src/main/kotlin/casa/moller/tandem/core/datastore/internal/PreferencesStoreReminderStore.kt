package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import casa.moller.tandem.core.datastore.StoreFenceRegistration
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.datastore.StoreReminderStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * [StoreReminderStore] on a Preferences DataStore: one JSON string per account (`account.<id>`) and
 * one for the registration. A corrupt file or an unreadable value is "off" (the user sets the
 * places again); nothing is ever logged.
 */
internal class PreferencesStoreReminderStore(private val dataStore: DataStore<Preferences>) : StoreReminderStore {

    override fun observe(accountId: String): Flow<StoreReminderRecord> = dataStore.data
        .map { preferences -> decode(preferences[accountKey(accountId)]) }
        .resubscribeOnFailure(fallback = StoreReminderRecord())
        .distinctUntilChanged()

    override suspend fun update(accountId: String, transform: (StoreReminderRecord) -> StoreReminderRecord) = write { preferences ->
        val key = accountKey(accountId)
        val next = transform(decode(preferences[key]))
        if (next == StoreReminderRecord()) {
            preferences.remove(key)
        } else {
            preferences[key] = json.encodeToString(StoreReminderRecord.serializer(), next)
        }
    }

    override val registration: Flow<StoreFenceRegistration?> = dataStore.data
        .map { preferences -> preferences[REGISTRATION]?.let(::decodeRegistration) }
        .resubscribeOnFailure(fallback = null)
        .distinctUntilChanged()

    override suspend fun setRegistration(registration: StoreFenceRegistration?) = write { preferences ->
        if (registration == null) {
            preferences.remove(REGISTRATION)
        } else {
            preferences[REGISTRATION] = json.encodeToString(StoreFenceRegistration.serializer(), registration)
        }
    }

    override suspend fun retainOnly(accountIds: Set<String>) = write { preferences ->
        preferences.asMap().keys
            .filter { it.name.startsWith(ACCOUNT_PREFIX) && it.name.removePrefix(ACCOUNT_PREFIX) !in accountIds }
            .forEach { key -> preferences.remove(key) }
    }

    override suspend fun accounts(): Set<String> = try {
        dataStore.data.first().asMap().keys
            .map { it.name }
            .filter { it.startsWith(ACCOUNT_PREFIX) }
            .mapTo(HashSet()) { it.removePrefix(ACCOUNT_PREFIX) }
    } catch (e: IOException) {
        emptySet()
    }

    private fun decode(value: String?): StoreReminderRecord {
        if (value == null) return StoreReminderRecord()
        return try {
            json.decodeFromString(StoreReminderRecord.serializer(), value)
        } catch (e: SerializationException) {
            StoreReminderRecord()
        } catch (e: IllegalArgumentException) {
            StoreReminderRecord()
        }
    }

    private fun decodeRegistration(value: String): StoreFenceRegistration? = try {
        json.decodeFromString(StoreFenceRegistration.serializer(), value)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: IOException) {
            // A reminder setting that cannot be stored is dropped; the page shows what is stored.
        }
    }

    companion object {
        private const val ACCOUNT_PREFIX = "account."
        private val REGISTRATION = stringPreferencesKey("registration")
        private val json = Json { ignoreUnknownKeys = true }

        private fun accountKey(accountId: String): Preferences.Key<String> = stringPreferencesKey(ACCOUNT_PREFIX + accountId)

        fun create(produceFile: () -> File, scope: CoroutineScope): PreferencesStoreReminderStore = PreferencesStoreReminderStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }
}
