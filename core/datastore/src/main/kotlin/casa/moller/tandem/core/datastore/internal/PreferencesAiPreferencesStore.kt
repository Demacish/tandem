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
import casa.moller.tandem.core.datastore.AiPreferences
import casa.moller.tandem.core.datastore.AiPreferencesStore
import casa.moller.tandem.core.model.ai.AiProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [AiPreferencesStore] on a Preferences DataStore. Providers are stored by name (an unknown name,
 * from a newer version, reads as none). A corrupt file is replaced by the defaults without a
 * recovery screen: nothing in it is secret or hard to set again.
 */
internal class PreferencesAiPreferencesStore(private val dataStore: DataStore<Preferences>) : AiPreferencesStore {

    override val preferences: Flow<AiPreferences> = dataStore.data
        .map { it.toAiPreferences() }
        .resubscribeOnFailure(fallback = AiPreferences())
        .distinctUntilChanged()

    override suspend fun update(transform: (AiPreferences) -> AiPreferences) {
        dataStore.edit { preferences -> preferences.write(transform(preferences.toAiPreferences())) }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toAiPreferences(): AiPreferences = AiPreferences(
        provider = this[Keys.PROVIDER]?.let { name -> AiProvider.entries.firstOrNull { it.name == name } },
        models = AiProvider.entries.mapNotNull { provider -> this[Keys.model(provider)]?.let { provider to it } }.toMap(),
        baseUrls = AiProvider.entries.mapNotNull { provider -> this[Keys.baseUrl(provider)]?.let { provider to it } }.toMap(),
        warningAcknowledged = this[Keys.WARNING_ACKNOWLEDGED] ?: false,
    )

    private fun MutablePreferences.write(value: AiPreferences) {
        value.provider?.let { this[Keys.PROVIDER] = it.name } ?: remove(Keys.PROVIDER)
        AiProvider.entries.forEach { provider ->
            setOrRemove(Keys.model(provider), value.models[provider])
            setOrRemove(Keys.baseUrl(provider), value.baseUrls[provider])
        }
        this[Keys.WARNING_ACKNOWLEDGED] = value.warningAcknowledged
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) remove(key) else this[key] = value
    }

    internal object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val WARNING_ACKNOWLEDGED = booleanPreferencesKey("warning_acknowledged")

        fun model(provider: AiProvider) = stringPreferencesKey("model_${provider.name.lowercase()}")

        fun baseUrl(provider: AiProvider) = stringPreferencesKey("base_url_${provider.name.lowercase()}")
    }

    companion object {
        fun create(produceFile: () -> File, scope: CoroutineScope): PreferencesAiPreferencesStore = PreferencesAiPreferencesStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }
}
