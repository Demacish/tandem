package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import casa.moller.tandem.core.datastore.DiagnosticsStore
import casa.moller.tandem.core.datastore.DiagnosticsTimings
import casa.moller.tandem.core.model.diagnostics.ColdStartTiming
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [DiagnosticsStore] on a Preferences DataStore: durations and times as epoch milliseconds. A
 * corrupt file is replaced by an empty one without a recovery screen (it only holds measurements).
 */
internal class PreferencesDiagnosticsStore(private val dataStore: DataStore<Preferences>) : DiagnosticsStore {

    override val timings: Flow<DiagnosticsTimings> = dataStore.data
        .map { it.toTimings() }
        .resubscribeOnFailure(fallback = DiagnosticsTimings.None)
        .distinctUntilChanged()

    override suspend fun recordColdStart(timing: ColdStartTiming) = write { preferences ->
        preferences[Keys.COLD_START_MILLIS] = timing.duration.inWholeMilliseconds.coerceAtLeast(0)
        preferences[Keys.COLD_START_AT] = timing.measuredAt.toEpochMilliseconds()
    }

    override suspend fun recordFullSync(timing: FullSyncTiming) = write { preferences ->
        preferences[Keys.FULL_SYNC_MILLIS] = timing.duration.inWholeMilliseconds.coerceAtLeast(0)
        preferences[Keys.FULL_SYNC_RECIPES] = timing.recipeCount.coerceAtLeast(0)
        preferences[Keys.FULL_SYNC_AT] = timing.finishedAt.toEpochMilliseconds()
    }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: IOException) {
            // A measurement that cannot be stored is dropped; it never fails the app or the sync.
        }
    }

    private fun Preferences.toTimings(): DiagnosticsTimings {
        val coldMillis = this[Keys.COLD_START_MILLIS]
        val coldAt = this[Keys.COLD_START_AT]
        val syncMillis = this[Keys.FULL_SYNC_MILLIS]
        val syncAt = this[Keys.FULL_SYNC_AT]
        return DiagnosticsTimings(
            lastColdStart = if (coldMillis != null && coldAt != null && coldMillis >= 0) {
                ColdStartTiming(coldMillis.milliseconds, Instant.fromEpochMilliseconds(coldAt))
            } else {
                null
            },
            lastFullSync = if (syncMillis != null && syncAt != null && syncMillis >= 0) {
                FullSyncTiming(
                    duration = syncMillis.milliseconds,
                    recipeCount = (this[Keys.FULL_SYNC_RECIPES] ?: 0).coerceAtLeast(0),
                    finishedAt = Instant.fromEpochMilliseconds(syncAt),
                )
            } else {
                null
            },
        )
    }

    internal object Keys {
        val COLD_START_MILLIS = longPreferencesKey("cold_start_millis")
        val COLD_START_AT = longPreferencesKey("cold_start_at")
        val FULL_SYNC_MILLIS = longPreferencesKey("full_sync_millis")
        val FULL_SYNC_RECIPES = intPreferencesKey("full_sync_recipes")
        val FULL_SYNC_AT = longPreferencesKey("full_sync_at")
    }

    companion object {
        fun create(produceFile: () -> File, scope: CoroutineScope): PreferencesDiagnosticsStore = PreferencesDiagnosticsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }
}
