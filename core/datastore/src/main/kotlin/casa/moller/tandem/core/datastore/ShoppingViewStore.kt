package casa.moller.tandem.core.datastore

import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (lane handla, plan §8.2 "Rutnätsvy med stora rutor"): how each account on this device shows
 * the shopping list, in its own small Preferences DataStore file ([DatastoreFiles.SHOPPING_VIEW]).
 * Only account ids with the grid turned on are stored (nothing personal); a failed read is "no grid",
 * a failed write is dropped.
 */
interface ShoppingViewStore {
    /** True when [accountId] shows the list as a grid. */
    fun observeGrid(accountId: String): Flow<Boolean>

    suspend fun setGrid(accountId: String, enabled: Boolean)

    /** Forgets every account not in [accountIds] (accounts that left this device). */
    suspend fun retainOnly(accountIds: Set<String>)
}
