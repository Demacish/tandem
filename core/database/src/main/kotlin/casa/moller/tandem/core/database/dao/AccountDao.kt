package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.database.query.RecipeSql
import kotlinx.coroutines.flow.Flow

/** The account mirror and removal of everything an account stored (logout, account removal). */
@Dao
abstract class AccountDao {
    @Upsert
    abstract suspend fun upsert(account: ServerAccountEntity)

    @Query("SELECT * FROM server_account WHERE id = :accountId")
    abstract suspend fun get(accountId: String): ServerAccountEntity?

    @Query("SELECT * FROM server_account ORDER BY id")
    abstract fun observeAll(): Flow<List<ServerAccountEntity>>

    // --- Phase 2 (böcker lane, guest mode #42): the user's group in the space. ---

    /** `guest`, `user` or `admin` (Tandoor's group), null while unknown. */
    @Query("SELECT group_level FROM server_account WHERE id = :accountId")
    abstract fun observeGroupLevel(accountId: String): Flow<String?>

    /** Stores the group read from `/api/user-space/all_personal/`; `AccountMirror` keeps it. */
    @Query("UPDATE server_account SET group_level = :groupLevel WHERE id = :accountId")
    abstract suspend fun setGroupLevel(accountId: String, groupLevel: String?)

    // --- Phase 2 (konto lane): the household on the account card and in the account switcher. ---

    /** The mirror row of [accountId] as it changes (household, group, version), null when none. */
    @Query("SELECT * FROM server_account WHERE id = :accountId")
    abstract fun observe(accountId: String): Flow<ServerAccountEntity?>

    /**
     * Stores what `/api/user-space/all_personal/` said about the account's space in one write:
     * the group and the household (both null when the user has none); `AccountMirror` keeps them.
     */
    @Query(
        "UPDATE server_account SET group_level = :groupLevel, household_id = :householdId, household_name = :householdName " +
            "WHERE id = :accountId",
    )
    abstract suspend fun setMembership(accountId: String, groupLevel: String?, householdId: Long?, householdName: String?)

    /**
     * Deletes every row of [accountId] in every table (the account mirror included), in one
     * transaction. Other accounts are untouched. Use `TandemDatabase.clearAllTables()` to wipe
     * the whole file instead.
     */
    @Transaction
    open suspend fun deleteAccountData(accountId: String) {
        // FTS rows are found through recipe_overview.local_id, so they go first.
        deleteFts(accountId)
        deleteFrom(accountId)
    }

    @Query(RecipeSql.FTS_DELETE_ACCOUNT)
    abstract suspend fun deleteFts(accountId: String)

    /**
     * Every account id that has a row in any account-scoped table (the mirror included). Logout
     * clean-up compares it with the stored accounts, so rows a late response wrote after the
     * account was removed are found too, although its `server_account` row is gone. Keep the
     * table list the same as [deleteFrom] (AccountIsolationTest checks both against the schema).
     */
    @Query(
        "SELECT account_id FROM recipe_overview UNION SELECT account_id FROM recipe_detail " +
            "UNION SELECT account_id FROM step UNION SELECT account_id FROM ingredient " +
            "UNION SELECT account_id FROM recipe_keyword UNION SELECT account_id FROM keyword " +
            "UNION SELECT account_id FROM food UNION SELECT account_id FROM unit " +
            "UNION SELECT account_id FROM meal_type UNION SELECT account_id FROM meal_plan " +
            "UNION SELECT account_id FROM user_preference UNION SELECT account_id FROM home_section " +
            "UNION SELECT account_id FROM home_section_recipe UNION SELECT account_id FROM sync_state " +
            "UNION SELECT account_id FROM outbox UNION SELECT account_id FROM cook_log " +
            "UNION SELECT account_id FROM shopping_entry UNION SELECT account_id FROM shopping_list_recipe " +
            "UNION SELECT account_id FROM shopping_list UNION SELECT account_id FROM shopping_history " +
            "UNION SELECT account_id FROM supermarket UNION SELECT account_id FROM supermarket_category " +
            "UNION SELECT account_id FROM supermarket_category_relation UNION SELECT account_id FROM recipe_book " +
            "UNION SELECT account_id FROM recipe_book_entry UNION SELECT account_id FROM id_mapping " +
            "UNION SELECT account_id FROM inventory_location UNION SELECT account_id FROM inventory_entry " +
            "UNION SELECT account_id FROM food_alias UNION SELECT account_id FROM barcode_product " +
            "UNION SELECT account_id FROM make_now UNION SELECT account_id FROM staple " +
            "UNION SELECT account_id FROM drink_draft " +
            "UNION SELECT id FROM server_account",
    )
    abstract suspend fun getStoredAccountIds(): List<String>

    private suspend fun deleteFrom(accountId: String) {
        deleteRecipeOverviews(accountId)
        deleteRecipeDetails(accountId)
        deleteSteps(accountId)
        deleteIngredients(accountId)
        deleteRecipeKeywords(accountId)
        deleteKeywords(accountId)
        deleteFoods(accountId)
        deleteUnits(accountId)
        deleteMealTypes(accountId)
        deleteMealPlans(accountId)
        deleteUserPreferences(accountId)
        deleteHomeSections(accountId)
        deleteHomeSectionRecipes(accountId)
        deleteSyncStates(accountId)
        deleteOutbox(accountId)
        deleteCookLog(accountId)
        // Phase 2 (schema 4)
        deletePhase2Tables(accountId)
        // Fas S (schema 5)
        deletePhaseSTables(accountId)
        deleteAccount(accountId)
    }

    /** Fas S (schema 5): every new account-scoped table. */
    private suspend fun deletePhaseSTables(accountId: String) {
        deleteInventoryLocations(accountId)
        deleteInventoryEntries(accountId)
        deleteFoodAliases(accountId)
        deleteBarcodeProducts(accountId)
        deleteMakeNow(accountId)
        deleteStaples(accountId)
        deleteDrinkDrafts(accountId)
    }

    @Query("DELETE FROM inventory_location WHERE account_id = :accountId")
    abstract suspend fun deleteInventoryLocations(accountId: String)

    @Query("DELETE FROM inventory_entry WHERE account_id = :accountId")
    abstract suspend fun deleteInventoryEntries(accountId: String)

    @Query("DELETE FROM food_alias WHERE account_id = :accountId")
    abstract suspend fun deleteFoodAliases(accountId: String)

    @Query("DELETE FROM barcode_product WHERE account_id = :accountId")
    abstract suspend fun deleteBarcodeProducts(accountId: String)

    @Query("DELETE FROM make_now WHERE account_id = :accountId")
    abstract suspend fun deleteMakeNow(accountId: String)

    @Query("DELETE FROM staple WHERE account_id = :accountId")
    abstract suspend fun deleteStaples(accountId: String)

    @Query("DELETE FROM drink_draft WHERE account_id = :accountId")
    abstract suspend fun deleteDrinkDrafts(accountId: String)

    /** Phase 2 (schema 4): every new account-scoped table. */
    private suspend fun deletePhase2Tables(accountId: String) {
        deleteShoppingEntries(accountId)
        deleteShoppingListRecipes(accountId)
        deleteShoppingLists(accountId)
        deleteShoppingHistory(accountId)
        deleteSupermarkets(accountId)
        deleteSupermarketCategories(accountId)
        deleteSupermarketCategoryRelations(accountId)
        deleteRecipeBooks(accountId)
        deleteRecipeBookEntries(accountId)
        deleteIdMappings(accountId)
    }

    // ---------------------------------------------------------------- building blocks (Room)

    @Query("DELETE FROM recipe_overview WHERE account_id = :accountId")
    abstract suspend fun deleteRecipeOverviews(accountId: String)

    @Query("DELETE FROM recipe_detail WHERE account_id = :accountId")
    abstract suspend fun deleteRecipeDetails(accountId: String)

    @Query("DELETE FROM step WHERE account_id = :accountId")
    abstract suspend fun deleteSteps(accountId: String)

    @Query("DELETE FROM ingredient WHERE account_id = :accountId")
    abstract suspend fun deleteIngredients(accountId: String)

    @Query("DELETE FROM recipe_keyword WHERE account_id = :accountId")
    abstract suspend fun deleteRecipeKeywords(accountId: String)

    @Query("DELETE FROM keyword WHERE account_id = :accountId")
    abstract suspend fun deleteKeywords(accountId: String)

    @Query("DELETE FROM food WHERE account_id = :accountId")
    abstract suspend fun deleteFoods(accountId: String)

    @Query("DELETE FROM unit WHERE account_id = :accountId")
    abstract suspend fun deleteUnits(accountId: String)

    @Query("DELETE FROM meal_type WHERE account_id = :accountId")
    abstract suspend fun deleteMealTypes(accountId: String)

    @Query("DELETE FROM meal_plan WHERE account_id = :accountId")
    abstract suspend fun deleteMealPlans(accountId: String)

    @Query("DELETE FROM user_preference WHERE account_id = :accountId")
    abstract suspend fun deleteUserPreferences(accountId: String)

    @Query("DELETE FROM home_section WHERE account_id = :accountId")
    abstract suspend fun deleteHomeSections(accountId: String)

    @Query("DELETE FROM home_section_recipe WHERE account_id = :accountId")
    abstract suspend fun deleteHomeSectionRecipes(accountId: String)

    @Query("DELETE FROM sync_state WHERE account_id = :accountId")
    abstract suspend fun deleteSyncStates(accountId: String)

    @Query("DELETE FROM outbox WHERE account_id = :accountId")
    abstract suspend fun deleteOutbox(accountId: String)

    @Query("DELETE FROM cook_log WHERE account_id = :accountId")
    abstract suspend fun deleteCookLog(accountId: String)

    @Query("DELETE FROM server_account WHERE id = :accountId")
    abstract suspend fun deleteAccount(accountId: String)

    @Query("DELETE FROM shopping_entry WHERE account_id = :accountId")
    abstract suspend fun deleteShoppingEntries(accountId: String)

    @Query("DELETE FROM shopping_list_recipe WHERE account_id = :accountId")
    abstract suspend fun deleteShoppingListRecipes(accountId: String)

    @Query("DELETE FROM shopping_list WHERE account_id = :accountId")
    abstract suspend fun deleteShoppingLists(accountId: String)

    @Query("DELETE FROM shopping_history WHERE account_id = :accountId")
    abstract suspend fun deleteShoppingHistory(accountId: String)

    @Query("DELETE FROM supermarket WHERE account_id = :accountId")
    abstract suspend fun deleteSupermarkets(accountId: String)

    @Query("DELETE FROM supermarket_category WHERE account_id = :accountId")
    abstract suspend fun deleteSupermarketCategories(accountId: String)

    @Query("DELETE FROM supermarket_category_relation WHERE account_id = :accountId")
    abstract suspend fun deleteSupermarketCategoryRelations(accountId: String)

    @Query("DELETE FROM recipe_book WHERE account_id = :accountId")
    abstract suspend fun deleteRecipeBooks(accountId: String)

    @Query("DELETE FROM recipe_book_entry WHERE account_id = :accountId")
    abstract suspend fun deleteRecipeBookEntries(accountId: String)

    @Query("DELETE FROM id_mapping WHERE account_id = :accountId")
    abstract suspend fun deleteIdMappings(accountId: String)
}
