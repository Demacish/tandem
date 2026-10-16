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
            "UNION SELECT account_id FROM outbox UNION SELECT id FROM server_account",
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
        deleteAccount(accountId)
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

    @Query("DELETE FROM server_account WHERE id = :accountId")
    abstract suspend fun deleteAccount(accountId: String)
}
