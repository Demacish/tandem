package casa.moller.tandem.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.IngredientEntity
import casa.moller.tandem.core.database.entity.OutboxEntity
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

    // ---------------------------------------------------------------- inköp-data lane (Phase 2)

    /**
     * Runs [block] in one write transaction. The shopping repository and the sync call other DAOs
     * inside it (`OutboxDao.enqueue`, `IdRewriteDao.nextLocalId`): Room binds the transaction's
     * connection to the coroutine, so a local change and its outbox row are stored together, and a
     * server response is compared with the outbox and written in one step.
     */
    @Transaction
    open suspend fun inTransaction(block: suspend () -> Unit) {
        block()
    }

    @Query("SELECT * FROM shopping_entry WHERE account_id = :accountId AND local_id IN (:localIds)")
    abstract suspend fun getEntriesByLocalIds(accountId: String, localIds: List<Long>): List<ShoppingEntryEntity>

    @Query("SELECT * FROM shopping_entry WHERE account_id = :accountId AND server_id IN (:serverIds)")
    abstract suspend fun getEntriesByServerIds(accountId: String, serverIds: List<Long>): List<ShoppingEntryEntity>

    /** The checked entries ("Rensa avbockade"). */
    @Query("SELECT * FROM shopping_entry WHERE account_id = :accountId AND checked = 1 ORDER BY local_id")
    abstract suspend fun getCheckedEntries(accountId: String): List<ShoppingEntryEntity>

    /** The entries of one recipe or meal on the list. */
    @Query("SELECT * FROM shopping_entry WHERE account_id = :accountId AND list_recipe_id = :listRecipeId ORDER BY local_id")
    abstract suspend fun getEntriesOfListRecipe(accountId: String, listRecipeId: Long): List<ShoppingEntryEntity>

    @Query("SELECT * FROM shopping_list_recipe WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun getListRecipe(accountId: String, localId: Long): ShoppingListRecipeEntity?

    @Query("SELECT * FROM shopping_list_recipe WHERE account_id = :accountId ORDER BY local_id")
    abstract suspend fun getListRecipes(accountId: String): List<ShoppingListRecipeEntity>

    /** The shopping rows of the outbox (entries and list recipes), any state, in send order. */
    @Query(
        "SELECT * FROM outbox WHERE account_id = :accountId AND entity IN ('shopping_entry', 'shopping_list_recipe') ORDER BY id",
    )
    abstract fun observeOutbox(accountId: String): Flow<List<OutboxEntity>>

    @Query(
        "SELECT * FROM outbox WHERE account_id = :accountId AND entity IN ('shopping_entry', 'shopping_list_recipe') ORDER BY id",
    )
    abstract suspend fun getOutbox(accountId: String): List<OutboxEntity>

    /** Queued shopping changes ("3 ändringar väntar"): entries, list recipes, foods and aisle order. */
    @Query(
        "SELECT COUNT(*) FROM outbox WHERE account_id = :accountId AND entity IN " +
            "('shopping_entry', 'shopping_list_recipe', 'food', 'supermarket_category_relation')",
    )
    abstract fun observePendingCount(accountId: String): Flow<Int>

    /**
     * 0.3.0 review (fix-data): entries created on this device (no server id) whose CREATE is no
     * longer in the outbox can never be sent (an older version left them behind when an unsent meal
     * was deleted after "Handla för veckan"); a full read removes them.
     */
    @Query(
        "DELETE FROM shopping_entry WHERE account_id = :accountId AND server_id IS NULL AND NOT EXISTS " +
            "(SELECT 1 FROM outbox o WHERE o.account_id = shopping_entry.account_id AND o.entity = 'shopping_entry' " +
            "AND o.op = 'CREATE' AND o.local_id = shopping_entry.local_id)",
    )
    abstract suspend fun deleteOrphanEntries(accountId: String)

    /** The same for list recipes created on this device. */
    @Query(
        "DELETE FROM shopping_list_recipe WHERE account_id = :accountId AND server_id IS NULL AND NOT EXISTS " +
            "(SELECT 1 FROM outbox o WHERE o.account_id = shopping_list_recipe.account_id AND o.entity = 'shopping_list_recipe' " +
            "AND o.op = 'CREATE' AND o.local_id = shopping_list_recipe.local_id)",
    )
    abstract suspend fun deleteOrphanListRecipes(accountId: String)

    /** Every entry's aisle follows its food's aisle (`food.supermarket_category` is per space). */
    @Query("UPDATE shopping_entry SET category_id = :categoryId WHERE account_id = :accountId AND food_id = :foodId")
    abstract suspend fun setCategoryOfFood(accountId: String, foodId: Long, categoryId: Long?)

    /** Food names of the archived entries, newest first (suggestions, "köp igen"). */
    @Query("SELECT food_name FROM shopping_history WHERE account_id = :accountId ORDER BY completed_at DESC")
    abstract fun observeHistoryFoodNames(accountId: String): Flow<List<String>>

    @Query("SELECT entry_id FROM shopping_history WHERE account_id = :accountId")
    abstract suspend fun getHistoryIds(accountId: String): List<Long>

    /** A stored recipe's ingredients ("Lägg på listan", #12), in the recipe's order. */
    @Query("SELECT * FROM ingredient WHERE account_id = :accountId AND recipe_id = :recipeId ORDER BY step_position, position")
    abstract suspend fun getRecipeIngredients(accountId: String, recipeId: Long): List<IngredientEntity>

    /** The servings a stored recipe is written for (null without a stored detail). */
    @Query("SELECT servings FROM recipe_detail WHERE account_id = :accountId AND recipe_id = :recipeId AND raw_json IS NOT NULL")
    abstract suspend fun getRecipeServings(accountId: String, recipeId: Long): Int?

    @Query("SELECT name FROM recipe_overview WHERE account_id = :accountId AND server_id = :recipeId")
    abstract suspend fun getRecipeName(accountId: String, recipeId: Long): String?

    /** The server's `shopping_min_autosync_interval` (seconds), the floor of list polling (plan 3.5). */
    @Query("UPDATE server_account SET shopping_min_autosync_seconds = :seconds WHERE id = :accountId")
    abstract suspend fun setMinAutosyncSeconds(accountId: String, seconds: Int?)

    @Query("SELECT shopping_min_autosync_seconds FROM server_account WHERE id = :accountId")
    abstract suspend fun getMinAutosyncSeconds(accountId: String): Int?

    /** 0.3.0 komplettering: the server's polling floor for the settings page ("Synk och lagring"). */
    @Query("SELECT shopping_min_autosync_seconds FROM server_account WHERE id = :accountId")
    abstract fun observeMinAutosyncSeconds(accountId: String): Flow<Int?>

    /**
     * 0.3.0 komplettering ("Delad med: …", plan 8.6): who created the stored rows. Tandoor lists
     * another user's rows only when that user is in the same household (core:domain `HouseholdMembers`).
     */
    @Query(
        "SELECT created_by AS id, MAX(created_by_name) AS name FROM shopping_entry " +
            "WHERE account_id = :accountId AND created_by IS NOT NULL GROUP BY created_by ORDER BY created_by",
    )
    abstract fun observeCreators(accountId: String): Flow<List<CreatorRow>>

    // ---------------------------------------------------------------- building blocks (Room)

    @Upsert
    abstract suspend fun upsertHistory(rows: List<ShoppingHistoryEntity>)

    @Upsert
    abstract suspend fun insertShoppingLists(rows: List<ShoppingListEntity>)

    @Query("DELETE FROM shopping_list WHERE account_id = :accountId")
    abstract suspend fun deleteShoppingLists(accountId: String)
}

/** 0.3.0 komplettering: a user who created stored rows, with the display name the rows carry (if any). */
data class CreatorRow(@ColumnInfo(name = "id") val id: Long, @ColumnInfo(name = "name") val name: String?)
