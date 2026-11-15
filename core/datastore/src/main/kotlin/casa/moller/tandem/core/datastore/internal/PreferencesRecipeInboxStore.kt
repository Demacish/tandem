package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import casa.moller.tandem.core.datastore.RecipeInboxRecord
import casa.moller.tandem.core.datastore.RecipeInboxStore
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
 * [RecipeInboxStore] on a Preferences DataStore: one JSON string per account (`account.<id>`). A
 * corrupt file or an unreadable value is "not set up"; nothing is ever logged.
 */
internal class PreferencesRecipeInboxStore(private val dataStore: DataStore<Preferences>) : RecipeInboxStore {

    override fun observe(accountId: String): Flow<RecipeInboxRecord> = dataStore.data
        .map { preferences -> decode(preferences[accountKey(accountId)]) }
        .resubscribeOnFailure(fallback = RecipeInboxRecord())
        .distinctUntilChanged()

    override suspend fun update(accountId: String, transform: (RecipeInboxRecord) -> RecipeInboxRecord) = write { preferences ->
        val key = accountKey(accountId)
        val next = transform(decode(preferences[key]))
        if (next == RecipeInboxRecord()) {
            preferences.remove(key)
        } else {
            preferences[key] = json.encodeToString(RecipeInboxRecord.serializer(), next)
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

    private fun decode(value: String?): RecipeInboxRecord {
        if (value == null) return RecipeInboxRecord()
        return try {
            json.decodeFromString(RecipeInboxRecord.serializer(), value)
        } catch (e: SerializationException) {
            RecipeInboxRecord()
        } catch (e: IllegalArgumentException) {
            RecipeInboxRecord()
        }
    }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: IOException) {
            // An inbox setting that cannot be stored is dropped; the page shows what is stored.
        }
    }

    companion object {
        private const val ACCOUNT_PREFIX = "account."
        private val json = Json { ignoreUnknownKeys = true }

        private fun accountKey(accountId: String): Preferences.Key<String> = stringPreferencesKey(ACCOUNT_PREFIX + accountId)

        fun create(produceFile: () -> File, scope: CoroutineScope): PreferencesRecipeInboxStore = PreferencesRecipeInboxStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }
}
