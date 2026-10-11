package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.HomeSectionEntity
import casa.moller.tandem.core.database.entity.HomeSectionRecipeEntity
import casa.moller.tandem.core.database.query.RecipeSql
import casa.moller.tandem.core.database.relation.HomeSectionRow
import casa.moller.tandem.core.model.home.HomeSectionType
import kotlinx.coroutines.flow.Flow

/**
 * Cache of the Today carousels (plan 6.2 "Idag"), so Today shows the last answer offline. The
 * pinned and locally viewed recipes come straight from `recipe_detail` ([RecipeDao.observePinned],
 * [RecipeDao.observeRecentlyViewedLocal]).
 */
@Dao
abstract class HomeSectionDao {
    /** Stores the answer of one carousel query: [recipeIds] in carousel order. */
    @Transaction
    open suspend fun replaceSection(accountId: String, type: HomeSectionType, recipeIds: List<Long>, fetchedAtEpochMillis: Long) {
        deleteSectionRecipes(accountId, type)
        val rows = recipeIds.distinct().mapIndexed { index, recipeId -> HomeSectionRecipeEntity(accountId, type, index, recipeId) }
        if (rows.isNotEmpty()) insertSectionRecipes(rows)
        upsertSection(HomeSectionEntity(accountId, type, fetchedAtEpochMillis))
    }

    /**
     * Every cached carousel entry whose recipe is stored, by section and position. Group with
     * `toHomeSections()` (mapper package).
     */
    @Query(
        "SELECT hs.section_type AS section_type, hs.position AS position, " + RecipeSql.ITEM_COLUMNS + " " +
            "FROM home_section_recipe hs " +
            "JOIN recipe_overview o ON o.account_id = hs.account_id AND o.server_id = hs.recipe_id " +
            RecipeSql.DETAIL_JOIN + " " +
            "WHERE hs.account_id = :accountId ORDER BY hs.section_type, hs.position",
    )
    abstract fun observeSectionRows(accountId: String): Flow<List<HomeSectionRow>>

    /** When each carousel was last fetched. */
    @Query("SELECT * FROM home_section WHERE account_id = :accountId")
    abstract fun observeSections(accountId: String): Flow<List<HomeSectionEntity>>

    @Query("SELECT * FROM home_section WHERE account_id = :accountId")
    abstract suspend fun getSections(accountId: String): List<HomeSectionEntity>

    /** Every recipe id in any cached carousel (e.g. to prefetch details), by section and position. */
    @Query("SELECT recipe_id FROM home_section_recipe WHERE account_id = :accountId ORDER BY section_type, position")
    abstract suspend fun getSectionRecipeIds(accountId: String): List<Long>

    // ---------------------------------------------------------------- building blocks (Room)

    @Query("DELETE FROM home_section_recipe WHERE account_id = :accountId AND section_type = :type")
    abstract suspend fun deleteSectionRecipes(accountId: String, type: HomeSectionType)

    @Insert
    abstract suspend fun insertSectionRecipes(rows: List<HomeSectionRecipeEntity>)

    @Upsert
    abstract suspend fun upsertSection(row: HomeSectionEntity)
}
