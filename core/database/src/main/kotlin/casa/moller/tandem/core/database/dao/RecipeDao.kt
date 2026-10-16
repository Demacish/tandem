package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.IngredientEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.RecipeDetailEntity
import casa.moller.tandem.core.database.entity.RecipeKeywordCrossRef
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.database.entity.StepEntity
import casa.moller.tandem.core.database.fts.SearchText
import casa.moller.tandem.core.database.query.RecipeListQuery
import casa.moller.tandem.core.database.query.RecipeSorting
import casa.moller.tandem.core.database.query.RecipeSql
import casa.moller.tandem.core.database.relation.RecipeDetailWithSteps
import casa.moller.tandem.core.database.relation.RecipeListItem
import casa.moller.tandem.core.model.recipe.RecipeQuery
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Recipes: overviews, details with steps and ingredients, keyword links and the FTS index
 * (plan 5.7). Every function takes the account id; rows of other accounts are never read or
 * written.
 *
 * Use the high-level functions ([observeRecipeList], [applyIndex], [upsertOverviews],
 * [replaceDetail], [deleteRecipes], [setPinned], [markViewed], [setPortionsChosen],
 * [evictDetail]). They keep `recipe_fts` in sync inside the same transaction. The abstract
 * building blocks below them are public only because Room generates them; calling the write
 * blocks directly leaves the FTS index stale.
 */
@Dao
abstract class RecipeDao {
    // ---------------------------------------------------------------- reads for the UI

    /**
     * The offline recipe list for [query] (filters in SQL, sort in Kotlin with a [locale]
     * collator). Emits again whenever a recipe, its local state or its keywords change.
     */
    fun observeRecipeList(accountId: String, query: RecipeQuery, locale: Locale = Locale.getDefault()): Flow<List<RecipeListItem>> {
        val comparator = RecipeSorting.comparator(query.sort, locale)
        return observeRecipeListRaw(RecipeListQuery.build(accountId, query)).map { it.sortedWith(comparator) }
    }

    /** Building block of [observeRecipeList]; the query must select `RecipeSql.SELECT_ITEMS`. */
    @RawQuery(
        observedEntities = [
            RecipeOverviewEntity::class,
            RecipeDetailEntity::class,
            RecipeKeywordCrossRef::class,
            KeywordEntity::class,
            IngredientEntity::class,
        ],
    )
    abstract fun observeRecipeListRaw(query: RoomRawQuery): Flow<List<RecipeListItem>>

    /** One recipe's list entry (header of the detail screen), null when unknown. */
    @Query(RecipeSql.SELECT_ITEMS + "WHERE o.account_id = :accountId AND o.server_id = :recipeId")
    abstract fun observeListItem(accountId: String, recipeId: Long): Flow<RecipeListItem?>

    /** List entries for [recipeIds] (at most 500 per call), in no particular order. */
    @Query(RecipeSql.SELECT_ITEMS + "WHERE o.account_id = :accountId AND o.server_id IN (:recipeIds)")
    abstract suspend fun getListItems(accountId: String, recipeIds: List<Long>): List<RecipeListItem>

    /** Pinned recipes (Today's "Pinned" carousel), in no particular order. */
    @Query(RecipeSql.SELECT_ITEMS + "WHERE o.account_id = :accountId AND d.pinned = 1")
    abstract fun observePinned(accountId: String): Flow<List<RecipeListItem>>

    /** Recipes opened on this device, most recent first (offline "Recently viewed"). */
    @Query(
        RecipeSql.SELECT_ITEMS +
            "WHERE o.account_id = :accountId AND d.last_viewed_local IS NOT NULL ORDER BY d.last_viewed_local DESC LIMIT :limit",
    )
    abstract fun observeRecentlyViewedLocal(accountId: String, limit: Int): Flow<List<RecipeListItem>>

    /** The stored detail (or stub) with steps and ingredients, null when there is no row. */
    @Transaction
    @Query("SELECT * FROM recipe_detail WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract fun observeDetail(accountId: String, recipeId: Long): Flow<RecipeDetailWithSteps?>

    /** One-shot version of [observeDetail]. */
    @Transaction
    @Query("SELECT * FROM recipe_detail WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun getDetail(accountId: String, recipeId: Long): RecipeDetailWithSteps?

    /** The detail row alone (no steps), null when there is none. */
    @Query("SELECT * FROM recipe_detail WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun getDetailRow(accountId: String, recipeId: Long): RecipeDetailEntity?

    /** The servings the user picked for a recipe, null = recipe default. */
    @Query("SELECT portions_chosen FROM recipe_detail WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract fun observePortionsChosen(accountId: String, recipeId: Long): Flow<Double?>

    /** Every keyword of the account, by name (binary order; sort with a collator for display). */
    @Query("SELECT * FROM keyword WHERE account_id = :accountId ORDER BY name")
    abstract fun observeKeywords(accountId: String): Flow<List<KeywordEntity>>

    /** The keywords linked to one recipe. */
    @Query(
        "SELECT k.* FROM keyword k JOIN recipe_keyword rk ON rk.account_id = k.account_id AND rk.keyword_id = k.id " +
            "WHERE rk.account_id = :accountId AND rk.recipe_id = :recipeId ORDER BY k.name",
    )
    abstract suspend fun getKeywordsOfRecipe(accountId: String, recipeId: Long): List<KeywordEntity>

    // ---------------------------------------------------------------- reads for sync

    /** Every stored recipe id of the account. */
    @Query("SELECT server_id FROM recipe_overview WHERE account_id = :accountId")
    abstract suspend fun getRecipeIds(accountId: String): List<Long>

    /**
     * Pinned recipes whose detail is missing or older than [olderThanEpochMillis] (plan 3.5:
     * refresh every 24 h), or fetched after [newerThanEpochMillis]: a time in the future means
     * the device clock was ahead then, and such a copy would otherwise never count as stale.
     */
    @Query(
        "SELECT recipe_id FROM recipe_detail WHERE account_id = :accountId AND pinned = 1 " +
            "AND (raw_json IS NULL OR fetched_at IS NULL OR fetched_at < :olderThanEpochMillis " +
            "OR fetched_at > :newerThanEpochMillis) ORDER BY recipe_id",
    )
    abstract suspend fun getPinnedIdsToRefresh(
        accountId: String,
        olderThanEpochMillis: Long,
        newerThanEpochMillis: Long = Long.MAX_VALUE,
    ): List<Long>

    /** Recipes with a stored detail (available offline). */
    @Query("SELECT recipe_id FROM recipe_detail WHERE account_id = :accountId AND raw_json IS NOT NULL ORDER BY recipe_id")
    abstract suspend fun getStoredDetailIds(accountId: String): List<Long>

    /**
     * Recipes that must survive an index deletion although the server no longer lists them:
     * pinned recipes with unsent edits in the outbox (plan 3.5 "utom fästa som redigeras").
     */
    @Query(
        "SELECT d.recipe_id FROM recipe_detail d WHERE d.account_id = :accountId AND d.pinned = 1 AND EXISTS " +
            "(SELECT 1 FROM outbox ob WHERE ob.account_id = d.account_id AND ob.entity = 'recipe' " +
            "AND ob.server_id = d.recipe_id AND ob.op NOT IN ('VIEW_LOG', 'COOK_LOG'))",
    )
    abstract suspend fun getProtectedRecipeIds(accountId: String): List<Long>

    // ---------------------------------------------------------------- transactional writes

    /**
     * Applies the recipe index (`recipe/flat`, plan 3.5): adds new recipes, updates changed names
     * and images (other columns are kept), and deletes recipes the index no longer lists, except
     * [getProtectedRecipeIds]. An empty [entries] list deletes nothing unless [allowEmpty] is set
     * (protects against a wrong space or a broken response wiping the local copy).
     */
    @Transaction
    open suspend fun applyIndex(accountId: String, entries: List<RecipeIndexEntry>, allowEmpty: Boolean = false): IndexChange {
        val index = entries.associateBy { it.serverId }
        val stored = getAllOverviews(accountId).associateBy { it.serverId }
        val inserts = index.values.filter { it.serverId !in stored }.map { entry ->
            RecipeOverviewEntity(
                accountId = accountId,
                serverId = entry.serverId,
                name = entry.name,
                imageUrl = entry.imageUrl,
                searchName = SearchText.normalize(entry.name),
            )
        }
        val updates = index.values.mapNotNull { entry ->
            stored[entry.serverId]
                ?.takeIf { it.name != entry.name || it.imageUrl != entry.imageUrl }
                ?.copy(name = entry.name, imageUrl = entry.imageUrl, searchName = SearchText.normalize(entry.name))
        }
        if (inserts.isNotEmpty()) insertOverviews(inserts)
        if (updates.isNotEmpty()) updateOverviews(updates)
        val missing = (stored.keys - index.keys).sorted()
        val (deleted, kept) = if (index.isEmpty() && !allowEmpty) {
            emptyList<Long>() to missing
        } else {
            val protectedIds = getProtectedRecipeIds(accountId).toSet()
            missing.partition { it !in protectedIds }
        }
        deleteRecipeRows(accountId, deleted)
        deleteOrphanDetails(accountId)
        refreshFts(accountId, inserts.map { it.serverId } + updates.map { it.serverId })
        return IndexChange(
            inserted = inserts.map { it.serverId },
            updated = updates.map { it.serverId },
            deleted = deleted,
            kept = kept,
        )
    }

    /**
     * Stores recipes from a list response. Columns the list sends as null although the stored
     * row knows them (rating outside rating sorts, `last_cooked`, `times_cooked`, dates) keep
     * their stored value. Keyword links are replaced for writes that carry keywords.
     */
    @Transaction
    open suspend fun upsertOverviews(accountId: String, writes: List<RecipeOverviewWrite>) {
        if (writes.isEmpty()) return
        val unique = writes.associateBy { it.overview.serverId }.values.toList()
        writeOverviews(accountId, unique.map { it.overview }, fromDetail = false)
        val withKeywords = unique.filter { it.keywords != null }
        if (withKeywords.isNotEmpty()) {
            val keywords = withKeywords.flatMap { it.keywords.orEmpty() }.map { it.copy(accountId = accountId) }
            if (keywords.isNotEmpty()) insertKeywordsIfAbsent(keywords.distinctBy { it.id })
            withKeywords.map { it.overview.serverId }.chunked(RecipeSql.CHUNK).forEach { deleteKeywordRefs(accountId, it) }
            val refs = withKeywords.flatMap { write ->
                write.keywords.orEmpty().map { RecipeKeywordCrossRef(accountId, write.overview.serverId, it.id) }
            }
            if (refs.isNotEmpty()) insertKeywordRefs(refs)
        }
        refreshFts(accountId, unique.map { it.overview.serverId })
    }

    /**
     * Stores a fetched recipe atomically (plan 5.7): overview columns, the detail row (keeping
     * its local state), steps, ingredients, keywords and the FTS row. If anything fails, nothing
     * changes.
     */
    @Transaction
    open suspend fun replaceDetail(accountId: String, write: RecipeDetailWrite) {
        val recipeId = write.detail.recipeId
        writeOverviews(accountId, listOf(write.overview.copy(serverId = recipeId)), fromDetail = true)
        val stored = getDetailRow(accountId, recipeId)
        upsertDetailRow(
            write.detail.copy(
                accountId = accountId,
                pinned = stored?.pinned ?: write.detail.pinned,
                lastViewedLocalEpochMillis = stored?.lastViewedLocalEpochMillis ?: write.detail.lastViewedLocalEpochMillis,
                portionsChosen = stored?.portionsChosen ?: write.detail.portionsChosen,
            ),
        )
        deleteSteps(accountId, recipeId)
        deleteIngredients(accountId, recipeId)
        if (write.steps.isNotEmpty()) insertSteps(write.steps.map { it.copy(accountId = accountId, recipeId = recipeId) })
        if (write.ingredients.isNotEmpty()) {
            insertIngredients(write.ingredients.map { it.copy(accountId = accountId, recipeId = recipeId) })
        }
        val keywords = write.keywords.map { it.copy(accountId = accountId) }.distinctBy { it.id }
        if (keywords.isNotEmpty()) upsertKeywords(keywords)
        deleteKeywordRefs(accountId, listOf(recipeId))
        if (keywords.isNotEmpty()) insertKeywordRefs(keywords.map { RecipeKeywordCrossRef(accountId, recipeId, it.id) })
        refreshFts(accountId, listOf(recipeId))
    }

    /**
     * Deletes recipes with everything that belongs to them (detail, steps, ingredients, keyword
     * links, carousel entries, FTS rows) and their unsent outbox rows (plan 3.5: never send a
     * change for an object the server no longer has).
     */
    @Transaction
    open suspend fun deleteRecipes(accountId: String, recipeIds: List<Long>) {
        deleteRecipeRows(accountId, recipeIds.distinct())
    }

    /** Removes the stored detail (steps, ingredients, ingredient search) but keeps pin and local state. */
    @Transaction
    open suspend fun evictDetail(accountId: String, recipeId: Long) {
        clearDetailColumns(accountId, recipeId)
        deleteSteps(accountId, recipeId)
        deleteIngredients(accountId, recipeId)
        refreshFts(accountId, listOf(recipeId))
    }

    /** Pins or unpins a recipe ("Download for offline use"); works before the detail is stored. */
    @Transaction
    open suspend fun setPinned(accountId: String, recipeId: Long, pinned: Boolean) {
        insertDetailStub(RecipeDetailEntity(accountId = accountId, recipeId = recipeId))
        updatePinned(accountId, recipeId, pinned)
    }

    /** Remembers that the recipe was opened on this device at [nowEpochMillis]. */
    @Transaction
    open suspend fun markViewed(accountId: String, recipeId: Long, nowEpochMillis: Long) {
        insertDetailStub(RecipeDetailEntity(accountId = accountId, recipeId = recipeId))
        updateLastViewed(accountId, recipeId, nowEpochMillis)
    }

    /** Remembers the servings the user picked; null returns to the recipe's own servings. */
    @Transaction
    open suspend fun setPortionsChosen(accountId: String, recipeId: Long, servings: Double?) {
        insertDetailStub(RecipeDetailEntity(accountId = accountId, recipeId = recipeId))
        updatePortionsChosen(accountId, recipeId, servings)
    }

    /** Sets the locally computed cook statistics (plan 3.3: `last_cooked` comes from `cook-log` in 2.6). */
    @Query(
        "UPDATE recipe_overview SET last_cooked = :lastCookedEpochMillis, times_cooked = :timesCooked " +
            "WHERE account_id = :accountId AND server_id = :recipeId",
    )
    abstract suspend fun setCookStats(accountId: String, recipeId: Long, lastCookedEpochMillis: Long?, timesCooked: Int?)

    /**
     * Replaces the cook statistics of the whole account with [stats] (computed from the complete
     * cook log) in ONE transaction, so the observed lists are invalidated once: recipes missing
     * from [stats] (their cook-log entries were deleted) go back to "never cooked".
     *
     * @param withRatings also replace `rating` with [CookStatsRow.rating] (null for recipes not
     *   in [stats]): the list responses carry no rating (FACTS "Fas 1 c"), so the cook log is
     *   its source. False keeps the stored ratings (demo mode, whose details carry them).
     */
    @Transaction
    open suspend fun replaceCookStats(accountId: String, stats: List<CookStatsRow>, withRatings: Boolean) {
        if (withRatings) resetCookStatsAndRatings(accountId) else resetCookStats(accountId)
        stats.distinctBy { it.recipeId }.forEach { row ->
            if (withRatings) {
                setCookStatsAndRating(accountId, row.recipeId, row.lastCookedEpochMillis, row.timesCooked, row.rating)
            } else {
                setCookStats(accountId, row.recipeId, row.lastCookedEpochMillis, row.timesCooked)
            }
        }
    }

    @Query(
        "UPDATE recipe_overview SET last_cooked = NULL, times_cooked = NULL " +
            "WHERE account_id = :accountId AND (last_cooked IS NOT NULL OR times_cooked IS NOT NULL)",
    )
    abstract suspend fun resetCookStats(accountId: String)

    @Query(
        "UPDATE recipe_overview SET last_cooked = NULL, times_cooked = NULL, rating = NULL " +
            "WHERE account_id = :accountId AND (last_cooked IS NOT NULL OR times_cooked IS NOT NULL OR rating IS NOT NULL)",
    )
    abstract suspend fun resetCookStatsAndRatings(accountId: String)

    @Query(
        "UPDATE recipe_overview SET last_cooked = :lastCookedEpochMillis, times_cooked = :timesCooked, rating = :rating " +
            "WHERE account_id = :accountId AND server_id = :recipeId",
    )
    abstract suspend fun setCookStatsAndRating(
        accountId: String,
        recipeId: Long,
        lastCookedEpochMillis: Long?,
        timesCooked: Int?,
        rating: Double?,
    )

    /** Rebuilds the whole FTS index of the account (after the keyword list changed names). */
    @Transaction
    open suspend fun rebuildFts(accountId: String) {
        deleteFtsForAccount(accountId)
        insertFtsForAccount(accountId)
    }

    /** Replaces the account's keyword list (full `GET /api/keyword/`) and rebuilds the FTS index. */
    @Transaction
    open suspend fun replaceKeywords(accountId: String, keywords: List<KeywordEntity>) {
        deleteKeywords(accountId)
        if (keywords.isNotEmpty()) upsertKeywords(keywords.map { it.copy(accountId = accountId) }.distinctBy { it.id })
        deleteFtsForAccount(accountId)
        insertFtsForAccount(accountId)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun writeOverviews(accountId: String, rows: List<RecipeOverviewEntity>, fromDetail: Boolean) {
        val unique = rows.associateBy { it.serverId }.values.map {
            it.copy(accountId = accountId, searchName = SearchText.normalize(it.name))
        }
        val stored = unique.map { it.serverId }
            .chunked(RecipeSql.CHUNK)
            .flatMap { getOverviews(accountId, it) }
            .associateBy { it.serverId }
        val (updates, inserts) = unique.partition { it.serverId in stored }
        if (inserts.isNotEmpty()) insertOverviews(inserts.map { it.copy(localId = 0) })
        if (updates.isNotEmpty()) {
            updateOverviews(updates.map { it.mergedOnto(stored.getValue(it.serverId), fromDetail) })
        }
    }

    private suspend fun deleteRecipeRows(accountId: String, recipeIds: List<Long>) {
        recipeIds.chunked(RecipeSql.CHUNK).forEach { chunk ->
            // FTS rows are found through recipe_overview.local_id, so they go first.
            deleteFts(accountId, chunk)
            deleteOverviewRows(accountId, chunk)
            deleteDetailRows(accountId, chunk)
            deleteStepRows(accountId, chunk)
            deleteIngredientRows(accountId, chunk)
            deleteKeywordRefs(accountId, chunk)
            deleteHomeSectionRefs(accountId, chunk)
            deleteUnsentOutboxRows(accountId, chunk)
        }
    }

    private suspend fun refreshFts(accountId: String, recipeIds: List<Long>) {
        recipeIds.distinct().chunked(RecipeSql.CHUNK).forEach { chunk ->
            deleteFts(accountId, chunk)
            insertFts(accountId, chunk)
        }
    }

    // ---------------------------------------------------------------- building blocks (Room)

    @Query("SELECT * FROM recipe_overview WHERE account_id = :accountId")
    abstract suspend fun getAllOverviews(accountId: String): List<RecipeOverviewEntity>

    @Query("SELECT * FROM recipe_overview WHERE account_id = :accountId AND server_id IN (:serverIds)")
    abstract suspend fun getOverviews(accountId: String, serverIds: List<Long>): List<RecipeOverviewEntity>

    @Insert
    abstract suspend fun insertOverviews(rows: List<RecipeOverviewEntity>)

    @Update
    abstract suspend fun updateOverviews(rows: List<RecipeOverviewEntity>)

    @Upsert
    abstract suspend fun upsertDetailRow(row: RecipeDetailEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDetailStub(row: RecipeDetailEntity)

    @Query("UPDATE recipe_detail SET pinned = :pinned WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun updatePinned(accountId: String, recipeId: Long, pinned: Boolean)

    @Query("UPDATE recipe_detail SET last_viewed_local = :epochMillis WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun updateLastViewed(accountId: String, recipeId: Long, epochMillis: Long)

    @Query("UPDATE recipe_detail SET portions_chosen = :servings WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun updatePortionsChosen(accountId: String, recipeId: Long, servings: Double?)

    @Query("UPDATE recipe_detail SET raw_json = NULL, fetched_at = NULL WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun clearDetailColumns(accountId: String, recipeId: Long)

    @Insert
    abstract suspend fun insertSteps(rows: List<StepEntity>)

    @Insert
    abstract suspend fun insertIngredients(rows: List<IngredientEntity>)

    @Query("DELETE FROM step WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun deleteSteps(accountId: String, recipeId: Long)

    @Query("DELETE FROM ingredient WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun deleteIngredients(accountId: String, recipeId: Long)

    @Upsert
    abstract suspend fun upsertKeywords(rows: List<KeywordEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertKeywordsIfAbsent(rows: List<KeywordEntity>)

    @Query("DELETE FROM keyword WHERE account_id = :accountId")
    abstract suspend fun deleteKeywords(accountId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertKeywordRefs(rows: List<RecipeKeywordCrossRef>)

    @Query("DELETE FROM recipe_keyword WHERE account_id = :accountId AND recipe_id IN (:recipeIds)")
    abstract suspend fun deleteKeywordRefs(accountId: String, recipeIds: List<Long>)

    @Query("DELETE FROM recipe_overview WHERE account_id = :accountId AND server_id IN (:recipeIds)")
    abstract suspend fun deleteOverviewRows(accountId: String, recipeIds: List<Long>)

    @Query("DELETE FROM recipe_detail WHERE account_id = :accountId AND recipe_id IN (:recipeIds)")
    abstract suspend fun deleteDetailRows(accountId: String, recipeIds: List<Long>)

    @Query("DELETE FROM step WHERE account_id = :accountId AND recipe_id IN (:recipeIds)")
    abstract suspend fun deleteStepRows(accountId: String, recipeIds: List<Long>)

    @Query("DELETE FROM ingredient WHERE account_id = :accountId AND recipe_id IN (:recipeIds)")
    abstract suspend fun deleteIngredientRows(accountId: String, recipeIds: List<Long>)

    @Query("DELETE FROM home_section_recipe WHERE account_id = :accountId AND recipe_id IN (:recipeIds)")
    abstract suspend fun deleteHomeSectionRefs(accountId: String, recipeIds: List<Long>)

    @Query(
        "DELETE FROM outbox WHERE account_id = :accountId AND entity = 'recipe' AND state != 'IN_FLIGHT' " +
            "AND server_id IN (:recipeIds)",
    )
    abstract suspend fun deleteUnsentOutboxRows(accountId: String, recipeIds: List<Long>)

    /** Detail rows (stubs included) whose recipe is not in the overview any more. */
    @Query(
        "DELETE FROM recipe_detail WHERE account_id = :accountId AND recipe_id NOT IN " +
            "(SELECT server_id FROM recipe_overview WHERE account_id = :accountId)",
    )
    abstract suspend fun deleteOrphanDetails(accountId: String)

    @Query(RecipeSql.FTS_DELETE_RECIPES)
    abstract suspend fun deleteFts(accountId: String, serverIds: List<Long>)

    @Query(RecipeSql.FTS_INSERT_RECIPES)
    abstract suspend fun insertFts(accountId: String, serverIds: List<Long>)

    @Query(RecipeSql.FTS_DELETE_ACCOUNT)
    abstract suspend fun deleteFtsForAccount(accountId: String)

    @Query(RecipeSql.FTS_INSERT_ACCOUNT)
    abstract suspend fun insertFtsForAccount(accountId: String)
}

/**
 * This row written over the [stored] one: keeps the rowid, and keeps stored values for columns
 * the server left out (null). A list response ([fromDetail] = false) only carries the rating
 * when sorted by it, so a null rating keeps the stored one; the detail's rating is authoritative
 * but the detail has no `new` flag, so [fromDetail] keeps the stored `is_new`.
 */
private fun RecipeOverviewEntity.mergedOnto(stored: RecipeOverviewEntity, fromDetail: Boolean): RecipeOverviewEntity = copy(
    localId = stored.localId,
    rating = if (rating == null && !fromDetail) stored.rating else rating,
    lastCookedEpochMillis = lastCookedEpochMillis ?: stored.lastCookedEpochMillis,
    timesCooked = timesCooked ?: stored.timesCooked,
    isNew = if (fromDetail) stored.isNew else isNew,
    createdAtEpochMillis = createdAtEpochMillis ?: stored.createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis ?: stored.updatedAtEpochMillis,
)
