package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.mapper.toEntity
import casa.moller.tandem.core.database.mapper.toHomeSections
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Meal plan window and Today carousel caches. */
@RunWith(AndroidJUnit4::class)
class MealPlanAndHomeTest {
    private val database = newDatabase()
    private val mealPlans = database.mealPlanDao()

    @After
    fun close() {
        database.close()
    }

    private val dinner = MealType(id = 2, name = "Middag", order = 2, time = LocalTime(18, 0))
    private val lunch = MealType(id = 1, name = "Lunch", order = 1)

    private fun entry(
        id: Long,
        from: String,
        to: String = from,
        type: MealType = dinner,
        recipeId: Long? = null,
        title: String = "",
    ): MealPlanEntity {
        val model = MealPlanEntry(
            id = id,
            title = title,
            recipe = recipeId?.let { RecipeOverview(id = it, name = "") },
            servings = 2.0,
            fromDate = LocalDate.parse(from),
            toDate = LocalDate.parse(to),
            mealType = type,
        )
        return model.toEntity(ACCOUNT_A, fromRaw = from + "T18:00:00+02:00")
    }

    @Test
    fun rangeQueryJoinsMealTypeAndRecipe() = runTest {
        database.store(ACCOUNT_A, TestRecipes.PeaSoup)
        database.referenceDao().replaceMealTypes(ACCOUNT_A, listOf(dinner, lunch).map { it.toEntity(ACCOUNT_A) })
        mealPlans.replaceWindow(
            ACCOUNT_A,
            "2026-09-22",
            "2026-11-17",
            listOf(
                entry(10, "2026-10-06", recipeId = TestRecipes.PeaSoup.id),
                entry(11, "2026-10-06", type = lunch, title = "Rester"),
                entry(12, "2026-10-05", "2026-10-07", title = "Långkok"),
                entry(13, "2026-10-08", title = "Inte i dag"),
                entry(14, "2026-10-06", type = MealType(id = 99, name = "?"), title = "Okänd typ"),
            ),
        )

        val today = mealPlans.observeRange(ACCOUNT_A, "2026-10-06", "2026-10-06").first().map { it.toModel() }

        assertEquals(listOf("Långkok", "Okänd typ", "Rester", "Ärtsoppa"), today.map { it.displayTitle })
        val peaSoup = today.single { it.id == 10L }
        assertEquals(dinner, peaSoup.mealType)
        assertEquals("Ärtsoppa", peaSoup.recipe?.name)
        assertEquals(2.0, peaSoup.servings, 0.0)
        assertEquals(MealType(id = 99, name = ""), today.single { it.id == 14L }.mealType)
        assertNull(today.single { it.id == 11L }.recipe)
        assertEquals(LocalDate(2026, 10, 7), today.single { it.id == 12L }.toDate)
    }

    @Test
    fun replaceWindowReplacesOnlyTheWindowAndKeepsEntriesWithUnsentChanges() = runTest {
        mealPlans.replaceWindow(
            ACCOUNT_A,
            "2026-10-01",
            "2026-10-31",
            listOf(entry(1, "2026-10-02"), entry(2, "2026-10-03"), entry(3, "2026-10-04"), entry(4, "2026-11-15")),
        )
        mealPlans.replaceWindow(ACCOUNT_A, "2026-11-01", "2026-11-30", listOf(entry(4, "2026-11-15")))
        database.outboxDao().insert(
            OutboxEntity(
                accountId = ACCOUNT_A,
                op = OutboxOps.PATCH,
                entity = OutboxEntities.MEAL_PLAN,
                serverId = 3,
                createdAtEpochMillis = 1,
            ),
        )

        // The server no longer lists 1, and 2 moved to the 20th; 3 has an unsent local change.
        mealPlans.replaceWindow(ACCOUNT_A, "2026-10-01", "2026-10-31", listOf(entry(2, "2026-10-20"), entry(3, "2026-10-25")))

        val rows = mealPlans.getRange(ACCOUNT_A, "2026-01-01", "2026-12-31").map { it.entry }
        assertEquals(listOf(3L to "2026-10-04", 2L to "2026-10-20", 4L to "2026-11-15"), rows.map { it.serverId to it.fromDate })
        assertEquals(listOf(3L, 2L, 4L), rows.map { it.localId })
    }

    @Test
    fun carouselsKeepTheirOrderAndSkipUnknownRecipes() = runTest {
        TestRecipes.All.forEach { database.store(ACCOUNT_A, it) }
        val home = database.homeSectionDao()
        home.replaceSection(ACCOUNT_A, HomeSectionType.OFTEN_COOKED, listOf(3, 1, 99, 2), fetchedAtEpochMillis = 10)
        home.replaceSection(ACCOUNT_A, HomeSectionType.NEW, listOf(6), fetchedAtEpochMillis = 10)
        home.replaceSection(ACCOUNT_A, HomeSectionType.NOT_COOKED_LATELY, emptyList(), fetchedAtEpochMillis = 10)

        val sections = home.observeSectionRows(ACCOUNT_A).first().toHomeSections()

        assertEquals(listOf(HomeSectionType.OFTEN_COOKED, HomeSectionType.NEW), sections.map { it.type })
        assertEquals(listOf("Äppelkaka", "Krämig svamppasta", "Ärtsoppa"), sections.first().recipes.map { it.name })
        assertEquals(3, home.getSections(ACCOUNT_A).size)

        home.replaceSection(ACCOUNT_A, HomeSectionType.OFTEN_COOKED, listOf(2), fetchedAtEpochMillis = 20)
        database.recipeDao().applyIndex(ACCOUNT_A, listOf(RecipeIndexEntry(2, "Ärtsoppa")))
        val after = home.observeSectionRows(ACCOUNT_A).first().toHomeSections()
        assertEquals(listOf(HomeSectionType.OFTEN_COOKED), after.map { it.type })
        assertEquals(listOf(2L), home.getSectionRecipeIds(ACCOUNT_A))
    }

    @Test
    fun pinnedAndRecentlyViewedComeFromLocalState() = runTest {
        TestRecipes.All.forEach { database.store(ACCOUNT_A, it) }
        val recipes = database.recipeDao()
        recipes.setPinned(ACCOUNT_A, 2, pinned = true)
        recipes.markViewed(ACCOUNT_A, 1, nowEpochMillis = 100)
        recipes.markViewed(ACCOUNT_A, 3, nowEpochMillis = 300)
        recipes.markViewed(ACCOUNT_A, 5, nowEpochMillis = 200)

        assertEquals(listOf("Ärtsoppa"), recipes.observePinned(ACCOUNT_A).first().map { it.toModel().name })
        assertEquals(listOf(3L, 5L), recipes.observeRecentlyViewedLocal(ACCOUNT_A, limit = 2).first().map { it.overview.serverId })
    }
}
