package casa.moller.tandem.core.data.sync

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.sync.OfflineStorage
import kotlinx.coroutines.flow.Flow

/**
 * Settings → Sync and storage (plan 6.2 "Synk": "ladda ner alla recept", storage used, "Rensa
 * bildcache"). Implemented in core:sync next to the database and the image cache.
 *
 * "Download all recipes" itself is a setting (`SettingsRepository.setDownloadAllRecipes`): while
 * it is on, every sync run also fetches the details and images of recipes that are not stored
 * yet; [observeStorage] shows how far it got.
 *
 * - **Threading:** main-safe; sizes are measured off the main thread.
 * - **Demo mode / no account:** counts of the demo data (or zero); nothing to download.
 */
interface OfflineStorageRepository {

    /** Counts and sizes of the active account, re-measured while collected (every few seconds and on changes). */
    fun observeStorage(): Flow<OfflineStorage>

    /**
     * Empties the image cache (memory and disk). Images of recipes kept offline are fetched again
     * by the next sync. Fails with `TandemError.Unknown` when the cache could not be cleared.
     */
    suspend fun clearImageCache(): Outcome<Unit>
}
