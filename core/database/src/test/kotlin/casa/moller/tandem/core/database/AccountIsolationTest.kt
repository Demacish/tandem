package casa.moller.tandem.core.database

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.dao.CookStatsRules
import casa.moller.tandem.core.database.entity.CookLogEntity
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.database.entity.SyncEntities
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.database.mapper.toEntity
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.RecipeQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Two accounts with the same server ids never see each other's rows (plan 5.3/5.7, decision B14). */
@RunWith(AndroidJUnit4::class)
class AccountIsolationTest {
    private val database = newDatabase()

    @After
    fun close() {
        database.close()
    }

    private suspend fun seed(accountId: String, recipeName: String, mealTitle: String) {
        val account = ServerAccountEntity(id = accountId, baseUrl = "https://$accountId.example.com", userId = 1, spaceId = 1)
        database.accountDao().upsert(account)
        database.store(accountId, TestRecipes.recipe(id = 1, name = recipeName, foods = listOf(recipeName)))
        database.referenceDao().replaceMealTypes(accountId, listOf(MealTypeEntity(accountId, id = 1, name = "Middag")))
        database.mealPlanDao().replaceWindow(
            accountId,
            "2026-10-01",
            "2026-10-31",
            listOf(
                MealPlanEntity(
                    accountId = accountId,
                    localId = 1,
                    serverId = 1,
                    title = mealTitle,
                    recipeId = 1,
                    fromDate = "2026-10-06",
                    toDate = "2026-10-06",
                    mealTypeId = 1,
                ),
            ),
        )
        database.homeSectionDao().replaceSection(accountId, HomeSectionType.NEW, listOf(1), 1)
        database.syncStateDao().upsert(SyncStateEntity(accountId, SyncEntities.RECIPE_INDEX, lastFullRelistEpochMillis = 1))
        database.outboxDao().enqueueViewLog(accountId, recipeId = 1, payload = "{}", nowEpochMillis = 1)
        database.cookLogDao().logLocally(
            CookLogEntity(accountId = accountId, localId = 0, serverId = null, recipeId = 1, rating = 4, cookedAtEpochMillis = 1),
            outboxPayload = "{}",
            nowEpochMillis = 1,
            rules = CookStatsRules(ownUserId = 1, ratingsFromLog = true),
        )
        database.referenceDao().upsertUserPreference(UserPreferences(useFractions = accountId == ACCOUNT_A).toEntity(accountId, 1))
    }

    private suspend fun recipeNames(accountId: String, text: String = ""): List<String> =
        database.recipeDao().observeRecipeList(accountId, RecipeQuery(text = text), SWEDISH).first().map { it.overview.name }

    @Test
    fun everyQueryIsScopedToItsAccount() = runTest {
        seed(ACCOUNT_A, "Pannkakor", "Pannkaksfest")
        seed(ACCOUNT_B, "Våfflor", "Våffeldag")

        assertEquals(listOf("Pannkakor"), recipeNames(ACCOUNT_A))
        assertEquals(listOf("Våfflor"), recipeNames(ACCOUNT_B))
        assertEquals(emptyList<String>(), recipeNames(ACCOUNT_A, "våfflor"))
        assertEquals("Pannkakor", database.recipeDao().observeListItem(ACCOUNT_A, 1).first()?.overview?.name)
        assertEquals(listOf("Pannkakor"), checkNotNull(database.recipeDao().getDetail(ACCOUNT_A, 1)).ingredients.map { it.foodName })
        val meals = database.mealPlanDao().getRange(ACCOUNT_B, "2026-10-06", "2026-10-06").map { it.toModel() }
        assertEquals(listOf("Våffeldag"), meals.map { it.title })
        assertEquals("Våfflor", meals.single().recipe?.name)
        assertEquals(listOf(1L), database.homeSectionDao().getSectionRecipeIds(ACCOUNT_B))
        assertEquals(OutboxEntities.RECIPE, database.outboxDao().nextReady(ACCOUNT_B)?.entity)
        assertTrue(checkNotNull(database.referenceDao().getUserPreference(ACCOUNT_A)).toModel().useFractions)
        assertEquals(false, database.referenceDao().getUserPreference(ACCOUNT_B)?.toModel()?.useFractions)
        // Each account counts only its own "Klart!" (same recipe id in both).
        database.cookLogDao().logLocally(
            CookLogEntity(accountId = ACCOUNT_A, localId = 0, serverId = null, recipeId = 1, cookedAtEpochMillis = 2),
            outboxPayload = "{}",
            nowEpochMillis = 2,
            rules = CookStatsRules(ownUserId = 1, ratingsFromLog = true),
        )
        assertEquals(2, database.recipeDao().observeListItem(ACCOUNT_A, 1).first()?.overview?.timesCooked)
        assertEquals(1, database.recipeDao().observeListItem(ACCOUNT_B, 1).first()?.overview?.timesCooked)
        assertEquals(1, database.cookLogDao().observePendingCount(ACCOUNT_B).first())
    }

    @Test
    fun deletingAnAccountLeavesTheOtherIntact() = runTest {
        seed(ACCOUNT_A, "Pannkakor", "Pannkaksfest")
        seed(ACCOUNT_B, "Våfflor", "Våffeldag")

        database.accountDao().deleteAccountData(ACCOUNT_A)

        assertEquals(emptyList<String>(), recipeNames(ACCOUNT_A))
        assertNull(database.recipeDao().getDetail(ACCOUNT_A, 1))
        assertEquals(emptyList<Any>(), database.mealPlanDao().getRange(ACCOUNT_A, "2026-01-01", "2026-12-31"))
        assertNull(database.outboxDao().nextReady(ACCOUNT_A))
        assertNull(database.syncStateDao().get(ACCOUNT_A, SyncEntities.RECIPE_INDEX))
        assertNull(database.referenceDao().getUserPreference(ACCOUNT_A))
        assertNull(database.accountDao().get(ACCOUNT_A))
        assertEquals(listOf(ACCOUNT_B), database.accountDao().observeAll().first().map { it.id })

        assertEquals(listOf("Våfflor"), recipeNames(ACCOUNT_B, "vafflor"))
        assertEquals(1, database.mealPlanDao().getRange(ACCOUNT_B, "2026-10-06", "2026-10-06").size)
        assertEquals(1L, database.count("SELECT COUNT(*) FROM recipe_fts"))
        assertEquals("B's view and cook log", 2L, database.count("SELECT COUNT(*) FROM outbox"))
        assertEquals(1, database.cookLogDao().getEntries(ACCOUNT_B).size)
        // Every account-scoped table is empty for A (the FTS table has no account column, see above).
        ACCOUNT_TABLES.forEach { table ->
            assertEquals(table, 0L, database.count("SELECT COUNT(*) FROM $table WHERE account_id = '$ACCOUNT_A'"))
        }
    }

    @Test
    fun storedAccountIdsIncludeRowsWithoutAMirrorRow() = runTest {
        seed(ACCOUNT_A, "Pannkakor", "Pannkaksfest")
        seed(ACCOUNT_B, "Våfflor", "Våffeldag")
        // A late response written after B's mirror row was removed (logout during a sync).
        database.accountDao().deleteAccountData(ACCOUNT_B)
        database.store(ACCOUNT_B, TestRecipes.recipe(id = 2, name = "Sen pannkaka"))

        assertEquals(listOf(ACCOUNT_A, ACCOUNT_B), database.accountDao().getStoredAccountIds().sorted())
        assertEquals(listOf(ACCOUNT_A), database.accountDao().observeAll().first().map { it.id })

        database.accountDao().deleteAccountData(ACCOUNT_B)
        assertEquals(listOf(ACCOUNT_A), database.accountDao().getStoredAccountIds())
    }

    @Test
    fun everyTableWithAnAccountColumnIsCleanedOnRemoval() = runTest {
        val tables = database.texts("SELECT name FROM sqlite_master WHERE type = 'table'").filter { table ->
            "account_id" in database.texts("PRAGMA table_info(`$table`)", column = 1)
        }

        // A new account-scoped table must be added to AccountDao.deleteFrom and getStoredAccountIds.
        assertEquals(ACCOUNT_TABLES.sorted(), tables.sorted())
    }

    private suspend fun TandemDatabase.texts(sql: String, column: Int = 0): List<String> = useReaderConnection { connection ->
        connection.usePrepared(sql) { statement ->
            buildList { while (statement.step()) add(statement.getText(column)) }
        }
    }

    private companion object {
        val ACCOUNT_TABLES = listOf(
            "recipe_overview",
            "recipe_detail",
            "step",
            "ingredient",
            "recipe_keyword",
            "keyword",
            "food",
            "unit",
            "meal_type",
            "meal_plan",
            "user_preference",
            "home_section",
            "home_section_recipe",
            "sync_state",
            "outbox",
            "cook_log",
        )
    }
}
