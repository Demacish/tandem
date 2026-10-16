package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.UnitEntity
import casa.moller.tandem.core.database.entity.UserPreferenceEntity
import casa.moller.tandem.core.database.relation.FoodNameRow
import kotlinx.coroutines.flow.Flow

/**
 * The small reference tables that are always re-read in full (plan 3.5): foods, units, meal
 * types and the user preferences. Keywords live in [RecipeDao] because they feed the search
 * index (`RecipeDao.replaceKeywords`).
 */
@Dao
abstract class ReferenceDao {
    /** Replaces the account's foods with the full list from the server. */
    @Transaction
    open suspend fun replaceFoods(accountId: String, foods: List<FoodEntity>) {
        deleteFoods(accountId)
        if (foods.isNotEmpty()) insertFoods(foods.map { it.copy(accountId = accountId) }.distinctBy { it.id })
    }

    /** Replaces the account's units with the full list from the server. */
    @Transaction
    open suspend fun replaceUnits(accountId: String, units: List<UnitEntity>) {
        deleteUnits(accountId)
        if (units.isNotEmpty()) insertUnits(units.map { it.copy(accountId = accountId) }.distinctBy { it.id })
    }

    /** Replaces the account's meal types with the full list from the server. */
    @Transaction
    open suspend fun replaceMealTypes(accountId: String, mealTypes: List<MealTypeEntity>) {
        deleteMealTypes(accountId)
        if (mealTypes.isNotEmpty()) insertMealTypes(mealTypes.map { it.copy(accountId = accountId) }.distinctBy { it.id })
    }

    @Query("SELECT * FROM food WHERE account_id = :accountId ORDER BY name")
    abstract suspend fun getFoods(accountId: String): List<FoodEntity>

    /**
     * Every food the recipe list can filter on (plan 6.2 "livsmedel"): the account's food list
     * plus the foods of stored ingredients that the list does not have (the demo database has no
     * food list). Unsorted: the caller sorts with a locale collator. Re-emits when either changes.
     */
    @Query(
        "SELECT id, name, plural_name FROM food WHERE account_id = :accountId " +
            "UNION ALL " +
            "SELECT food_id AS id, MAX(food_name) AS name, MAX(food_plural_name) AS plural_name FROM ingredient " +
            "WHERE account_id = :accountId AND food_id IS NOT NULL AND food_name IS NOT NULL " +
            "AND food_id NOT IN (SELECT id FROM food WHERE account_id = :accountId) " +
            "GROUP BY food_id",
    )
    abstract fun observeFilterFoods(accountId: String): Flow<List<FoodNameRow>>

    @Query("SELECT * FROM unit WHERE account_id = :accountId ORDER BY name")
    abstract suspend fun getUnits(accountId: String): List<UnitEntity>

    /** Meal types in the server's order. */
    @Query("SELECT * FROM meal_type WHERE account_id = :accountId ORDER BY type_order, id")
    abstract fun observeMealTypes(accountId: String): Flow<List<MealTypeEntity>>

    /** The user's Tandoor preferences, null until fetched once. */
    @Query("SELECT * FROM user_preference WHERE account_id = :accountId")
    abstract fun observeUserPreference(accountId: String): Flow<UserPreferenceEntity?>

    @Query("SELECT * FROM user_preference WHERE account_id = :accountId")
    abstract suspend fun getUserPreference(accountId: String): UserPreferenceEntity?

    @Upsert
    abstract suspend fun upsertUserPreference(row: UserPreferenceEntity)

    // ---------------------------------------------------------------- building blocks (Room)

    @Insert
    abstract suspend fun insertFoods(rows: List<FoodEntity>)

    @Query("DELETE FROM food WHERE account_id = :accountId")
    abstract suspend fun deleteFoods(accountId: String)

    @Insert
    abstract suspend fun insertUnits(rows: List<UnitEntity>)

    @Query("DELETE FROM unit WHERE account_id = :accountId")
    abstract suspend fun deleteUnits(accountId: String)

    @Insert
    abstract suspend fun insertMealTypes(rows: List<MealTypeEntity>)

    @Query("DELETE FROM meal_type WHERE account_id = :accountId")
    abstract suspend fun deleteMealTypes(accountId: String)
}
