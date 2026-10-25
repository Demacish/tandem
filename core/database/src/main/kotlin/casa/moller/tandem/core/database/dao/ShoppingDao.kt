package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.database.entity.ShoppingHistoryEntity
import casa.moller.tandem.core.database.entity.ShoppingListEntity
import casa.moller.tandem.core.database.entity.ShoppingListRecipeEntity
import kotlinx.coroutines.flow.Flow

/**
 * The shopping list (Phase 2, schema 4; plan 3.5, 5.7). Skeleton from the grund lane: the shopping
 * lane adds the queries it needs (no schema change; a new table or column needs migration 4 -> 5,
 * coordinated in docs/BYGGE.md). Server rows have `local_id = server_id`; offline rows a negative
 * `local_id` (see [IdRewriteDao]).
 */
@Dao
abstract class ShoppingDao {
    /** Every entry of the account (checked ones too), in the server's order. */
    @Query("SELECT * FROM shopping_entry WHERE account_id = :accountId ORDER BY checked, entry_order, food_name, local_id")
    abstract fun observeEntries(accountId: String): Flow<List<ShoppingEntryEntity>>

    @Query("SELECT * FROM shopping_entry WHERE account_id = :accountId ORDER BY local_id")
    abstract suspend fun getEntries(accountId: String): List<ShoppingEntryEntity>

    @Query("SELECT * FROM shopping_entry WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun getEntry(accountId: String, localId: Long): ShoppingEntryEntity?

    @Upsert
    abstract suspend fun upsertEntries(rows: List<ShoppingEntryEntity>)

    @Query("DELETE FROM shopping_entry WHERE account_id = :accountId AND local_id IN (:localIds)")
    abstract suspend fun deleteEntries(accountId: String, localIds: List<Long>)

    @Query("SELECT * FROM shopping_list_recipe WHERE account_id = :accountId ORDER BY local_id")
    abstract fun observeListRecipes(accountId: String): Flow<List<ShoppingListRecipeEntity>>

    @Upsert
    abstract suspend fun upsertListRecipes(rows: List<ShoppingListRecipeEntity>)

    @Query("DELETE FROM shopping_list_recipe WHERE account_id = :accountId AND local_id IN (:localIds)")
    abstract suspend fun deleteListRecipes(accountId: String, localIds: List<Long>)

    /** The named Tandoor shopping lists ("listval"), by name. */
    @Query("SELECT * FROM shopping_list WHERE account_id = :accountId ORDER BY name, id")
    abstract fun observeShoppingLists(accountId: String): Flow<List<ShoppingListEntity>>

    /** Replaces the account's named lists with the full list from the server (small table, plan 3.5). */
    @Transaction
    open suspend fun replaceShoppingLists(accountId: String, lists: List<ShoppingListEntity>) {
        deleteShoppingLists(accountId)
        if (lists.isNotEmpty()) insertShoppingLists(lists.map { it.copy(accountId = accountId) }.distinctBy { it.id })
    }

    /**
     * Moves aged-out checked entries (plan 3.5 window rule: missing from a full re-read, checked
     * before the window) to `shopping_history`, in one transaction. Entries already archived are
     * replaced (same `entry_id`).
     */
    @Transaction
    open suspend fun archive(accountId: String, history: List<ShoppingHistoryEntity>, localIds: List<Long>) {
        if (history.isNotEmpty()) upsertHistory(history.map { it.copy(accountId = accountId) })
        if (localIds.isNotEmpty()) deleteEntries(accountId, localIds)
    }

    /** The local history, newest first (#29 "brukar köpa", Phase 4). */
    @Query("SELECT * FROM shopping_history WHERE account_id = :accountId ORDER BY completed_at DESC, entry_id DESC")
    abstract fun observeHistory(accountId: String): Flow<List<ShoppingHistoryEntity>>

    // ---------------------------------------------------------------- building blocks (Room)

    @Upsert
    abstract suspend fun upsertHistory(rows: List<ShoppingHistoryEntity>)

    @Upsert
    abstract suspend fun insertShoppingLists(rows: List<ShoppingListEntity>)

    @Query("DELETE FROM shopping_list WHERE account_id = :accountId")
    abstract suspend fun deleteShoppingLists(accountId: String)
}
