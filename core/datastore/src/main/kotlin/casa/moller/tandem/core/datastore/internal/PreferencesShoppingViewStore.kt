package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import casa.moller.tandem.core.datastore.ShoppingViewStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [ShoppingViewStore] on a Preferences DataStore: one string set with the accounts that show the
 * grid. A corrupt file is replaced by an empty one (the list falls back to rows).
 */
internal class PreferencesShoppingViewStore(private val dataStore: DataStore<Preferences>) : ShoppingViewStore {

    override fun observeGrid(accountId: String): Flow<Boolean> = dataStore.data
        .map { accountId in it[Keys.GRID_ACCOUNTS].orEmpty() }
        .resubscribeOnFailure(fallback = false)
        .distinctUntilChanged()

    override suspend fun setGrid(accountId: String, enabled: Boolean) = write { preferences ->
        val accounts = preferences[Keys.GRID_ACCOUNTS].orEmpty()
        preferences[Keys.GRID_ACCOUNTS] = if (enabled) accounts + accountId else accounts - accountId
    }

    override suspend fun retainOnly(accountIds: Set<String>) = write { preferences ->
        val accounts = preferences[Keys.GRID_ACCOUNTS].orEmpty()
        if (!accountIds.containsAll(accounts)) preferences[Keys.GRID_ACCOUNTS] = accounts intersect accountIds
    }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: IOException) {
            // A view setting that cannot be stored is dropped; the list still works.
        }
    }

    internal object Keys {
        val GRID_ACCOUNTS = stringSetPreferencesKey("grid_accounts")
    }

    companion object {
        fun create(produceFile: () -> File, scope: CoroutineScope): PreferencesShoppingViewStore = PreferencesShoppingViewStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }
}
